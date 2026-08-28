package com.alex.speedshare.migration

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class ResilientMigrationController private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("speedshare_migration_v2", Context.MODE_PRIVATE)
    private val taskStore = MigrationTaskStore(context)
    private val deviceId = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).apply()
    }
    private val deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
    private val appVersion = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private val _state = MutableStateFlow(
        ResilientMigrationState(
            localDeviceName = deviceName,
            pendingTask = taskStore.loadLatestIncomplete(),
            status = "正在搜索同一 Wi‑Fi 下的 SpeedShare 设备…"
        )
    )
    val state: StateFlow<ResilientMigrationState> = _state.asStateFlow()

    @Volatile private var session: MigrationSession? = null
    @Volatile private var transferControl: MigrationTransferControl? = null
    private val lastProgressSyncAt = AtomicLong(0L)

    private val peerServer = ResilientMigrationPeerServer(
        localDeviceId = deviceId,
        localDeviceName = deviceName,
        appVersion = appVersion,
        onPairRequest = { request ->
            update {
                it.copy(
                    incomingPairRequest = request,
                    pairing = true,
                    stage = MigrationStage.PAIRING,
                    status = "${request.peer.name} 请求恢复/建立换机连接"
                )
            }
        },
        onPeerConnected = { peer, sharedToken ->
            session = MigrationSession(peer, sharedToken, sharedToken)
            update {
                it.copy(
                    connectedPeer = peer,
                    incomingPairRequest = null,
                    pairing = false,
                    stage = MigrationStage.SPEED_TEST,
                    status = "已连接 ${peer.name}，可快速测速或直接跳过"
                )
            }
        },
        onRole = { role ->
            update { it.copy(role = role, stage = MigrationStage.SELECTION, status = roleStatus(role)) }
            if (role == MigrationRole.OLD_PHONE) scanContent()
            if (role == MigrationRole.NEW_PHONE) {
                MigrationForegroundService.update(context, MigrationProgress(), "已连接，等待旧手机发送数据")
            }
        },
        onSpeedResult = { result ->
            update {
                it.copy(
                    speedTesting = false,
                    speedResult = result,
                    stage = MigrationStage.SPEED_TEST,
                    status = speedSummary(result)
                )
            }
        },
        onTransferPlan = { migrationId, totalBytes, totalItems ->
            update {
                it.copy(
                    activeMigrationId = migrationId,
                    stage = MigrationStage.TRANSFERRING,
                    progress = MigrationProgress(totalBytes = totalBytes, totalItems = totalItems),
                    report = null,
                    error = null,
                    status = "旧手机已开始迁移，共 $totalItems 项"
                )
            }
            MigrationForegroundService.update(context, _state.value.progress, "正在接收换机数据")
        },
        onProgressSync = { progress ->
            update {
                it.copy(
                    stage = MigrationStage.TRANSFERRING,
                    progress = progress,
                    paused = false,
                    status = if (progress.currentName.isBlank()) "正在接收换机数据" else "正在接收 ${progress.currentName}"
                )
            }
            MigrationForegroundService.update(context, progress, "正在接收 ${progress.currentName}")
        },
        onReport = { report ->
            val totalItems = report.successCount + report.failedCount + report.notMigratedCount
            update {
                it.copy(
                    stage = MigrationStage.COMPLETE,
                    report = report,
                    progress = it.progress.copy(
                        totalBytes = report.totalBytes,
                        transferredBytes = report.transferredBytes,
                        totalItems = totalItems,
                        completedItems = totalItems,
                        failedItems = report.failedCount,
                        currentName = ""
                    ),
                    status = when {
                        report.notMigratedCount > 0 -> "对方已提前结束，${report.notMigratedCount} 项未迁移"
                        report.failedCount == 0 -> "换机完成"
                        else -> "完成，但有 ${report.failedCount} 项失败"
                    }
                )
            }
            MigrationForegroundService.stop(context)
        }
    )

    private val discovery: PeerDiscoveryManager

    init {
        peerServer.start()
        discovery = PeerDiscoveryManager(
            context = context,
            localDeviceId = deviceId,
            localDeviceName = deviceName,
            servicePort = peerServer.port,
            appVersion = appVersion,
            onPeersChanged = { peers ->
                update { current -> current.copy(peers = peers) }
            }
        )
        discovery.start()
    }

    fun connect(peer: MigrationPeer) {
        if (_state.value.pairing) return
        update {
            it.copy(
                pairing = true,
                stage = MigrationStage.PAIRING,
                error = null,
                status = "正在连接 ${peer.name}…"
            )
        }
        scope.launch {
            val connected = establishSession(peer)
            if (connected == null) {
                update {
                    it.copy(
                        pairing = false,
                        stage = MigrationStage.DISCOVERY,
                        status = "连接失败或对方未接受"
                    )
                }
                return@launch
            }
            update {
                it.copy(
                    connectedPeer = connected.peer,
                    pairing = false,
                    stage = MigrationStage.SPEED_TEST,
                    status = "连接成功，可快速测速或直接跳过"
                )
            }
        }
    }

    fun acceptPair() {
        val request = _state.value.incomingPairRequest ?: return
        peerServer.respondPair(request.requestId, true)
    }

    fun rejectPair() {
        val request = _state.value.incomingPairRequest ?: return
        peerServer.respondPair(request.requestId, false)
        update {
            it.copy(
                incomingPairRequest = null,
                pairing = false,
                stage = MigrationStage.DISCOVERY,
                status = "已拒绝连接"
            )
        }
    }

    fun runSpeedTest() {
        val currentSession = session ?: return
        if (_state.value.speedTesting) return
        update {
            it.copy(
                speedTesting = true,
                stage = MigrationStage.SPEED_TEST,
                error = null,
                status = "正在进行快速多流双向测速，约需 2～3 秒…"
            )
        }
        scope.launch {
            try {
                val result = ResilientMigrationClient.testSpeed(currentSession)
                update {
                    it.copy(
                        speedTesting = false,
                        speedResult = result,
                        stage = MigrationStage.SPEED_TEST,
                        status = speedSummary(result)
                    )
                }
                runCatching { ResilientMigrationClient.sendSpeedResult(currentSession, result) }
            } catch (error: Throwable) {
                update {
                    it.copy(
                        speedTesting = false,
                        error = error.message,
                        status = "测速失败，可重新测试或直接跳过"
                    )
                }
            }
        }
    }

    fun skipSpeedTest() {
        if (_state.value.speedTesting) return
        update {
            it.copy(
                stage = MigrationStage.ROLE,
                speedResult = null,
                error = null,
                status = "已跳过测速，请选择这台手机的角色"
            )
        }
    }

    fun confirmNetwork() {
        val result = _state.value.speedResult ?: return
        update {
            it.copy(
                stage = MigrationStage.ROLE,
                status = "已选择当前 Wi‑Fi · 多流平均 ${formatRate(result.averageBytesPerSecond)}"
            )
        }
    }

    fun setRole(role: MigrationRole) {
        if (role == MigrationRole.UNSET) return
        val currentSession = session ?: return
        val remoteRole = if (role == MigrationRole.OLD_PHONE) {
            MigrationRole.NEW_PHONE
        } else {
            MigrationRole.OLD_PHONE
        }
        update { it.copy(role = role, stage = MigrationStage.SELECTION, status = roleStatus(role)) }
        scope.launch { runCatching { ResilientMigrationClient.sendRole(currentSession, remoteRole) } }
        if (role == MigrationRole.OLD_PHONE) scanContent()
        if (role == MigrationRole.NEW_PHONE) {
            MigrationForegroundService.update(context, MigrationProgress(), "新手机已准备，等待旧手机")
        }
    }

    fun scanContent() {
        if (_state.value.scanning) return
        update {
            it.copy(
                scanning = true,
                stage = MigrationStage.SELECTION,
                status = "正在扫描照片、视频、文档和应用…"
            )
        }
        scope.launch {
            try {
                val result = MigrationScannerV2.scan(context)
                update {
                    it.copy(
                        scanning = false,
                        scanResult = result,
                        status = "扫描完成，可选择要迁移的内容"
                    )
                }
                refreshReceiverStorage()
            } catch (error: Throwable) {
                update {
                    it.copy(
                        scanning = false,
                        error = error.message,
                        status = "扫描失败，请检查存储权限"
                    )
                }
            }
        }
    }

    fun toggleCategory(category: MigrationCategory) {
        update { current ->
            val next = current.selectedCategories.toMutableSet()
            if (!next.add(category)) next.remove(category)
            current.copy(selectedCategories = next)
        }
    }

    fun refreshReceiverStorage() {
        val currentSession = session ?: return
        scope.launch {
            runCatching { ResilientMigrationClient.storageInfo(currentSession) }
                .onSuccess { storage -> update { it.copy(receiverStorage = storage) } }
        }
    }

    fun startTransfer() {
        val current = _state.value
        val currentSession = session ?: return
        if (current.role != MigrationRole.OLD_PHONE || current.scanning) return

        val summary = MigrationSelectionCalculator.effectiveItems(
            current.scanResult,
            current.selectedCategories
        )
        if (summary.items.isEmpty()) {
            update { it.copy(status = "没有选择可迁移内容") }
            return
        }

        val free = current.receiverStorage?.freeBytes
        if (free != null && summary.totalBytes + STORAGE_RESERVE_BYTES > free) {
            update {
                it.copy(
                    error = "新手机空间不足：需要约 ${formatBytes(summary.totalBytes)}，可用 ${formatBytes(free)}",
                    status = "无法开始换机"
                )
            }
            return
        }

        val task = taskStore.create(currentSession.peer, summary.items, current.selectedCategories)
        update {
            it.copy(
                pendingTask = task,
                activeMigrationId = task.migrationId,
                error = null
            )
        }
        scope.launch { runTask(task, currentSession) }
    }

    fun resumePendingTask() {
        val task = _state.value.pendingTask ?: taskStore.loadLatestIncomplete() ?: return
        scope.launch {
            update {
                it.copy(
                    reconnecting = true,
                    error = null,
                    status = "正在寻找 ${task.peerName} 并恢复未完成换机…"
                )
            }
            val restoredSession = reconnectToDevice(task.peerDeviceId, 90_000L)
            if (restoredSession == null) {
                update {
                    it.copy(
                        reconnecting = false,
                        error = "暂时找不到原来的新手机",
                        status = "未完成任务已保留，可稍后继续"
                    )
                }
                return@launch
            }
            update {
                it.copy(
                    connectedPeer = restoredSession.peer,
                    role = MigrationRole.OLD_PHONE,
                    selectedCategories = task.selectedCategories,
                    activeMigrationId = task.migrationId,
                    reconnecting = false,
                    stage = MigrationStage.TRANSFERRING,
                    status = "已重新连接，继续未完成换机"
                )
            }
            runTask(task, restoredSession)
        }
    }

    fun discardPendingTask() {
        val task = _state.value.pendingTask ?: return
        taskStore.discard(task.migrationId)
        update { it.copy(pendingTask = null, status = "已删除未完成任务记录") }
    }

    fun pauseTransfer() {
        transferControl?.pause()
        update { it.copy(paused = true, status = "换机已暂停，连接和进度会保留") }
        MigrationForegroundService.update(context, _state.value.progress, "换机已暂停")
    }

    fun resumeTransfer() {
        transferControl?.resume()
        update { it.copy(paused = false, status = "正在继续换机…") }
    }

    fun finishEarlyTransfer() {
        transferControl?.finishEarly()
        update {
            it.copy(
                paused = false,
                status = "正在提前结束；已完成内容会保留，剩余内容不再迁移"
            )
        }
        MigrationForegroundService.update(context, _state.value.progress, "正在提前结束换机")
    }

    fun cancelTransfer() {
        transferControl?.cancel()
        update { it.copy(paused = false, status = "正在停止；已完成进度会保留") }
    }

    fun reset() {
        if (transferControl != null && _state.value.report == null) transferControl?.cancel()
        MigrationForegroundService.stop(context)
        session = null
        peerServer.clearSessions()
        transferControl = null
        update {
            ResilientMigrationState(
                localDeviceName = deviceName,
                peers = it.peers,
                pendingTask = taskStore.loadLatestIncomplete(),
                status = "正在搜索同一 Wi‑Fi 下的 SpeedShare 设备…"
            )
        }
    }

    private suspend fun runTask(task: PendingMigrationTask, initialSession: MigrationSession) {
        var activeSession = initialSession
        MigrationDuplicatePolicyRegistry.set(task.duplicatePolicy)
        val pending = task.pendingItems
        if (pending.isEmpty()) {
            taskStore.markComplete(task.migrationId)
            update {
                it.copy(
                    pendingTask = null,
                    stage = MigrationStage.COMPLETE,
                    status = "换机已经完成"
                )
            }
            return
        }

        val control = MigrationTransferControl()
        transferControl = control
        val concurrency = recommendedConcurrency(_state.value.speedResult)
        val totalBytes = task.totalBytes
        val totalItems = task.totalItems
        val initialCompletedBytes = task.completedBytes
        val initialCompletedItems = task.completedPaths.size
        update {
            it.copy(
                stage = MigrationStage.TRANSFERRING,
                progress = MigrationProgress(
                    totalBytes = totalBytes,
                    transferredBytes = initialCompletedBytes,
                    totalItems = totalItems,
                    completedItems = initialCompletedItems
                ),
                paused = false,
                reconnecting = false,
                error = null,
                status = if (initialCompletedItems > 0) {
                    "继续换机：已完成 $initialCompletedItems / $totalItems 项"
                } else {
                    "开始换机，共 $totalItems 项 · $concurrency 路并发"
                }
            )
        }
        MigrationForegroundService.update(context, _state.value.progress, "正在准备换机")

        try {
            ResilientMigrationClient.sendTransferPlan(
                activeSession,
                task.migrationId,
                pending.sumOf { it.size },
                pending.size
            )
        } catch (error: Throwable) {
            transferControl = null
            MigrationForegroundService.stop(context)
            update {
                it.copy(
                    stage = MigrationStage.SELECTION,
                    error = friendlyTransferError(error),
                    status = "新手机尚未准备好"
                )
            }
            return
        }

        val manager = ResilientMigrationTransferManager(taskStore)
        var totalDuration = 0L
        var lastFailed = pending
        var attempt = 0
        while (
            lastFailed.isNotEmpty() &&
            attempt < MAX_TRANSFER_ATTEMPTS &&
            !control.isCancelled() &&
            !control.isFinishingEarly()
        ) {
            val latestTask = taskStore.loadLatestIncomplete()?.takeIf { it.migrationId == task.migrationId }
            val completedBytes = latestTask?.completedBytes ?: (totalBytes - lastFailed.sumOf { it.size })
            val completedItems = latestTask?.completedPaths?.size ?: (totalItems - lastFailed.size)
            val result = manager.transfer(
                session = activeSession,
                migrationId = task.migrationId,
                items = lastFailed,
                totalBytes = totalBytes,
                totalItems = totalItems,
                alreadyCompletedBytes = completedBytes,
                alreadyCompletedItems = completedItems,
                concurrency = concurrency,
                control = control,
                onProgress = { progress -> onSenderProgress(activeSession, progress) }
            )
            totalDuration += result.report.durationMs
            lastFailed = result.failedItems
            if (result.finishedEarly || control.isFinishingEarly()) break
            if (control.isCancelled() || lastFailed.isEmpty()) break

            attempt++
            if (attempt >= MAX_TRANSFER_ATTEMPTS) break
            update {
                it.copy(
                    reconnecting = true,
                    status = "${lastFailed.size} 项中断，正在自动寻找 ${task.peerName} 并重连…"
                )
            }
            MigrationForegroundService.update(context, _state.value.progress, "网络中断，正在自动重连")
            val reconnected = reconnectToDevice(task.peerDeviceId, RECONNECT_WINDOW_MS)
            if (reconnected == null) break
            activeSession = reconnected
            session = reconnected
            runCatching {
                ResilientMigrationClient.sendTransferPlan(
                    activeSession,
                    task.migrationId,
                    lastFailed.sumOf { it.size },
                    lastFailed.size
                )
            }
            update {
                it.copy(
                    connectedPeer = activeSession.peer,
                    reconnecting = false,
                    status = "已重新连接，继续剩余 ${lastFailed.size} 项"
                )
            }
        }

        transferControl = null
        if (control.isCancelled()) {
            MigrationForegroundService.stop(context)
            update {
                it.copy(
                    stage = MigrationStage.SELECTION,
                    paused = false,
                    reconnecting = false,
                    pendingTask = taskStore.loadLatestIncomplete(),
                    status = "换机已停止，未完成进度已保存"
                )
            }
            return
        }

        if (control.isFinishingEarly()) {
            val latest = taskStore.loadLatestIncomplete()?.takeIf { it.migrationId == task.migrationId }
            val completedBytes = latest?.completedBytes ?: _state.value.progress.transferredBytes
            val omittedCount = latest?.pendingItems?.size ?: (totalItems - _state.value.progress.completedItems).coerceAtLeast(0)
            val successCount = (totalItems - omittedCount).coerceAtLeast(0)
            val duration = totalDuration.coerceAtLeast(1L)
            val report = MigrationReport(
                totalBytes = totalBytes,
                transferredBytes = completedBytes.coerceIn(0L, totalBytes),
                successCount = successCount,
                skippedCount = _state.value.progress.skippedItems,
                failedCount = 0,
                durationMs = duration,
                averageBytesPerSecond = completedBytes.coerceAtLeast(0L) * 1000L / duration,
                notMigratedCount = omittedCount
            )
            taskStore.markComplete(task.migrationId)
            update {
                it.copy(
                    stage = MigrationStage.COMPLETE,
                    report = report,
                    progress = it.progress.copy(
                        totalBytes = totalBytes,
                        transferredBytes = report.transferredBytes,
                        totalItems = totalItems,
                        completedItems = totalItems,
                        failedItems = 0,
                        currentName = ""
                    ),
                    paused = false,
                    reconnecting = false,
                    pendingTask = null,
                    status = "已提前结束：保留已完成内容，$omittedCount 项未迁移"
                )
            }
            MigrationForegroundService.stop(context)
            runCatching { ResilientMigrationClient.sendReport(activeSession, report) }
            return
        }

        val finalTask = taskStore.loadLatestIncomplete()?.takeIf { it.migrationId == task.migrationId }
        val finalFailed = finalTask?.pendingItems ?: emptyList()
        val failedBytes = finalFailed.sumOf { it.size }
        val completedBytes = (totalBytes - failedBytes).coerceAtLeast(0L)
        val report = MigrationReport(
            totalBytes = totalBytes,
            transferredBytes = completedBytes,
            successCount = totalItems - finalFailed.size,
            skippedCount = _state.value.progress.skippedItems,
            failedCount = finalFailed.size,
            durationMs = totalDuration.coerceAtLeast(1L),
            averageBytesPerSecond = completedBytes * 1000L / totalDuration.coerceAtLeast(1L),
            notMigratedCount = 0
        )
        if (finalFailed.isEmpty()) taskStore.markComplete(task.migrationId)
        update {
            it.copy(
                stage = MigrationStage.COMPLETE,
                report = report,
                progress = it.progress.copy(
                    totalBytes = totalBytes,
                    transferredBytes = completedBytes,
                    totalItems = totalItems,
                    completedItems = totalItems - finalFailed.size,
                    failedItems = finalFailed.size,
                    currentName = ""
                ),
                reconnecting = false,
                pendingTask = taskStore.loadLatestIncomplete(),
                status = if (finalFailed.isEmpty()) {
                    "换机完成"
                } else {
                    "已保存进度，仍有 ${finalFailed.size} 项未完成"
                }
            )
        }
        MigrationForegroundService.stop(context)
        runCatching { ResilientMigrationClient.sendReport(activeSession, report) }
    }

    private fun onSenderProgress(activeSession: MigrationSession, progress: MigrationProgress) {
        update {
            it.copy(
                progress = progress,
                stage = MigrationStage.TRANSFERRING,
                status = transferStatus(progress)
            )
        }
        MigrationForegroundService.update(context, progress, transferStatus(progress))
        val now = System.currentTimeMillis()
        val previous = lastProgressSyncAt.get()
        if (now - previous >= 800L && lastProgressSyncAt.compareAndSet(previous, now)) {
            scope.launch {
                runCatching { ResilientMigrationClient.sendProgress(activeSession, progress) }
            }
        }
    }

    private suspend fun reconnectToDevice(deviceId: String, timeoutMs: Long): MigrationSession? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val reusableToken = session?.outboundToken?.takeIf { it.isNotBlank() }
        while (System.currentTimeMillis() < deadline) {
            val peer = _state.value.peers.firstOrNull { it.deviceId == deviceId }
            if (peer != null) {
                if (reusableToken != null) {
                    val reused = MigrationSession(peer, reusableToken, reusableToken)
                    val stillTrusted = runCatching {
                        ResilientMigrationClient.storageInfo(reused)
                        true
                    }.getOrDefault(false)
                    if (stillTrusted) {
                        session = reused
                        return reused
                    }
                }
                val restored = establishSession(peer)
                if (restored != null) return restored
            }
            delay(2_000L)
        }
        return null
    }

    private fun establishSession(peer: MigrationPeer): MigrationSession? {
        val sharedToken = ResilientMigrationClient.newInboundToken()
        return try {
            val result = ResilientMigrationClient.requestPair(localPeer(), peer, sharedToken)
            if (!result.accepted) return null
            peerServer.acceptInboundToken(sharedToken)
            MigrationSession(result.peer, sharedToken, sharedToken).also { session = it }
        } catch (_: Throwable) {
            null
        }
    }

    private fun localPeer() = MigrationPeer(
        deviceId = deviceId,
        name = deviceName,
        host = "",
        port = peerServer.port,
        model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        appVersion = appVersion,
        androidSdk = Build.VERSION.SDK_INT,
        supportedAbis = Build.SUPPORTED_ABIS.toList()
    )

    private fun recommendedConcurrency(result: SpeedTestResult?): Int {
        if (result == null) return 6
        val speed = result.averageBytesPerSecond
        return when {
            speed < 10L * 1024L * 1024L -> 2
            speed < 35L * 1024L * 1024L -> 4
            speed < 70L * 1024L * 1024L -> 6
            else -> 8
        }
    }

    private fun update(block: (ResilientMigrationState) -> ResilientMigrationState) {
        synchronized(_state) {
            _state.value = block(_state.value)
        }
    }

    private fun roleStatus(role: MigrationRole) = when (role) {
        MigrationRole.OLD_PHONE -> "这台是旧手机，正在准备可迁移内容"
        MigrationRole.NEW_PHONE -> "这台是新手机，等待旧手机选择并发送内容"
        MigrationRole.UNSET -> "请选择这台手机的角色"
    }

    private fun speedSummary(result: SpeedTestResult): String {
        val single = if (result.singleStreamBytesPerSecond > 0L) {
            "，单流 ${formatRate(result.singleStreamBytesPerSecond)}"
        } else {
            ""
        }
        return "${result.streamCount} 路测速：发送 ${formatRate(result.uploadBytesPerSecond)}，接收 ${formatRate(result.downloadBytesPerSecond)}$single，延迟 ${result.latencyMs} ms"
    }

    private fun transferStatus(progress: MigrationProgress) = when {
        _state.value.paused -> "换机已暂停"
        progress.currentName.isBlank() -> "正在迁移"
        else -> "正在迁移 ${progress.currentName} · ${formatRate(progress.bytesPerSecond)}"
    }

    private fun friendlyTransferError(error: Throwable): String = when {
        error.message?.contains("insufficient_space") == true -> "新手机剩余空间不足"
        error.message?.contains("receiver_storage_permission_required") == true -> "新手机还没有授予全部文件访问权限"
        error.message?.contains("session_required") == true -> "配对会话已失效，请重新连接"
        else -> error.message ?: "连接失败"
    }

    companion object {
        private const val MAX_TRANSFER_ATTEMPTS = 4
        private const val RECONNECT_WINDOW_MS = 90_000L
        private const val STORAGE_RESERVE_BYTES = 256L * 1024L * 1024L
        @Volatile private var instance: ResilientMigrationController? = null

        fun get(context: Context): ResilientMigrationController = instance ?: synchronized(this) {
            instance ?: ResilientMigrationController(context.applicationContext).also { instance = it }
        }

        fun release() {
            synchronized(this) {
                instance?.let {
                    it.transferControl?.cancel()
                    it.discovery.stop()
                    it.peerServer.stop()
                }
                instance = null
            }
        }

        private fun formatRate(bytes: Long): String = when {
            bytes >= 1024L * 1024L -> String.format("%.1f MB/s", bytes / 1024.0 / 1024.0)
            bytes >= 1024L -> String.format("%.0f KB/s", bytes / 1024.0)
            else -> "$bytes B/s"
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
            else -> String.format("%.0f KB", bytes / 1024.0)
        }
    }
}
