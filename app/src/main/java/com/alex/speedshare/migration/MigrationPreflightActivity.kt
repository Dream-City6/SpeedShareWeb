package com.alex.speedshare.migration

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alex.speedshare.AppSettings
import com.alex.speedshare.ui.theme.SpeedShareTheme
import java.util.Locale

class MigrationPreflightActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings = remember { AppSettings.load(this) }
            SpeedShareTheme(themeMode = settings.themeMode) {
                MigrationPreflightScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun MigrationPreflightScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ResilientMigrationController.get(context) }
    val state by controller.state.collectAsState()
    val duplicatePolicy by MigrationDuplicatePolicyRegistry.current.collectAsState()
    val selectedApps by MigrationAppSelectionRegistry.selectedPackages.collectAsState()
    val selectedMedia by MigrationMediaSelectionRegistry.selectedPaths.collectAsState()
    val selectedFiles by MigrationFileSelectionRegistry.selectedPaths.collectAsState()
    val contactsEnabled by MigrationContactsRegistry.enabled.collectAsState()
    val contactsCount by MigrationContactsRegistry.count.collectAsState()
    val health = remember { MigrationDeviceHealthReader.read(context) }
    val summary = remember(
        state.scanResult,
        state.selectedCategories,
        selectedApps,
        selectedMedia,
        selectedFiles,
        contactsEnabled,
        contactsCount
    ) {
        MigrationSelectionCalculator.effectiveItems(state.scanResult, state.selectedCategories)
    }

    LaunchedEffect(Unit) { controller.refreshReceiverStorage() }

    val free = state.receiverStorage?.freeBytes
    val enoughSpace = free == null || free >= summary.totalBytes + 256L * 1024L * 1024L
    val estimatedSeconds = state.speedResult?.averageBytesPerSecond
        ?.takeIf { it > 0L }
        ?.let { (summary.totalBytes / it).coerceAtLeast(1L) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("开始换机前确认", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text("所有提示都是建议，不会因为电量或温度强制阻止你继续。", color = MaterialTheme.colorScheme.onSurfaceVariant)

            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("本次迁移", fontWeight = FontWeight.Black)
                    PreflightLine("总数据", formatPreflightBytes(summary.totalBytes))
                    PreflightLine("文件 / APK组件", summary.totalItems.toString())
                    PreflightLine("照片", "${summary.photoCount} 张")
                    PreflightLine("视频", "${summary.videoCount} 个")
                    PreflightLine("应用", "${summary.appCount} 个")
                    if (summary.contactsCount > 0) PreflightLine("联系人", "${summary.contactsCount} 个")
                    free?.let { PreflightLine("新手机可用空间", formatPreflightBytes(it)) }
                    PreflightLine("预计时间", estimatedSeconds?.let(::formatPreflightDuration) ?: "测速已跳过，将在传输中动态计算")
                    Text(
                        if (enoughSpace) "空间检查通过" else "新手机空间不足，请返回减少选择或清理空间。",
                        color = if (enoughSpace) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("联系人（可选）", fontWeight = FontWeight.Black)
                            Text(
                                if (contactsEnabled) "已准备 $contactsCount 个联系人，将作为 VCF 一起迁移。" else "不会自动申请权限；需要时由你主动开启。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(context, MigrationContactsSelectionActivity::class.java))
                        }) {
                            Text(if (contactsEnabled) "修改" else "选择")
                        }
                    }
                }
            }

            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("设备状态", fontWeight = FontWeight.Black)
                    PreflightLine("本机电量", health.batteryLabel + if (health.charging) " · 正在充电" else "")
                    PreflightLine("电池温度", health.temperatureLabel)
                    val recommendations = health.recommendations()
                    if (recommendations.isEmpty()) {
                        Text("当前状态适合继续；长时间换机仍建议保持充电和散热。", color = MaterialTheme.colorScheme.primary)
                    } else {
                        recommendations.forEach { recommendation ->
                            Text("• $recommendation", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("以上只做建议，不限制开始换机。", style = MaterialTheme.typography.labelSmall)
                }
            }

            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("遇到同名文件时", fontWeight = FontWeight.Black)
                    DuplicateChoice(
                        "自动跳过完全相同；不同内容两份都保留",
                        duplicatePolicy == MigrationDuplicatePolicy.SKIP_IDENTICAL_KEEP_CONFLICT
                    ) { MigrationDuplicatePolicyRegistry.set(MigrationDuplicatePolicy.SKIP_IDENTICAL_KEEP_CONFLICT) }
                    DuplicateChoice(
                        "覆盖新手机已有的同名文件",
                        duplicatePolicy == MigrationDuplicatePolicy.OVERWRITE
                    ) { MigrationDuplicatePolicyRegistry.set(MigrationDuplicatePolicy.OVERWRITE) }
                    DuplicateChoice(
                        "始终两份都保留（相同文件也保留副本）",
                        duplicatePolicy == MigrationDuplicatePolicy.KEEP_BOTH
                    ) { MigrationDuplicatePolicyRegistry.set(MigrationDuplicatePolicy.KEEP_BOTH) }
                }
            }

            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("隐私", fontWeight = FontWeight.Bold)
                    Text(
                        "数据在两台手机之间通过局域网直接传输，不上传到 SpeedShare 服务器。当前 beta 的传输内容仍未做 TLS/E2E 加密，请继续使用可信 Wi‑Fi。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("返回修改") }
                Button(
                    onClick = {
                        controller.startTransfer()
                        onClose()
                    },
                    enabled = enoughSpace && summary.totalItems > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("开始换机") }
            }
        }
    }
}

@Composable
private fun DuplicateChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, Modifier.weight(1f))
    }
}

@Composable
private fun PreflightLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

private fun formatPreflightBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatPreflightDuration(seconds: Long): String {
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    val rest = seconds % 60L
    return when {
        hours > 0 -> "约 ${hours}小时${minutes}分"
        minutes > 0 -> "约 ${minutes}分${rest}秒"
        else -> "约 ${rest}秒"
    }
}
