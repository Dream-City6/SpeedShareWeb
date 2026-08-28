package com.alex.speedshare.migration

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alex.speedshare.AppSettings
import com.alex.speedshare.ui.theme.SpeedShareTheme
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

data class MigrationHistoryEntry(
    val migrationId: String,
    val peerName: String,
    val createdAt: Long,
    val completedAt: Long?,
    val complete: Boolean,
    val totalItems: Int,
    val totalBytes: Long
)

class MigrationHistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings = remember { AppSettings.load(this) }
            SpeedShareTheme(themeMode = settings.themeMode) {
                MigrationHistoryScreen(
                    onClose = { finish() },
                    onOpenFolder = { openSpeedShareFolder() }
                )
            }
        }
    }

    private fun openSpeedShareFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val initial = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Download/SpeedShareWeb"
            )
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        }
        startActivity(intent)
    }
}

@Composable
private fun MigrationHistoryScreen(onClose: () -> Unit, onOpenFolder: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val history = remember { MigrationHistoryReader.read(context) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("换机历史", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text("任务记录保存在本机，不包含文件内容副本。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onClose) { Text("返回") }
            }
            OutlinedButton(onClick = onOpenFolder, modifier = Modifier.fillMaxWidth()) {
                Text("打开 Download/SpeedShareWeb")
            }
            Text(
                "Apps、Contacts 和 Temporary 都集中在这里。Temporary 是断点工作区，异常残留时你也可以手动删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (history.isEmpty()) {
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("暂时还没有换机记录", Modifier.padding(18.dp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(history, key = { it.migrationId }) { entry ->
                        Card(shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(entry.peerName.ifBlank { "SpeedShare 设备" }, fontWeight = FontWeight.Black)
                                    Text(
                                        if (entry.complete) "已完成" else "未完成",
                                        color = if (entry.complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(formatHistoryDate(entry.createdAt), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${entry.totalItems} 项 · ${formatHistoryBytes(entry.totalBytes)}")
                                entry.completedAt?.let {
                                    Text("完成：${formatHistoryDate(it)}", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    "任务 ${entry.migrationId.take(8)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private object MigrationHistoryReader {
    fun read(context: android.content.Context): List<MigrationHistoryEntry> {
        val root = File(context.filesDir, "migration_tasks")
        return root.listFiles().orEmpty().asSequence()
            .filter { it.isDirectory }
            .mapNotNull(::readEntry)
            .sortedByDescending { it.createdAt }
            .toList()
    }

    private fun readEntry(dir: File): MigrationHistoryEntry? {
        val metaFile = File(dir, "task.json")
        val manifestFile = File(dir, "manifest.ndjson")
        if (!metaFile.isFile || !manifestFile.isFile) return null
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return null
        var count = 0
        var bytes = 0L
        manifestFile.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val item = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            count++
            bytes += item.optLong("size", 0L).coerceAtLeast(0L)
        }
        return MigrationHistoryEntry(
            migrationId = meta.optString("migrationId", dir.name),
            peerName = meta.optString("peerName", "SpeedShare"),
            createdAt = meta.optLong("createdAt", dir.lastModified()),
            completedAt = meta.optLong("completedAt", 0L).takeIf { it > 0L },
            complete = meta.optBoolean("complete", false),
            totalItems = count,
            totalBytes = bytes
        )
    }
}

private fun formatHistoryDate(value: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))

private fun formatHistoryBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
