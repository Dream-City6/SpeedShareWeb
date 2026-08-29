package com.alex.speedshare.migration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom

class MigrationCryptoTest {
    @Test
    fun ecdhPeersDeriveSameSessionKeyAndSecurityCode() {
        val a = MigrationCrypto.generateEphemeralKeyPair()
        val b = MigrationCrypto.generateEphemeralKeyPair()
        val aPublic = MigrationCrypto.encodePublicKey(a.public)
        val bPublic = MigrationCrypto.encodePublicKey(b.public)
        val transcript = MigrationCrypto.transcript("device-a", "device-b", aPublic, bPublic)

        val aKey = MigrationCrypto.deriveSessionKey(a.private, MigrationCrypto.decodePublicKey(bPublic), transcript)
        val bKey = MigrationCrypto.deriveSessionKey(b.private, MigrationCrypto.decodePublicKey(aPublic), transcript)

        assertArrayEquals(aKey.encoded, bKey.encoded)
        assertEquals(MigrationCrypto.securityCode(aKey), MigrationCrypto.securityCode(bKey))
        assertTrue(MigrationCrypto.securityCode(aKey).matches(Regex("\\d{6}")))
    }

    @Test
    fun aesGcmFrameRoundTripsAndRejectsTampering() {
        val a = MigrationCrypto.generateEphemeralKeyPair()
        val b = MigrationCrypto.generateEphemeralKeyPair()
        val aPublic = MigrationCrypto.encodePublicKey(a.public)
        val bPublic = MigrationCrypto.encodePublicKey(b.public)
        val transcript = MigrationCrypto.transcript("old", "new", aPublic, bPublic)
        val key = MigrationCrypto.deriveSessionKey(a.private, MigrationCrypto.decodePublicKey(bPublic), transcript)
        val plaintext = ByteArray(1024 * 1024).also(SecureRandom()::nextBytes)

        val frame = MigrationCrypto.encryptFrame(key, "migration-1", "DCIM/Camera/video.mp4", 17L, plaintext)
        val decrypted = MigrationCrypto.decryptFrame(key, "migration-1", "DCIM/Camera/video.mp4", 17L, frame)
        assertArrayEquals(plaintext, decrypted)
        assertNotEquals(plaintext.size, frame.ciphertext.size)

        val tampered = frame.copy(ciphertext = frame.ciphertext.copyOf().also { it[it.lastIndex] = (it.last() xor 1) })
        try {
            MigrationCrypto.decryptFrame(key, "migration-1", "DCIM/Camera/video.mp4", 17L, tampered)
            fail("tampered ciphertext unexpectedly decrypted")
        } catch (_: Throwable) {
            // Expected authentication failure.
        }
    }

    @Test
    fun aadBindsFrameToPathAndIndex() {
        val a = MigrationCrypto.generateEphemeralKeyPair()
        val b = MigrationCrypto.generateEphemeralKeyPair()
        val aPublic = MigrationCrypto.encodePublicKey(a.public)
        val bPublic = MigrationCrypto.encodePublicKey(b.public)
        val transcript = MigrationCrypto.transcript("a", "b", aPublic, bPublic)
        val key = MigrationCrypto.deriveSessionKey(a.private, MigrationCrypto.decodePublicKey(bPublic), transcript)
        val frame = MigrationCrypto.encryptFrame(key, "m1", "Pictures/a.jpg", 0L, byteArrayOf(1, 2, 3, 4))

        try {
            MigrationCrypto.decryptFrame(key, "m1", "Pictures/b.jpg", 0L, frame)
            fail("frame unexpectedly decrypted for another path")
        } catch (_: Throwable) {
        }
        try {
            MigrationCrypto.decryptFrame(key, "m1", "Pictures/a.jpg", 1L, frame)
            fail("frame unexpectedly decrypted for another index")
        } catch (_: Throwable) {
        }
    }

    private infix fun Byte.xor(other: Int): Byte = (toInt() xor other).toByte()
}
