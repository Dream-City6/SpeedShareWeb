package com.alex.speedshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import java.net.BindException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class SpeedShareService : Service() {

    private data class StartConfig(
        val mode: ShareMode,
        val uris: List<Uri>,
        val fileNames: List<String>,
        val mimeTypes: List<String>,
        val uploadEnabled: Boolean,
        val remoteManagementEnabled: Boolean,
        val clipboardSyncEnabled: Boolean,
        val deleteToTrashByDefault: Boolean,
        val keepAwakeDuringTransfer: Boolean,
        val autoStopMinutes: Int,
        val preferredPort: Int,
        val autoPortFallback: Boolean,
        val copyAddressAfterStart: Boolean,
        val language: AppLanguage,
        val successPrefix: String
    )

    private val commandExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SpeedShareWeb-ServiceCommands").apply {
            isDaemon = true
        }
    }

    private val commandGeneration = AtomicLong(0L)

    @Volatile
    private var server: SpeedShareServer? = null

    @Volatile
    private var currentConfig: StartConfig? = null

    @Volatile
    private var activeLanguage: AppLanguage = AppLanguage.SYSTEM

    @Volatile
    private var lastMetrics = TransferSnapshot()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastNotificationAt = AtomicLong(0L)
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private var transferWakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var activePort: Int? = null

    @Volatile
    private var lastActivityAtElapsedMs: Long = 0L

    private val idleStopRunnable = Runnable { stopForIdleTimeout() }

    override fun onCreate() {
        super.onCreate()
        activeLanguage = AppSettings.load(this).language
        createNotificationChannel()
        createTransferWakeLock()
        registerNetworkCallback()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_OR_REPLACE -> {
                val config = parseStartConfig(intent)
                if (config == null) {
                    val tr = Localization.translator(this, AppSettings.load(this).language)
                    updateState(
                        ServerUiState(
                            statusText = tr.text("invalid_share_config")
                        )
                    )
                    stopForegroundCompat()
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                activeLanguage = config.language
                val tr = Localization.translator(this, config.language)
                val generation = commandGeneration.incrementAndGet()
                val preparingState = ServerUiState(
                    running = false,
                    starting = true,
                    mode = config.mode,
                    selectedFileCount = config.uris.size,
                    uploadEnabled = config.uploadEnabled && config.mode == ShareMode.WHOLE_STORAGE,
                    remoteManagementEnabled = config.remoteManagementEnabled && config.mode == ShareMode.WHOLE_STORAGE,
                    keepAwakeDuringTransfer = config.keepAwakeDuringTransfer,
                    networkAvailable = buildAddress(config.preferredPort) != null,
                    address = buildAddress(config.preferredPort),
                    port = config.preferredPort,
                    autoStopMinutes = config.autoStopMinutes,
                    statusText = if (server == null) {
                        tr.text("server_starting")
                    } else {
                        tr.text("server_replacing")
                    }
                )

                updateState(preparingState)
                startForegroundCompat(buildNotification(preparingState))

                commandExecutor.execute {
                    replaceServer(
                        generation = generation,
                        config = config,
                        startId = startId
                    )
                }
            }

            ACTION_STOP -> {
                val generation = commandGeneration.incrementAndGet()
                commandExecutor.execute {
                    if (generation != commandGeneration.get()) return@execute
                    stopOwnedServer()
                    updateState(ServerUiState(statusText = Localization.translator(this, activeLanguage).text("server_stopped")))
                    stopForegroundCompat()
                    stopSelf()
                }
            }

            ACTION_COPY_ADDRESS -> {
                val address = SpeedShareRuntime.state.value.address ?: buildAddress(activePort)
                if (address != null) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(Localization.translator(this, activeLanguage).text("address_label"), address)
                    )
                    Toast.makeText(this, Localization.translator(this, activeLanguage).text("address_copied"), Toast.LENGTH_SHORT).show()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun parseStartConfig(intent: Intent): StartConfig? {
        val mode = runCatching {
            ShareMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty())
        }.getOrNull() ?: return null

        val uris = getUriListExtra(intent, EXTRA_URIS)
        if (mode == ShareMode.SELECTED_FILES && uris.isEmpty()) {
            return null
        }

        return StartConfig(
            mode = mode,
            uris = uris,
            fileNames = intent.getStringArrayListExtra(EXTRA_FILE_NAMES).orEmpty(),
            mimeTypes = intent.getStringArrayListExtra(EXTRA_MIME_TYPES).orEmpty(),
            uploadEnabled = intent.getBooleanExtra(EXTRA_UPLOAD_ENABLED, false),
            remoteManagementEnabled = intent.getBooleanExtra(EXTRA_REMOTE_MANAGEMENT, false),
            clipboardSyncEnabled = intent.getBooleanExtra(EXTRA_CLIPBOARD_SYNC, false),
            deleteToTrashByDefault = intent.getBooleanExtra(EXTRA_DELETE_TO_TRASH_DEFAULT, true),
            keepAwakeDuringTransfer = intent.getBooleanExtra(EXTRA_KEEP_AWAKE, true),
            autoStopMinutes = intent.getIntExtra(EXTRA_AUTO_STOP_MINUTES, 0)
                .takeIf { it in setOf(0, 10, 30, 60) } ?: 0,
            preferredPort = intent.getIntExtra(EXTRA_PREFERRED_PORT, 9999)
                .coerceIn(1024, 65535),
            autoPortFallback = intent.getBooleanExtra(EXTRA_AUTO_PORT_FALLBACK, true),
            copyAddressAfterStart = intent.getBooleanExtra(EXTRA_COPY_ADDRESS_AFTER_START, true),
            language = AppLanguage.fromStored(intent.getStringExtra(EXTRA_LANGUAGE)),
            successPrefix = intent.getStringExtra(EXTRA_SUCCESS_PREFIX)
                ?: Localization.translator(this, AppLanguage.fromStored(intent.getStringExtra(EXTRA_LANGUAGE))).text("server_started")
        )
    }

    private fun replaceServer(
        generation: Long,
        config: StartConfig,
        startId: Int
    ) {
        if (generation != commandGeneration.get()) return

        activeLanguage = config.language
        val tr = Localization.translator(this, config.language)
        stopOwnedServer()

        if (generation != commandGeneration.get()) return

        val selectedFiles = if (config.mode == ShareMode.SELECTED_FILES) {
            config.uris.mapIndexedNotNull { index, uri ->
                querySharedFile(
                    context = applicationContext,
                    uri = uri,
                    mimeTypeHint = config.mimeTypes.getOrNull(index)
                )?.let { queried ->
                    queried.copy(
                        name = config.fileNames.getOrNull(index)
                            ?.takeIf { it.isNotBlank() }
                            ?: queried.name,
                        mimeType = config.mimeTypes.getOrNull(index)
                            ?.takeIf { it.isNotBlank() && it != "*/*" }
                            ?: queried.mimeType
                    )
                }
            }
        } else {
            emptyList()
        }

        if (config.mode == ShareMode.SELECTED_FILES && selectedFiles.isEmpty()) {
            failStart(
                message = tr.text("cannot_read_share"),
                startId = startId,
                generation = generation
            )
            return
        }

        val startedAt = SystemClock.elapsedRealtime()
        var lastError: Throwable? = null
        var startedServer: SpeedShareServer? = null
        var selectedPort: Int? = null

        val candidatePorts = buildList {
            add(config.preferredPort)
            if (config.autoPortFallback) {
                for (offset in 1..AUTO_PORT_FALLBACK_COUNT) {
                    val candidate = config.preferredPort + offset
                    if (candidate <= 65535) add(candidate)
                }
            }
        }

        portLoop@ for (candidatePort in candidatePorts) {
            val retries = if (candidatePort == config.preferredPort) PORT_BIND_RETRY_COUNT else 1
            for (attempt in 1..retries) {
                if (generation != commandGeneration.get()) return

                val candidateServer = SpeedShareServer(
                    context = applicationContext,
                    mode = config.mode,
                    selectedFiles = selectedFiles,
                    rootDirectory = Environment.getExternalStorageDirectory(),
                    uploadEnabled = config.uploadEnabled && config.mode == ShareMode.WHOLE_STORAGE,
                    remoteManagementEnabled = config.remoteManagementEnabled && config.mode == ShareMode.WHOLE_STORAGE,
                    clipboardSyncEnabled = config.clipboardSyncEnabled,
                    deleteToTrashByDefault = config.deleteToTrashByDefault,
                    language = Localization.resolve(this, config.language),
                    port = candidatePort,
                    onMetrics = ::handleMetrics
                )

                try {
                    candidateServer.start()
                    startedServer = candidateServer
                    selectedPort = candidatePort
                    break@portLoop
                } catch (error: Throwable) {
                    candidateServer.stop()
                    lastError = error
                    val isAddressBusy = error is BindException ||
                        error.cause is BindException ||
                        error.message?.contains("Address already in use", ignoreCase = true) == true

                    if (!isAddressBusy) break@portLoop
                    if (attempt < retries) Thread.sleep(PORT_BIND_RETRY_DELAY_MS)
                }
            }
        }

        if (startedServer == null || selectedPort == null) {
            failStart(
                message = tr.text("start_failed", lastError?.message ?: lastError?.javaClass?.simpleName ?: tr.text("unknown_error")),
                startId = startId,
                generation = generation
            )
            return
        }

        if (generation != commandGeneration.get()) {
            startedServer.stop()
            return
        }

        server = startedServer
        activePort = selectedPort
        currentConfig = config
        lastActivityAtElapsedMs = SystemClock.elapsedRealtime()
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val address = buildAddress(selectedPort)
        val state = ServerUiState(
            running = true,
            starting = false,
            mode = config.mode,
            selectedFileCount = selectedFiles.size,
            uploadEnabled = config.uploadEnabled && config.mode == ShareMode.WHOLE_STORAGE,
            remoteManagementEnabled = config.remoteManagementEnabled && config.mode == ShareMode.WHOLE_STORAGE,
            keepAwakeDuringTransfer = config.keepAwakeDuringTransfer,
            networkAvailable = address != null,
            address = address,
            port = selectedPort,
            autoStopMinutes = config.autoStopMinutes,
            idleStopAtMs = calculateIdleStopAtEpochMs(config.autoStopMinutes),
            statusText = if (address != null) {
                val portMessage = if (selectedPort == config.preferredPort) {
                    tr.text("port_only", selectedPort)
                } else {
                    tr.text("port_fallback", config.preferredPort, selectedPort)
                }
                tr.text("server_started_detail", config.successPrefix, portMessage, elapsed)
            } else {
                tr.text("waiting_lan")
            },
            activeConnections = lastMetrics.activeConnections,
            downloadBytesPerSecond = lastMetrics.downloadBytesPerSecond,
            uploadBytesPerSecond = lastMetrics.uploadBytesPerSecond,
            totalDownloadedBytes = lastMetrics.totalDownloadedBytes,
            totalUploadedBytes = lastMetrics.totalUploadedBytes,
            activeTransfers = lastMetrics.activeTransfers
        )

        updateState(state)
        notifyState(state)
        if (config.copyAddressAfterStart && address != null) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(tr.text("address_label"), address))
        }
        scheduleIdleStop()
    }

    private fun failStart(
        message: String,
        startId: Int,
        generation: Long
    ) {
        if (generation != commandGeneration.get()) return
        stopOwnedServer()
        val state = ServerUiState(statusText = message)
        updateState(state)
        stopForegroundCompat()
        stopSelf(startId)
    }

    private fun stopOwnedServer() {
        val oldServer = server
        server = null
        currentConfig = null
        activePort = null
        mainHandler.removeCallbacks(idleStopRunnable)
        oldServer?.stop()
        lastMetrics = TransferSnapshot()
        releaseTransferWakeLock()
    }

    override fun onDestroy() {
        commandGeneration.incrementAndGet()
        stopOwnedServer()
        commandExecutor.shutdownNow()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        mainHandler.removeCallbacksAndMessages(null)
        releaseTransferWakeLock()

        val current = SpeedShareRuntime.state.value
        if (current.running || current.starting) {
            updateState(ServerUiState(statusText = Localization.translator(this, activeLanguage).text("server_stopped")))
        }

        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        commandGeneration.incrementAndGet()
        stopOwnedServer()
        updateState(
            ServerUiState(
                statusText = Localization.translator(this, activeLanguage).text("background_timeout")
            )
        )
        stopForegroundCompat()
        stopSelf(startId)
    }

    private fun handleMetrics(snapshot: TransferSnapshot) {
        val previousMetrics = lastMetrics
        lastMetrics = snapshot
        val current = SpeedShareRuntime.state.value
        if (!current.running && !current.starting) return

        val shouldHoldWakeLock = current.keepAwakeDuringTransfer &&
            snapshot.activeTransfers.isNotEmpty()
        updateTransferWakeLock(shouldHoldWakeLock)

        val hasActivity = snapshot.activeConnections > 0 ||
            snapshot.activeTransfers.isNotEmpty() ||
            snapshot.totalDownloadedBytes > previousMetrics.totalDownloadedBytes ||
            snapshot.totalUploadedBytes > previousMetrics.totalUploadedBytes
        if (hasActivity) {
            lastActivityAtElapsedMs = SystemClock.elapsedRealtime()
        }

        val updated = current.copy(
            wakeLockActive = transferWakeLock?.isHeld == true,
            activeConnections = snapshot.activeConnections,
            downloadBytesPerSecond = snapshot.downloadBytesPerSecond,
            uploadBytesPerSecond = snapshot.uploadBytesPerSecond,
            totalDownloadedBytes = snapshot.totalDownloadedBytes,
            totalUploadedBytes = snapshot.totalUploadedBytes,
            activeTransfers = snapshot.activeTransfers,
            idleStopAtMs = calculateIdleStopAtEpochMs(current.autoStopMinutes)
        )
        updateState(updated)
        scheduleIdleStop()

        val now = SystemClock.elapsedRealtime()
        val previous = lastNotificationAt.get()
        if (now - previous >= 900L && lastNotificationAt.compareAndSet(previous, now)) {
            notifyState(updated)
        }
    }

    private fun createTransferWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java)
        transferWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SpeedShareWeb:ActiveTransfer"
        ).apply {
            setReferenceCounted(false)
        }
    }

    private fun updateTransferWakeLock(shouldHold: Boolean) {
        val wakeLock = transferWakeLock ?: return
        if (shouldHold && !wakeLock.isHeld) {
            runCatching { wakeLock.acquire(10L * 60L * 1000L) }
        } else if (!shouldHold && wakeLock.isHeld) {
            runCatching { wakeLock.release() }
        }
    }

    private fun releaseTransferWakeLock() {
        val wakeLock = transferWakeLock ?: return
        if (wakeLock.isHeld) runCatching { wakeLock.release() }
    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleNetworkRefresh()
            override fun onLost(network: Network) = scheduleNetworkRefresh()
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) = scheduleNetworkRefresh()

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties
            ) = scheduleNetworkRefresh()
        }
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
    }

    private fun scheduleNetworkRefresh() {
        mainHandler.removeCallbacks(networkRefreshRunnable)
        mainHandler.postDelayed(networkRefreshRunnable, 250L)
    }

    private val networkRefreshRunnable = Runnable {
        val current = SpeedShareRuntime.state.value
        if (!current.running && !current.starting) return@Runnable

        val address = buildAddress(activePort)
        val updated = current.copy(
            networkAvailable = address != null,
            address = address,
            statusText = when {
                address == null -> Localization.translator(this, activeLanguage).text("server_running_waiting")
                current.address == null -> Localization.translator(this, activeLanguage).text("network_restored")
                current.address != address -> Localization.translator(this, activeLanguage).text("address_changed")
                else -> current.statusText
            }
        )
        updateState(updated)
        notifyState(updated)
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return "${formatBytes(bytesPerSecond)}/s"
    }

    private fun updateState(state: ServerUiState) {
        SpeedShareRuntime.state.value = state
        SpeedShareTileService.requestRefresh(applicationContext)
    }

    private fun notifyState(state: ServerUiState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildAddress(port: Int? = activePort): String? {
        val actualPort = port ?: return null
        val ip = findLocalIpv4Address() ?: return null
        return "http://$ip:$actualPort/"
    }

    private fun calculateIdleStopAtEpochMs(autoStopMinutes: Int): Long? {
        if (autoStopMinutes <= 0) return null
        val remaining = autoStopMinutes * 60_000L -
            (SystemClock.elapsedRealtime() - lastActivityAtElapsedMs)
        return System.currentTimeMillis() + remaining.coerceAtLeast(0L)
    }

    private fun scheduleIdleStop() {
        mainHandler.removeCallbacks(idleStopRunnable)
        val config = currentConfig ?: return
        if (config.autoStopMinutes <= 0) return

        val snapshot = lastMetrics
        if (snapshot.activeConnections > 0 || snapshot.activeTransfers.isNotEmpty()) return

        val timeoutMs = config.autoStopMinutes * 60_000L
        val elapsed = SystemClock.elapsedRealtime() - lastActivityAtElapsedMs
        val delay = timeoutMs - elapsed
        if (delay <= 0L) {
            mainHandler.post(idleStopRunnable)
        } else {
            mainHandler.postDelayed(idleStopRunnable, delay)
        }
    }

    private fun stopForIdleTimeout() {
        val config = currentConfig ?: return
        if (config.autoStopMinutes <= 0) return
        val snapshot = lastMetrics
        if (snapshot.activeConnections > 0 || snapshot.activeTransfers.isNotEmpty()) {
            lastActivityAtElapsedMs = SystemClock.elapsedRealtime()
            scheduleIdleStop()
            return
        }

        commandGeneration.incrementAndGet()
        stopOwnedServer()
        updateState(ServerUiState(statusText = Localization.translator(this, activeLanguage).text("idle_stopped")))
        stopForegroundCompat()
        stopSelf()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val translator = Localization.translator(this, activeLanguage)

        val channel = NotificationChannel(
            CHANNEL_ID,
            translator.text("notification_channel"),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = translator.text("notification_channel_desc")
            setShowBadge(false)
        }

        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: ServerUiState): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val copyIntent = Intent(this, SpeedShareService::class.java).apply {
            action = ACTION_COPY_ADDRESS
        }
        val copyPendingIntent = PendingIntent.getService(
            this,
            11,
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SpeedShareService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            12,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val tr = Localization.translator(this, activeLanguage)
        val modeText = when (state.mode) {
            ShareMode.SELECTED_FILES -> tr.text("mode_selected_count", state.selectedFileCount)
            ShareMode.WHOLE_STORAGE -> buildString {
                append(tr.text("mode_whole"))
                if (state.uploadEnabled) append(" · ${tr.text("mode_upload")}") else append(" · ${tr.text("mode_readonly")}")
                if (state.remoteManagementEnabled) append(" · ${tr.text("mode_manage")}")
            }
            null -> tr.text("mode_server")
        }

        val speedText = buildString {
            if (state.downloadBytesPerSecond > 0L) {
                append("${tr.text("send")} ${formatSpeed(state.downloadBytesPerSecond)}")
            }
            if (state.uploadBytesPerSecond > 0L) {
                if (isNotEmpty()) append(" · ")
                append("${tr.text("receive")} ${formatSpeed(state.uploadBytesPerSecond)}")
            }
        }

        val contentText = when {
            state.starting -> state.statusText
            state.address == null -> tr.text("notification_waiting", modeText)
            speedText.isNotEmpty() -> tr.text("notification_speed", speedText, state.activeTransfers.size, state.address.orEmpty())
            else -> "$modeText · ${state.address}"
        }

        val titleText = when {
            state.starting -> tr.text("notification_switching")
            speedText.isNotEmpty() -> speedText
            else -> tr.text("notification_running")
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speedshare_tile)
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setStyle(Notification.BigTextStyle().bigText(contentText))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_view, tr.text("open"), openPendingIntent)
            .addAction(android.R.drawable.ic_menu_set_as, tr.text("copy_address"), copyPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, tr.text("stop"), stopPendingIntent)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val CHANNEL_ID = "speedshare_server"
        private const val NOTIFICATION_ID = 9001
        private const val PORT_BIND_RETRY_COUNT = 10
        private const val PORT_BIND_RETRY_DELAY_MS = 50L
        private const val AUTO_PORT_FALLBACK_COUNT = 20

        private const val ACTION_START_OR_REPLACE =
            "com.alex.speedshare.action.START_OR_REPLACE"
        private const val ACTION_STOP =
            "com.alex.speedshare.action.STOP"
        private const val ACTION_COPY_ADDRESS =
            "com.alex.speedshare.action.COPY_ADDRESS"

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_FILE_NAMES = "file_names"
        private const val EXTRA_MIME_TYPES = "mime_types"
        private const val EXTRA_UPLOAD_ENABLED = "upload_enabled"
        private const val EXTRA_REMOTE_MANAGEMENT = "remote_management"
        private const val EXTRA_CLIPBOARD_SYNC = "clipboard_sync"
        private const val EXTRA_DELETE_TO_TRASH_DEFAULT = "delete_to_trash_default"
        private const val EXTRA_KEEP_AWAKE = "keep_awake"
        private const val EXTRA_AUTO_STOP_MINUTES = "auto_stop_minutes"
        private const val EXTRA_PREFERRED_PORT = "preferred_port"
        private const val EXTRA_AUTO_PORT_FALLBACK = "auto_port_fallback"
        private const val EXTRA_COPY_ADDRESS_AFTER_START = "copy_address_after_start"
        private const val EXTRA_SUCCESS_PREFIX = "success_prefix"
        private const val EXTRA_LANGUAGE = "language"

        fun startOrReplace(
            context: Context,
            mode: ShareMode,
            files: List<SharedFile>,
            uploadEnabled: Boolean,
            remoteManagementEnabled: Boolean = false,
            clipboardSyncEnabled: Boolean = false,
            deleteToTrashByDefault: Boolean = true,
            keepAwakeDuringTransfer: Boolean = true,
            autoStopMinutes: Int = 0,
            preferredPort: Int = 9999,
            autoPortFallback: Boolean = true,
            copyAddressAfterStart: Boolean = true,
            language: AppLanguage = AppSettings.load(context).language,
            successPrefix: String = Localization.translator(context, language).text("server_started")
        ) {
            val uris = ArrayList<Uri>(files.map { it.uri })
            val intent = Intent(context, SpeedShareService::class.java).apply {
                action = ACTION_START_OR_REPLACE
                putExtra(EXTRA_MODE, mode.name)
                putParcelableArrayListExtra(EXTRA_URIS, uris)
                putStringArrayListExtra(EXTRA_FILE_NAMES, ArrayList(files.map { it.name }))
                putStringArrayListExtra(EXTRA_MIME_TYPES, ArrayList(files.map { it.mimeType }))
                putExtra(EXTRA_UPLOAD_ENABLED, uploadEnabled)
                putExtra(EXTRA_REMOTE_MANAGEMENT, remoteManagementEnabled)
                putExtra(EXTRA_CLIPBOARD_SYNC, clipboardSyncEnabled)
                putExtra(EXTRA_DELETE_TO_TRASH_DEFAULT, deleteToTrashByDefault)
                putExtra(EXTRA_KEEP_AWAKE, keepAwakeDuringTransfer)
                putExtra(EXTRA_AUTO_STOP_MINUTES, autoStopMinutes)
                putExtra(EXTRA_PREFERRED_PORT, preferredPort.coerceIn(1024, 65535))
                putExtra(EXTRA_AUTO_PORT_FALLBACK, autoPortFallback)
                putExtra(EXTRA_COPY_ADDRESS_AFTER_START, copyAddressAfterStart)
                putExtra(EXTRA_LANGUAGE, language.storedValue)
                putExtra(EXTRA_SUCCESS_PREFIX, successPrefix)

                if (uris.isNotEmpty()) {
                    val data = ClipData.newRawUri("SpeedShareWeb files", uris.first())
                    uris.drop(1).forEach { data.addItem(ClipData.Item(it)) }
                    clipData = data
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            try {
                context.startForegroundService(intent)
            } catch (error: Exception) {
                SpeedShareRuntime.state.value = ServerUiState(
                    statusText = Localization.translator(context, language).text("cannot_start_background", error.message ?: error.javaClass.simpleName)
                )
                SpeedShareTileService.requestRefresh(context.applicationContext)
            }
        }

        fun stopServer(context: Context) {
            val intent = Intent(context, SpeedShareService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                val tr = Localization.translator(context, AppSettings.load(context).language)
                SpeedShareRuntime.state.value = ServerUiState(statusText = tr.text("server_stopped"))
                SpeedShareTileService.requestRefresh(context.applicationContext)
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun getUriListExtra(intent: Intent, key: String): List<Uri> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
    } else {
        intent.getParcelableArrayListExtra<Uri>(key).orEmpty()
    }
}
