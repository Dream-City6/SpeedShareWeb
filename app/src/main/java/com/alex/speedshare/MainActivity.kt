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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.LinkedHashSet

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
        if (!hasManageAllFilesAccess()) {
            waitingForAllFilesAccess = true
            autoStartWholeAfterPermission = true
            openAllFilesAccessSettings(context)
            localStatusText = tr.text("permission_manage_files_prompt")
            return
        }

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
            querySharedFile(context, uri)
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

                if (waitingForAllFilesAccess && granted) {
                    waitingForAllFilesAccess = false
                    mode = ShareMode.WHOLE_STORAGE
                    localStatusText = tr.text("permission_granted")
                    if (autoStartWholeAfterPermission) {
                        autoStartWholeAfterPermission = false
                        startWholeStorageNow()
                    }
                }
            }
        }

        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    if (showSettings) {
        SettingsScreen(
            initialSettings = settings,
            onBack = { showSettings = false },
            onSaved = { saved ->
                AppSettings.save(context, saved)
                settings = saved
                mode = saved.defaultMode
                keepAwakeDuringTransfer = saved.keepAwakeDuringTransfer
                uploadEnabled = saved.defaultUploadEnabled && saved.defaultMode == ShareMode.WHOLE_STORAGE
                remoteManagementEnabled = saved.remoteManagementEnabled && saved.defaultMode == ShareMode.WHOLE_STORAGE
                val savedTranslator = Localization.translator(context, saved.language)
                localStatusText = savedTranslator.text("settings_saved")
                if (serverState.running || serverState.starting) {
                    SpeedShareService.startOrReplace(
                        context = context.applicationContext,
                        mode = mode,
                        files = selectedFiles,
                        uploadEnabled = uploadEnabled,
                        remoteManagementEnabled = remoteManagementEnabled,
                        deleteToTrashByDefault = saved.deleteToTrashByDefault,
                        keepAwakeDuringTransfer = saved.keepAwakeDuringTransfer,
                        autoStopMinutes = saved.autoStopMinutes,
                        preferredPort = saved.preferredPort,
                        autoPortFallback = saved.autoPortFallback,
                        copyAddressAfterStart = false,
                        language = saved.language,
                        successPrefix = savedTranslator.text("replaced_server")
                    )
                }
                showSettings = false
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
                            text = "SpeedShare",
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
                        "$direction · ${transfer.fileName} · ${String.format("%.1f%%", percent)} · ${formatBytes(transfer.bytesPerSecond)}/s"
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
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedButton(onClick = { showQr = false }) { Text(tr.text("collapse")) }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ActionTile(
                        symbol = "＋",
                        title = tr.text("choose_files"),
                        subtitle = tr.text("choose_files_sub"),
                        modifier = Modifier.weight(1f),
                        onClick = { filePicker.launch(arrayOf("*/*")) }
                    )
                    ActionTile(
                        symbol = "↗",
                        title = tr.text("whole_phone"),
                        subtitle = tr.text("whole_phone_sub"),
                        modifier = Modifier.weight(1f),
                        onClick = { startWholeStorageNow() }
                    )
                }

                CompactCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAdvanced = !showAdvanced },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr.text("custom_start"), fontWeight = FontWeight.Bold)
                            Text(
                                if (showAdvanced) tr.text("adjust_mode_permissions") else tr.text("mode_summary", if (mode == ShareMode.WHOLE_STORAGE) tr.text("whole_phone") else tr.text("selected_files"), selectedFiles.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(if (showAdvanced) "${tr.text("collapse")} ︿" else "${tr.text("expand")} ﹀", color = MaterialTheme.colorScheme.primary)
                    }

                    AnimatedVisibility(visible = showAdvanced) {
                        Column(
                            modifier = Modifier.padding(top = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
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
                                shape = RoundedCornerShape(13.dp)
                            ) {
                                Text(if (isServerActive) tr.text("replace_restart") else tr.text("start_current"))
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
private fun SettingsScreen(
    initialSettings: SpeedShareSettings,
    onBack: () -> Unit,
    onSaved: (SpeedShareSettings) -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var draft by remember(initialSettings) { mutableStateOf(initialSettings) }
    val tr = remember(draft.language, configuration) {
        Localization.translator(context, draft.language)
    }
    var portText by remember(initialSettings) { mutableStateOf(initialSettings.preferredPort.toString()) }
    var statusText by remember { mutableStateOf("") }

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(tr.text("settings"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                        Text(tr.text("settings_subtitle"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                        Text(tr.text("back"))
                    }
                }

                SettingsSection(tr.text("language")) {
                    Text(tr.text("language_sub"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            AppLanguage.SYSTEM to tr.text("language_system"),
                            AppLanguage.SIMPLIFIED_CHINESE to tr.text("language_chinese"),
                            AppLanguage.JAPANESE to tr.text("language_japanese"),
                            AppLanguage.ENGLISH to tr.text("language_english")
                        ).forEach { (language, label) ->
                            CompactSegment(
                                text = label,
                                selected = draft.language == language,
                                modifier = Modifier.weight(1f)
                            ) { draft = draft.copy(language = language) }
                        }
                    }
                }

                SettingsSection(tr.text("default_behavior")) {
                    Text(tr.text("default_share_mode"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactSegment(
                            text = tr.text("whole_phone"),
                            selected = draft.defaultMode == ShareMode.WHOLE_STORAGE,
                            modifier = Modifier.weight(1f)
                        ) { draft = draft.copy(defaultMode = ShareMode.WHOLE_STORAGE) }
                        CompactSegment(
                            text = tr.text("selected_files"),
                            selected = draft.defaultMode == ShareMode.SELECTED_FILES,
                            modifier = Modifier.weight(1f)
                        ) { draft = draft.copy(defaultMode = ShareMode.SELECTED_FILES) }
                    }
                    CompactSwitchRow(tr.text("allow_upload"), tr.text("allow_upload_sub"), draft.defaultUploadEnabled) {
                        draft = draft.copy(defaultUploadEnabled = it)
                    }
                    CompactSwitchRow(tr.text("remote_management"), tr.text("remote_management_sub"), draft.remoteManagementEnabled) {
                        draft = draft.copy(remoteManagementEnabled = it)
                    }
                    CompactSwitchRow(tr.text("delete_to_trash"), tr.text("delete_to_trash_sub"), draft.deleteToTrashByDefault) {
                        draft = draft.copy(deleteToTrashByDefault = it)
                    }
                    CompactSwitchRow(tr.text("auto_start_share"), tr.text("auto_start_share_sub"), draft.autoStartIncomingShare) {
                        draft = draft.copy(autoStartIncomingShare = it)
                    }
                    CompactSwitchRow(tr.text("lockscreen_protection"), tr.text("lockscreen_protection_sub"), draft.keepAwakeDuringTransfer) {
                        draft = draft.copy(keepAwakeDuringTransfer = it)
                    }
                }

                SettingsSection(tr.text("auto_stop")) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            0 to tr.text("never"),
                            10 to tr.text("minutes_10"),
                            30 to tr.text("minutes_30"),
                            60 to tr.text("hour_1")
                        ).forEach { (minutes, label) ->
                            CompactSegment(
                                text = label,
                                selected = draft.autoStopMinutes == minutes,
                                modifier = Modifier.weight(1f)
                            ) { draft = draft.copy(autoStopMinutes = minutes) }
                        }
                    }
                }

                SettingsSection(tr.text("network_port")) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                        label = { Text(tr.text("preferred_port")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(13.dp)
                    )
                    CompactSwitchRow(tr.text("auto_find_port"), tr.text("auto_find_port_sub"), draft.autoPortFallback) {
                        draft = draft.copy(autoPortFallback = it)
                    }
                    CompactSwitchRow(tr.text("copy_after_start"), tr.text("copy_after_start_sub"), draft.copyAddressAfterStart) {
                        draft = draft.copy(copyAddressAfterStart = it)
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

                Button(
                    onClick = {
                        val port = portText.toIntOrNull()
                        if (port == null || port !in 1024..65535) {
                            statusText = tr.text("port_range_error")
                        } else {
                            onSaved(draft.copy(preferredPort = port))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 13.dp)
                ) { Text(tr.text("save_settings"), fontWeight = FontWeight.Bold) }

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
            primaryContainer = Color(0xFF26327A),
            onPrimaryContainer = Color(0xFFE0E3FF),
            secondary = Color(0xFFC8A8FF),
            secondaryContainer = Color(0xFF3E2762),
            background = Color(0xFF0B1020),
            surface = Color(0xFF12182A),
            surfaceVariant = Color(0xFF1B2236),
            outline = Color(0xFF39435D)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF4057D6),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE1E5FF),
            onPrimaryContainer = Color(0xFF111D63),
            secondary = Color(0xFF7656B8),
            secondaryContainer = Color(0xFFEBDDFF),
            background = Color(0xFFF5F7FF),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEEF1FA),
            outline = Color(0xFFD7DCEA)
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
    val secondaryText = if (running) Color.White.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant

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
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (running) Color(0xFF78F2BA) else if (starting) Color(0xFFFFD166) else Color(0xFF9CA3AF))
                )
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (running) tr.text("running") else if (starting) tr.text("starting") else tr.text("stopped"),
                        color = foreground,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        statusText,
                        color = secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (running) {
                    OutlinedButton(
                        onClick = onStop,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                    ) { Text(tr.text("stop")) }
                }
            }

            if (address != null) {
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

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                MetricPill(tr.text("connections"), connections.toString(), foreground, secondaryText, Modifier.weight(1f))
                MetricPill(tr.text("send"), "${formatBytes(downloadSpeed)}/s", foreground, secondaryText, Modifier.weight(1f))
                MetricPill(tr.text("receive"), "${formatBytes(uploadSpeed)}/s", foreground, secondaryText, Modifier.weight(1f))
                MetricPill(tr.text("tasks"), taskCount.toString(), foreground, secondaryText, Modifier.weight(1f))
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
    foreground: Color,
    secondaryText: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(foreground.copy(alpha = 0.09f))
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Text(label, color = secondaryText, style = MaterialTheme.typography.labelSmall)
        Text(value, color = foreground, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ActionTile(
    symbol: String,
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
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(symbol, color = Color.White, fontWeight = FontWeight.Black)
            }
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
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
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
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.45f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
