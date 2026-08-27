package com.alex.speedshare.migration

import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

internal data class ResilientBatchResult(
    val report: MigrationReport,
    val failedItems: List<MigrationFileItem>,
    val cancelled: Boolean
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
        val started = System.currentTimeMillis()
        val lastSampleAt = AtomicLong(System.nanoTime())
        val lastSampleBytes = AtomicLong(0L)
        val speed = AtomicLong(0L)
        val pool = Executors.newFixedThreadPool(concurrency.coerceIn(1, 6))

        fun publish(name: String) {
            val now = System.nanoTime()
            val previousAt = lastSampleAt.get()
            if (now - previousAt >= 350_000_000L && lastSampleAt.compareAndSet(previousAt, now)) {
                val currentWire = wireBytes.get()
                val previousWire = lastSampleBytes.getAndSet(currentWire)
                speed.set(
                    ((currentWire - previousWire).coerceAtLeast(0L) * 1_000_000_000.0 /
                        max(1L, now - previousAt)).toLong()
                )
            }
            onProgress(
                MigrationProgress(
                    totalBytes = totalBytes,
                    transferredBytes = logicalBytes.get().coerceIn(0L, totalBytes),
                    totalItems = totalItems,
                    completedItems = completedItems.get().coerceIn(0, totalItems),
                    skippedItems = skippedItems.get(),
                    failedItems = failedItems.size,
                    bytesPerSecond = speed.get(),
                    currentName = name
                )
            )
        }

        val futures = items.map { item ->
            pool.submit {
                try {
                    control.awaitReady()
                    val hash = sha256(item.file)
                    control.awaitReady()
                    val result = ResilientMigrationClient.sendFile(
                        session = session,
                        migrationId = migrationId,
                        item = item,
                        hash = hash,
                        control = control,
                        onBytes = { delta ->
                            wireBytes.addAndGet(delta)
                            logicalBytes.addAndGet(delta)
                            publish(item.file.name)
                        }
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
                } catch (_: CancellationException) {
                    failedItems += item
                } catch (_: Throwable) {
                    failedItems += item
                } finally {
                    publish(item.file.name)
                }
            }
        }

        futures.forEach { runCatching { it.get() } }
        pool.shutdownNow()
        val duration = (System.currentTimeMillis() - started).coerceAtLeast(1L)
        val failedBytes = failedItems.sumOf { it.size }
        val finalBytes = (totalBytes - failedBytes).coerceAtLeast(0L)
        val report = MigrationReport(
            totalBytes = totalBytes,
            transferredBytes = finalBytes,
            successCount = (totalItems - failedItems.size).coerceAtLeast(0),
            skippedCount = skippedItems.get(),
            failedCount = failedItems.size,
            durationMs = duration,
            averageBytesPerSecond = finalBytes * 1000L / duration
        )
        publish("")
        return ResilientBatchResult(report, failedItems.toList(), control.isCancelled())
    }
}
