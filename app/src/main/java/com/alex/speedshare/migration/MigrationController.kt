package com.alex.speedshare.migration

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class MigrationController private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("speedshare_migration", Context.MODE_PRIVATE)
    private val deviceId = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).apply()
    }
    private val deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
    private val appVersion = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private val _state = MutableStateFlow(MigrationUiState(localDeviceName = deviceName, status = "正在搜索同一 Wi‑Fi 下的 SpeedShare 设备…"))
    val state: StateFlow<MigrationUiState> = _state.asStateFlow()

    private val peerServer = MigrationPeerServer(
        context = context,
        localDeviceId = deviceId,
        localDeviceName = deviceName,
        appVersion = appVersion,
        onPairRequest = { request ->
            update { it.copy(incomingPairRequest = request, stage = MigrationStage.PAIRING, status = "${request.peer.name} 请求连接") }
        },
        onPeerConnected = { peer ->
            update { it.copy(connectedPeer = peer, incomingPairRequest = null, pairing = false, stage = MigrationStage.SPEED_TEST, status = "已连接 ${peer.name}，等待测速") }
        },
        onRole = { role ->
            update { it.copy(role = role, stage = MigrationStage.SELECTION, status = roleStatus(role)) }
            when (role) {
                MigrationRole.OLD_PHONE -> scanContent()
                MigrationRole.NEW_PHONE -> MigrationForegroundService.update(context, MigrationProgress(), "已连接，等待旧手机发送数据")
                MigrationRole.UNSET -> Unit
            }
        },
        onSpeedResult = { result ->
            update { it.copy(speedTesting = false, speedResult = result, stage = MigrationStage.ROLE, status = speedSummary(result)) }
        },
        onReceiveBytes = { bytes, name ->
            update { current ->
                val p = current.progress
                current.copy(
                    stage = MigrationStage.TRANSFERRING,
                    progress = p.copy(
                        transferredBytes = p.transferredBytes + bytes,
                        currentName = name
                    ),
                    status = "正在接收 $name"
                )
            }
            MigrationForegroundService.update(context, _state.value.progress, "正在接收 $name")
        },
        onReport = { report ->
            update {
                it.copy(
                    stage = MigrationStage.COMPLETE,
                    report = report,
                    progress = it.progress.copy(
                        totalBytes = report.totalBytes,
                        transferredBytes = report.transferredBytes,
                        totalItems = report.successCount + report.failedCount,
                        completedItems = report.successCount + report.failedCount,
                        skippedItems = report.skippedCount,
                        failedItems = report.failedCount
                    ),
                    status = if (report.failedCount == 0) "换机完成" else "换机完成，但有 ${report.failedCount} 项失败"
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
            onPeersChanged = { peers -> update { it.copy(peers = peers, discovering = true) } }
        )
        discovery.start()
    }

    fun connect(peer: MigrationPeer) {
        if (_state.value.pairing) return
        update { it.copy(pairing = true, stage = MigrationStage.PAIRING, error = null, status = "正在连接 ${peer.name}…") }
        scope.launch {
            try {
                val accepted = MigrationClient.requestPair(localPeer(), peer)
                if (!accepted) {
                    update { it.copy(pairing = false, stage = MigrationStage.DISCOVERY, status = "对方未接受连接") }
                    return@launch
                }
                update { it.copy(connectedPeer = peer, pairing = false, stage = MigrationStage.SPEED_TEST, status = "连接成功，正在测试 Wi‑Fi 实际速度…") }
                runSpeedTest()
            } catch (error: Throwable) {
                update { it.copy(pairing = false, stage = MigrationStage.DISCOVERY, error = error.message, status = "连接失败") }
            }
        }
    }

    fun acceptPair() {
        val request = _state.value.incomingPairRequest ?: return
        peerServer.respondPair(request.requestId, true)
        update { it.copy(connectedPeer = request.peer, incomingPairRequest = null, pairing = false, stage = MigrationStage.SPEED_TEST, status = "已连接 ${request.peer.name}，等待对方测速") }
    }

    fun rejectPair() {
        val request = _state.value.incomingPairRequest ?: return
        peerServer.respondPair(request.requestId, false)
        update { it.copy(incomingPairRequest = null, pairing = false, stage = MigrationStage.DISCOVERY, status = "已拒绝连接") }
    }

    fun runSpeedTest() {
        val peer = _state.value.connectedPeer ?: return
        if (_state.value.speedTesting) return
        update { it.copy(speedTesting = true, stage = MigrationStage.SPEED_TEST, error = null, status = "正在双向测速…") }
        scope.launch {
            try {
                val result = MigrationClient.testSpeed(peer)
                update { it.copy(speedTesting = false, speedResult = result, stage = MigrationStage.SPEED_TEST, status = speedSummary(result)) }
                runCatching { MigrationClient.sendSpeedResult(peer, result) }
            } catch (error: Throwable) {
                update { it.copy(speedTesting = false, error = error.message, status = "测速失败，可重新测试") }
            }
        }
    }

    fun confirmNetwork() {
        val result = _state.value.speedResult ?: return
        update { it.copy(stage = MigrationStage.ROLE, status = "已选择当前 Wi‑Fi · 平均 ${formatRate(result.averageBytesPerSecond)}") }
    }

    fun setRole(role: MigrationRole) {
        if (role == MigrationRole.UNSET) return
        val peer = _state.value.connectedPeer ?: return
        val remoteRole = if (role == MigrationRole.OLD_PHONE) MigrationRole.NEW_PHONE else MigrationRole.OLD_PHONE
        update { it.copy(role = role, stage = MigrationStage.SELECTION, status = roleStatus(role)) }
        scope.launch { runCatching { MigrationClient.sendRole(peer, remoteRole) } }
        when (role) {
            MigrationRole.OLD_PHONE -> scanContent()
            MigrationRole.NEW_PHONE -> MigrationForegroundService.update(context, MigrationProgress(), "已连接，等待旧手机发送数据")
            MigrationRole.UNSET -> Unit
        }
    }

    fun scanContent() {
        if (_state.value.scanning) return
        update { it.copy(scanning = true, stage = MigrationStage.SELECTION, status = "正在扫描照片、视频、文档和应用…") }
        scope.launch {
            try {
                val result = MigrationScanner.scan(context)
                update { it.copy(scanning = false, scanResult = result, status = "扫描完成，可选择要迁移的内容") }
            } catch (error: Throwable) {
                update { it.copy(scanning = false, error = error.message, status = "扫描失败，请确认已授予全部文件访问权限") }
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

    fun startTransfer() {
        val current = _state.value
        val peer = current.connectedPeer ?: return
        if (current.role != MigrationRole.OLD_PHONE || current.scanning) return
        val selected = current.selectedCategories
        val normalItems = current.scanResult.files.filter { it.category in selected }
        val appItems = if (MigrationCategory.APPS in selected) {
            MigrationScanner.appTransferItems(current.scanResult.apps)
        } else emptyList()
        val items = normalItems + appItems
        if (items.isEmpty()) {
            update { it.copy(status = "没有选择可迁移内容") }
            return
        }
        val concurrency = recommendedConcurrency(current.speedResult)
        update {
            it.copy(
                stage = MigrationStage.TRANSFERRING,
                progress = MigrationProgress(totalBytes = items.sumOf { item -> item.size }, totalItems = items.size),
                report = null,
                error = null,
                status = "开始迁移，共 ${items.size} 项；并发 $concurrency 路"
            )
        }
        MigrationForegroundService.update(context, _state.value.progress, "正在准备迁移 ${items.size} 项")
        scope.launch {
            val manager = ReliableMigrationTransferManager()
            val first = manager.transfer(peer, items, concurrency) { progress ->
                val status = transferStatus(progress)
                update { it.copy(progress = progress, stage = MigrationStage.TRANSFERRING, status = status) }
                MigrationForegroundService.update(context, progress, status)
            }

            val retry = if (first.failedItems.isNotEmpty()) {
                update { it.copy(status = "检测到 ${first.failedItems.size} 项中断，正在自动重连并继续…") }
                manager.transfer(peer, first.failedItems, concurrency) { progress ->
                    val status = if (progress.currentName.isBlank()) {
                        "正在自动重试中断项目"
                    } else {
                        "自动重试 ${progress.currentName} · ${formatRate(progress.bytesPerSecond)}"
                    }
                    update { it.copy(progress = progress, stage = MigrationStage.TRANSFERRING, status = status) }
                    MigrationForegroundService.update(context, progress, status)
                }
            } else null

            val report = manager.combine(items, first, retry)
            update {
                it.copy(
                    stage = MigrationStage.COMPLETE,
                    report = report,
                    progress = MigrationProgress(
                        totalBytes = report.totalBytes,
                        transferredBytes = report.transferredBytes,
                        totalItems = report.successCount + report.failedCount,
                        completedItems = report.successCount + report.failedCount,
                        skippedItems = report.skippedCount,
                        failedItems = report.failedCount
                    ),
                    status = if (report.failedCount == 0) "换机完成" else "完成，但仍有 ${report.failedCount} 项失败"
                )
            }
            MigrationForegroundService.stop(context)
            runCatching { MigrationClient.sendReport(peer, report) }
        }
    }

    fun reset() {
        MigrationForegroundService.stop(context)
        update {
            MigrationUiState(
                localDeviceName = deviceName,
                peers = it.peers,
                discovering = true,
                status = "正在搜索同一 Wi‑Fi 下的 SpeedShare 设备…"
            )
        }
    }

    fun shutdown() {
        MigrationForegroundService.stop(context)
        discovery.stop()
        peerServer.stop()
    }

    private fun localPeer(): MigrationPeer = MigrationPeer(
        deviceId = deviceId,
        name = deviceName,
        host = "",
        port = peerServer.port,
        model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        appVersion = appVersion
    )

    private fun update(block: (MigrationUiState) -> MigrationUiState) {
        synchronized(_state) { _state.value = block(_state.value) }
    }

    private fun recommendedConcurrency(result: SpeedTestResult?): Int {
        val speed = result?.averageBytesPerSecond ?: 0L
        return when {
            speed < 10L * 1024L * 1024L -> 2
            speed < 50L * 1024L * 1024L -> 4
            else -> 6
        }
    }

    private fun roleStatus(role: MigrationRole): String = when (role) {
        MigrationRole.OLD_PHONE -> "这台是旧手机，正在准备可迁移内容"
        MigrationRole.NEW_PHONE -> "这台是新手机，等待旧手机选择并发送内容"
        MigrationRole.UNSET -> "请选择这台手机的角色"
    }

    private fun speedSummary(result: SpeedTestResult): String =
        "测速完成：发送 ${formatRate(result.uploadBytesPerSecond)}，接收 ${formatRate(result.downloadBytesPerSecond)}，延迟 ${result.latencyMs} ms"

    private fun transferStatus(progress: MigrationProgress): String =
        if (progress.currentName.isBlank()) "正在迁移" else "正在迁移 ${progress.currentName} · ${formatRate(progress.bytesPerSecond)}"

    companion object {
        @Volatile private var instance: MigrationController? = null

        fun get(context: Context): MigrationController = instance ?: synchronized(this) {
            instance ?: MigrationController(context.applicationContext).also { instance = it }
        }

        fun release() {
            synchronized(this) {
                instance?.shutdown()
                instance = null
            }
        }

        private fun formatRate(bytes: Long): String = when {
            bytes >= 1024L * 1024L -> String.format("%.1f MB/s", bytes / 1024.0 / 1024.0)
            bytes >= 1024L -> String.format("%.0f KB/s", bytes / 1024.0)
            else -> "$bytes B/s"
        }
    }
}
