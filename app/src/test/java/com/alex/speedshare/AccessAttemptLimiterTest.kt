package com.alex.speedshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessAttemptLimiterTest {
    @Test
    fun repeatedFailuresTemporarilyBlockOnlyThatClient() {
        var now = 1_000L
        val limiter = AccessAttemptLimiter(
            maxFailures = 3,
            failureWindowMs = 1_000L,
            blockDurationMs = 500L,
            nowMs = { now }
        )

        repeat(3) { limiter.recordFailure("client-a") }

        assertFalse(limiter.isAllowed("client-a"))
        assertTrue(limiter.isAllowed("client-b"))
        now += 501L
        assertTrue(limiter.isAllowed("client-a"))
    }

    @Test
    fun successfulAuthenticationClearsPreviousFailures() {
        val limiter = AccessAttemptLimiter(maxFailures = 2)
        limiter.recordFailure("client")
        limiter.recordSuccess("client")
        limiter.recordFailure("client")

        assertTrue(limiter.isAllowed("client"))
    }

    @Test
    fun staleAndExcessClientEntriesAreBounded() {
        var now = 1_000L
        val limiter = AccessAttemptLimiter(
            maxEntries = 2,
            failureWindowMs = 100L,
            blockDurationMs = 100L,
            nowMs = { now }
        )
        limiter.recordFailure("a")
        now++
        limiter.recordFailure("b")
        now++
        limiter.recordFailure("c")

        assertEquals(2, limiter.trackedClientCount())
        now += 101L
        assertEquals(0, limiter.trackedClientCount())
    }
}
