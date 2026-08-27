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
import androidx.compose.foundation.layout.weight
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
import java.util.Locale

class MigrationCenterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings = remember { AppSettings.load(this) }
            SpeedShareTheme(themeMode = settings.themeMode) {
                MigrationCenterScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun MigrationCenterScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val controller = remember { MigrationController.get(context) }
    val state by controller.state.collectAsState()
    var storageAccess by remember { mutableStateOf(hasMigrationStorageAccessCenter()) }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                storageAccess = hasMigrationStorageAccessCenter()
            }
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
                    Text("${request.peer.name} 想与你的手机建立换机连接。")
                    if (request.peer.model.isNotBlank()) {
                        Text(
                            request.peer.model,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "只允许你认识的设备。允许后，该设备才能进行测速和发送换机数据。",
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
            TopBar(state = state, onClose = onClose)
            MigrationHero(state)
            StepRail(state.stage)
            StatusBanner(state)

            val needsStorage = state.role != MigrationRole.UNSET
            if (!storageAccess && needsStorage) {
                PermissionCard(
                    isReceiver = state.role == MigrationRole.NEW_PHONE,
                    onGrant = { openAllFilesAccessCenter(context) }
                )
            }

            when (state.stage) {
                MigrationStage.DISCOVERY, MigrationStage.PAIRING -> DiscoveryPane(state, controller)
                MigrationStage.SPEED_TEST -> SpeedPane(state, controller)
                MigrationStage.ROLE -> RolePane(controller)
                MigrationStage.SELECTION -> SelectionPane(state, controller, storageAccess)
                MigrationStage.TRANSFERRING, MigrationStage.VERIFYING -> TransferPane(state)
                MigrationStage.COMPLETE -> ReportPane(state, controller)
            }

            if (
                state.connectedPeer != null &&
                state.stage !in setOf(MigrationStage.DISCOVERY, MigrationStage.PAIRING, MigrationStage.TRANSFERRING, MigrationStage.VERIFYING)
            ) {
                OutlinedButton(onClick = controller::reset, modifier = Modifier.fillMaxWidth()) {
                    Text("结束当前连接")
                }
            }

            SecurityNote()
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun TopBar(state: MigrationUiState, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text("SpeedShare 换机", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text(
                "手机对手机 · 自动发现 · 断点续传",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onClose) {
            Text(if (state.stage in setOf(MigrationStage.TRANSFERRING, MigrationStage.VERIFYING)) "后台运行" else "返回")
        }
    }
}

@Composable
private fun MigrationHero(state: MigrationUiState) {
    val start = MaterialTheme.colorScheme.primaryContainer
    val end = MaterialTheme.colorScheme.secondaryContainer
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(start, end)))
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("一键换机", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                ) {
                    Text(
                        stageLabel(state.stage),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DeviceMiniCard(
                    modifier = Modifier.weight(1f),
                    title = state.localDeviceName.ifBlank { "本机" },
                    subtitle = roleShortLabel(state.role)
                )
                Text("→", fontSize = 24.sp, fontWeight = FontWeight.Black)
                DeviceMiniCard(
                    modifier = Modifier.weight(1f),
                    title = state.connectedPeer?.name ?: "等待设备",
                    subtitle = state.connectedPeer?.model?.takeIf { it.isNotBlank() } ?: "同一 Wi‑Fi 自动发现"
                )
            }
        }
    }
}

@Composable
private fun DeviceMiniCard(modifier: Modifier, title: String, subtitle: String) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StepRail(stage: MigrationStage) {
    val current = stepIndex(stage)
    val labels = listOf("连接", "测速", "角色", "内容", "迁移")
    Card(shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            labels.forEachIndexed { index, label ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (index <= current) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (index < current) "✓" else (index + 1).toString(),
                            color = if (index <= current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (index <= current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(state: MigrationUiState) {
    val container = if (state.error == null) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer
    Card(colors = CardDefaults.cardColors(containerColor = container), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(state.status.ifBlank { "准备换机" }, fontWeight = FontWeight.Bold)
            state.connectedPeer?.let {
                Text(
                    "已连接 ${it.name}${it.appVersion.takeIf { v -> v.isNotBlank() }?.let { v -> " · SpeedShare $v" }.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PermissionCard(isReceiver: Boolean, onGrant: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("还差一个权限", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
            Text(
                if (isReceiver) {
                    "新手机需要“全部文件访问”权限，才能把照片、下载和文档恢复到原来的目录。"
                } else {
                    "旧手机需要“全部文件访问”权限，才能完整扫描照片、视频、下载和文档。"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text("去授权") }
        }
    }
}

@Composable
private fun DiscoveryPane(state: MigrationUiState, controller: MigrationController) {
    MatureSection("附近设备", "两台手机连接同一个 Wi‑Fi，并同时打开 SpeedShare Migration。") {
        if (state.peers.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                    Column {
                        Text("正在自动搜索", fontWeight = FontWeight.Bold)
                        Text(
                            "无需扫码，也不用输入 IP 地址",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            state.peers.forEach { peer ->
                PeerCard(peer, state.pairing) { controller.connect(peer) }
            }
        }
    }
}

@Composable
private fun PeerCard(peer: MigrationPeer, busy: Boolean, onConnect: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(peer.name.take(1).uppercase(), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(peer.name, fontWeight = FontWeight.Black)
                Text(
                    listOf(peer.model, peer.appVersion.takeIf { it.isNotBlank() }?.let { "v$it" })
                        .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(onClick = onConnect, enabled = !busy) { Text(if (busy) "连接中" else "连接") }
        }
    }
}

@Composable
private fun SpeedPane(state: MigrationUiState, controller: MigrationController) {
    MatureSection("连接质量", "使用真实数据双向传输测试，而不是只看 Wi‑Fi 信号格。") {
        if (state.speedTesting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("正在测试上传、下载、延迟和稳定性…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        state.speedResult?.let { result ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("发送", formatRateCenter(result.uploadBytesPerSecond), Modifier.weight(1f))
                MetricTile("接收", formatRateCenter(result.downloadBytesPerSecond), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("延迟", "${result.latencyMs} ms", Modifier.weight(1f))
                MetricTile("稳定性", "${result.stabilityPercent}%", Modifier.weight(1f))
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(networkAdviceCenter(result), fontWeight = FontWeight.Bold)
                    Text(
                        "按当前平均速度，迁移 50 GB 约需 ${estimateDurationCenter(50L * 1024L * 1024L * 1024L, result.averageBytesPerSecond)}。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!state.speedTesting) {
                Button(onClick = controller::confirmNetwork, modifier = Modifier.fillMaxWidth()) {
                    Text("使用当前 Wi‑Fi，继续")
                }
            }
        }

        OutlinedButton(onClick = controller::runSpeedTest, enabled = !state.speedTesting, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.speedResult == null) "开始测速" else "重新测速")
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RolePane(controller: MigrationController) {
    MatureSection("选择这台手机", "只需要在其中一台选择，另一台会自动切换成相反角色。") {
        RoleChoice(
            title = "这是旧手机",
            subtitle = "扫描并发送照片、视频、文档和应用",
            primary = true,
            onClick = { controller.setRole(MigrationRole.OLD_PHONE) }
        )
        RoleChoice(
            title = "这是新手机",
            subtitle = "接收数据，并尽可能恢复原目录结构",
            primary = false,
            onClick = { controller.setRole(MigrationRole.NEW_PHONE) }
        )
    }
}

@Composable
private fun RoleChoice(title: String, subtitle: String, primary: Boolean, onClick: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (primary) {
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("选择旧手机") }
            } else {
                OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("选择新手机") }
            }
        }
    }
}

@Composable
private fun SelectionPane(state: MigrationUiState, controller: MigrationController, storageAccess: Boolean) {
    if (state.role == MigrationRole.NEW_PHONE) {
        ReceiverReadyPane(state, storageAccess)
        return
    }

    val selectedBytes = state.selectedCategories.sumOf { state.scanResult.bytes(it) }
    val selectedCount = state.selectedCategories.sumOf { state.scanResult.count(it) }

    MatureSection("选择要迁移的内容", "默认全选。你可以按类别排除不需要的数据。") {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.46f)),
            shape = RoundedCornerShape(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("已选择", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatBytesCenter(selectedBytes), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                }
                Text("$selectedCount 项", fontWeight = FontWeight.Bold)
            }
        }

        if (state.scanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("正在扫描存储和已安装应用…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        MigrationCategory.entries.forEach { category ->
            CategoryRow(
                category = category,
                count = state.scanResult.count(category),
                bytes = state.scanResult.bytes(category),
                checked = category in state.selectedCategories,
                onToggle = { controller.toggleCategory(category) }
            )
        }

        if (state.scanResult.apps.isNotEmpty()) {
            Text(
                "应用迁移会尽量保留 base.apk + split APK。应用登录状态和私有数据仍受 Android 系统限制。",
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
                enabled = !state.scanning && storageAccess && selectedCount > 0,
                modifier = Modifier.weight(1f)
            ) { Text("开始换机") }
        }
    }
}

@Composable
private fun CategoryRow(
    category: MigrationCategory,
    count: Int,
    bytes: Long,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(categoryLabelCenter(category), fontWeight = FontWeight.Bold)
                Text(
                    "$count 项 · ${formatBytesCenter(bytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReceiverReadyPane(state: MigrationUiState, storageAccess: Boolean) {
    MatureSection("新手机已准备", "旧手机完成选择后会自动开始传输。") {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (storageAccess) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
                else MaterialTheme.colorScheme.errorContainer
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (storageAccess) "准备完成" else "等待授权", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (storageAccess) {
                        "保持连接即可。照片、下载和文档会尽可能恢复到原来的目录。"
                    } else {
                        "请先完成上方的文件访问授权，否则无法按原目录接收数据。"
                    }
                )
                state.speedResult?.let {
                    Text(
                        "当前连接平均 ${formatRateCenter(it.averageBytesPerSecond)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferPane(state: MigrationUiState) {
    val p = state.progress
    val remaining = (p.totalBytes - p.transferredBytes).coerceAtLeast(0L)
    val eta = estimateDurationCenter(remaining, p.bytesPerSecond)

    MatureSection("正在迁移", "可以切到后台，前台服务会继续保持换机任务。") {
        Card(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${(p.fraction * 100).toInt()}%", fontSize = 42.sp, fontWeight = FontWeight.Black)
                    Text("预计剩余 $eta", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile("实时速度", formatRateCenter(p.bytesPerSecond), Modifier.weight(1f))
                    MetricTile("已传输", formatBytesCenter(p.transferredBytes), Modifier.weight(1f))
                }
                MetricLine("总数据", "${formatBytesCenter(p.transferredBytes)} / ${formatBytesCenter(p.totalBytes)}")
                MetricLine("项目", "${p.completedItems} / ${p.totalItems}")
                if (p.skippedItems > 0) MetricLine("自动跳过重复", p.skippedItems.toString())
                if (p.failedItems > 0) MetricLine("当前失败", p.failedItems.toString())
                if (p.currentName.isNotBlank()) {
                    HorizontalDivider()
                    Text("正在处理", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(p.currentName, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            "断线后重新连接会继续未完成的大文件；完整文件使用 SHA‑256 做最终校验。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReportPane(state: MigrationUiState, controller: MigrationController) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val report = state.report
    val receivedApps = remember(state.report, state.role) {
        if (state.role == MigrationRole.NEW_PHONE) AppPackageInstaller.receivedPackages() else emptyList()
    }

    MatureSection("换机报告", "传输结束后会自动汇总成功、跳过和失败项目。") {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if ((report?.failedCount ?: 0) == 0) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
            ),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if ((report?.failedCount ?: 0) == 0) "换机完成" else "换机完成，有部分项目失败",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black
                )
                report?.let {
                    MetricLine("总数据", formatBytesCenter(it.totalBytes))
                    MetricLine("成功项目", it.successCount.toString())
                    MetricLine("重复跳过", it.skippedCount.toString())
                    MetricLine("失败项目", it.failedCount.toString())
                    MetricLine("耗时", formatDurationCenter(it.durationMs))
                    MetricLine("平均速度", formatRateCenter(it.averageBytesPerSecond))
                }
            }
        }

        if (receivedApps.isNotEmpty()) {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("已接收应用 ${receivedApps.size} 个", fontWeight = FontWeight.Black)
                    Text(
                        "应用以完整 APK 集合保存。点击安装后，由 Android 系统逐个确认。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    receivedApps.forEach { directory ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(directory.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            OutlinedButton(
                                enabled = activity != null,
                                onClick = {
                                    val result = activity?.let { AppPackageInstaller.requestInstall(it, directory) }
                                    val message = when (result) {
                                        AppPackageInstaller.InstallStartResult.STARTED -> "已交给系统安装器"
                                        AppPackageInstaller.InstallStartResult.PERMISSION_REQUIRED -> "请允许安装未知应用后再试"
                                        AppPackageInstaller.InstallStartResult.NO_APKS -> "没有找到 APK"
                                        AppPackageInstaller.InstallStartResult.FAILED -> "启动安装失败"
                                        null -> "无法启动安装"
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            ) { Text("安装") }
                        }
                    }
                }
            }
        }

        Button(onClick = controller::reset, modifier = Modifier.fillMaxWidth()) { Text("完成并返回设备列表") }
    }
}

@Composable
private fun MatureSection(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SecurityNote() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("安全提示", fontWeight = FontWeight.Bold)
            Text(
                "仅已确认配对的设备可以发送换机数据。当前测试版建议在家庭或其他可信 Wi‑Fi 下使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun stepIndex(stage: MigrationStage): Int = when (stage) {
    MigrationStage.DISCOVERY, MigrationStage.PAIRING -> 0
    MigrationStage.SPEED_TEST -> 1
    MigrationStage.ROLE -> 2
    MigrationStage.SELECTION -> 3
    MigrationStage.TRANSFERRING, MigrationStage.VERIFYING, MigrationStage.COMPLETE -> 4
}

private fun stageLabel(stage: MigrationStage): String = when (stage) {
    MigrationStage.DISCOVERY -> "正在发现设备"
    MigrationStage.PAIRING -> "正在连接"
    MigrationStage.SPEED_TEST -> "网络测速"
    MigrationStage.ROLE -> "选择角色"
    MigrationStage.SELECTION -> "选择内容"
    MigrationStage.TRANSFERRING -> "正在迁移"
    MigrationStage.VERIFYING -> "完整性校验"
    MigrationStage.COMPLETE -> "已完成"
}

private fun roleShortLabel(role: MigrationRole): String = when (role) {
    MigrationRole.OLD_PHONE -> "旧手机 · 发送"
    MigrationRole.NEW_PHONE -> "新手机 · 接收"
    MigrationRole.UNSET -> "本机"
}

private fun categoryLabelCenter(category: MigrationCategory): String = when (category) {
    MigrationCategory.PHOTOS -> "照片"
    MigrationCategory.VIDEOS -> "视频"
    MigrationCategory.MUSIC -> "音乐 / 录音"
    MigrationCategory.DOCUMENTS -> "文档"
    MigrationCategory.DOWNLOADS -> "下载文件"
    MigrationCategory.OTHER -> "其他文件"
    MigrationCategory.APPS -> "应用"
}

private fun hasMigrationStorageAccessCenter(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

private fun openAllFilesAccessCenter(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.recoverCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun formatBytesCenter(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatRateCenter(bytesPerSecond: Long): String = "${formatBytesCenter(bytesPerSecond)}/s"

private fun formatDurationCenter(ms: Long): String {
    val seconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = seconds / 60L
    val remain = seconds % 60L
    return if (minutes > 0L) "${minutes}分${remain}秒" else "${remain}秒"
}

private fun estimateDurationCenter(bytes: Long, speed: Long): String {
    if (bytes <= 0L) return "0秒"
    if (speed <= 0L) return "计算中"
    val seconds = (bytes / speed).coerceAtLeast(1L)
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    return when {
        hours > 0L -> "${hours}小时${minutes}分"
        minutes > 0L -> "${minutes}分钟"
        else -> "${seconds}秒"
    }
}

private fun networkAdviceCenter(result: SpeedTestResult): String = when {
    result.averageBytesPerSecond >= 50L * 1024L * 1024L && result.stabilityPercent >= 90 -> "连接质量优秀，适合大容量换机"
    result.averageBytesPerSecond >= 15L * 1024L * 1024L -> "连接质量良好，可以继续换机"
    else -> "当前网络偏慢，建议切换 5GHz / 6GHz Wi‑Fi 或靠近路由器"
}
