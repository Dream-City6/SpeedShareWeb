package com.alex.speedshare.migration

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alex.speedshare.AppSettings
import com.alex.speedshare.ui.theme.SpeedShareTheme
import java.io.File

class MigrationResultDetailsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings = remember { AppSettings.load(this) }
            SpeedShareTheme(themeMode = settings.themeMode) {
                MigrationResultDetailsScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun MigrationResultDetailsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val controller = remember { ResilientMigrationController.get(context) }
    val state by controller.state.collectAsState()
    val installStatuses by AppPackageInstaller.statuses.collectAsState()
    val report = state.report
    val task = state.pendingTask
    val migrationId = state.activeMigrationId
    val packages = remember(migrationId, report, task) {
        AppPackageInstaller.receivedPackages(migrationId)
    }
    val failures = remember(task) {
        task?.failedReasons?.entries?.sortedBy { it.key }.orEmpty()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("换机详细报告", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text(
                        migrationId?.let { "任务 ${it.take(8)}" } ?: "当前换机任务",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onClose) { Text("返回") }
            }

            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("迁移结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                    if (report != null) {
                        DetailLine("总数据", formatDetailBytes(report.totalBytes))
                        DetailLine("已完成", formatDetailBytes(report.transferredBytes))
                        DetailLine("成功项目", report.successCount.toString())
                        DetailLine("失败 / 待续传", report.failedCount.toString())
                        DetailLine("平均速度", "${formatDetailBytes(report.averageBytesPerSecond)}/s")
                    } else if (task != null) {
                        DetailLine("总数据", formatDetailBytes(task.totalBytes))
                        DetailLine("已完成", formatDetailBytes(task.completedBytes))
                        DetailLine("剩余项目", task.pendingItems.size.toString())
                    } else {
                        Text("当前没有可显示的换机报告。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (failures.isNotEmpty()) {
                Text("失败详情", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        failures.forEach { (path, rawReason) ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    File(path).name.ifBlank { path },
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    path,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    friendlyFailureReason(rawReason),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                Button(onClick = controller::resumePendingTask, modifier = Modifier.fillMaxWidth()) {
                    Text("重新传输失败项目")
                }
            }

            if (packages.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("收到的应用", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        Text("${packages.size} 个应用 · 按顺序交给 Android 系统安装", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        enabled = activity != null,
                        onClick = {
                            val result = activity?.let { AppPackageInstaller.requestInstallAll(it, packages) }
                            Toast.makeText(context, installStartMessage(result), Toast.LENGTH_LONG).show()
                        }
                    ) { Text("安装全部") }
                }

                packages.forEach { directory ->
                    val installStatus = installStatuses[directory.absolutePath] ?: MigrationAppInstallStatus()
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(directory.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    installStateLabel(installStatus),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (installStatus.state) {
                                        MigrationAppInstallState.INSTALLED -> MaterialTheme.colorScheme.primary
                                        MigrationAppInstallState.FAILED -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            OutlinedButton(
                                enabled = activity != null && installStatus.state !in setOf(
                                    MigrationAppInstallState.PREPARING,
                                    MigrationAppInstallState.WAITING_CONFIRMATION,
                                    MigrationAppInstallState.INSTALLED
                                ),
                                onClick = {
                                    val result = activity?.let { AppPackageInstaller.requestInstall(it, directory) }
                                    Toast.makeText(context, installStartMessage(result), Toast.LENGTH_LONG).show()
                                }
                            ) {
                                Text(if (installStatus.state == MigrationAppInstallState.FAILED) "重试" else "安装")
                            }
                        }
                    }
                }
            }

            if (failures.isEmpty() && packages.isEmpty()) {
                Text(
                    "没有失败项目，也没有待安装应用。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

private fun installStateLabel(status: MigrationAppInstallStatus): String = when (status.state) {
    MigrationAppInstallState.READY -> "等待安装"
    MigrationAppInstallState.PREPARING -> status.message.ifBlank { "正在准备安装" }
    MigrationAppInstallState.WAITING_CONFIRMATION -> status.message.ifBlank { "等待系统确认" }
    MigrationAppInstallState.INSTALLED -> "已安装"
    MigrationAppInstallState.FAILED -> status.message.ifBlank { "安装失败" }
}

private fun installStartMessage(result: AppPackageInstaller.InstallStartResult?): String = when (result) {
    AppPackageInstaller.InstallStartResult.STARTED -> "已开始安装"
    AppPackageInstaller.InstallStartResult.PERMISSION_REQUIRED -> "请先允许 SpeedShare 安装未知应用，然后再点一次"
    AppPackageInstaller.InstallStartResult.NO_APKS -> "没有找到可安装的 APK"
    AppPackageInstaller.InstallStartResult.FAILED -> "启动安装失败"
    null -> "无法启动系统安装器"
}

private fun friendlyFailureReason(reason: String): String = when {
    reason.contains("insufficient_space", ignoreCase = true) -> "新手机存储空间不足"
    reason.contains("receiver_storage_permission_required", ignoreCase = true) -> "新手机缺少全部文件访问权限"
    reason.contains("session_required", ignoreCase = true) -> "换机会话已失效，需要重新连接"
    reason.contains("hash_mismatch", ignoreCase = true) -> "完整性校验失败，文件会重新传输"
    reason.contains("timeout", ignoreCase = true) -> "网络超时"
    reason.contains("reset", ignoreCase = true) || reason.contains("broken pipe", ignoreCase = true) -> "网络连接中断"
    reason.contains("source_ended_early", ignoreCase = true) -> "旧手机源文件在迁移过程中发生变化"
    else -> reason.ifBlank { "传输失败" }
}

private fun formatDetailBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
