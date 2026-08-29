package com.alex.speedshare.migration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

    @Test
    fun encryptedTransportStreamsOneMegabyteFrameWithoutChangingPlaintext() {
        val a = MigrationCrypto.generateEphemeralKeyPair()
        val b = MigrationCrypto.generateEphemeralKeyPair()
        val aPublic = MigrationCrypto.encodePublicKey(a.public)
        val bPublic = MigrationCrypto.encodePublicKey(b.public)
        val transcript = MigrationCrypto.transcript("sender", "receiver", aPublic, bPublic)
        val key = MigrationCrypto.deriveSessionKey(a.private, MigrationCrypto.decodePublicKey(bPublic), transcript)
        val plaintext = ByteArray(MigrationEncryptedTransport.FRAME_PLAINTEXT_BYTES).also(SecureRandom()::nextBytes)
        val wire = ByteArrayOutputStream()

        MigrationEncryptedTransport.writeFrame(
            output = wire,
            key = key,
            migrationId = "migration-stream-test",
            relativePath = "Movies/test.mp4",
            absoluteOffset = 64L * 1024L * 1024L,
            buffer = plaintext,
            length = plaintext.size
        )

        val restored = MigrationEncryptedTransport.readFrame(
            input = ByteArrayInputStream(wire.toByteArray()),
            key = key,
            migrationId = "migration-stream-test",
            relativePath = "Movies/test.mp4",
            absoluteOffset = 64L * 1024L * 1024L,
            maxPlaintextBytes = plaintext.size
        )
        assertArrayEquals(plaintext, restored)
        assertEquals(plaintext.size + 4 + 12 + 16, wire.size())
    }

    private infix fun Byte.xor(other: Int): Byte = (toInt() xor other).toByte()
}
