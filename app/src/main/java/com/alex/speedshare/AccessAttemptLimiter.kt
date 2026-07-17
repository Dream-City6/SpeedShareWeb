package com.alex.speedshare

internal class AccessAttemptLimiter(
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val failureWindowMs: Long = DEFAULT_FAILURE_WINDOW_MS,
    private val blockDurationMs: Long = DEFAULT_BLOCK_DURATION_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    init {
        require(maxFailures > 0) { "maxFailures must be greater than zero" }
        require(failureWindowMs > 0L) { "failureWindowMs must be greater than zero" }
        require(blockDurationMs > 0L) { "blockDurationMs must be greater than zero" }
        require(maxEntries > 0) { "maxEntries must be greater than zero" }
    }

    private data class State(
        var failures: Int,
        var windowStartedAtMs: Long,
        var blockedUntilMs: Long,
        var lastSeenAtMs: Long
    )

    private val states = linkedMapOf<String, State>()

    @Synchronized
    fun isAllowed(clientKey: String): Boolean {
        val now = nowMs()
        prune(now)
        val state = states[clientKey] ?: return true
        state.lastSeenAtMs = now
        if (state.blockedUntilMs > now) return false
        if (now - state.windowStartedAtMs >= failureWindowMs) {
            states.remove(clientKey)
        }
        return true
    }

    @Synchronized
    fun recordFailure(clientKey: String) {
        val now = nowMs()
        prune(now)
        val state = states[clientKey]
        val current = if (state == null || now - state.windowStartedAtMs >= failureWindowMs) {
            State(
                failures = 0,
                windowStartedAtMs = now,
                blockedUntilMs = 0L,
                lastSeenAtMs = now
            ).also { states[clientKey] = it }
        } else {
            state
        }

        current.failures++
        current.lastSeenAtMs = now
        if (current.failures >= maxFailures) {
            current.blockedUntilMs = now + blockDurationMs
        }
        trimToCapacity()
    }

    @Synchronized
    fun recordSuccess(clientKey: String) {
        states.remove(clientKey)
    }

    @Synchronized
    internal fun trackedClientCount(): Int {
        prune(nowMs())
        return states.size
    }

    private fun prune(now: Long) {
        val iterator = states.entries.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next().value
            val blockExpired = state.blockedUntilMs <= now
            val windowExpired = now - state.windowStartedAtMs >= failureWindowMs
            if (blockExpired && windowExpired) iterator.remove()
        }
    }

    private fun trimToCapacity() {
        while (states.size > maxEntries) {
            val oldest = states.minByOrNull { it.value.lastSeenAtMs } ?: return
            states.remove(oldest.key)
        }
    }

    private companion object {
        const val DEFAULT_MAX_FAILURES = 8
        const val DEFAULT_FAILURE_WINDOW_MS = 60_000L
        const val DEFAULT_BLOCK_DURATION_MS = 60_000L
        const val DEFAULT_MAX_ENTRIES = 256
    }
}
