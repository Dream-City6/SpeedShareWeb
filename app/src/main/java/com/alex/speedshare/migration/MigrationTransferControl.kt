package com.alex.speedshare.migration

import java.util.concurrent.CancellationException

internal class MigrationEarlyFinishException : CancellationException("migration_finish_early")

internal class MigrationTransferControl {
    private val lock = java.lang.Object()
    @Volatile private var paused = false
    @Volatile private var cancelled = false
    @Volatile private var finishingEarly = false

    fun pause() {
        if (!cancelled && !finishingEarly) paused = true
    }

    fun resume() {
        synchronized(lock) {
            paused = false
            lock.notifyAll()
        }
    }

    fun finishEarly() {
        synchronized(lock) {
            finishingEarly = true
            paused = false
            lock.notifyAll()
        }
    }

    fun cancel() {
        synchronized(lock) {
            cancelled = true
            paused = false
            lock.notifyAll()
        }
    }

    fun isPaused(): Boolean = paused
    fun isCancelled(): Boolean = cancelled
    fun isFinishingEarly(): Boolean = finishingEarly

    fun awaitReady() {
        if (finishingEarly) throw MigrationEarlyFinishException()
        if (cancelled) throw CancellationException("migration_cancelled")
        synchronized(lock) {
            while (paused && !cancelled && !finishingEarly) lock.wait(1000L)
        }
        if (finishingEarly) throw MigrationEarlyFinishException()
        if (cancelled) throw CancellationException("migration_cancelled")
    }
}
