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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    @Synchronized
    fun sync(apps: List<MigrationAppItem>) {
        val packages = apps.mapTo(linkedSetOf()) { it.packageName }
        if (catalog != packages) {
            catalog = packages
            _selectedPackages.value = packages
        }
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

    fun selectedCount(): Int = _selectedPackages.value.size
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
    val controller = remember { ResilientMigrationController.get(androidx.compose.ui.platform.LocalContext.current) }
    val state by controller.state.collectAsState()
    val apps = state.scanResult.apps
    MigrationAppSelectionRegistry.sync(apps)
    val selected by MigrationAppSelectionRegistry.selectedPackages.collectAsState()
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) apps else apps.filter {
            it.label.lowercase().contains(needle) || it.packageName.lowercase().contains(needle)
        }
    }
    val selectedBytes = apps.asSequence().filter { it.packageName in selected }.sumOf { it.totalBytes }

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
                "这里只迁移应用安装包（base.apk + split APK）。账号登录状态、聊天记录和应用私有数据仍受 Android 限制。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            filtered.forEach { app ->
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
                            Text(app.label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${app.versionName.ifBlank { "未知版本" }} · ${formatAppBytes(app.totalBytes)} · ${app.apkFiles.size} 个 APK",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
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

private fun formatAppBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
