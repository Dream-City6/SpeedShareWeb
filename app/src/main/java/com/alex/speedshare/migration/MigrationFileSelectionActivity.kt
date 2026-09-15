package com.alex.speedshare.migration

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alex.speedshare.AppSettings
import com.alex.speedshare.ui.theme.SpeedShareTheme
import java.util.Locale

private data class BrowserEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val descendants: List<MigrationFileItem>,
    val file: MigrationFileItem? = null
)

class MigrationFileSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings = remember { AppSettings.load(this) }
            SpeedShareTheme(themeMode = settings.themeMode) { FolderBrowserScreen { finish() } }
        }
    }
}

@Composable
private fun FolderBrowserScreen(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember(context) { ResilientMigrationController.get(context) }
    val state by controller.state.collectAsState()
    val files = state.scanResult.files
    MigrationFileSelectionRegistry.sync(files)
    val selected by MigrationFileSelectionRegistry.selectedPaths.collectAsState()
    var currentPath by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    fun goUp() {
        currentPath = currentPath.substringBeforeLast('/', "")
        query = ""
    }
    BackHandler(enabled = currentPath.isNotEmpty()) { goUp() }

    val entries = remember(files, currentPath, query) {
        val prefix = currentPath.takeIf { it.isNotEmpty() }?.plus("/").orEmpty()
        val descendants = files.filter { it.relativePath.replace('\\', '/').startsWith(prefix, true) }
        val directories = descendants.mapNotNull { item ->
            val rest = item.relativePath.replace('\\', '/').removePrefix(prefix)
            rest.substringBefore('/', "").takeIf { rest.contains('/') && it.isNotBlank() }
        }.distinct().map { name ->
            val path = prefix + name
            BrowserEntry(name, path, true, files.filter {
                val normalized = it.relativePath.replace('\\', '/')
                normalized == path || normalized.startsWith("$path/", true)
            })
        }
        val directFiles = descendants.mapNotNull { item ->
            val rest = item.relativePath.replace('\\', '/').removePrefix(prefix)
            if ('/' !in rest) BrowserEntry(rest, prefix + rest, false, listOf(item), item) else null
        }
        (directories + directFiles)
            .filter { query.isBlank() || it.name.contains(query.trim(), true) }
            .sortedWith(compareByDescending<BrowserEntry> { it.directory }.thenBy { it.name.lowercase(Locale.getDefault()) })
    }
    val selectedBytes = files.asSequence().filter { it.relativePath in selected }.sumOf { it.size }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (currentPath.isNotEmpty()) TextButton(onClick = ::goUp) { Text("‹ 返回") }
                Column(Modifier.weight(1f)) {
                    Text("转移文件夹", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text(if (currentPath.isEmpty()) "内部存储" else currentPath, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onClose) { Text("完成") }
            }
            Text("已选 ${selected.size} 个文件 · ${formatFolderBytes(selectedBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索当前目录") }, singleLine = true)
            val allSelected = files.isNotEmpty() && files.all { it.relativePath in selected }
            OutlinedButton(
                onClick = { if (allSelected) MigrationFileSelectionRegistry.selectNone() else MigrationFileSelectionRegistry.selectAll() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (allSelected) "☐ 全部取消选择" else "☑ 全选所有文件夹") }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(entries, key = { (if (it.directory) "d:" else "f:") + it.path }) { entry ->
                    val paths = entry.descendants.mapTo(linkedSetOf()) { it.relativePath }
                    val selectedCount = paths.count { it in selected }
                    val checked = paths.isNotEmpty() && selectedCount == paths.size
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { MigrationFileSelectionRegistry.select(paths, !checked) },
                        shape = RoundedCornerShape(15.dp),
                        colors = CardDefaults.cardColors(containerColor = if (selectedCount > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (checked) "☑" else if (selectedCount > 0) "◩" else "☐", style = MaterialTheme.typography.titleLarge)
                            Text(if (entry.directory) "  📁" else "  📄", style = MaterialTheme.typography.titleLarge)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(entry.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (entry.directory) "$selectedCount / ${paths.size} 个文件" else formatFolderBytes(entry.file?.size ?: 0), style = MaterialTheme.typography.bodySmall)
                            }
                            if (entry.directory) TextButton(onClick = { currentPath = entry.path; query = "" }) { Text("进入 ›") }
                        }
                    }
                }
            }
        }
    }
}

private fun formatFolderBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
