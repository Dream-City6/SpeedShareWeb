package com.alex.speedshare.migration

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alex.speedshare.AppSettings
import com.alex.speedshare.ui.theme.SpeedShareTheme
import kotlinx.coroutines.launch
import java.util.Locale

private enum class FileSortMode { NAME, DATE, SIZE }

class MigrationFileSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings = remember { AppSettings.load(this) }
            SpeedShareTheme(themeMode = settings.themeMode) {
                MigrationFileSelectionScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun MigrationFileSelectionScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) { ResilientMigrationController.get(context) }
    val state by controller.state.collectAsState()
    val files = state.scanResult.files
    MigrationFileSelectionRegistry.sync(files)
    val selected by MigrationFileSelectionRegistry.selectedPaths.collectAsState()

    var category by remember { mutableStateOf(MigrationCategory.DOCUMENTS) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(FileSortMode.DATE) }
    var largeOnly by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 8 } }

    val visible = remember(files, category, query, sort, largeOnly) {
        val needle = query.trim().lowercase(Locale.getDefault())
        val base = files.asSequence()
            .filter { it.category == category }
            .filter { !largeOnly || it.size >= 500L * 1024L * 1024L }
            .filter {
                needle.isBlank() ||
                    it.file.name.lowercase(Locale.getDefault()).contains(needle) ||
                    it.relativePath.lowercase(Locale.getDefault()).contains(needle)
            }
            .toList()
        when (sort) {
            FileSortMode.NAME -> base.sortedBy { it.file.name.lowercase(Locale.getDefault()) }
            FileSortMode.DATE -> base.sortedByDescending { it.modifiedAt }
            FileSortMode.SIZE -> base.sortedByDescending { it.size }
        }
    }
    val visiblePaths = remember(visible) { visible.mapTo(linkedSetOf()) { it.relativePath } }
    val selectedVisible = visible.count { it.relativePath in selected }
    val selectedBytes = visible.asSequence().filter { it.relativePath in selected }.sumOf { it.size }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("选择文件", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                        Text(
                            "当前已选 $selectedVisible / ${visible.size} · ${formatPickerBytes(selectedBytes)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = onClose) { Text("完成") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FileTab("文档", category == MigrationCategory.DOCUMENTS, Modifier.weight(1f)) {
                        category = MigrationCategory.DOCUMENTS
                    }
                    FileTab("下载", category == MigrationCategory.DOWNLOADS, Modifier.weight(1f)) {
                        category = MigrationCategory.DOWNLOADS
                    }
                    FileTab("其他", category == MigrationCategory.OTHER, Modifier.weight(1f)) {
                        category = MigrationCategory.OTHER
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索文件名或路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { MigrationFileSelectionRegistry.select(visiblePaths, true) }, modifier = Modifier.weight(1f)) {
                        Text("全选")
                    }
                    OutlinedButton(onClick = { MigrationFileSelectionRegistry.select(visiblePaths, false) }, modifier = Modifier.weight(1f)) {
                        Text("全不选")
                    }
                    OutlinedButton(onClick = { largeOnly = !largeOnly }, modifier = Modifier.weight(1f)) {
                        Text(if (largeOnly) "全部大小" else ">500MB")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SortButton("按日期", sort == FileSortMode.DATE, Modifier.weight(1f)) { sort = FileSortMode.DATE }
                    SortButton("按大小", sort == FileSortMode.SIZE, Modifier.weight(1f)) { sort = FileSortMode.SIZE }
                    SortButton("按名称", sort == FileSortMode.NAME, Modifier.weight(1f)) { sort = FileSortMode.NAME }
                }

                if (visible.isEmpty()) {
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("当前筛选条件下没有文件", Modifier.padding(18.dp))
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(visible, key = { it.relativePath }) { item ->
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    MigrationFileSelectionRegistry.toggle(item.relativePath)
                                }
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = item.relativePath in selected,
                                        onCheckedChange = { MigrationFileSelectionRegistry.toggle(item.relativePath) }
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(item.file.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            item.relativePath.substringBeforeLast('/', "主存储"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(formatPickerBytes(item.size), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

            if (showTop) {
                FloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                ) {
                    Text("↑", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun FileTab(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (active) Button(onClick = onClick, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}

@Composable
private fun SortButton(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (active) Button(onClick = onClick, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}

private fun formatPickerBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
