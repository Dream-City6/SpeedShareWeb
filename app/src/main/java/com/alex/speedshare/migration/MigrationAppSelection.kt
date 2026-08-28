package com.alex.speedshare.migration

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

internal object MigrationAppSelectionRegistry {
    private val _selectedPackages = MutableStateFlow<Set<String>>(emptySet())
    val selectedPackages = _selectedPackages.asStateFlow()
    private var catalog: Set<String>? = null
    private var catalogSource: List<MigrationAppItem>? = null
    private var compatibilityDefaultsPeer: String? = null

    @Synchronized
    fun sync(apps: List<MigrationAppItem>) {
        if (catalogSource === apps) return
        catalogSource = apps
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
        val current = _selectedPackages.value
        _selectedPackages.value = if (packageName in current) current - packageName else current + packageName
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

    var permissionGranted by remember { mutableStateOf(InstalledAppsPermission.isGranted(context)) }
    var showPermissionDialog by remember {
        mutableStateOf(InstalledAppsPermission.isRuntimeManaged(context) && !permissionGranted)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted || InstalledAppsPermission.isGranted(context)
        if (permissionGranted) controller.scanContent()
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("允许读取应用列表？") },
            text = {
                Text("为了显示并迁移这台手机已安装的应用，SpeedShare 需要读取应用列表。拒绝不会影响照片、视频和普通文件迁移。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionDialog = false
                        permissionLauncher.launch(InstalledAppsPermission.XIAOMI_PERMISSION)
                    }
                ) { Text("继续授权") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) { Text("暂不") }
            }
        )
    }

    val compatibility by produceState<Map<String, AppCompatibilityResult>>(
        initialValue = emptyMap(),
        key1 = apps,
        key2 = receiver
    ) {
        value = withContext(Dispatchers.Default) {
            apps.associate { app -> app.packageName to AppCompatibilityAnalyzer.analyze(context, app, receiver) }
        }
    }
    val incompatible = remember(compatibility) {
        compatibility.filterValues { it.status == AppCompatibilityStatus.INCOMPATIBLE }.keys
    }
    LaunchedEffect(receiver?.deviceId, incompatible) {
        if (compatibility.isNotEmpty()) {
            MigrationAppSelectionRegistry.removeKnownIncompatible(receiver?.deviceId, incompatible)
        }
    }

    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) apps else apps.filter {
            it.label.lowercase().contains(needle) || it.packageName.lowercase().contains(needle)
        }
    }
    val appSizes = remember(apps) { apps.associate { it.packageName to it.totalBytes } }
    val selectedBytes = remember(selected, appSizes) { selected.sumOf { appSizes[it] ?: 0L } }
    val compatibleCount = compatibility.count { it.value.status == AppCompatibilityStatus.COMPATIBLE }
    val reviewCount = compatibility.count { it.value.status == AppCompatibilityStatus.REVIEW }
    val incompatibleCount = compatibility.size - compatibleCount - reviewCount

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showBackToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 5 }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = 88.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "header") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("选择应用", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                            Text("已选 ${selected.size} 个 · ${formatAppBytes(selectedBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = onClose) { Text("完成") }
                    }
                }

                if (InstalledAppsPermission.isRuntimeManaged(context) && !permissionGranted) {
                    item(key = "permission") {
                        Card(shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("应用列表权限未开启", fontWeight = FontWeight.Black)
                                Text(
                                    "当前系统可能只返回少量应用。授权后会自动重新扫描；不授权也可以继续迁移其他内容。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(onClick = { showPermissionDialog = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text("授权读取应用列表")
                                }
                            }
                        }
                    }
                }

                if (receiver != null) {
                    item(key = "receiver") {
                        Card(shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("新手机：${receiver.name}", fontWeight = FontWeight.Bold)
                                Text(
                                    if (compatibility.isEmpty() && apps.isNotEmpty()) {
                                        "正在后台检查应用兼容性…"
                                    } else {
                                        "兼容 $compatibleCount · 需确认 $reviewCount · 不兼容 $incompatibleCount"
                                    },
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
                }

                item(key = "search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索应用或包名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item(key = "select-actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = MigrationAppSelectionRegistry::selectAll, modifier = Modifier.weight(1f)) {
                            Text("全选")
                        }
                        OutlinedButton(onClick = MigrationAppSelectionRegistry::selectNone, modifier = Modifier.weight(1f)) {
                            Text("全不选")
                        }
                    }
                }

                item(key = "hint") {
                    Text(
                        "已明确判断为不兼容的应用默认取消选择；这里只迁移 APK / Split APK，不迁移登录状态和应用私有数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (filtered.isEmpty()) {
                    item(key = "empty") {
                        Card(shape = RoundedCornerShape(16.dp)) {
                            Text(
                                if (!permissionGranted && InstalledAppsPermission.isRuntimeManaged(context)) {
                                    "暂时没有可显示的应用，请先授权应用列表权限。"
                                } else if (query.isNotBlank()) {
                                    "没有匹配的应用。"
                                } else {
                                    "正在扫描或没有找到可迁移应用。"
                                },
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(
                    items = filtered,
                    key = { it.packageName },
                    contentType = { "app" }
                ) { app ->
                    val appCompatibility = compatibility[app.packageName]
                        ?: AppCompatibilityResult(AppCompatibilityStatus.REVIEW, "正在检查")
                    AppSelectionRow(
                        app = app,
                        compatibility = appCompatibility,
                        checked = app.packageName in selected,
                        onToggle = { MigrationAppSelectionRegistry.toggle(app.packageName) }
                    )
                }
            }

            if (showBackToTop) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)
                ) {
                    Text("↑", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun AppSelectionRow(
    app: MigrationAppItem,
    compatibility: AppCompatibilityResult,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppIcon(app.packageName, app.label)
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        app.label,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        compatibilityLabel(compatibility.status),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "${app.versionName.ifBlank { "未知版本" }} · ${formatAppBytes(app.totalBytes)} · ${app.apkFiles.size} 个 APK组件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    compatibility.reason,
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

@Composable
private fun AppIcon(packageName: String, label: String) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = packageName) {
        value = withContext(Dispatchers.IO) {
            MigrationAppVisualResolver.installedIcon(context, packageName, 128)
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = label,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(label.take(1).uppercase(), fontWeight = FontWeight.Black)
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
