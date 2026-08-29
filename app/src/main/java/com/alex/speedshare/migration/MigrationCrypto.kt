package com.alex.speedshare.migration

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class MigrationEncryptedFrame(
    val nonce: ByteArray,
    val ciphertext: ByteArray
)

/**
 * Crypto primitives for the next migration protocol revision.
 *
 * The transport integration will exchange only ephemeral public keys, derive an ECDH secret,
 * show the same short verification code on both devices, then protect each resumable frame with
 * AES-256-GCM. No long-term migration key is stored on disk.
 */
internal object MigrationCrypto {
    private val random = SecureRandom()

    fun generateEphemeralKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), random)
        }.generateKeyPair()

    fun encodePublicKey(publicKey: PublicKey): ByteArray = publicKey.encoded

    fun decodePublicKey(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

    fun deriveSessionKey(
        privateKey: PrivateKey,
        peerPublicKey: PublicKey,
        transcript: ByteArray
    ): SecretKey {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        val sharedSecret = agreement.generateSecret()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN)
        digest.update(sharedSecret)
        digest.update(transcript)
        val keyBytes = digest.digest()
        sharedSecret.fill(0)
        return SecretKeySpec(keyBytes, "AES")
    }

    fun securityCode(key: SecretKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        val value = (ByteBuffer.wrap(digest, 0, 4).int.toLong() and 0xffffffffL) % 1_000_000L
        return value.toString().padStart(6, '0')
    }

    fun encryptFrame(
        key: SecretKey,
        migrationId: String,
        relativePath: String,
        frameIndex: Long,
        plaintext: ByteArray
    ): MigrationEncryptedFrame {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(migrationId, relativePath, frameIndex))
        return MigrationEncryptedFrame(nonce, cipher.doFinal(plaintext))
    }

    fun decryptFrame(
        key: SecretKey,
        migrationId: String,
        relativePath: String,
        frameIndex: Long,
        frame: MigrationEncryptedFrame
    ): ByteArray {
        require(frame.nonce.size == NONCE_BYTES) { "invalid_gcm_nonce" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, frame.nonce))
        cipher.updateAAD(aad(migrationId, relativePath, frameIndex))
        return cipher.doFinal(frame.ciphertext)
    }

    fun transcript(
        localDeviceId: String,
        remoteDeviceId: String,
        firstPublicKey: ByteArray,
        secondPublicKey: ByteArray
    ): ByteArray {
        val ids = listOf(localDeviceId, remoteDeviceId).sorted().joinToString("|")
        val keys = listOf(firstPublicKey, secondPublicKey)
            .sortedWith { a, b -> compareUnsigned(a, b) }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ids.toByteArray(StandardCharsets.UTF_8))
        keys.forEach(digest::update)
        return digest.digest()
    }

    private fun aad(migrationId: String, relativePath: String, frameIndex: Long): ByteArray =
        "$migrationId\u0000$relativePath\u0000$frameIndex".toByteArray(StandardCharsets.UTF_8)

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val size = minOf(a.size, b.size)
        for (index in 0 until size) {
            val av = a[index].toInt() and 0xff
            val bv = b[index].toInt() and 0xff
            if (av != bv) return av - bv
        }
        return a.size - b.size
    }

    private val DOMAIN = "SpeedShareWeb-ECDH-AESGCM-v1".toByteArray(StandardCharsets.UTF_8)
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
}
