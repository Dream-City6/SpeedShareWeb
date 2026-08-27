package com.alex.speedshare.migration

import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

internal data class MigrationBatchResult(
    val report: MigrationReport,
    val failedItems: List<MigrationFileItem>
)

internal class ReliableMigrationTransferManager {
    fun transfer(
        peer: MigrationPeer,
        items: List<MigrationFileItem>,
        concurrency: Int,
        onProgress: (MigrationProgress) -> Unit
    ): MigrationBatchResult {
        val totalBytes = items.sumOf { it.size }
        val totalItems = items.size
        val transferred = AtomicLong(0L)
        val completed = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val failedItems = Collections.synchronizedList(mutableListOf<MigrationFileItem>())
        val started = System.currentTimeMillis()
        val lastSampleAt = AtomicLong(System.nanoTime())
        val lastSampleBytes = AtomicLong(0L)
        val speed = AtomicLong(0L)
        val pool = Executors.newFixedThreadPool(concurrency.coerceIn(1, 6))

        fun publish(name: String) {
            val now = System.nanoTime()
            val previousAt = lastSampleAt.get()
            if (now - previousAt >= 300_000_000L && lastSampleAt.compareAndSet(previousAt, now)) {
                val bytesNow = transferred.get()
                val oldBytes = lastSampleBytes.getAndSet(bytesNow)
                speed.set(
                    ((bytesNow - oldBytes).coerceAtLeast(0L) * 1_000_000_000.0 /
                        max(1L, now - previousAt)).toLong()
                )
            }
            onProgress(
                MigrationProgress(
                    totalBytes = totalBytes,
                    transferredBytes = transferred.get().coerceAtMost(totalBytes),
                    totalItems = totalItems,
                    completedItems = completed.get(),
                    skippedItems = skipped.get(),
                    failedItems = failed.get(),
                    bytesPerSecond = speed.get(),
                    currentName = name
                )
            )
        }

        val futures = items.map { item ->
            pool.submit {
                try {
                    val hash = sha256(item.file)
                    val result = MigrationClient.sendFile(
                        peer = peer,
                        item = item,
                        kind = if (item.appPackageName != null) "app" else "file",
                        hash = hash,
                        onBytes = { delta ->
                            transferred.addAndGet(delta)
                            publish(item.file.name)
                        }
                    )
                    if (result.skipped) {
                        skipped.incrementAndGet()
                        transferred.addAndGet(item.size)
                    }
                } catch (_: Throwable) {
                    failed.incrementAndGet()
                    failedItems += item
                } finally {
                    completed.incrementAndGet()
                    publish(item.file.name)
                }
            }
        }

        futures.forEach { runCatching { it.get() } }
        pool.shutdown()

        val duration = (System.currentTimeMillis() - started).coerceAtLeast(1L)
        val completeBytes = (totalBytes - failedItems.sumOf { it.size }).coerceAtLeast(0L)
        val report = MigrationReport(
            totalBytes = totalBytes,
            transferredBytes = completeBytes,
            successCount = (totalItems - failed.get()).coerceAtLeast(0),
            skippedCount = skipped.get(),
            failedCount = failed.get(),
            durationMs = duration,
            averageBytesPerSecond = (completeBytes * 1000L / duration).coerceAtLeast(0L)
        )
        publish("")
        return MigrationBatchResult(report, failedItems.toList())
    }

    fun combine(
        allItems: List<MigrationFileItem>,
        first: MigrationBatchResult,
        retry: MigrationBatchResult?
    ): MigrationReport {
        if (retry == null) return first.report
        val finalFailedPaths = retry.failedItems.mapTo(hashSetOf()) { it.file.absolutePath }
        val failedBytes = allItems.asSequence()
            .filter { it.file.absolutePath in finalFailedPaths }
            .sumOf { it.size }
        val totalBytes = allItems.sumOf { it.size }
        val duration = (first.report.durationMs + retry.report.durationMs).coerceAtLeast(1L)
        val completedBytes = (totalBytes - failedBytes).coerceAtLeast(0L)
        return MigrationReport(
            totalBytes = totalBytes,
            transferredBytes = completedBytes,
            successCount = allItems.size - retry.failedItems.size,
            skippedCount = first.report.skippedCount + retry.report.skippedCount,
            failedCount = retry.failedItems.size,
            durationMs = duration,
            averageBytesPerSecond = completedBytes * 1000L / duration
        )
    }
}
