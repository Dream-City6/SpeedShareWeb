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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.alex.speedshare.AppSettings
import com.alex.speedshare.ui.theme.SpeedShareTheme
import java.io.File
import java.util.Locale

class ResilientMigrationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings = remember { AppSettings.load(this) }
            SpeedShareTheme(themeMode = settings.themeMode) {
                ResilientMigrationScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun ResilientMigrationScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val controller = remember { ResilientMigrationController.get(context) }
    val state by controller.state.collectAsState()
    var storageAccess by remember { mutableStateOf(hasStorageAccessV2()) }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) storageAccess = hasStorageAccessV2()
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    state.incomingPairRequest?.let { request ->
        AlertDialog(
            onDismissRequest = controller::rejectPair,
            title = { Text("确认连接") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${request.peer.name} 想与你建立换机连接。")
                    Text(
                        "确认后双方会建立本次换机专用 Session Token；结束连接后立即失效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { Button(onClick = controller::acceptPair) { Text("允许连接") } },
            dismissButton = { TextButton(onClick = controller::rejectPair) { Text("拒绝") } }
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeaderV2(state, onClose)
            HeroV2(state)
            StepsV2(state.stage)
            StatusV2(state)

            if (state.pendingTask != null && state.stage == MigrationStage.DISCOVERY) {
                ResumeTaskCard(state.pendingTask!!, controller)
            }

            if (!storageAccess && state.role != MigrationRole.UNSET) {
                PermissionCardV2(state.role == MigrationRole.NEW_PHONE) {
                    openAllFilesAccessV2(context)
                }
            }

            when (state.stage) {
                MigrationStage.DISCOVERY, MigrationStage.PAIRING -> DiscoveryV2(state, controller)
                MigrationStage.SPEED_TEST -> SpeedV2(state, controller)
                MigrationStage.ROLE -> RoleV2(controller)
                MigrationStage.SELECTION -> SelectionV2(state, controller, storageAccess)
                MigrationStage.TRANSFERRING, MigrationStage.VERIFYING -> TransferV2(state, controller)
                MigrationStage.COMPLETE -> ReportV2(state, controller)
            }

            if (state.connectedPeer != null && state.stage !in setOf(MigrationStage.TRANSFERRING, MigrationStage.VERIFYING)) {
                OutlinedButton(onClick = controller::reset, modifier = Modifier.fillMaxWidth()) {
                    Text("结束当前连接")
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("可靠换机", fontWeight = FontWeight.Bold)
                    Text(
                        "已启用任务持久化、Session Token、断点续传、自动重连和新机空间检查。当前传输内容仍未做 TLS 加密，建议在可信 Wi‑Fi 下使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HeaderV2(state: ResilientMigrationState, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text("SpeedShare 换机", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text("可靠续传 · 自动重连 · 空间检查", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onClose) {
            Text(if (state.stage in setOf(MigrationStage.TRANSFERRING, MigrationStage.VERIFYING)) "后台运行" else "返回")
        }
    }
}

@Composable
private fun HeroV2(state: ResilientMigrationState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer)
                )
            )
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("一键换机", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f)) {
                    Text(
                        stageLabelV2(state.stage),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DeviceTileV2(Modifier.weight(1f), state.localDeviceName.ifBlank { "本机" }, roleLabelV2(state.role))
                Text("→", fontSize = 24.sp, fontWeight = FontWeight.Black)
                DeviceTileV2(
                    Modifier.weight(1f),
                    state.connectedPeer?.name ?: "等待设备",
                    state.connectedPeer?.model?.takeIf { it.isNotBlank() } ?: "同一 Wi‑Fi 自动发现"
                )
            }
        }
    }
}

@Composable
private fun DeviceTileV2(modifier: Modifier, title: String, subtitle: String) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun StepsV2(stage: MigrationStage) {
    val labels = listOf("连接", "测速", "角色", "内容", "迁移")
    val current = stepIndexV2(stage)
    Card(shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
            labels.forEachIndexed { index, label ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(
                            if (index <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (index < current) "✓" else "${index + 1}",
                            color = if (index <= current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun StatusV2(state: ResilientMigrationState) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.error == null) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.status.ifBlank { "准备换机" }, fontWeight = FontWeight.Bold)
            if (state.reconnecting) Text("正在自动寻找原设备…", color = MaterialTheme.colorScheme.primary)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ResumeTaskCard(task: PendingMigrationTask, controller: ResilientMigrationController) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("发现未完成的换机", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
            Text("目标：${task.peerName}")
            Text(
                "已完成 ${formatBytesV2(task.completedBytes)} / ${formatBytesV2(task.totalBytes)} · 剩余 ${task.pendingItems.size} 项",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = controller::resumePendingTask, modifier = Modifier.fillMaxWidth()) { Text("继续上次换机") }
            TextButton(onClick = controller::discardPendingTask, modifier = Modifier.fillMaxWidth()) { Text("删除这条未完成任务") }
        }
    }
}

@Composable
private fun PermissionCardV2(receiver: Boolean, onGrant: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("需要存储权限", fontWeight = FontWeight.Black)
            Text(if (receiver) "新手机需要全部文件访问权限才能恢复原目录。" else "旧手机需要全部文件访问权限才能完整扫描数据。")
            Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text("去授权") }
        }
    }
}

@Composable
private fun DiscoveryV2(state: ResilientMigrationState, controller: ResilientMigrationController) {
    SectionV2("附近设备", "两台手机连接同一个 Wi‑Fi，并同时打开换机页面。")
    if (state.peers.isEmpty()) {
        Card(shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text("正在自动搜索", fontWeight = FontWeight.Bold)
                    Text("无需扫码，也不用输入 IP", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    } else {
        state.peers.forEach { peer ->
            Card(shape = RoundedCornerShape(18.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Text(peer.name.take(1).uppercase(), fontWeight = FontWeight.Black)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(peer.name, fontWeight = FontWeight.Black)
                        Text(listOf(peer.model, peer.appVersion).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = { controller.connect(peer) }, enabled = !state.pairing) { Text("连接") }
                }
            }
        }
    }
}

@Composable
private fun SpeedV2(state: ResilientMigrationState, controller: ResilientMigrationController) {
    SectionV2("连接质量", "使用真实双向数据测试 Wi‑Fi 速度和稳定性。")
    if (state.speedTesting) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.speedResult?.let { result ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricV2("发送", formatRateV2(result.uploadBytesPerSecond), Modifier.weight(1f))
            MetricV2("接收", formatRateV2(result.downloadBytesPerSecond), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricV2("延迟", "${result.latencyMs} ms", Modifier.weight(1f))
            MetricV2("稳定性", "${result.stabilityPercent}%", Modifier.weight(1f))
        }
        Button(onClick = controller::confirmNetwork, modifier = Modifier.fillMaxWidth(), enabled = !state.speedTesting) {
            Text("使用当前 Wi‑Fi，继续")
        }
    }
    OutlinedButton(onClick = controller::runSpeedTest, enabled = !state.speedTesting, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.speedResult == null) "开始测速" else "重新测速")
    }
}

@Composable
private fun RoleV2(controller: ResilientMigrationController) {
    SectionV2("选择这台手机", "一台选择旧手机，另一台会自动切换为新手机。")
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().clickable { controller.setRole(MigrationRole.OLD_PHONE) }) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("这是旧手机", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            Text("扫描并发送照片、视频、文档和应用", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().clickable { controller.setRole(MigrationRole.NEW_PHONE) }) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("这是新手机", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            Text("接收数据并尽量恢复原目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SelectionV2(state: ResilientMigrationState, controller: ResilientMigrationController, storageAccess: Boolean) {
    if (state.role == MigrationRole.NEW_PHONE) {
        SectionV2("新手机已准备", "保持页面或切到后台即可，系统会通过前台服务保持任务。")
        state.receiverStorage?.let { storage ->
            MetricV2("本机可用空间", formatBytesV2(storage.freeBytes), Modifier.fillMaxWidth())
        }
        return
    }

    val selectedBytes = state.selectedCategories.sumOf { state.scanResult.bytes(it) }
    val selectedCount = state.selectedCategories.sumOf { state.scanResult.count(it) }
    SectionV2("选择迁移内容", "开始前会再次检查新手机空间是否足够。")
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricLineV2("已选择", "${formatBytesV2(selectedBytes)} · $selectedCount 项")
            val receiver = state.receiverStorage
            if (receiver != null) {
                MetricLineV2("新手机可用", formatBytesV2(receiver.freeBytes))
                val enough = receiver.freeBytes >= selectedBytes + 256L * 1024L * 1024L
                Text(
                    if (enough) "空间充足，可以开始" else "空间不足，请减少选择或清理新手机空间",
                    color = if (enough) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            } else {
                TextButton(onClick = controller::refreshReceiverStorage) { Text("重新读取新手机空间") }
            }
        }
    }
    if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
    MigrationCategory.entries.forEach { category ->
        Card(shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = category in state.selectedCategories, onCheckedChange = { controller.toggleCategory(category) })
                Column(Modifier.weight(1f)) {
                    Text(categoryLabelV2(category), fontWeight = FontWeight.Bold)
                    Text(
                        "${state.scanResult.count(category)} 项 · ${formatBytesV2(state.scanResult.bytes(category))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = controller::scanContent, enabled = !state.scanning, modifier = Modifier.weight(1f)) { Text("重新扫描") }
        Button(
            onClick = controller::startTransfer,
            enabled = !state.scanning && storageAccess && selectedCount > 0,
            modifier = Modifier.weight(1f)
        ) { Text("开始换机") }
    }
}

@Composable
private fun TransferV2(state: ResilientMigrationState, controller: ResilientMigrationController) {
    val progress = state.progress
    val remaining = (progress.totalBytes - progress.transferredBytes).coerceAtLeast(0L)
    SectionV2(if (state.paused) "换机已暂停" else if (state.reconnecting) "正在恢复连接" else "正在迁移", "可以切到后台，进度会保留。")
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("${(progress.fraction * 100).toInt()}%", fontSize = 42.sp, fontWeight = FontWeight.Black)
                Text("剩余 ${estimateV2(remaining, progress.bytesPerSecond)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth().height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricV2("实时速度", formatRateV2(progress.bytesPerSecond), Modifier.weight(1f))
                MetricV2("已完成", formatBytesV2(progress.transferredBytes), Modifier.weight(1f))
            }
            MetricLineV2("项目", "${progress.completedItems} / ${progress.totalItems}")
            if (progress.currentName.isNotBlank()) MetricLineV2("正在处理", progress.currentName)
            if (state.reconnecting) Text("网络中断后会自动寻找同一 Device ID；重新配对后从 .part 继续。", color = MaterialTheme.colorScheme.primary)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.paused) {
            Button(onClick = controller::resumeTransfer, modifier = Modifier.weight(1f)) { Text("继续") }
        } else {
            OutlinedButton(onClick = controller::pauseTransfer, modifier = Modifier.weight(1f), enabled = !state.reconnecting) { Text("暂停") }
        }
        OutlinedButton(onClick = controller::cancelTransfer, modifier = Modifier.weight(1f)) { Text("停止并保留进度") }
    }
}

@Composable
private fun ReportV2(state: ResilientMigrationState, controller: ResilientMigrationController) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val report = state.report
    SectionV2("换机报告", "失败项目会保留在任务记录中，可以稍后继续。")
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(if ((report?.failedCount ?: 0) == 0) "换机完成" else "本轮换机结束", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            report?.let {
                MetricLineV2("总数据", formatBytesV2(it.totalBytes))
                MetricLineV2("成功", it.successCount.toString())
                MetricLineV2("失败/待续传", it.failedCount.toString())
                MetricLineV2("平均速度", formatRateV2(it.averageBytesPerSecond))
            }
        }
    }

    val appRoot = remember(state.activeMigrationId, state.report) {
        state.activeMigrationId?.let { migrationId ->
            File(Environment.getExternalStorageDirectory(), "Download/SpeedShare/Apps/$migrationId")
        }
    }
    val packageDirs = remember(appRoot, state.report) {
        appRoot?.listFiles()?.filter { dir -> dir.isDirectory && dir.listFiles()?.any { it.extension.equals("apk", true) } == true }
            ?.sortedBy { it.name.lowercase() }.orEmpty()
    }
    if (packageDirs.isNotEmpty()) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本次收到 ${packageDirs.size} 个应用", fontWeight = FontWeight.Black)
                Text("每次换机使用独立目录，不会把上次留下的 split APK 混入本次安装。", style = MaterialTheme.typography.bodySmall)
                packageDirs.forEach { directory ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(directory.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        OutlinedButton(
                            enabled = activity != null,
                            onClick = {
                                val result = activity?.let { AppPackageInstaller.requestInstall(it, directory) }
                                Toast.makeText(context, result?.name ?: "FAILED", Toast.LENGTH_LONG).show()
                            }
                        ) { Text("安装") }
                    }
                }
            }
        }
    }
    state.pendingTask?.let {
        Button(onClick = controller::resumePendingTask, modifier = Modifier.fillMaxWidth()) { Text("继续剩余 ${it.pendingItems.size} 项") }
    }
    OutlinedButton(onClick = controller::reset, modifier = Modifier.fillMaxWidth()) { Text("返回设备列表") }
}

@Composable
private fun SectionV2(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricV2(label: String, value: String, modifier: Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(13.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun MetricLineV2(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun hasStorageAccessV2(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

private fun openAllFilesAccessV2(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun stepIndexV2(stage: MigrationStage) = when (stage) {
    MigrationStage.DISCOVERY, MigrationStage.PAIRING -> 0
    MigrationStage.SPEED_TEST -> 1
    MigrationStage.ROLE -> 2
    MigrationStage.SELECTION -> 3
    MigrationStage.TRANSFERRING, MigrationStage.VERIFYING, MigrationStage.COMPLETE -> 4
}

private fun stageLabelV2(stage: MigrationStage) = when (stage) {
    MigrationStage.DISCOVERY -> "发现设备"
    MigrationStage.PAIRING -> "建立连接"
    MigrationStage.SPEED_TEST -> "测速"
    MigrationStage.ROLE -> "选择角色"
    MigrationStage.SELECTION -> "选择内容"
    MigrationStage.TRANSFERRING -> "迁移中"
    MigrationStage.VERIFYING -> "校验中"
    MigrationStage.COMPLETE -> "完成"
}

private fun roleLabelV2(role: MigrationRole) = when (role) {
    MigrationRole.OLD_PHONE -> "旧手机 · 发送"
    MigrationRole.NEW_PHONE -> "新手机 · 接收"
    MigrationRole.UNSET -> "本机"
}

private fun categoryLabelV2(category: MigrationCategory) = when (category) {
    MigrationCategory.PHOTOS -> "照片"
    MigrationCategory.VIDEOS -> "视频"
    MigrationCategory.MUSIC -> "音乐 / 录音"
    MigrationCategory.DOCUMENTS -> "文档"
    MigrationCategory.DOWNLOADS -> "下载文件"
    MigrationCategory.OTHER -> "其他文件"
    MigrationCategory.APPS -> "应用"
}

private fun formatBytesV2(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatRateV2(bytes: Long): String = "${formatBytesV2(bytes)}/s"

private fun estimateV2(bytes: Long, speed: Long): String {
    if (bytes <= 0) return "0秒"
    if (speed <= 0) return "计算中"
    val seconds = (bytes / speed).coerceAtLeast(1L)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分钟"
        else -> "${seconds}秒"
    }
}
