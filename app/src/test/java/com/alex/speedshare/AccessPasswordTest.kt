package com.alex.speedshare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun basicAuthorizationExtractsOnlyAValidPassword() {
        val encoded = Base64.getEncoder().encodeToString("speedshare:秘密123".toByteArray(StandardCharsets.UTF_8))

        assertTrue(AccessPassword.passwordFromBasicAuthorization("Basic $encoded") == "秘密123")
        assertTrue(AccessPassword.passwordFromBasicAuthorization("Bearer $encoded") == null)
        assertTrue(AccessPassword.passwordFromBasicAuthorization("Basic invalid-base64") == null)
    }
}
