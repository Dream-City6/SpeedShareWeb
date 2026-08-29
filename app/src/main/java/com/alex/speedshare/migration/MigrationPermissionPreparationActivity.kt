package com.alex.speedshare.migration

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.alex.speedshare.AppSettings
import com.alex.speedshare.ui.theme.SpeedShareTheme

class MigrationPermissionPreparationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_INTRO_SEEN, false) && MigrationPermissionRequirements.snapshot(this).coreReady) {
            openMigration()
            return
        }
        setContent {
            val settings = remember { AppSettings.load(this) }
            SpeedShareTheme(themeMode = settings.themeMode) {
                PermissionPreparationScreen(
                    onReady = {
                        prefs.edit().putBoolean(KEY_INTRO_SEEN, true).apply()
                        openMigration()
                    },
                    onCancel = { finish() }
                )
            }
        }
    }

    private fun openMigration() {
        startActivity(Intent(this, ResilientMigrationActivity::class.java))
        finish()
    }

    companion object {
        private const val PREFS = "speedshare_migration_permissions"
        private const val KEY_INTRO_SEEN = "permission_intro_seen"
    }
}

internal data class MigrationPermissionSnapshot(
    val storage: Boolean,
    val media: Boolean,
    val apps: Boolean,
    val contacts: Boolean,
    val notifications: Boolean,
    val partialVisualMedia: Boolean
) {
    val coreReady: Boolean get() = storage && media && apps
}

internal object MigrationPermissionRequirements {
    fun snapshot(context: Context): MigrationPermissionSnapshot {
        val storage = hasStorageAccess(context)
        val media = hasMediaAccess(context, storage)
        val apps = InstalledAppsPermission.isGranted(context)
        val contacts = granted(context, Manifest.permission.READ_CONTACTS)
        val notifications = Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)
        val partial = Build.VERSION.SDK_INT >= 34 &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
            !(granted(context, Manifest.permission.READ_MEDIA_IMAGES) && granted(context, Manifest.permission.READ_MEDIA_VIDEO))
        return MigrationPermissionSnapshot(storage, media, apps, contacts, notifications, partial)
    }

    fun runtimePermissions(context: Context): List<String> = buildList {
        when {
            Build.VERSION.SDK_INT >= 33 -> {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        add(Manifest.permission.READ_CONTACTS)
        if (InstalledAppsPermission.isRuntimeManaged(context)) add(InstalledAppsPermission.XIAOMI_PERMISSION)
    }.distinct().filter { !granted(context, it) }

    fun hasStorageAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT <= 29 -> granted(context, Manifest.permission.READ_EXTERNAL_STORAGE) &&
            granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else -> granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasMediaAccess(context: Context, storage: Boolean): Boolean = when {
        Build.VERSION.SDK_INT >= 33 ->
            granted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
                granted(context, Manifest.permission.READ_MEDIA_VIDEO) &&
                granted(context, Manifest.permission.READ_MEDIA_AUDIO)
        Build.VERSION.SDK_INT >= 30 -> storage || granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun allFilesIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) return null
        val appSpecific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        return if (appSpecific.resolveActivity(context.packageManager) != null) {
            appSpecific
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
}

@Composable
private fun PermissionPreparationScreen(onReady: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var snapshot by remember { mutableStateOf(MigrationPermissionRequirements.snapshot(context)) }

    val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        snapshot = MigrationPermissionRequirements.snapshot(context)
        if (snapshot.coreReady) onReady()
    }
    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        snapshot = MigrationPermissionRequirements.snapshot(context)
        val special = MigrationPermissionRequirements.allFilesIntent(context)
        if (special != null) {
            allFilesLauncher.launch(special)
        } else if (snapshot.coreReady) {
            onReady()
        }
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        snapshot = MigrationPermissionRequirements.snapshot(context)
        if (snapshot.coreReady) onReady()
    }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) snapshot = MigrationPermissionRequirements.snapshot(context)
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    fun requestEverything() {
        val runtime = MigrationPermissionRequirements.runtimePermissions(context)
        val special = MigrationPermissionRequirements.allFilesIntent(context)
        when {
            runtime.isNotEmpty() -> runtimeLauncher.launch(runtime.toTypedArray())
            special != null -> allFilesLauncher.launch(special)
            snapshot.coreReady -> onReady()
            else -> settingsLauncher.launch(MigrationPermissionRequirements.appDetailsIntent(context))
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("换机前准备权限", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text(
                "为了避免进入内容页后出现“照片 0、下载文件 0、应用不完整”，SpeedShare 会先把扫描所需权限准备好。点一次“统一授权”，系统可能依次显示权限弹窗和“所有文件访问”页面。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            PermissionRow(
                "照片 / 视频 / 音乐",
                "读取媒体文件和生成缩略图；需要完整媒体权限。",
                snapshot.media,
                if (snapshot.partialVisualMedia) "当前只允许了部分照片，请改为允许全部" else null
            )
            PermissionRow("文件与下载", "扫描 Download、Documents、DCIM 等共享存储，并在新手机恢复原目录。", snapshot.storage)
            PermissionRow("应用列表", "识别已安装 App、版本、图标以及 base/split APK。小米/HyperOS 可能单独弹出“获取应用列表”。", snapshot.apps)
            PermissionRow("联系人（可选）", "只有迁移联系人时使用，用于导出标准 VCF。拒绝不会影响照片和文件换机。", snapshot.contacts, optional = true)
            PermissionRow("通知（可选）", "长时间换机在后台时显示进度通知。拒绝不会影响扫描。", snapshot.notifications, optional = true)

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (snapshot.coreReady) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    if (snapshot.coreReady) "核心权限已经准备完成，可以进入换机。" else "核心权限未完整前不会开始扫描，避免生成不完整的 0 项结果。",
                    modifier = Modifier.padding(14.dp),
                    fontWeight = FontWeight.Bold
                )
            }

            Button(onClick = ::requestEverything, modifier = Modifier.fillMaxWidth()) {
                Text(if (snapshot.coreReady) "进入一键换机" else "统一授权并继续")
            }
            if (!snapshot.coreReady) {
                OutlinedButton(
                    onClick = { settingsLauncher.launch(MigrationPermissionRequirements.appDetailsIntent(context)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("打开系统权限设置")
                }
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("暂不换机") }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    warning: String? = null,
    optional: Boolean = false
) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontWeight = FontWeight.Black)
                Text(
                    when {
                        granted -> "已授权"
                        optional -> "可选"
                        else -> "需要授权"
                    },
                    color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
        }
    }
}
