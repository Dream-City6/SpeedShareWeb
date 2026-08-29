package com.alex.speedshare.migration

import com.alex.speedshare.TransferPerformanceSettingsStore
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

internal data class ResilientBatchResult(
    val report: MigrationReport,
    val failedItems: List<MigrationFileItem>,
    val omittedItems: List<MigrationFileItem>,
    val cancelled: Boolean,
    val finishedEarly: Boolean
)

internal class ResilientMigrationTransferManager(
    private val store: MigrationTaskStore
) {
    fun transfer(
        session: MigrationSession,
        migrationId: String,
        items: List<MigrationFileItem>,
        totalBytes: Long,
        totalItems: Int,
        alreadyCompletedBytes: Long,
        alreadyCompletedItems: Int,
        concurrency: Int,
        control: MigrationTransferControl,
        onProgress: (MigrationProgress) -> Unit
    ): ResilientBatchResult {
        val logicalBytes = AtomicLong(alreadyCompletedBytes)
        val wireBytes = AtomicLong(0L)
        val completedItems = AtomicInteger(alreadyCompletedItems)
        val skippedItems = AtomicInteger(0)
        val failedItems = Collections.synchronizedList(mutableListOf<MigrationFileItem>())
        val omittedItems = Collections.synchronizedList(mutableListOf<MigrationFileItem>())
        val activeNames = ConcurrentHashMap.newKeySet<String>()
        val started = System.currentTimeMillis()
        val performance = TransferPerformanceSettingsStore.load(store.appContext)
            .resolved(store.appContext)
            .migration
        val thermalLimiter = MigrationAdaptiveThermalLimiter(store.appContext, performance)
        val requestedConcurrency = min(concurrency, performance.maxFileConcurrency).coerceAtLeast(1)
        val effectiveConcurrency = thermalLimiter.initialConcurrency(requestedConcurrency)
        val largeFileThreshold = performance.largeFileThresholdMb.toLong() * 1024L * 1024L

        val lastUiPublishAt = AtomicLong(0L)
        val lastSpeedSampleAt = AtomicLong(System.nanoTime())
        val lastSampleBytes = AtomicLong(0L)
        val smoothedSpeed = AtomicLong(0L)
        val pool = Executors.newFixedThreadPool(effectiveConcurrency.coerceIn(1, HARD_MAX_FILE_CONCURRENCY))

        fun publish(force: Boolean = false) {
            val now = System.nanoTime()
            val previousUi = lastUiPublishAt.get()
            if (!force && now - previousUi < UI_REFRESH_NANOS) return
            if (!force && !lastUiPublishAt.compareAndSet(previousUi, now)) return
            if (force) lastUiPublishAt.set(now)

            val previousSampleAt = lastSpeedSampleAt.get()
            if (now - previousSampleAt >= SPEED_SAMPLE_NANOS && lastSpeedSampleAt.compareAndSet(previousSampleAt, now)) {
                val currentWire = wireBytes.get()
                val previousWire = lastSampleBytes.getAndSet(currentWire)
                val instant = (
                    (currentWire - previousWire).coerceAtLeast(0L) * 1_000_000_000.0 /
                        max(1L, now - previousSampleAt)
                    ).toLong()
                val old = smoothedSpeed.get()
                smoothedSpeed.set(if (old <= 0L) instant else ((old * 3L + instant * 2L) / 5L).coerceAtLeast(0L))
            }

            val names = activeNames.toList()
            val displayName = when (names.size) {
                0 -> ""
                1 -> names.first()
                else -> "并行传输 ${names.size} 个项目"
            }
            onProgress(
                MigrationProgress(
                    totalBytes = totalBytes,
                    transferredBytes = logicalBytes.get().coerceIn(0L, totalBytes),
                    totalItems = totalItems,
                    completedItems = completedItems.get().coerceIn(0, totalItems),
                    skippedItems = skippedItems.get(),
                    failedItems = failedItems.size,
                    bytesPerSecond = smoothedSpeed.get(),
                    currentName = displayName
                )
            )
        }

        val futures = items.map { item ->
            pool.submit {
                activeNames += item.file.name.ifBlank { item.relativePath.substringAfterLast('/') }
                try {
                    control.awaitReady()
                    MigrationSourceValidator.problem(item)?.let { problem -> error(problem) }
                    val hash = MigrationHashCache.sha256(item.file)
                    control.awaitReady()
                    val desiredChunkStreams = when {
                        item.size < largeFileThreshold || effectiveConcurrency < 2 -> 1
                        items.size == 1 -> min(performance.maxChunkStreams, effectiveConcurrency)
                        items.size == 2 -> min(
                            min(performance.maxChunkStreams, HARD_MAX_CHUNK_STREAMS_PER_FILE_WHEN_TWO_ACTIVE),
                            (effectiveConcurrency / 2).coerceAtLeast(2)
                        )
                        else -> 1
                    }
                    val chunkStreams = thermalLimiter.chunkStreams(desiredChunkStreams)
                    val result = ResilientMigrationClient.sendFile(
                        session = session,
                        migrationId = migrationId,
                        item = item,
                        hash = hash,
                        control = control,
                        onBytes = { delta ->
                            wireBytes.addAndGet(delta)
                            logicalBytes.addAndGet(delta)
                            thermalLimiter.onBytesTransferred(delta)
                            publish()
                        },
                        parallelStreams = chunkStreams
                    )
                    if (result.skipped) {
                        skippedItems.incrementAndGet()
                        logicalBytes.addAndGet(item.size)
                    } else {
                        val resumedPrefix = (item.size - result.sentBytes).coerceAtLeast(0L)
                        if (resumedPrefix > 0L) logicalBytes.addAndGet(resumedPrefix)
                    }
                    completedItems.incrementAndGet()
                    store.markCompleted(migrationId, item, result.skipped)
                } catch (_: MigrationEarlyFinishException) {
                    omittedItems += item
                } catch (error: CancellationException) {
                    if (control.isFinishingEarly()) {
                        omittedItems += item
                    } else {
                        failedItems += item
                        store.markFailed(migrationId, item, "paused_or_cancelled")
                    }
                } catch (error: Throwable) {
                    if (control.isFinishingEarly()) {
                        omittedItems += item
                    } else {
                        failedItems += item
                        store.markFailed(migrationId, item, normalizeFailure(error))
                    }
                } finally {
                    activeNames -= item.file.name.ifBlank { item.relativePath.substringAfterLast('/') }
                    publish(force = true)
                }
            }
        }

        futures.forEach { runCatching { it.get() } }
        pool.shutdownNow()
        val duration = (System.currentTimeMillis() - started).coerceAtLeast(1L)
        val failedBytes = failedItems.sumOf { it.size }
        val omittedBytes = omittedItems.sumOf { it.size }
        val finalBytes = (totalBytes - failedBytes - omittedBytes).coerceAtLeast(0L)
        val report = MigrationReport(
            totalBytes = totalBytes,
            transferredBytes = finalBytes,
            successCount = (totalItems - failedItems.size - omittedItems.size).coerceAtLeast(0),
            skippedCount = skippedItems.get(),
            failedCount = failedItems.size,
            durationMs = duration,
            averageBytesPerSecond = finalBytes * 1000L / duration,
            notMigratedCount = omittedItems.size
        )
        publish(force = true)
        return ResilientBatchResult(
            report = report,
            failedItems = failedItems.toList(),
            omittedItems = omittedItems.toList(),
            cancelled = control.isCancelled(),
            finishedEarly = control.isFinishingEarly()
        )
    }

    private fun normalizeFailure(error: Throwable): String {
        val text = error.message.orEmpty().lowercase()
        return when {
            "旧手机源文件" in error.message.orEmpty() -> error.message.orEmpty().take(200)
            "insufficient_space" in text -> "新手机空间不足"
            "receiver_storage_permission_required" in text -> "新手机缺少存储权限"
            "hash_mismatch" in text -> "完整性校验失败"
            "session_required" in text -> "配对会话已失效"
            "timeout" in text -> "连接超时"
            "connection" in text || "broken pipe" in text || "reset" in text -> "网络连接中断"
            text.isNotBlank() -> error.message.orEmpty().take(200)
            else -> error::class.java.simpleName
        }
    }

    companion object {
        private const val HARD_MAX_FILE_CONCURRENCY = 8
        private const val HARD_MAX_CHUNK_STREAMS_PER_FILE_WHEN_TWO_ACTIVE = 4
        private const val UI_REFRESH_NANOS = 250_000_000L
        private const val SPEED_SAMPLE_NANOS = 500_000_000L
    }
}
