package com.alex.speedshare.migration

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

internal object MigrationProtocol {
    const val MAX_HEADER_BYTES = 256 * 1024

    fun writeJson(output: OutputStream, json: JSONObject) {
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_HEADER_BYTES) { "Header too large" }
        val data = DataOutputStream(output)
        data.writeInt(bytes.size)
        data.write(bytes)
        data.flush()
    }

    fun readJson(input: InputStream): JSONObject {
        val data = DataInputStream(input)
        val length = try {
            data.readInt()
        } catch (e: EOFException) {
            throw EOFException("Peer closed before header")
        }
        require(length in 2..MAX_HEADER_BYTES) { "Invalid header length: $length" }
        val bytes = ByteArray(length)
        data.readFully(bytes)
        return JSONObject(String(bytes, StandardCharsets.UTF_8))
    }

    fun connect(host: String, port: Int, timeoutMs: Int = 8_000): Socket =
        Socket().apply {
            tcpNoDelay = true
            keepAlive = true
            sendBufferSize = 4 * 1024 * 1024
            receiveBufferSize = 4 * 1024 * 1024
            connect(java.net.InetSocketAddress(host, port), timeoutMs)
            soTimeout = 90_000
        }
}

internal object MigrationCommands {
    const val HELLO = "hello"
    const val PAIR = "pair"
    const val PAIR_RESULT = "pair_result"
    const val ROLE = "role"
    const val SPEED_UPLOAD = "speed_upload"
    const val SPEED_DOWNLOAD = "speed_download"
    const val SPEED_RESULT = "speed_result"
    const val TRANSFER_PLAN = "transfer_plan"
    const val FILE_OFFER = "file_offer"
    const val FILE_READY = "file_ready"
    const val FILE_RESULT = "file_result"
    const val REPORT = "report"
}
