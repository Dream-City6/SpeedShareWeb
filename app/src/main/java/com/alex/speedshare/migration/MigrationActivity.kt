package com.alex.speedshare.migration

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.alex.speedshare.AppSettings
import com.alex.speedshare.ui.theme.SpeedShareTheme
import java.util.Locale

class MigrationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings = remember { AppSettings.load(this) }
            SpeedShareTheme(themeMode = settings.themeMode) {
                MigrationScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun MigrationScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val controller = remember { MigrationController.get(context) }
    val state by controller.state.collectAsState()
    var storageAccess by remember { mutableStateOf(hasMigrationStorageAccess()) }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) storageAccess = hasMigrationStorageAccess()
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    state.incomingPairRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { controller.rejectPair() },
            title = { Text("连接请求") },
            text = {
                Text("${request.peer.name}${request.peer.model.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()} 想连接这台手机并开始换机。")
            },
            confirmButton = { Button(onClick = controller::acceptPair) { Text("允许") } },
            dismissButton = { TextButton(onClick = controller::rejectPair) { Text("拒绝") } }
        )
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("一键换机", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                    Text(
                        "同一 Wi‑Fi 自动发现 · 无需扫码 · 可断点续传",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onClose) { Text("返回") }
            }

            StatusCard(state)

            val roleNeedsStorage = state.role == MigrationRole.OLD_PHONE || state.role == MigrationRole.NEW_PHONE
            if (!storageAccess && roleNeedsStorage) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("需要全部文件访问权限", fontWeight = FontWeight.Bold)
                        Text(
                            if (state.role == MigrationRole.NEW_PHONE) {
                                "新手机需要此权限，才能把 DCIM、Download、Documents 等内容恢复到原目录。Android/data 与其他应用私有数据仍受系统限制。"
                            } else {
                                "旧手机需要此权限，用于扫描并保持 DCIM、Download、Documents 等原目录结构。Android/data 与其他应用私有数据仍受系统限制。"
                            }
                        )
                        Button(onClick = { openAllFilesAccess(context) }) { Text("授予权限") }
                    }
                }
            }

            when (state.stage) {
                MigrationStage.DISCOVERY, MigrationStage.PAIRING -> DiscoverySection(state, controller)
                MigrationStage.SPEED_TEST -> SpeedTestSection(state, controller)
                MigrationStage.ROLE -> RoleSection(controller)
                MigrationStage.SELECTION -> SelectionSection(state, controller, storageAccess)
                MigrationStage.TRANSFERRING, MigrationStage.VERIFYING -> ProgressSection(state)
                MigrationStage.COMPLETE -> ReportSection(state, controller)
            }

            if (state.connectedPeer != null && state.stage !in setOf(MigrationStage.DISCOVERY, MigrationStage.PAIRING)) {
                OutlinedButton(onClick = controller::reset, modifier = Modifier.fillMaxWidth()) {
                    Text("结束当前连接并返回设备列表")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: MigrationUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.status.ifBlank { "准备换机" }, fontWeight = FontWeight.Bold)
            state.connectedPeer?.let {
                Text(
                    "已连接：${it.name}${it.model.takeIf { model -> model.isNotBlank() }?.let { model -> " · $model" }.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun DiscoverySection(state: MigrationUiState, controller: MigrationController) {
    SectionCard("附近的 SpeedShare") {
        if (state.peers.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.height(28.dp))
                Text("正在搜索同一 Wi‑Fi 下的设备…")
            }
            Text("两台手机都进入【一键换机】页面后会自动出现，无需扫码或输入 IP。", style = MaterialTheme.typography.bodySmall)
        } else {
            state.peers.forEachIndexed { index, peer ->
                if (index > 0) HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(peer.name, fontWeight = FontWeight.Bold)
                        Text(
                            listOf(peer.model, peer.appVersion.takeIf { it.isNotBlank() }?.let { "SpeedShare $it" })
                                .filterNotNull()
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(enabled = !state.pairing, onClick = { controller.connect(peer) }) {
                        Text(if (state.pairing) "连接中" else "连接")
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedTestSection(state: MigrationUiState, controller: MigrationController) {
    SectionCard("Wi‑Fi 实际速度") {
        if (state.speedTesting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("正在实际互传测试数据，测试发送、接收、延迟和稳定性…")
        }
        state.speedResult?.let { result ->
            MetricRow("发送速度", formatRate(result.uploadBytesPerSecond))
            MetricRow("接收速度", formatRate(result.downloadBytesPerSecond))
            MetricRow("延迟", "${result.latencyMs} ms")
            MetricRow("稳定性", "${result.stabilityPercent}%")
            Text(networkAdvice(result), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!state.speedTesting) {
                Button(onClick = controller::confirmNetwork, modifier = Modifier.fillMaxWidth()) {
                    Text("使用当前 Wi‑Fi，继续")
                }
            }
        }
        OutlinedButton(enabled = !state.speedTesting, onClick = controller::runSpeedTest, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.speedResult == null) "开始测速" else "重新测试")
        }
    }
}

@Composable
private fun RoleSection(controller: MigrationController) {
    SectionCard("这台手机是？") {
        Text("只需在其中一台选择，另一台会自动切换成相反角色。")
        Button(onClick = { controller.setRole(MigrationRole.OLD_PHONE) }, modifier = Modifier.fillMaxWidth()) {
            Text("这是旧手机 · 发送数据")
        }
        OutlinedButton(onClick = { controller.setRole(MigrationRole.NEW_PHONE) }, modifier = Modifier.fillMaxWidth()) {
            Text("这是新手机 · 接收数据")
        }
    }
}

@Composable
private fun SelectionSection(state: MigrationUiState, controller: MigrationController, storageAccess: Boolean) {
    if (state.role == MigrationRole.NEW_PHONE) {
        SectionCard("新手机已准备好") {
            Text(
                if (storageAccess) {
                    "保持此页面打开。旧手机完成内容选择后会自动开始传输，目录会尽可能恢复到原位置。"
                } else {
                    "请先授予上方的全部文件访问权限。授权后新手机才能按原目录接收换机内容。"
                }
            )
            state.speedResult?.let { Text("当前连接平均约 ${formatRate(it.averageBytesPerSecond)}") }
        }
        return
    }

    SectionCard("选择迁移内容") {
        if (state.scanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("正在扫描存储和已安装应用…")
        }
        MigrationCategory.entries.forEach { category ->
            val checked = category in state.selectedCategories
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = checked, onCheckedChange = { controller.toggleCategory(category) })
                Column(Modifier.weight(1f)) {
                    Text(categoryLabel(category), fontWeight = FontWeight.SemiBold)
                    Text(
                        "${state.scanResult.count(category)} 项 · ${formatBytes(state.scanResult.bytes(category))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (state.scanResult.apps.isNotEmpty()) {
            Text(
                "应用会保留 base.apk 和 split APK 组合，接收到 Download/SpeedShare/Apps，最大限度保持安装兼容性。应用登录状态和私有数据无法由普通 Android 应用完整迁移。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = controller::scanContent,
                enabled = !state.scanning,
                modifier = Modifier.weight(1f)
            ) { Text("重新扫描") }
            Button(
                onClick = controller::startTransfer,
                enabled = !state.scanning && storageAccess &&
                    (state.scanResult.files.isNotEmpty() || state.scanResult.apps.isNotEmpty()),
                modifier = Modifier.weight(1f)
            ) { Text("开始换机") }
        }
    }
}

@Composable
private fun ProgressSection(state: MigrationUiState) {
    val p = state.progress
    SectionCard("正在换机") {
        LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
        MetricRow("进度", "${(p.fraction * 100).toInt()}%")
        MetricRow("数据", "${formatBytes(p.transferredBytes)} / ${formatBytes(p.totalBytes)}")
        MetricRow("速度", formatRate(p.bytesPerSecond))
        MetricRow("文件", "${p.completedItems} / ${p.totalItems}")
        if (p.skippedItems > 0) MetricRow("自动跳过重复", p.skippedItems.toString())
        if (p.failedItems > 0) MetricRow("失败", p.failedItems.toString())
        if (p.currentName.isNotBlank()) Text("当前：${p.currentName}", style = MaterialTheme.typography.bodySmall)
        Text(
            "断线后重新连接并再次发送时，会根据同一文件的临时片段继续传输；完整文件使用 SHA‑256 校验。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReportSection(state: MigrationUiState, controller: MigrationController) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val receivedApps = remember(state.report, state.role) {
        if (state.role == MigrationRole.NEW_PHONE) AppPackageInstaller.receivedPackages() else emptyList()
    }
    val report = state.report
    SectionCard("换机报告") {
        if (report == null) {
            Text("迁移已结束")
        } else {
            MetricRow("总数据", formatBytes(report.totalBytes))
            MetricRow("成功项目", report.successCount.toString())
            MetricRow("重复跳过", report.skippedCount.toString())
            MetricRow("失败项目", report.failedCount.toString())
            MetricRow("耗时", formatDuration(report.durationMs))
            MetricRow("平均速度", formatRate(report.averageBytesPerSecond))
            Text(
                if (report.failedCount == 0) {
                    "数据传输与完整性检查已完成。"
                } else {
                    "有 ${report.failedCount} 项未成功，可返回后重新连接再传；已经完成的相同文件会自动跳过。"
                },
                color = if (report.failedCount == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }

        if (receivedApps.isNotEmpty()) {
            HorizontalDivider()
            Text("已接收应用 ${receivedApps.size} 个", fontWeight = FontWeight.Bold)
            Text(
                "每个目录包含原机可读取到的 base.apk 和 split APK。点击安装后由 Android 系统确认安装。",
                style = MaterialTheme.typography.bodySmall
            )
            receivedApps.forEach { directory ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(directory.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        enabled = activity != null,
                        onClick = {
                            val result = activity?.let { AppPackageInstaller.requestInstall(it, directory) }
                            val text = when (result) {
                                AppPackageInstaller.InstallStartResult.STARTED -> "已交给系统安装器"
                                AppPackageInstaller.InstallStartResult.PERMISSION_REQUIRED -> "请先允许 SpeedShare 安装未知应用，然后再次点击安装"
                                AppPackageInstaller.InstallStartResult.NO_APKS -> "没有找到 APK"
                                AppPackageInstaller.InstallStartResult.FAILED -> "启动安装失败"
                                null -> "无法启动安装"
                            }
                            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                        }
                    ) { Text("安装") }
                }
            }
        }

        Button(onClick = controller::reset, modifier = Modifier.fillMaxWidth()) { Text("完成") }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

private fun hasMigrationStorageAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

private fun openAllFilesAccess(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.recoverCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

private fun categoryLabel(category: MigrationCategory): String = when (category) {
    MigrationCategory.PHOTOS -> "照片"
    MigrationCategory.VIDEOS -> "视频"
    MigrationCategory.MUSIC -> "音乐 / 录音"
    MigrationCategory.DOCUMENTS -> "文档"
    MigrationCategory.DOWNLOADS -> "下载文件"
    MigrationCategory.OTHER -> "其他文件"
    MigrationCategory.APPS -> "应用 APK（含 Split APK）"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(
        Locale.getDefault(),
        "%.2f GB",
        bytes / 1024.0 / 1024.0 / 1024.0
    )
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatRate(bytes: Long): String = "${formatBytes(bytes)}/s"

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000L
    val minutes = seconds / 60L
    val remain = seconds % 60L
    return if (minutes > 0) "${minutes}分${remain}秒" else "${remain}秒"
}

private fun networkAdvice(result: SpeedTestResult): String = when {
    result.averageBytesPerSecond >= 50L * 1024L * 1024L && result.stabilityPercent >= 90 ->
        "连接质量：优秀，适合大容量换机。"
    result.averageBytesPerSecond >= 15L * 1024L * 1024L ->
        "连接质量：良好。若有大量视频，建议使用 5GHz / 6GHz Wi‑Fi。"
    else ->
        "当前网络偏慢。建议两台手机靠近路由器、使用 5GHz / 6GHz Wi‑Fi，并暂时关闭 VPN。"
}
