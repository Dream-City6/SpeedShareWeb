package com.alex.speedshare

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale

private data class IncomingShareRequest(
    val requestId: Long,
    val uris: List<Uri>,
    val mimeType: String?
)

private data class LaunchActionRequest(
    val requestId: Long,
    val action: String
)

class MainActivity : ComponentActivity() {
    private val incomingShareState = mutableStateOf<IncomingShareRequest?>(null)
    private val launchActionState = mutableStateOf<LaunchActionRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deliverIntent(intent)

        setContent {
            SpeedShareTheme {
                SpeedShareApp(
                    incomingShareRequest = incomingShareState.value,
                    onIncomingShareConsumed = { requestId ->
                        if (incomingShareState.value?.requestId == requestId) {
                            incomingShareState.value = null
                        }
                    },
                    launchActionRequest = launchActionState.value,
                    onLaunchActionConsumed = { requestId ->
                        if (launchActionState.value?.requestId == requestId) {
                            launchActionState.value = null
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deliverIntent(intent)
    }

    private fun deliverIntent(intent: Intent?) {
        if (intent == null) return

        val isShareAction = intent.action == Intent.ACTION_SEND ||
            intent.action == Intent.ACTION_SEND_MULTIPLE

        if (isShareAction) {
            incomingShareState.value = IncomingShareRequest(
                requestId = System.nanoTime(),
                uris = extractSharedUris(intent),
                mimeType = intent.type
            )
            clearHandledIntent()
            return
        }

        val action = intent.action.orEmpty()
        val supportedAction = action in setOf(
            AppActions.SHARE_WHOLE_STORAGE,
            AppActions.CHOOSE_FILES,
            AppActions.STOP_SERVER,
            AppActions.OPEN_SETTINGS,
            AppActions.REQUEST_ALL_FILES_ACCESS,
            TileService.ACTION_QS_TILE_PREFERENCES
        )

        if (supportedAction) {
            launchActionState.value = LaunchActionRequest(
                requestId = System.nanoTime(),
                action = action
            )
            clearHandledIntent()
        }
    }

    private fun clearHandledIntent() {
        setIntent(
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
            }
        )
    }
}

@Composable
private fun SpeedShareApp(
    incomingShareRequest: IncomingShareRequest?,
    onIncomingShareConsumed: (Long) -> Unit,
    launchActionRequest: LaunchActionRequest?,
    onLaunchActionConsumed: (Long) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val serverState by SpeedShareRuntime.state.collectAsState()

    var settings by remember { mutableStateOf(AppSettings.load(context)) }
    val configuration = LocalConfiguration.current
    val tr = remember(settings.language, configuration) {
        Localization.translator(context, settings.language)
    }
    var showSettings by remember { mutableStateOf(false) }
    var showTrashManager by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(settings.defaultMode) }
    var selectedFiles by remember { mutableStateOf<List<SharedFile>>(emptyList()) }
    var uploadEnabled by remember {
        mutableStateOf(settings.defaultUploadEnabled && settings.defaultMode == ShareMode.WHOLE_STORAGE)
    }
    var remoteManagementEnabled by remember {
        mutableStateOf(settings.remoteManagementEnabled && settings.defaultMode == ShareMode.WHOLE_STORAGE)
    }
    var keepAwakeDuringTransfer by remember { mutableStateOf(settings.keepAwakeDuringTransfer) }
    var hasAllFilesAccess by remember { mutableStateOf(hasManageAllFilesAccess()) }
    var waitingForAllFilesAccess by remember { mutableStateOf(false) }
    var autoStartWholeAfterPermission by remember { mutableStateOf(false) }
    var openTrashAfterPermission by remember { mutableStateOf(false) }
    var localStatusText by remember {
        mutableStateOf(tr.text("choose_or_whole"))
    }
    var showAdvanced by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            localStatusText = tr.text("notification_permission_denied")
        }
    }

    fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun startOrReplaceServer(
        targetMode: ShareMode = mode,
        targetFiles: List<SharedFile> = selectedFiles,
        targetUploadEnabled: Boolean = uploadEnabled,
        targetRemoteManagementEnabled: Boolean = remoteManagementEnabled,
        targetKeepAwake: Boolean = keepAwakeDuringTransfer,
        successPrefix: String = tr.text("server_started")
    ) {
        requestNotificationPermissionIfNeeded()
        localStatusText = if (serverState.running || serverState.starting) {
            tr.text("server_replacing")
        } else {
            tr.text("server_starting")
        }

        SpeedShareService.startOrReplace(
            context = context.applicationContext,
            mode = targetMode,
            files = targetFiles,
            uploadEnabled = targetUploadEnabled,
            remoteManagementEnabled = targetRemoteManagementEnabled,
            clipboardSyncEnabled = settings.clipboardSyncEnabled,
            deleteToTrashByDefault = settings.deleteToTrashByDefault,
            keepAwakeDuringTransfer = targetKeepAwake,
            autoStopMinutes = settings.autoStopMinutes,
            preferredPort = settings.preferredPort,
            autoPortFallback = settings.autoPortFallback,
            copyAddressAfterStart = settings.copyAddressAfterStart,
            language = settings.language,
            successPrefix = successPrefix
        )
    }

    fun startWholeStorageNow(successPrefix: String = tr.text("shortcut_whole_started")) {
        openTrashAfterPermission = false
        if (!hasManageAllFilesAccess()) {
            waitingForAllFilesAccess = true
            autoStartWholeAfterPermission = true
            openAllFilesAccessSettings(context)
            localStatusText = tr.text("permission_manage_files_prompt")
            return
        }

        waitingForAllFilesAccess = false
        autoStartWholeAfterPermission = false
        hasAllFilesAccess = true
        mode = ShareMode.WHOLE_STORAGE
        uploadEnabled = settings.defaultUploadEnabled
        remoteManagementEnabled = settings.remoteManagementEnabled
        startOrReplaceServer(
            targetMode = ShareMode.WHOLE_STORAGE,
            targetFiles = emptyList(),
            targetUploadEnabled = settings.defaultUploadEnabled,
            targetRemoteManagementEnabled = settings.remoteManagementEnabled,
            targetKeepAwake = settings.keepAwakeDuringTransfer,
            successPrefix = successPrefix
        )
    }

    fun syncCurrentClipboardToWeb(showStatus: Boolean = false) {
        if (!settings.clipboardSyncEnabled || !serverState.running) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            .orEmpty()
        ClipboardSyncRuntime.snapshot.value = ClipboardSyncSnapshot(
            text = text,
            updatedAtMs = System.currentTimeMillis()
        )
        if (showStatus) {
            localStatusText = if (text.isBlank()) {
                tr.text("clipboard_sync_empty")
            } else {
                tr.text("clipboard_sync_updated")
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val files = uris.mapNotNull { uri ->
                tryTakePersistableReadPermission(context, uri)
                querySharedFile(context, uri)
            }

            selectedFiles = files
            mode = ShareMode.SELECTED_FILES
            uploadEnabled = false
            remoteManagementEnabled = false
            localStatusText = tr.text("selected_files_ready", files.size)
        }
    }

    LaunchedEffect(incomingShareRequest?.requestId) {
        val request = incomingShareRequest ?: return@LaunchedEffect

        if (request.uris.isEmpty()) {
            localStatusText = tr.text("no_readable_files")
            onIncomingShareConsumed(request.requestId)
            return@LaunchedEffect
        }

        val files = request.uris.mapNotNull { uri ->
            tryTakePersistableReadPermission(context, uri)
            querySharedFile(context, uri, request.mimeType)
        }

        if (files.isEmpty()) {
            localStatusText = tr.text("share_files_unreadable")
            onIncomingShareConsumed(request.requestId)
            return@LaunchedEffect
        }

        selectedFiles = files
        mode = ShareMode.SELECTED_FILES
        uploadEnabled = false
        remoteManagementEnabled = false

        if (settings.autoStartIncomingShare) {
            startOrReplaceServer(
                targetMode = ShareMode.SELECTED_FILES,
                targetFiles = files,
                targetUploadEnabled = false,
                targetRemoteManagementEnabled = false,
                targetKeepAwake = settings.keepAwakeDuringTransfer,
                successPrefix = tr.text("incoming_auto_replaced", files.size)
            )
        } else {
            localStatusText = tr.text("incoming_wait_manual", files.size)
        }

        onIncomingShareConsumed(request.requestId)
    }

    LaunchedEffect(launchActionRequest?.requestId) {
        val request = launchActionRequest ?: return@LaunchedEffect
        when (request.action) {
            AppActions.SHARE_WHOLE_STORAGE -> startWholeStorageNow(tr.text("shortcut_whole_started"))
            AppActions.CHOOSE_FILES -> filePicker.launch(arrayOf("*/*"))
            AppActions.STOP_SERVER -> {
                SpeedShareService.stopServer(context.applicationContext)
                localStatusText = tr.text("server_stopped")
            }
            AppActions.OPEN_SETTINGS,
            TileService.ACTION_QS_TILE_PREFERENCES -> showSettings = true
            AppActions.REQUEST_ALL_FILES_ACCESS -> {
                waitingForAllFilesAccess = true
                autoStartWholeAfterPermission = true
                openAllFilesAccessSettings(context)
                localStatusText = tr.text("manage_files_return_start")
            }
        }
        onLaunchActionConsumed(request.requestId)
    }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasManageAllFilesAccess()
                hasAllFilesAccess = granted
                syncCurrentClipboardToWeb()

                if (waitingForAllFilesAccess && granted) {
                    waitingForAllFilesAccess = false
                    localStatusText = tr.text("permission_granted")
                    when {
                        openTrashAfterPermission -> {
                            openTrashAfterPermission = false
                            showTrashManager = true
                        }
                        autoStartWholeAfterPermission -> {
                            autoStartWholeAfterPermission = false
                            mode = ShareMode.WHOLE_STORAGE
                            startWholeStorageNow()
                        }
                    }
                }
            }
        }

        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    DisposableEffect(context, settings.clipboardSyncEnabled, serverState.running) {
        if (!settings.clipboardSyncEnabled || !serverState.running) {
            onDispose { }
        } else {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val listener = ClipboardManager.OnPrimaryClipChangedListener {
                syncCurrentClipboardToWeb()
            }
            clipboard.addPrimaryClipChangedListener(listener)
            syncCurrentClipboardToWeb()
            onDispose {
                clipboard.removePrimaryClipChangedListener(listener)
            }
        }
    }

    BackHandler(enabled = showSettings || showTrashManager) {
        when {
            showTrashManager -> showTrashManager = false
            showSettings -> showSettings = false
        }
    }

    if (showTrashManager) {
        TrashScreen(
            tr = tr,
            onBack = { showTrashManager = false },
            onOpenSystemManager = {
                if (!openSpeedShareTrashInFileManager(context)) {
                    localStatusText = tr.text("trash_open_failed")
                }
            }
        )
        return
    }

    if (showSettings) {
        SettingsScreen(
            initialSettings = settings,
            onBack = { showSettings = false },
            onSettingsChanged = { saved ->
                AppSettings.save(context, saved)
                settings = saved
                if (!serverState.running && !serverState.starting) {
                    mode = saved.defaultMode
                    keepAwakeDuringTransfer = saved.keepAwakeDuringTransfer
                    uploadEnabled = saved.defaultUploadEnabled && saved.defaultMode == ShareMode.WHOLE_STORAGE
                    remoteManagementEnabled = saved.remoteManagementEnabled && saved.defaultMode == ShareMode.WHOLE_STORAGE
                }
                localStatusText = Localization.translator(context, saved.language).text("settings_saved")
            }
        )
        return
    }

    val isServerActive = serverState.running || serverState.starting
    val displayStatus = when {
        isServerActive -> serverState.statusText
        serverState.statusText.isNotBlank() -> serverState.statusText
        else -> localStatusText
    }
    val address = serverState.address ?: findLocalIpv4Address()?.let {
        "http://$it:${settings.preferredPort}/"
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SpeedShareWeb",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "${getAppVersion(context, tr)} · ${tr.text("app_subtitle")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { showSettings = true },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) { Text(tr.text("settings")) }
                }

                ServerStatusCard(
                    tr = tr,
                    running = serverState.running,
                    starting = serverState.starting,
                    statusText = displayStatus,
                    address = address,
                    connections = serverState.activeConnections,
                    downloadSpeed = serverState.downloadBytesPerSecond,
                    uploadSpeed = serverState.uploadBytesPerSecond,
                    taskCount = serverState.activeTransfers.size,
                    activeTransferText = serverState.activeTransfers.firstOrNull()?.let { transfer ->
                        val direction = if (transfer.direction == TransferDirection.UPLOAD) tr.text("speed_upload") else tr.text("speed_download")
                        val percent = if (transfer.totalBytes > 0L) {
                            transfer.transferredBytes * 100.0 / transfer.totalBytes
                        } else 0.0
                        "$direction · ${transfer.fileName} · ${String.format(Locale.getDefault(), "%.1f%%", percent)} · ${formatBytes(transfer.bytesPerSecond)}/s"
                    },
                    onCopy = {
                        address?.let {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(tr.text("address_label"), it))
                            localStatusText = tr.text("address_copied")
                        }
                    },
                    onToggleQr = { showQr = !showQr },
                    onStop = {
                        SpeedShareService.stopServer(context.applicationContext)
                        localStatusText = tr.text("server_stopped")
                    }
                )

                AnimatedVisibility(visible = showQr && serverState.running && address != null) {
                    address?.let { currentAddress ->
                        val qrBitmap = remember(currentAddress) { createQrCodeBitmap(currentAddress, 768) }
                        if (qrBitmap != null) {
                            CompactCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Image(
                                        bitmap = qrBitmap.asImageBitmap(),
                                        contentDescription = tr.text("qr_description"),
                                        modifier = Modifier
                                            .size(142.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(tr.text("scan_to_access"), fontWeight = FontWeight.Bold)
                                        Text(
                                            currentAddress,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            buildAccessSummary(tr, serverState),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(
                                                        ClipData.newPlainText(
                                                            tr.text("qr_access_instructions"),
                                                            buildAccessInstructions(tr, serverState)
                                                        )
                                                    )
                                                    localStatusText = tr.text("qr_access_copied")
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                                            ) { Text(tr.text("copy_access_info")) }
                                            OutlinedButton(
                                                onClick = { showQr = false },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                                            ) { Text(tr.text("collapse")) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = settings.clipboardSyncEnabled && serverState.running) {
                    CompactCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(tr.text("clipboard_sync"), fontWeight = FontWeight.Bold)
                                Text(
                                    tr.text("clipboard_sync_phone_sub"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    syncCurrentClipboardToWeb(showStatus = true)
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) { Text(tr.text("clipboard_sync_now")) }
                        }
                    }
                }

                AnimatedVisibility(visible = serverState.history.isNotEmpty()) {
                    CompactCard(modifier = Modifier.fillMaxWidth()) {
                        Text(tr.text("transfer_history"), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        serverState.history.take(4).forEach { item ->
                            Text(
                                formatHistoryLine(item, tr),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ActionTile(
                        iconRes = R.drawable.ic_add_24,
                        title = tr.text("choose_files"),
                        subtitle = tr.text("choose_files_sub"),
                        modifier = Modifier.weight(1f),
                        onClick = { filePicker.launch(arrayOf("*/*")) }
                    )
                    ActionTile(
                        iconRes = R.drawable.ic_storage_24,
                        title = tr.text("whole_phone"),
                        subtitle = tr.text("whole_phone_sub"),
                        modifier = Modifier.weight(1f),
                        onClick = { startWholeStorageNow() }
                    )
                }

                CompactCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!hasManageAllFilesAccess()) {
                                waitingForAllFilesAccess = true
                                autoStartWholeAfterPermission = false
                                openTrashAfterPermission = true
                                openAllFilesAccessSettings(context)
                                localStatusText = tr.text("trash_permission_prompt")
                            } else {
                                waitingForAllFilesAccess = false
                                openTrashAfterPermission = false
                                showTrashManager = true
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        IconBubble(iconRes = R.drawable.ic_delete_24, size = 38.dp, corner = 12.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr.text("open_trash"), fontWeight = FontWeight.Bold)
                            Text(
                                tr.text("open_trash_sub"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_right_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                CompactCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { showAdvanced = !showAdvanced }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp)
                    ) {
                        IconBubble(iconRes = R.drawable.ic_settings_24, size = 40.dp, corner = 13.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr.text("custom_start"), fontWeight = FontWeight.Black)
                            Text(
                                if (showAdvanced) tr.text("adjust_mode_permissions") else tr.text(
                                    "mode_summary",
                                    if (mode == ShareMode.WHOLE_STORAGE) tr.text("whole_phone") else tr.text("selected_files"),
                                    selectedFiles.size
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            if (showAdvanced) "︿" else "﹀",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatusChip(
                            if (mode == ShareMode.WHOLE_STORAGE) tr.text("whole_phone") else tr.text("selected_files")
                        )
                        StatusChip(tr.text("selected_count_short", selectedFiles.size))
                    }

                    AnimatedVisibility(visible = showAdvanced) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                            Text(
                                tr.text("default_share_mode"),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CompactSegment(
                                    text = tr.text("selected_share"),
                                    selected = mode == ShareMode.SELECTED_FILES,
                                    enabled = selectedFiles.isNotEmpty(),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    mode = ShareMode.SELECTED_FILES
                                    uploadEnabled = false
                                    remoteManagementEnabled = false
                                }
                                CompactSegment(
                                    text = tr.text("whole_directory"),
                                    selected = mode == ShareMode.WHOLE_STORAGE,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (hasManageAllFilesAccess()) {
                                        hasAllFilesAccess = true
                                        mode = ShareMode.WHOLE_STORAGE
                                        uploadEnabled = settings.defaultUploadEnabled
                                        remoteManagementEnabled = settings.remoteManagementEnabled
                                    } else {
                                        waitingForAllFilesAccess = true
                                        openAllFilesAccessSettings(context)
                                    }
                                }
                            }

                            CompactSwitchRow(
                                title = tr.text("allow_upload"),
                                subtitle = tr.text("allow_upload_sub"),
                                checked = uploadEnabled,
                                enabled = mode == ShareMode.WHOLE_STORAGE,
                                onCheckedChange = { uploadEnabled = it }
                            )
                            CompactSwitchRow(
                                title = tr.text("remote_management"),
                                subtitle = tr.text("remote_management_sub"),
                                checked = remoteManagementEnabled,
                                enabled = mode == ShareMode.WHOLE_STORAGE,
                                onCheckedChange = { remoteManagementEnabled = it }
                            )
                            CompactSwitchRow(
                                title = tr.text("lockscreen_protection"),
                                subtitle = tr.text("lockscreen_protection_sub"),
                                checked = keepAwakeDuringTransfer,
                                onCheckedChange = { keepAwakeDuringTransfer = it }
                            )

                            Button(
                                enabled = (mode == ShareMode.SELECTED_FILES && selectedFiles.isNotEmpty()) ||
                                    (mode == ShareMode.WHOLE_STORAGE && hasAllFilesAccess),
                                onClick = {
                                    startOrReplaceServer(
                                        targetMode = mode,
                                        targetFiles = selectedFiles,
                                        targetUploadEnabled = uploadEnabled,
                                        targetRemoteManagementEnabled = remoteManagementEnabled,
                                        targetKeepAwake = keepAwakeDuringTransfer,
                                        successPrefix = if (isServerActive) tr.text("replaced_server") else tr.text("server_started")
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(vertical = 12.dp)
                            ) {
                                Text(
                                    if (isServerActive) tr.text("replace_restart") else tr.text("start_current"),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (!serverState.running && address == null) {
                    Text(
                        tr.text("no_ipv4"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun TrashScreen(
    tr: Translator,
    onBack: () -> Unit,
    onOpenSystemManager: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val manager = remember(tr.language) {
        TrashManager(Environment.getExternalStorageDirectory(), tr)
    }
    var entries by remember(manager) { mutableStateOf<List<TrashEntry>>(emptyList()) }
    var selectedIds by remember(manager) { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<String?>(null) }

    fun loadEntries() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { manager.listEntries() }
            }
            result.onSuccess { loaded ->
                entries = loaded
                selectedIds = selectedIds.intersect(loaded.map { it.id }.toSet())
            }.onFailure { error ->
                statusText = tr.text("trash_operation_failed", error.message ?: tr.text("unknown_error"))
            }
        }
    }

    fun restoreSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty() || busy) return
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ids.forEach { manager.restore(it, ConflictPolicy.AUTO_RENAME) }
                    manager.listEntries()
                }
            }
            busy = false
            result.onSuccess { loaded ->
                entries = loaded
                selectedIds = emptySet()
                statusText = tr.text("trash_restore_done", ids.size)
            }.onFailure { error ->
                statusText = tr.text("trash_operation_failed", error.message ?: tr.text("unknown_error"))
                loadEntries()
            }
        }
    }

    fun deleteSelectedForever() {
        val ids = selectedIds.toList()
        if (ids.isEmpty() || busy) return
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ids.forEach { id ->
                        check(manager.permanentDelete(id)) { id }
                    }
                    manager.listEntries()
                }
            }
            busy = false
            result.onSuccess { loaded ->
                entries = loaded
                selectedIds = emptySet()
                statusText = tr.text("trash_delete_done", ids.size)
            }.onFailure { error ->
                statusText = tr.text("trash_operation_failed", error.message ?: tr.text("unknown_error"))
                loadEntries()
            }
        }
    }

    fun emptyTrash() {
        if (busy) return
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    check(manager.emptyTrash())
                    manager.listEntries()
                }
            }
            busy = false
            result.onSuccess { loaded ->
                entries = loaded
                selectedIds = emptySet()
                statusText = tr.text("trash_empty_done")
            }.onFailure { error ->
                statusText = tr.text("trash_operation_failed", error.message ?: tr.text("unknown_error"))
                loadEntries()
            }
        }
    }

    LaunchedEffect(manager) { loadEntries() }

    if (confirmation != null) {
        val deletingSelected = confirmation == "delete"
        AlertDialog(
            onDismissRequest = { if (!busy) confirmation = null },
            title = {
                Text(
                    if (deletingSelected) tr.text("trash_confirm_delete_title")
                    else tr.text("trash_confirm_empty_title")
                )
            },
            text = {
                Text(
                    if (deletingSelected) tr.text("trash_confirm_delete_body")
                    else tr.text("trash_confirm_empty_body")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmation = null
                        if (deletingSelected) deleteSelectedForever() else emptyTrash()
                    }
                ) { Text(tr.text("confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) { Text(tr.text("cancel")) }
            }
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CompactCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) { Text("🗑", color = Color.White) }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr.text("trash_manager_title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                            Text(
                                tr.text("trash_manager_subtitle"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp)) {
                            Text(tr.text("back"))
                        }
                    }
                }

                CompactCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(tr.text("trash_item_count", entries.size), fontWeight = FontWeight.Bold)
                            Text(
                                tr.text("trash_selected_count", selectedIds.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            enabled = entries.isNotEmpty() && !busy,
                            onClick = {
                                selectedIds = if (selectedIds.size == entries.size) emptySet() else entries.map { it.id }.toSet()
                            }
                        ) {
                            Text(if (selectedIds.size == entries.size && entries.isNotEmpty()) tr.text("clear_selection") else tr.text("select_all"))
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        if (maxWidth < 390.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Button(
                                    enabled = selectedIds.isNotEmpty() && !busy,
                                    onClick = ::restoreSelected,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(13.dp)
                                ) { Text(tr.text("restore_selected")) }
                                OutlinedButton(
                                    enabled = selectedIds.isNotEmpty() && !busy,
                                    onClick = { confirmation = "delete" },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(13.dp)
                                ) { Text(tr.text("delete_forever")) }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = selectedIds.isNotEmpty() && !busy,
                                    onClick = ::restoreSelected,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(13.dp)
                                ) { Text(tr.text("restore_selected")) }
                                OutlinedButton(
                                    enabled = selectedIds.isNotEmpty() && !busy,
                                    onClick = { confirmation = "delete" },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(13.dp)
                                ) { Text(tr.text("delete_forever")) }
                            }
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = entries.isNotEmpty() && !busy,
                            onClick = { confirmation = "empty" },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(13.dp)
                        ) { Text(tr.text("empty_trash"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = onOpenSystemManager,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(13.dp)
                        ) { Text(tr.text("open_system_manager"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }

                if (busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }

                if (statusText.isNotBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Text(statusText, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (entries.isEmpty() && !busy) {
                    CompactCard(modifier = Modifier.fillMaxWidth()) {
                        Text(tr.text("trash_empty"), fontWeight = FontWeight.Black)
                        Text(
                            tr.text("trash_empty_sub"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    entries.forEach { entry ->
                        val selected = entry.id in selectedIds
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) {
                                    selectedIds = if (selected) selectedIds - entry.id else selectedIds + entry.id
                                },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 3.dp else 1.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(
                                    checked = selected,
                                    enabled = !busy,
                                    onCheckedChange = {
                                        selectedIds = if (it) selectedIds + entry.id else selectedIds - entry.id
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        tr.text("original_location", entry.originalRelativePath),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${if (entry.isDirectory) tr.text("folder") else tr.text("file")} · ${formatBytes(entry.size)} · ${formatDate(entry.deletedAtMs)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    initialSettings: SpeedShareSettings,
    onBack: () -> Unit,
    onSettingsChanged: (SpeedShareSettings) -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var draft by remember(initialSettings) { mutableStateOf(initialSettings) }
    val tr = remember(draft.language, configuration) {
        Localization.translator(context, draft.language)
    }
    var portText by remember(initialSettings) { mutableStateOf(initialSettings.preferredPort.toString()) }
    var statusText by remember { mutableStateOf("") }

    fun saveDraft(updated: SpeedShareSettings) {
        draft = updated
        onSettingsChanged(updated)
        statusText = Localization.translator(context, updated.language).text("settings_saved")
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CompactCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp)
                    ) {
                        IconBubble(iconRes = R.drawable.ic_settings_24, size = 42.dp, corner = 14.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr.text("settings"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                            Text(
                                tr.text("settings_subtitle"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp)) {
                            Text(tr.text("back"))
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Text(
                        tr.text("settings_auto_save_hint"),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                SettingsSection(tr.text("language")) {
                    Text(tr.text("language_sub"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AdaptiveSegmentGrid(
                        options = listOf(
                            AppLanguage.SYSTEM to tr.text("language_system"),
                            AppLanguage.SIMPLIFIED_CHINESE to tr.text("language_chinese"),
                            AppLanguage.JAPANESE to tr.text("language_japanese"),
                            AppLanguage.ENGLISH to tr.text("language_english")
                        ),
                        selected = draft.language,
                        onSelect = { saveDraft(draft.copy(language = it)) }
                    )
                }

                SettingsSection(tr.text("default_behavior")) {
                    Text(tr.text("default_share_mode"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactSegment(
                            text = tr.text("whole_phone"),
                            selected = draft.defaultMode == ShareMode.WHOLE_STORAGE,
                            modifier = Modifier.weight(1f)
                        ) { saveDraft(draft.copy(defaultMode = ShareMode.WHOLE_STORAGE)) }
                        CompactSegment(
                            text = tr.text("selected_files"),
                            selected = draft.defaultMode == ShareMode.SELECTED_FILES,
                            modifier = Modifier.weight(1f)
                        ) { saveDraft(draft.copy(defaultMode = ShareMode.SELECTED_FILES)) }
                    }
                    CompactSwitchRow(tr.text("allow_upload"), tr.text("allow_upload_sub"), draft.defaultUploadEnabled) {
                        saveDraft(draft.copy(defaultUploadEnabled = it))
                    }
                    CompactSwitchRow(tr.text("remote_management"), tr.text("remote_management_sub"), draft.remoteManagementEnabled) {
                        saveDraft(draft.copy(remoteManagementEnabled = it))
                    }
                    CompactSwitchRow(tr.text("clipboard_sync"), tr.text("clipboard_sync_sub"), draft.clipboardSyncEnabled) {
                        saveDraft(draft.copy(clipboardSyncEnabled = it))
                    }
                    CompactSwitchRow(tr.text("delete_to_trash"), tr.text("delete_to_trash_sub"), draft.deleteToTrashByDefault) {
                        saveDraft(draft.copy(deleteToTrashByDefault = it))
                    }
                    CompactSwitchRow(tr.text("auto_start_share"), tr.text("auto_start_share_sub"), draft.autoStartIncomingShare) {
                        saveDraft(draft.copy(autoStartIncomingShare = it))
                    }
                    CompactSwitchRow(tr.text("lockscreen_protection"), tr.text("lockscreen_protection_sub"), draft.keepAwakeDuringTransfer) {
                        saveDraft(draft.copy(keepAwakeDuringTransfer = it))
                    }
                }

                SettingsSection(tr.text("auto_stop")) {
                    AdaptiveSegmentGrid(
                        options = listOf(
                            0 to tr.text("never"),
                            10 to tr.text("minutes_10"),
                            30 to tr.text("minutes_30"),
                            60 to tr.text("hour_1")
                        ),
                        selected = draft.autoStopMinutes,
                        onSelect = { saveDraft(draft.copy(autoStopMinutes = it)) }
                    )
                }

                SettingsSection(tr.text("network_port")) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { value ->
                            portText = value.filter(Char::isDigit).take(5)
                            val port = portText.toIntOrNull()
                            if (port != null && port in 1024..65535 && port != draft.preferredPort) {
                                saveDraft(draft.copy(preferredPort = port))
                            } else if (portText.length >= 4 && (port == null || port !in 1024..65535)) {
                                statusText = tr.text("port_range_error")
                            }
                        },
                        label = { Text(tr.text("preferred_port")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp)
                    )
                    CompactSwitchRow(tr.text("auto_find_port"), tr.text("auto_find_port_sub"), draft.autoPortFallback) {
                        saveDraft(draft.copy(autoPortFallback = it))
                    }
                    CompactSwitchRow(tr.text("copy_after_start"), tr.text("copy_after_start_sub"), draft.copyAddressAfterStart) {
                        saveDraft(draft.copy(copyAddressAfterStart = it))
                    }
                }

                SettingsSection(tr.text("system_shortcuts")) {
                    OutlinedButton(
                        onClick = { requestQuickSettingsTile(context, draft.language) { statusText = it } },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp)
                    ) { Text(tr.text("add_quick_tile")) }
                    OutlinedButton(
                        onClick = {
                            statusText = if (requestPinnedWholeStorageShortcut(context, draft.language)) {
                                tr.text("request_pinned_success")
                            } else {
                                tr.text("request_pinned_unsupported")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp)
                    ) { Text(tr.text("add_whole_to_home")) }
                    Text(
                        tr.text("version_tile_help", getAppVersion(context, tr)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (statusText.isNotBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Text(statusText, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }

                Text(
                    tr.text("settings_credit"),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
@Composable
private fun SpeedShareTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colorScheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF9EA8FF),
            onPrimary = Color(0xFF111A53),
            primaryContainer = Color(0xFF213B78),
            onPrimaryContainer = Color(0xFFE0E3FF),
            secondary = Color(0xFF5EEAD4),
            secondaryContainer = Color(0xFF134E4A),
            background = Color(0xFF07111F),
            surface = Color(0xFF101827),
            surfaceVariant = Color(0xFF172033),
            outline = Color(0xFF34415B)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF2563EB),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDBEAFE),
            onPrimaryContainer = Color(0xFF0F2D6B),
            secondary = Color(0xFF0F766E),
            secondaryContainer = Color(0xFFCCFBF1),
            background = Color(0xFFF6F8FC),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEEF3F8),
            outline = Color(0xFFD5DEE8)
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
private fun ServerStatusCard(
    tr: Translator,
    running: Boolean,
    starting: Boolean,
    statusText: String,
    address: String?,
    connections: Int,
    downloadSpeed: Long,
    uploadSpeed: Long,
    taskCount: Int,
    activeTransferText: String?,
    onCopy: () -> Unit,
    onToggleQr: () -> Unit,
    onStop: () -> Unit
) {
    val gradient = if (running) {
        Brush.linearGradient(listOf(Color(0xFF4057D6), Color(0xFF7656B8)))
    } else {
        Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.surface
            )
        )
    }
    val foreground = if (running) Color.White else MaterialTheme.colorScheme.onSurface
    val secondaryText = if (running) Color.White.copy(alpha = 0.80f) else MaterialTheme.colorScheme.onSurfaceVariant
    val compactStatus = statusText.replace(Regex("(\\d)\\s+ms\\b", RegexOption.IGNORE_CASE)) { match -> "${match.groupValues[1]}\u00A0ms" }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = if (running) 9.dp else 2.dp)
    ) {
        Column(
            modifier = Modifier
                .background(gradient)
                .padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (running) Color(0xFF78F2BA) else if (starting) Color(0xFFFFD166) else Color(0xFF9CA3AF))
                )
                Text(
                    if (running) tr.text("running") else if (starting) tr.text("starting") else tr.text("stopped"),
                    modifier = Modifier.weight(1f),
                    color = foreground,
                    fontWeight = FontWeight.Bold
                )
                if (running) {
                    OutlinedButton(
                        onClick = onStop,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground),
                        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp)
                    ) { Text(tr.text("stop"), maxLines = 1) }
                }
            }

            Text(
                compactStatus,
                modifier = Modifier.fillMaxWidth(),
                color = secondaryText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (address != null) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    if (maxWidth < 430.dp && running) {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            SelectionContainer {
                                Text(
                                    address,
                                    color = foreground,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                OutlinedButton(
                                    onClick = onCopy,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground),
                                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp)
                                ) { Text(tr.text("copy")) }
                                OutlinedButton(
                                    onClick = onToggleQr,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground),
                                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp)
                                ) { Text(tr.text("qr_code")) }
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SelectionContainer(modifier = Modifier.weight(1f)) {
                                Text(
                                    address,
                                    color = foreground,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (running) {
                                OutlinedButton(
                                    onClick = onCopy,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) { Text(tr.text("copy")) }
                                Spacer(Modifier.size(6.dp))
                                OutlinedButton(
                                    onClick = onToggleQr,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) { Text(tr.text("qr_code")) }
                            }
                        }
                    }
                }
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val connectionMetric = Triple(tr.text("connections"), connections.toString(), "")
                val sendMetric = Triple(tr.text("send"), formatTransferRate(downloadSpeed).first, formatTransferRate(downloadSpeed).second)
                val receiveMetric = Triple(tr.text("receive"), formatTransferRate(uploadSpeed).first, formatTransferRate(uploadSpeed).second)
                val taskMetric = Triple(tr.text("tasks"), taskCount.toString(), "")
                val metrics = listOf(connectionMetric, sendMetric, receiveMetric, taskMetric)

                if (maxWidth < 520.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        metrics.chunked(2).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                rowItems.forEach { (label, value, unit) ->
                                    MetricPill(label, value, unit, foreground, secondaryText, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        metrics.forEach { (label, value, unit) ->
                            MetricPill(label, value, unit, foreground, secondaryText, Modifier.weight(1f))
                        }
                    }
                }
            }

            if (!activeTransferText.isNullOrBlank()) {
                Text(
                    activeTransferText,
                    color = secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MetricPill(
    label: String,
    value: String,
    unit: String,
    foreground: Color,
    secondaryText: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(foreground.copy(alpha = 0.09f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = secondaryText,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = foreground,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
            if (unit.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    unit,
                    color = secondaryText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

private fun formatTransferRate(bytesPerSecond: Long): Pair<String, String> {
    val safe = bytesPerSecond.coerceAtLeast(0L).toDouble()
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = safe
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    val number = when {
        index == 0 -> value.toLong().toString()
        value >= 100 -> String.format(java.util.Locale.US, "%.0f", value)
        value >= 10 -> String.format(java.util.Locale.US, "%.1f", value)
        else -> String.format(java.util.Locale.US, "%.2f", value)
    }
    return number to units[index]
}

private fun buildAccessSummary(tr: Translator, state: ServerUiState): String {
    val address = state.address.orEmpty()
    val host = runCatching { java.net.URI(address).host }.getOrNull().orEmpty()
    val port = state.port?.toString().orEmpty()
    val mode = when (state.mode) {
        ShareMode.WHOLE_STORAGE -> tr.text("whole_phone")
        ShareMode.SELECTED_FILES -> tr.text("selected_files")
        null -> tr.text("mode_server")
    }
    val permissions = listOfNotNull(
        if (state.uploadEnabled) tr.text("mode_upload") else tr.text("mode_readonly"),
        if (state.remoteManagementEnabled) tr.text("mode_manage") else null
    ).joinToString(" · ")
    return listOf(
        tr.text("qr_ip", host.ifBlank { "-" }),
        tr.text("qr_port", port.ifBlank { "-" }),
        mode,
        permissions
    ).filter { it.isNotBlank() }.joinToString(" · ")
}

private fun buildAccessInstructions(tr: Translator, state: ServerUiState): String {
    val address = state.address.orEmpty()
    return buildString {
        appendLine(tr.text("qr_access_title"))
        appendLine(address)
        appendLine(buildAccessSummary(tr, state))
    }.trim()
}

private fun formatHistoryLine(item: TransferHistoryItem, tr: Translator): String {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.timestampMs))
    val kind = historyKindText(item.kind, tr)
    val size = if (item.bytes > 0L) " · ${formatBytes(item.bytes)}" else ""
    val count = if (item.itemCount > 1) " · ${tr.text("web_items", item.itemCount)}" else ""
    return "$time · $kind · ${item.name}$size$count · ${item.clientAddress}"
}

private fun historyKindText(kind: TransferHistoryKind, tr: Translator): String = when (kind) {
    TransferHistoryKind.DOWNLOAD -> tr.text("speed_download")
    TransferHistoryKind.UPLOAD -> tr.text("speed_upload")
    TransferHistoryKind.COPY -> tr.text("op_copy")
    TransferHistoryKind.MOVE -> tr.text("op_move")
    TransferHistoryKind.TRASH -> tr.text("op_trash")
    TransferHistoryKind.DELETE -> tr.text("op_delete")
    TransferHistoryKind.RESTORE -> tr.text("op_restore")
    TransferHistoryKind.RENAME -> tr.text("web_action_rename")
    TransferHistoryKind.MKDIR -> tr.text("web_new_folder")
}

@Composable
private fun ActionTile(
    iconRes: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, label = "actionScale")
    Card(
        modifier = modifier
            .scale(scale)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            IconBubble(iconRes = iconRes, size = 38.dp, corner = 12.dp)
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CompactCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun IconBubble(
    iconRes: Int,
    size: androidx.compose.ui.unit.Dp,
    corner: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.56f)
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    CompactCard(modifier = Modifier.fillMaxWidth()) {
        Text(title, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun StatusChip(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun <T> AdaptiveSegmentGrid(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columnCount = if (maxWidth < 430.dp) 2 else options.size.coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.chunked(columnCount).forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowOptions.forEach { (value, label) ->
                        CompactSegment(
                            text = label,
                            selected = selected == value,
                            modifier = Modifier.weight(1f)
                        ) { onSelect(value) }
                    }
                    repeat(columnCount - rowOptions.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactSegment(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background.copy(alpha = if (enabled) 1f else 0.45f))
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = foreground.copy(alpha = if (enabled) 1f else 0.45f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CompactSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 11.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.45f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

private fun getAppVersion(context: Context, tr: Translator): String {
    return runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: tr.text("unknown")
    }.getOrDefault(tr.text("unknown"))
}

private fun extractSharedUris(intent: Intent): List<Uri> {
    val uris = LinkedHashSet<Uri>()
    intent.clipData?.let { clipData ->
        for (index in 0 until clipData.itemCount) {
            clipData.getItemAt(index).uri?.let(uris::add)
        }
    }
    intent.data?.let(uris::add)
    when (intent.action) {
        Intent.ACTION_SEND -> getSingleStreamUri(intent)?.let(uris::add)
        Intent.ACTION_SEND_MULTIPLE -> getMultipleStreamUris(intent).forEach(uris::add)
    }
    return uris.toList()
}

@Suppress("DEPRECATION")
private fun getSingleStreamUri(intent: Intent): Uri? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }
}

@Suppress("DEPRECATION")
private fun getMultipleStreamUris(intent: Intent): List<Uri> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    } else {
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    }
}

private fun tryTakePersistableReadPermission(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    } catch (_: Exception) {
    }
}

private fun hasManageAllFilesAccess(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        Environment.isExternalStorageManager()
}
