package com.alex.speedshare.migration

import android.content.Context
import android.os.PowerManager
import com.alex.speedshare.MigrationPerformanceConfig
import com.alex.speedshare.MigrationThermalPolicy
import com.alex.speedshare.TransferPerformanceSettingsStore
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Keeps high-speed migration aggressive at normal temperatures while gently reducing load
 * when Android or the battery reports sustained heat. User settings define ceilings only;
 * thermal protection never force-stops a migration and can always scale the active load down.
 */
internal class MigrationAdaptiveThermalLimiter(
    context: Context,
    private val performance: MigrationPerformanceConfig =
        TransferPerformanceSettingsStore.load(context).resolved(context).migration
) {
    private val appContext = context.applicationContext
    private val bytesSinceThrottle = AtomicLong(0L)
    @Volatile private var nextRefreshAt = 0L
    @Volatile private var concurrencyCap = performance.maxFileConcurrency.coerceIn(1, 8)
    @Volatile private var chunkStreamCap = performance.maxChunkStreams.coerceIn(1, 8)
    @Volatile private var delayPerMegabyteMs = 0L

    fun initialConcurrency(requested: Int): Int {
        refresh(force = true)
        return min(requested.coerceIn(1, 8), concurrencyCap)
    }

    fun chunkStreams(requested: Int): Int {
        refresh()
        return min(requested.coerceAtLeast(1), chunkStreamCap).coerceAtLeast(1)
    }

    fun onBytesTransferred(deltaBytes: Long) {
        if (deltaBytes <= 0L) return
        val total = bytesSinceThrottle.addAndGet(deltaBytes)
        if (total < ONE_MIB) return
        bytesSinceThrottle.addAndGet(-ONE_MIB)
        refresh()
        val delay = delayPerMegabyteMs
        if (delay > 0L) {
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    @Synchronized
    private fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now < nextRefreshAt) return
        nextRefreshAt = now + 2_000L
        val health = MigrationDeviceHealthReader.read(appContext)
        val temp = health.batteryTemperatureC ?: 0f
        val thermal = health.thermalStatus

        val thresholds = when (performance.thermalPolicy) {
            MigrationThermalPolicy.CONSERVATIVE -> Thresholds(40.5f, 42.5f, 44.5f)
            MigrationThermalPolicy.BALANCED -> Thresholds(42f, 44f, 46f)
            MigrationThermalPolicy.PERFORMANCE -> Thresholds(43.5f, 46f, 48f)
        }

        val maxConcurrency = performance.maxFileConcurrency.coerceIn(1, 8)
        val maxChunkStreams = performance.maxChunkStreams.coerceIn(1, 8)

        when {
            temp >= thresholds.critical || thermal >= PowerManager.THERMAL_STATUS_CRITICAL -> {
                concurrencyCap = min(maxConcurrency, 2).coerceAtLeast(1)
                chunkStreamCap = 1
                delayPerMegabyteMs = 15L
            }
            temp >= thresholds.severe || thermal >= PowerManager.THERMAL_STATUS_SEVERE -> {
                concurrencyCap = min(maxConcurrency, 4).coerceAtLeast(1)
                chunkStreamCap = min(maxChunkStreams, 2).coerceAtLeast(1)
                delayPerMegabyteMs = 5L
            }
            temp >= thresholds.moderate || thermal >= PowerManager.THERMAL_STATUS_MODERATE -> {
                concurrencyCap = min(maxConcurrency, 6).coerceAtLeast(1)
                chunkStreamCap = min(maxChunkStreams, 4).coerceAtLeast(1)
                delayPerMegabyteMs = 1L
            }
            else -> {
                concurrencyCap = maxConcurrency
                chunkStreamCap = maxChunkStreams
                delayPerMegabyteMs = 0L
            }
        }
    }

    private data class Thresholds(
        val moderate: Float,
        val severe: Float,
        val critical: Float
    )

    companion object {
        private const val ONE_MIB = 1024L * 1024L
    }
}
