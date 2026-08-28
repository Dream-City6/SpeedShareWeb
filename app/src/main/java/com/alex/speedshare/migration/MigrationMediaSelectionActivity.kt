package com.alex.speedshare.migration

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alex.speedshare.AppSettings
import com.alex.speedshare.ui.theme.SpeedShareTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal object MigrationMediaSelectionRegistry {
    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths = _selectedPaths.asStateFlow()
    private var catalog: Set<String>? = null

    @Synchronized
    fun sync(files: List<MigrationFileItem>) {
        val media = files.asSequence()
            .filter { it.category == MigrationCategory.PHOTOS || it.category == MigrationCategory.VIDEOS }
            .mapTo(linkedSetOf()) { it.relativePath }
        if (catalog != media) {
            catalog = media
            _selectedPaths.value = media
        }
    }

    fun toggle(path: String) {
        val current = _selectedPaths.value
        _selectedPaths.value = if (path in current) current - path else current + path
    }

    fun selectCategory(items: List<MigrationFileItem>, category: MigrationCategory, selected: Boolean) {
        val paths = items.asSequence().filter { it.category == category }.map { it.relativePath }.toSet()
        _selectedPaths.value = if (selected) _selectedPaths.value + paths else _selectedPaths.value - paths
    }

    fun selectRecent(items: List<MigrationFileItem>, category: MigrationCategory, sinceMs: Long) {
        val categoryPaths = items.asSequence().filter { it.category == category }.map { it.relativePath }.toSet()
        val recent = items.asSequence()
            .filter { it.category == category && it.modifiedAt >= sinceMs }
            .map { it.relativePath }
            .toSet()
        _selectedPaths.value = (_selectedPaths.value - categoryPaths) + recent
    }

    fun filterTransferItems(items: List<MigrationFileItem>): List<MigrationFileItem> {
        val known = catalog ?: return items
        val selected = _selectedPaths.value
        return items.filter { item ->
            item.category !in MEDIA_CATEGORIES || item.relativePath !in known || item.relativePath in selected
        }
    }

    private val MEDIA_CATEGORIES = setOf(MigrationCategory.PHOTOS, MigrationCategory.VIDEOS)
}

class MigrationMediaSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings = remember { AppSettings.load(this) }
            SpeedShareTheme(themeMode = settings.themeMode) {
                MigrationMediaSelectionScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun MigrationMediaSelectionScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ResilientMigrationController.get(context) }
    val state by controller.state.collectAsState()
    val media = state.scanResult.files.filter {
        it.category == MigrationCategory.PHOTOS || it.category == MigrationCategory.VIDEOS
    }
    MigrationMediaSelectionRegistry.sync(media)
    val selected by MigrationMediaSelectionRegistry.selectedPaths.collectAsState()
    var category by remember { mutableStateOf(MigrationCategory.PHOTOS) }
    val visible = remember(media, category) { media.filter { it.category == category } }
    val selectedVisible = visible.count { it.relativePath in selected }
    val selectedBytes = visible.asSequence().filter { it.relativePath in selected }.sumOf { it.size }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val showBackToTop by remember { derivedStateOf { gridState.firstVisibleItemIndex > 18 } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("选择照片和视频", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                        Text(
                            "当前已选 $selectedVisible / ${visible.size} · ${formatMediaBytes(selectedBytes)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = onClose) { Text("完成") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MediaTab("照片", category == MigrationCategory.PHOTOS, Modifier.weight(1f)) {
                        category = MigrationCategory.PHOTOS
                    }
                    MediaTab("视频", category == MigrationCategory.VIDEOS, Modifier.weight(1f)) {
                        category = MigrationCategory.VIDEOS
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { MigrationMediaSelectionRegistry.selectCategory(media, category, true) },
                        modifier = Modifier.weight(1f)
                    ) { Text("全选") }
                    OutlinedButton(
                        onClick = { MigrationMediaSelectionRegistry.selectCategory(media, category, false) },
                        modifier = Modifier.weight(1f)
                    ) { Text("全不选") }
                    OutlinedButton(
                        onClick = {
                            MigrationMediaSelectionRegistry.selectRecent(
                                media,
                                category,
                                System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("近30天") }
                }

                if (visible.isEmpty()) {
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("没有扫描到此类媒体", Modifier.padding(18.dp))
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 82.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(visible, key = { it.relativePath }) { item ->
                            MediaTile(
                                item = item,
                                selected = item.relativePath in selected,
                                onToggle = { MigrationMediaSelectionRegistry.toggle(item.relativePath) }
                            )
                        }
                    }
                }
            }

            if (showBackToTop) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { gridState.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)
                ) {
                    Text("↑", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun MediaTab(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (active) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun MediaTile(item: MigrationFileItem, selected: Boolean, onToggle: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = item.file.absolutePath) {
        value = withContext(Dispatchers.IO) { MediaThumbnailCache.load(item) }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onToggle)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = item.file.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                if (item.category == MigrationCategory.VIDEOS) "视频" else "照片",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
            shape = RoundedCornerShape(999.dp),
            color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f),
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White
        ) {
            Text(if (selected) "✓" else "○", Modifier.padding(horizontal = 7.dp, vertical = 3.dp), fontWeight = FontWeight.Black)
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            shape = RoundedCornerShape(6.dp),
            color = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White
        ) {
            Text(
                text = if (item.category == MigrationCategory.VIDEOS) formatMediaBytes(item.size) else item.file.name,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private object MediaThumbnailCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    @Synchronized
    fun load(item: MigrationFileItem): Bitmap? {
        cache.get(item.file.absolutePath)?.let { return it }
        val bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (item.category == MigrationCategory.VIDEOS) {
                    ThumbnailUtils.createVideoThumbnail(item.file, Size(320, 320), null)
                } else {
                    ThumbnailUtils.createImageThumbnail(item.file, Size(320, 320), null)
                }
            } else if (item.category == MigrationCategory.VIDEOS) {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(item.file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
            } else {
                decodeSampledBitmap(item.file, 320)
            }
        }.getOrNull()
        if (bitmap != null) cache.put(item.file.absolutePath, bitmap)
        return bitmap
    }

    private fun decodeSampledBitmap(file: File, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > target * 2 || bounds.outHeight / sample > target * 2) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    }
}

private fun formatMediaBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
