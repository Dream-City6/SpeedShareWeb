package com.alex.speedshare

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TransferTracker(
    private val onSnapshot: (TransferSnapshot) -> Unit
) {
    private data class Task(
        val id: Long,
        val direction: TransferDirection,
        val fileName: String,
        val clientAddress: String,
        val totalBytes: Long,
        val startedAtMs: Long,
        val transferredBytes: AtomicLong = AtomicLong(0L),
        @Volatile var lastSampleBytes: Long = 0L,
        @Volatile var lastSampleNanos: Long = SystemClock.elapsedRealtimeNanos(),
        @Volatile var bytesPerSecond: Long = 0L
    )

    private val nextId = AtomicLong(1L)
    private val activeTasks = ConcurrentHashMap<Long, Task>()
    private val activeConnections = AtomicInteger(0)
    private val totalDownloadedBytes = AtomicLong(0L)
    private val totalUploadedBytes = AtomicLong(0L)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "SpeedShareWeb-TransferStats").apply { isDaemon = true }
    }

    @Volatile
    private var latestSnapshot = TransferSnapshot()

    init {
        scheduler.scheduleWithFixedDelay(
            { publishSnapshot() },
            0L,
            500L,
            TimeUnit.MILLISECONDS
        )
    }

    fun setConnectionCount(count: Int) {
        activeConnections.set(count.coerceAtLeast(0))
    }

    fun begin(
        direction: TransferDirection,
        fileName: String,
        clientAddress: String,
        totalBytes: Long,
        initialBytes: Long = 0L
    ): Long {
        val id = nextId.getAndIncrement()
        val normalizedInitialBytes = initialBytes.coerceIn(0L, totalBytes.coerceAtLeast(0L))
        activeTasks[id] = Task(
            id = id,
            direction = direction,
            fileName = fileName,
            clientAddress = clientAddress,
            totalBytes = totalBytes.coerceAtLeast(0L),
            startedAtMs = System.currentTimeMillis(),
            transferredBytes = AtomicLong(normalizedInitialBytes),
            lastSampleBytes = normalizedInitialBytes
        )
        publishSnapshot()
        return id
    }

    fun addBytes(id: Long, byteCount: Long) {
        if (byteCount <= 0L) return
        val task = activeTasks[id] ?: return
        task.transferredBytes.addAndGet(byteCount)
        when (task.direction) {
            TransferDirection.DOWNLOAD -> totalDownloadedBytes.addAndGet(byteCount)
            TransferDirection.UPLOAD -> totalUploadedBytes.addAndGet(byteCount)
        }
    }

    fun finish(id: Long) {
        activeTasks.remove(id)
        publishSnapshot()
    }

    fun snapshot(): TransferSnapshot = latestSnapshot

    fun close() {
        activeTasks.clear()
        scheduler.shutdownNow()
        latestSnapshot = TransferSnapshot(
            activeConnections = 0,
            totalDownloadedBytes = totalDownloadedBytes.get(),
            totalUploadedBytes = totalUploadedBytes.get()
        )
        onSnapshot(latestSnapshot)
    }

    private fun publishSnapshot() {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val transfers = activeTasks.values.map { task ->
            val bytes = task.transferredBytes.get()
            val elapsedNanos = (nowNanos - task.lastSampleNanos).coerceAtLeast(1L)
            val deltaBytes = (bytes - task.lastSampleBytes).coerceAtLeast(0L)
            val instantaneous = (deltaBytes * 1_000_000_000.0 / elapsedNanos).toLong()

            // 轻度平滑，防止通知栏和网页速度数字剧烈跳动。
            task.bytesPerSecond = if (task.bytesPerSecond == 0L) {
                instantaneous
            } else {
                ((task.bytesPerSecond * 2L) + instantaneous) / 3L
            }
            task.lastSampleBytes = bytes
            task.lastSampleNanos = nowNanos

            ActiveTransferSnapshot(
                id = task.id,
                direction = task.direction,
                fileName = task.fileName,
                clientAddress = task.clientAddress,
                totalBytes = task.totalBytes,
                transferredBytes = bytes,
                bytesPerSecond = task.bytesPerSecond,
                startedAtMs = task.startedAtMs
            )
        }.sortedBy { it.startedAtMs }

        val snapshot = TransferSnapshot(
            activeConnections = activeConnections.get(),
            downloadBytesPerSecond = transfers
                .filter { it.direction == TransferDirection.DOWNLOAD }
                .sumOf { it.bytesPerSecond },
            uploadBytesPerSecond = transfers
                .filter { it.direction == TransferDirection.UPLOAD }
                .sumOf { it.bytesPerSecond },
            totalDownloadedBytes = totalDownloadedBytes.get(),
            totalUploadedBytes = totalUploadedBytes.get(),
            activeTransfers = transfers,
            updatedAtMs = System.currentTimeMillis()
        )

        latestSnapshot = snapshot
        onSnapshot(snapshot)
    }
}
