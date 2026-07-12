package com.alex.speedshare

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object AccessPassword {
    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val derived = derive(password, salt, ITERATIONS)
        return listOf(
            FORMAT,
            ITERATIONS.toString(),
            Base64.getEncoder().encodeToString(salt),
            Base64.getEncoder().encodeToString(derived)
        ).joinToString("$")
    }

    fun matches(password: String, expectedHash: String): Boolean {
        val parts = expectedHash.split('$')
        if (parts.size != 4 || parts[0] != FORMAT) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 10_000..1_000_000 } ?: return false
        val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
        val actual = runCatching { derive(password, salt, iterations) }.getOrNull() ?: return false
        return MessageDigest.isEqual(actual, expected)
    }

    fun passwordFromBasicAuthorization(header: String?): String? {
        val encoded = header
            ?.takeIf { it.startsWith("Basic ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?: return null
        val credentials = runCatching {
            String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        if (':' !in credentials) return null
        return credentials.substringAfter(':')
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private const val FORMAT = "pbkdf2-sha256"
    private const val ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
}
