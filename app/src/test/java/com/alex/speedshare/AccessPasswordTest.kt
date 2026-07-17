package com.alex.speedshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class AccessPasswordTest {
    @Test
    fun passwordIsStoredAsAHashAndCanBeVerified() {
        val password = "local-only-123"
        val hash = AccessPassword.hash(password)

        assertNotEquals(password, hash)
        assertTrue(AccessPassword.matches(password, hash))
        assertFalse(AccessPassword.matches("wrong-password", hash))
        assertFalse(AccessPassword.matches(password, ""))
    }

    @Test
    fun malformedPasswordVerifiersAreRejectedBeforeDerivation() {
        assertFalse(AccessPassword.matches("password", "pbkdf2-sha256\$120000\$AA==\$AA=="))
        assertFalse(AccessPassword.matches("password", "x".repeat(513)))
    }

    @Test
    fun basicAuthorizationExtractsOnlyAValidPassword() {
        val encoded = Base64.getEncoder().encodeToString("speedshare:秘密123".toByteArray(StandardCharsets.UTF_8))

        assertTrue(AccessPassword.passwordFromBasicAuthorization("Basic $encoded") == "秘密123")
        assertTrue(AccessPassword.passwordFromBasicAuthorization("Bearer $encoded") == null)
        assertTrue(AccessPassword.passwordFromBasicAuthorization("Basic invalid-base64") == null)
        assertNull(AccessPassword.passwordFromBasicAuthorization("Basic ${"A".repeat(8 * 1024 + 1)}"))
    }

    @Test
    fun sessionCookieCanBeValidatedAndRevokedWithoutStoringThePassword() {
        val sessions = AccessSessionManager()
        val token = sessions.create()
        val cookie = "theme=dark; ${AccessSessionManager.COOKIE_NAME}=$token; view=grid"

        assertTrue(sessions.isValid(cookie))
        assertTrue(token.length >= 32)
        sessions.revoke(cookie)
        assertFalse(sessions.isValid(cookie))
    }

    @Test
    fun expiredSessionsAreRejectedAndRemoved() {
        var now = 1_000L
        val sessions = AccessSessionManager(sessionTtlMs = 100L, nowMs = { now })
        val token = sessions.create()
        val cookie = "${AccessSessionManager.COOKIE_NAME}=$token"

        assertTrue(sessions.isValid(cookie))
        now = 1_101L
        assertFalse(sessions.isValid(cookie))
        assertEquals(0, sessions.activeSessionCount())
    }

    @Test
    fun oldestSessionsAreEvictedWhenTheLimitIsReached() {
        var now = 1_000L
        val sessions = AccessSessionManager(sessionTtlMs = 10_000L, maxSessions = 2, nowMs = { now })
        val first = sessions.create()
        now += 1L
        val second = sessions.create()
        now += 1L
        val third = sessions.create()

        assertFalse(sessions.isValid("${AccessSessionManager.COOKIE_NAME}=$first"))
        assertTrue(sessions.isValid("${AccessSessionManager.COOKIE_NAME}=$second"))
        assertTrue(sessions.isValid("${AccessSessionManager.COOKIE_NAME}=$third"))
        assertEquals(2, sessions.activeSessionCount())
    }

    @Test
    fun malformedSessionCookiesAreIgnored() {
        val sessions = AccessSessionManager()

        assertNull(sessions.tokenFromCookie(null))
        assertNull(sessions.tokenFromCookie("${AccessSessionManager.COOKIE_NAME}=short"))
        assertNull(sessions.tokenFromCookie("${AccessSessionManager.COOKIE_NAME}=invalid value with spaces"))
    }
}
