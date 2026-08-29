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
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class MigrationEncryptedFrame(
    val nonce: ByteArray,
    val ciphertext: ByteArray
)

/**
 * Ephemeral migration-session cryptography.
 *
 * Peers exchange P-256 ECDH public keys, derive a 256-bit AES key through HKDF-SHA256,
 * display the same short verification code, then authenticate each resumable payload frame with
 * AES-256-GCM. No migration content key is persisted to disk.
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
        try {
            val saltDigest = MessageDigest.getInstance("SHA-256")
            saltDigest.update(DOMAIN)
            saltDigest.update(transcript)
            val salt = saltDigest.digest()

            val extract = Mac.getInstance("HmacSHA256")
            extract.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = extract.doFinal(sharedSecret)
            salt.fill(0)
            try {
                val expand = Mac.getInstance("HmacSHA256")
                expand.init(SecretKeySpec(prk, "HmacSHA256"))
                expand.update(DOMAIN)
                expand.update(transcript)
                expand.update(1.toByte())
                val keyBytes = expand.doFinal()
                return SecretKeySpec(keyBytes, "AES")
            } finally {
                prk.fill(0)
            }
        } finally {
            sharedSecret.fill(0)
        }
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
