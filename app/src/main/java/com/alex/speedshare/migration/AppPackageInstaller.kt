package com.alex.speedshare.migration

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MigrationAppInstallState {
    READY,
    PREPARING,
    WAITING_CONFIRMATION,
    INSTALLED,
    FAILED
}

data class MigrationAppInstallStatus(
    val state: MigrationAppInstallState = MigrationAppInstallState.READY,
    val message: String = ""
)

object AppPackageInstaller {
    private val _statuses = MutableStateFlow<Map<String, MigrationAppInstallStatus>>(emptyMap())
    val statuses = _statuses.asStateFlow()

    private val installQueue = ArrayDeque<File>()
    private var currentPackagePath: String? = null

    fun receivedPackages(migrationId: String? = null): List<File> {
        val root = MigrationStorageLayout.appsRoot()
        val searchRoot = migrationId?.takeIf { it.isNotBlank() }?.let { File(root, it) } ?: root
        if (!searchRoot.isDirectory) return emptyList()
        val direct = searchRoot.listFiles()
            ?.filter { dir -> dir.isDirectory && containsApk(dir) }
            .orEmpty()
        if (direct.isNotEmpty() || migrationId != null) return direct.sortedBy { it.name.lowercase() }
        return searchRoot.listFiles()
            ?.filter(File::isDirectory)
            ?.flatMap { migration -> migration.listFiles()?.filter { it.isDirectory && containsApk(it) }.orEmpty() }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    fun requestInstall(activity: Activity, packageDirectory: File): InstallStartResult {
        val permission = ensureInstallPermission(activity)
        if (permission != null) return permission
        synchronized(this) {
            if (currentPackagePath != null || installQueue.isNotEmpty()) return InstallStartResult.STARTED
            installQueue.add(packageDirectory)
        }
        return startNext(activity.applicationContext)
    }

    fun requestInstallAll(activity: Activity, packageDirectories: List<File>): InstallStartResult {
        val permission = ensureInstallPermission(activity)
        if (permission != null) return permission
        val valid = packageDirectories.filter { it.isDirectory && containsApk(it) }
        if (valid.isEmpty()) return InstallStartResult.NO_APKS
        synchronized(this) {
            if (currentPackagePath != null || installQueue.isNotEmpty()) return InstallStartResult.STARTED
            valid.forEach(installQueue::addLast)
            updateStatuses(valid.associate { it.absolutePath to MigrationAppInstallStatus() })
        }
        return startNext(activity.applicationContext)
    }

    fun statusFor(packageDirectory: File): MigrationAppInstallStatus =
        _statuses.value[packageDirectory.absolutePath] ?: MigrationAppInstallStatus()

    private fun ensureInstallPermission(activity: Activity): InstallStartResult? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return InstallStartResult.PERMISSION_REQUIRED
        }
        return null
    }

    @Synchronized
    private fun startNext(context: Context): InstallStartResult {
        if (currentPackagePath != null) return InstallStartResult.STARTED
        val directory = installQueue.pollFirst() ?: return InstallStartResult.NO_APKS
        val result = startSession(context, directory)
        if (result != InstallStartResult.STARTED) {
            updateStatus(directory.absolutePath, MigrationAppInstallState.FAILED, result.name)
            currentPackagePath = null
            if (installQueue.isNotEmpty()) startNext(context)
        }
        return result
    }

    private fun startSession(context: Context, packageDirectory: File): InstallStartResult {
        val apkFiles = packageDirectory.listFiles()
            ?.filter { it.isFile && it.extension.equals("apk", true) }
            ?.sortedWith(compareBy<File> { it.name != "base.apk" }.thenBy { it.name })
            .orEmpty()
        if (apkFiles.isEmpty()) return InstallStartResult.NO_APKS

        return try {
            updateStatus(packageDirectory.absolutePath, MigrationAppInstallState.PREPARING, "正在准备安装")
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setSize(apkFiles.sumOf { it.length() })
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            currentPackagePath = packageDirectory.absolutePath
            installer.openSession(sessionId).use { session ->
                apkFiles.forEachIndexed { index, apk ->
                    FileInputStream(apk).use { input ->
                        session.openWrite("${index}-${apk.name}", 0, apk.length()).use { output ->
                            input.copyTo(output, 1024 * 1024)
                            session.fsync(output)
                        }
                    }
                }
                val intent = Intent(context, MigrationInstallReceiver::class.java).apply {
                    action = ACTION_INSTALL_RESULT
                    putExtra(EXTRA_PACKAGE_DIR, packageDirectory.absolutePath)
                }
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
            }
            InstallStartResult.STARTED
        } catch (error: Throwable) {
            currentPackagePath = null
            updateStatus(
                packageDirectory.absolutePath,
                MigrationAppInstallState.FAILED,
                error.message ?: "启动安装失败"
            )
            InstallStartResult.FAILED
        }
    }

    internal fun onPendingConfirmation(path: String) {
        updateStatus(path, MigrationAppInstallState.WAITING_CONFIRMATION, "等待系统安装确认")
    }

    internal fun onInstallFinished(context: Context, path: String, status: Int, message: String?) {
        val success = status == PackageInstaller.STATUS_SUCCESS
        updateStatus(
            path,
            if (success) MigrationAppInstallState.INSTALLED else MigrationAppInstallState.FAILED,
            if (success) "安装完成" else message?.takeIf { it.isNotBlank() } ?: installStatusMessage(status)
        )
        synchronized(this) {
            if (currentPackagePath == path) currentPackagePath = null
        }
        startNext(context.applicationContext)
    }

    @Synchronized
    private fun updateStatus(path: String, state: MigrationAppInstallState, message: String) {
        _statuses.value = _statuses.value.toMutableMap().apply {
            put(path, MigrationAppInstallStatus(state, message))
        }
    }

    @Synchronized
    private fun updateStatuses(values: Map<String, MigrationAppInstallStatus>) {
        _statuses.value = _statuses.value.toMutableMap().apply { putAll(values) }
    }

    private fun containsApk(directory: File): Boolean =
        directory.listFiles()?.any { it.isFile && it.extension.equals("apk", true) } == true

    private fun installStatusMessage(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "用户取消安装"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "系统阻止安装"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "与已安装应用冲突"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "应用与新手机不兼容"
        PackageInstaller.STATUS_FAILURE_INVALID -> "APK 无效或不完整"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足"
        else -> "安装失败 ($status)"
    }

    enum class InstallStartResult { STARTED, PERMISSION_REQUIRED, NO_APKS, FAILED }

    const val ACTION_INSTALL_RESULT = "com.alex.speedshare.migration.INSTALL_RESULT"
    const val EXTRA_PACKAGE_DIR = "package_dir"
}

class MigrationInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppPackageInstaller.ACTION_INSTALL_RESULT) return
        val path = intent.getStringExtra(AppPackageInstaller.EXTRA_PACKAGE_DIR).orEmpty()
        if (path.isBlank()) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            AppPackageInstaller.onPendingConfirmation(path)
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirm != null) context.startActivity(confirm)
            return
        }
        AppPackageInstaller.onInstallFinished(
            context = context,
            path = path,
            status = status,
            message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        )
    }
}
