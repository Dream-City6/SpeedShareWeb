package com.alex.speedshare.migration

import java.util.concurrent.CancellationException

internal class MigrationTransferControl {
    private val lock = java.lang.Object()
    @Volatile private var paused = false
    @Volatile private var cancelled = false

    fun pause() {
        if (!cancelled) paused = true
    }

    fun resume() {
        synchronized(lock) {
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

    fun awaitReady() {
        if (cancelled) throw CancellationException("migration_cancelled")
        synchronized(lock) {
            while (paused && !cancelled) lock.wait(1000L)
        }
        if (cancelled) throw CancellationException("migration_cancelled")
    }
}
