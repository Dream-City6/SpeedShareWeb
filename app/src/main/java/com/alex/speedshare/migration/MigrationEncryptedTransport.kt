package com.alex.speedshare.migration

import java.io.InputStream
import java.io.OutputStream
import javax.crypto.SecretKey

internal object MigrationEncryptedTransport {
    const val FRAME_PLAINTEXT_BYTES = 1024 * 1024
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BYTES = 16

    fun writeFrame(
        output: OutputStream,
        key: SecretKey,
        migrationId: String,
        relativePath: String,
        absoluteOffset: Long,
        buffer: ByteArray,
        length: Int
    ) {
        require(length in 1..FRAME_PLAINTEXT_BYTES)
        val plaintext = if (length == buffer.size) buffer else buffer.copyOf(length)
        val frame = MigrationCrypto.encryptFrame(
            key = key,
            migrationId = migrationId,
            relativePath = relativePath,
            frameIndex = absoluteOffset,
            plaintext = plaintext
        )
        writeInt(output, length)
        output.write(frame.nonce)
        output.write(frame.ciphertext)
    }

    fun readFrame(
        input: InputStream,
        key: SecretKey,
        migrationId: String,
        relativePath: String,
        absoluteOffset: Long,
        maxPlaintextBytes: Int
    ): ByteArray {
        val length = readInt(input)
        require(length in 1..minOf(FRAME_PLAINTEXT_BYTES, maxPlaintextBytes)) { "invalid_encrypted_frame_length" }
        val nonce = ByteArray(NONCE_BYTES)
        readFully(input, nonce)
        val ciphertext = ByteArray(length + GCM_TAG_BYTES)
        readFully(input, ciphertext)
        val plaintext = MigrationCrypto.decryptFrame(
            key = key,
            migrationId = migrationId,
            relativePath = relativePath,
            frameIndex = absoluteOffset,
            frame = MigrationEncryptedFrame(nonce, ciphertext)
        )
        require(plaintext.size == length) { "invalid_encrypted_plaintext_length" }
        return plaintext
    }

    private fun writeInt(output: OutputStream, value: Int) {
        output.write((value ushr 24) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write(value and 0xff)
    }

    private fun readInt(input: InputStream): Int {
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        val b4 = input.read()
        if ((b1 or b2 or b3 or b4) < 0) error("encrypted_frame_ended")
        return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) error("encrypted_frame_ended")
            offset += read
        }
    }
}
