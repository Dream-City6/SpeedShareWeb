package com.alex.speedshare.migration

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Keeps high-speed migration aggressive at normal temperatures while gently reducing load
 * when Android or the battery reports sustained heat. It never blocks or cancels migration.
 */
internal class MigrationAdaptiveThermalLimiter(context: Context) {
    private val appContext = context.applicationContext
    private val bytesSinceThrottle = AtomicLong(0L)
    @Volatile private var nextRefreshAt = 0L
    @Volatile private var concurrencyCap = 8
    @Volatile private var chunkStreamCap = 4
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

        when {
            temp >= 46f || thermal >= PowerManager.THERMAL_STATUS_CRITICAL -> {
                concurrencyCap = 2
                chunkStreamCap = 1
                delayPerMegabyteMs = 15L
            }
            temp >= 44f || thermal >= PowerManager.THERMAL_STATUS_SEVERE -> {
                concurrencyCap = 4
                chunkStreamCap = 2
                delayPerMegabyteMs = 5L
            }
            temp >= 42f || thermal >= PowerManager.THERMAL_STATUS_MODERATE -> {
                concurrencyCap = 6
                chunkStreamCap = 3
                delayPerMegabyteMs = 1L
            }
            else -> {
                concurrencyCap = 8
                chunkStreamCap = 4
                delayPerMegabyteMs = 0L
            }
        }
    }

    companion object {
        private const val ONE_MIB = 1024L * 1024L
    }
}
