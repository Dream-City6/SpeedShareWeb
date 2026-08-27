package com.alex.speedshare.migration

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.io.FileInputStream

object AppPackageInstaller {
    fun receivedPackages(): List<File> {
        val root = File(Environment.getExternalStorageDirectory(), "Download/SpeedShare/Apps")
        return root.listFiles()
            ?.filter { dir -> dir.isDirectory && dir.listFiles()?.any { it.extension.equals("apk", true) } == true }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    fun requestInstall(activity: Activity, packageDirectory: File): InstallStartResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return InstallStartResult.PERMISSION_REQUIRED
        }
        val apkFiles = packageDirectory.listFiles()
            ?.filter { it.isFile && it.extension.equals("apk", true) }
            ?.sortedWith(compareBy<File> { it.name != "base.apk" }.thenBy { it.name })
            .orEmpty()
        if (apkFiles.isEmpty()) return InstallStartResult.NO_APKS

        return try {
            val installer = activity.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setSize(apkFiles.sumOf { it.length() })
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apkFiles.forEachIndexed { index, apk ->
                    FileInputStream(apk).use { input ->
                        session.openWrite("${index}-${apk.name}", 0, apk.length()).use { output ->
                            input.copyTo(output, 1024 * 1024)
                            session.fsync(output)
                        }
                    }
                }
                val intent = Intent(activity, MigrationInstallReceiver::class.java).apply {
                    action = ACTION_INSTALL_RESULT
                    putExtra(EXTRA_PACKAGE_DIR, packageDirectory.absolutePath)
                }
                val pending = PendingIntent.getBroadcast(
                    activity,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
            }
            InstallStartResult.STARTED
        } catch (_: Throwable) {
            InstallStartResult.FAILED
        }
    }

    enum class InstallStartResult { STARTED, PERMISSION_REQUIRED, NO_APKS, FAILED }

    const val ACTION_INSTALL_RESULT = "com.alex.speedshare.migration.INSTALL_RESULT"
    const val EXTRA_PACKAGE_DIR = "package_dir"
}

class MigrationInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppPackageInstaller.ACTION_INSTALL_RESULT) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) context.startActivity(confirm)
            }
            else -> Unit
        }
    }
}
