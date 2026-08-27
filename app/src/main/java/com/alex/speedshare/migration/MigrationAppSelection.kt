package com.alex.speedshare.migration

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alex.speedshare.AppSettings
import com.alex.speedshare.ui.theme.SpeedShareTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object MigrationAppSelectionRegistry {
    private val _selectedPackages = MutableStateFlow<Set<String>>(emptySet())
    val selectedPackages = _selectedPackages.asStateFlow()
    private var catalog: Set<String>? = null
    private var compatibilityDefaultsPeer: String? = null

    @Synchronized
    fun sync(apps: List<MigrationAppItem>) {
        val packages = apps.mapTo(linkedSetOf()) { it.packageName }
        if (catalog != packages) {
            catalog = packages
            compatibilityDefaultsPeer = null
            _selectedPackages.value = packages
        }
    }

    @Synchronized
    fun removeKnownIncompatible(peerDeviceId: String?, packageNames: Set<String>) {
        if (peerDeviceId.isNullOrBlank() || compatibilityDefaultsPeer == peerDeviceId) return
        compatibilityDefaultsPeer = peerDeviceId
        _selectedPackages.value = _selectedPackages.value - packageNames
    }

    fun toggle(packageName: String) {
        val next = _selectedPackages.value.toMutableSet()
        if (!next.add(packageName)) next.remove(packageName)
        _selectedPackages.value = next
    }

    fun selectAll() {
        _selectedPackages.value = catalog.orEmpty()
    }

    fun selectNone() {
        _selectedPackages.value = emptySet()
    }

    fun filterTransferItems(items: List<MigrationFileItem>): List<MigrationFileItem> {
        val knownCatalog = catalog ?: return items
        val selected = _selectedPackages.value
        return items.filter { item ->
            val packageName = item.appPackageName
            packageName == null || packageName !in knownCatalog || packageName in selected
        }
    }
}

class MigrationAppSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings = remember { AppSettings.load(this) }
            SpeedShareTheme(themeMode = settings.themeMode) {
                MigrationAppSelectionScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun MigrationAppSelectionScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ResilientMigrationController.get(context) }
    val state by controller.state.collectAsState()
    val apps = state.scanResult.apps
    MigrationAppSelectionRegistry.sync(apps)
    val selected by MigrationAppSelectionRegistry.selectedPackages.collectAsState()
    val receiver = state.connectedPeer
    val compatibility = remember(apps, receiver) {
        apps.associate { app -> app.packageName to AppCompatibilityAnalyzer.analyze(context, app, receiver) }
    }
    val incompatible = remember(compatibility) {
        compatibility.filterValues { it.status == AppCompatibilityStatus.INCOMPATIBLE }.keys
    }
    LaunchedEffect(receiver?.deviceId, incompatible) {
        MigrationAppSelectionRegistry.removeKnownIncompatible(receiver?.deviceId, incompatible)
    }

    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) apps else apps.filter {
            it.label.lowercase().contains(needle) || it.packageName.lowercase().contains(needle)
        }
    }
    val selectedBytes = apps.asSequence().filter { it.packageName in selected }.sumOf { it.totalBytes }
    val compatibleCount = compatibility.count { it.value.status == AppCompatibilityStatus.COMPATIBLE }
    val reviewCount = compatibility.count { it.value.status == AppCompatibilityStatus.REVIEW }
    val incompatibleCount = compatibility.size - compatibleCount - reviewCount

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("选择应用", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text("已选 ${selected.size} 个 · ${formatAppBytes(selectedBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onClose) { Text("完成") }
            }

            if (receiver != null) {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("新手机：${receiver.name}", fontWeight = FontWeight.Bold)
                        Text(
                            "兼容 $compatibleCount · 需确认 $reviewCount · 不兼容 $incompatibleCount",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (receiver.androidSdk > 0) {
                            Text(
                                "Android API ${receiver.androidSdk} · ${receiver.supportedAbis.joinToString().ifBlank { "ABI 未知" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索应用或包名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = MigrationAppSelectionRegistry::selectAll, modifier = Modifier.weight(1f)) {
                    Text("全选")
                }
                OutlinedButton(onClick = MigrationAppSelectionRegistry::selectNone, modifier = Modifier.weight(1f)) {
                    Text("全不选")
                }
            }

            Text(
                "已明确判断为不兼容的应用默认取消选择；“需确认”表示 APK 内部架构需要由 Android 安装器最终判断。这里只迁移安装包，不迁移登录状态和私有数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            filtered.forEach { app ->
                val appCompatibility = compatibility[app.packageName]
                    ?: AppCompatibilityResult(AppCompatibilityStatus.REVIEW, "等待检查")
                Card(shape = RoundedCornerShape(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = app.packageName in selected,
                            onCheckedChange = { MigrationAppSelectionRegistry.toggle(app.packageName) }
                        )
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(app.label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(
                                    compatibilityLabel(appCompatibility.status),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                "${app.versionName.ifBlank { "未知版本" }} · ${formatAppBytes(app.totalBytes)} · ${app.apkFiles.size} 个 APK",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                appCompatibility.reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun compatibilityLabel(status: AppCompatibilityStatus): String = when (status) {
    AppCompatibilityStatus.COMPATIBLE -> "兼容"
    AppCompatibilityStatus.REVIEW -> "需确认"
    AppCompatibilityStatus.INCOMPATIBLE -> "不兼容"
}

private fun formatAppBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
