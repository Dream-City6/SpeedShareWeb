package com.alex.speedshare.migration

import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Peer server used by the migration flow.
 *
 * Discovery/HELLO and the explicit PAIR request are public on the local network. Every command
 * that can change state, receive data or expose migration metadata is accepted only from a host
 * that participated in an accepted pairing. This is not transport encryption, but it prevents
 * unrelated LAN clients from writing files merely by discovering or scanning the migration port.
 */
internal class SecureMigrationPeerServer(
    private val localDeviceId: String,
    private val localDeviceName: String,
    private val appVersion: String,
    private val onPairRequest: (IncomingPairRequest) -> Unit,
    private val onPeerConnected: (MigrationPeer) -> Unit,
    private val onRole: (MigrationRole) -> Unit,
    private val onSpeedResult: (SpeedTestResult) -> Unit,
    private val onReceiveBytes: (Long, String) -> Unit,
    private val onReport: (MigrationReport) -> Unit
) {
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "SpeedShare-SecureMigrationPeer").apply { isDaemon = true }
    }
    private val pairDecisions = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private val authorizedHosts = ConcurrentHashMap.newKeySet<String>()

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    val port: Int get() = serverSocket?.localPort ?: 0

    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket(0).apply { reuseAddress = true }
        serverSocket = socket
        running = true
        executor.execute {
            while (running) {
                try {
                    val client = socket.accept()
                    executor.execute { handle(client) }
                } catch (_: Throwable) {
                    if (!running) break
                }
            }
        }
    }

    fun respondPair(requestId: String, accepted: Boolean) {
        pairDecisions.remove(requestId)?.complete(accepted)
    }

    /** Authorizes the remote side after this phone initiated a PAIR request and it was accepted. */
    fun authorizePeer(peer: MigrationPeer) {
        normalizeHost(peer.host)?.let(authorizedHosts::add)
    }

    /** Ends the trust window when the user leaves/resets the current migration session. */
    fun clearAuthorizedPeers() {
        authorizedHosts.clear()
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        pairDecisions.values.forEach { it.complete(false) }
        pairDecisions.clear()
        authorizedHosts.clear()
        executor.shutdownNow()
    }

    private fun handle(raw: Socket) {
        raw.use { socket ->
            try {
                socket.tcpNoDelay = true
                socket.sendBufferSize = 1024 * 1024
                socket.receiveBufferSize = 1024 * 1024
                socket.soTimeout = 120_000

                val input = BufferedInputStream(socket.getInputStream(), 1024 * 1024)
                val output = BufferedOutputStream(socket.getOutputStream(), 1024 * 1024)
                val request = MigrationProtocol.readJson(input)
                val type = request.optString("type")
                val remoteHost = normalizeHost(socket.inetAddress.hostAddress.orEmpty()).orEmpty()

                if (type !in PUBLIC_COMMANDS && remoteHost !in authorizedHosts) {
                    MigrationProtocol.writeJson(
                        output,
                        JSONObject().put("ok", false).put("error", "pairing_required")
                    )
                    output.flush()
                    return
                }

                when (type) {
                    MigrationCommands.HELLO -> {
                        MigrationProtocol.writeJson(
                            output,
                            JSONObject().put("ok", true).put("deviceId", localDeviceId)
                        )
                        output.flush()
                    }
                    MigrationCommands.PAIR -> handlePair(socket, output, request)
                    MigrationCommands.ROLE -> handleRole(output, request)
                    MigrationCommands.SPEED_UPLOAD -> handleSpeedUpload(input, output, request)
                    MigrationCommands.SPEED_DOWNLOAD -> handleSpeedDownload(output, request)
                    MigrationCommands.SPEED_RESULT -> handleSpeedResult(output, request)
                    MigrationCommands.FILE_OFFER -> handleFileOffer(input, output, request)
                    MigrationCommands.REPORT -> handleReport(output, request)
                    else -> {
                        MigrationProtocol.writeJson(
                            output,
                            JSONObject().put("ok", false).put("error", "unknown_command")
                        )
                        output.flush()
                    }
                }
            } catch (_: Throwable) {
                // A dropped peer leaves its .speedshare.part file intact for the next resume pass.
            }
        }
    }

    private fun handlePair(socket: Socket, output: BufferedOutputStream, request: JSONObject) {
        val requestId = request.optString("requestId").ifBlank { UUID.randomUUID().toString() }
        val peer = MigrationPeer(
            deviceId = request.optString("deviceId"),
            name = request.optString("name").ifBlank { "SpeedShare" },
            host = socket.inetAddress.hostAddress.orEmpty(),
            port = request.optInt("servicePort"),
            model = request.optString("model"),
            appVersion = request.optString("version")
        )
        if (peer.deviceId.isBlank() || peer.host.isBlank() || peer.port !in 1..65535) {
            MigrationProtocol.writeJson(
                output,
                JSONObject().put("type", MigrationCommands.PAIR_RESULT).put("accepted", false)
            )
            output.flush()
            return
        }

        val decision = CompletableFuture<Boolean>()
        pairDecisions[requestId] = decision
        onPairRequest(IncomingPairRequest(requestId, peer))
        val accepted = runCatching { decision.get(60, TimeUnit.SECONDS) }.getOrDefault(false)
        pairDecisions.remove(requestId)

        if (accepted) {
            authorizePeer(peer)
            onPeerConnected(peer)
        }

        MigrationProtocol.writeJson(
            output,
            JSONObject()
                .put("type", MigrationCommands.PAIR_RESULT)
                .put("accepted", accepted)
                .put("deviceId", localDeviceId)
                .put("name", localDeviceName)
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                .put("version", appVersion)
                .put("servicePort", port)
        )
        output.flush()
    }

    private fun handleRole(output: BufferedOutputStream, request: JSONObject) {
        val role = runCatching { MigrationRole.valueOf(request.optString("role")) }
            .getOrDefault(MigrationRole.UNSET)
        onRole(role)
        MigrationProtocol.writeJson(output, JSONObject().put("ok", true))
        output.flush()
    }

    private fun handleSpeedResult(output: BufferedOutputStream, request: JSONObject) {
        onSpeedResult(
            SpeedTestResult(
                latencyMs = request.optLong("latencyMs"),
                uploadBytesPerSecond = request.optLong("uploadBps"),
                downloadBytesPerSecond = request.optLong("downloadBps"),
                stabilityPercent = request.optInt("stability", 100).coerceIn(0, 100)
            )
        )
        MigrationProtocol.writeJson(output, JSONObject().put("ok", true))
        output.flush()
    }

    private fun handleReport(output: BufferedOutputStream, request: JSONObject) {
        onReport(
            MigrationReport(
                totalBytes = request.optLong("totalBytes"),
                transferredBytes = request.optLong("transferredBytes"),
                successCount = request.optInt("successCount"),
                skippedCount = request.optInt("skippedCount"),
                failedCount = request.optInt("failedCount"),
                durationMs = request.optLong("durationMs"),
                averageBytesPerSecond = request.optLong("averageBps")
            )
        )
        MigrationProtocol.writeJson(output, JSONObject().put("ok", true))
        output.flush()
    }

    private fun handleSpeedUpload(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        request: JSONObject
    ) {
        val size = request.optLong("size").coerceIn(1L, MAX_SPEED_TEST_BYTES)
        val buffer = ByteArray(1024 * 1024)
        var remaining = size
        val started = System.nanoTime()
        while (remaining > 0L) {
            val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
            if (read < 0) error("Speed upload ended early")
            remaining -= read
        }
        val elapsed = max(1L, System.nanoTime() - started)
        MigrationProtocol.writeJson(
            output,
            JSONObject().put("ok", true).put("elapsedNanos", elapsed).put("size", size)
        )
        output.flush()
    }

    private fun handleSpeedDownload(output: BufferedOutputStream, request: JSONObject) {
        val size = request.optLong("size").coerceIn(1L, MAX_SPEED_TEST_BYTES)
        MigrationProtocol.writeJson(output, JSONObject().put("ok", true).put("size", size))
        val bytes = ByteArray(1024 * 1024)
        var remaining = size
        while (remaining > 0L) {
            val count = min(bytes.size.toLong(), remaining).toInt()
            output.write(bytes, 0, count)
            remaining -= count
        }
        output.flush()
    }

    private fun handleFileOffer(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        request: JSONObject
    ) {
        val relativePath = normalizeRelativePath(request.optString("path"))
            ?: return sendFileFailure(output, "invalid_path")
        val size = request.optLong("size", -1L)
        if (size < 0L) return sendFileFailure(output, "invalid_size")
        val modifiedAt = request.optLong("modifiedAt", 0L)
        val sourceHash = request.optString("sha256")
        if (!sourceHash.matches(SHA256_REGEX)) return sendFileFailure(output, "invalid_hash")

        val kind = request.optString("kind", "file")
        val requestedTarget = resolveTarget(relativePath, kind)
            ?: return sendFileFailure(output, "invalid_target")
        requestedTarget.parentFile?.mkdirs()

        if (requestedTarget.isFile && requestedTarget.length() == size) {
            val existingHash = runCatching { sha256(requestedTarget) }.getOrNull()
            if (existingHash == sourceHash) {
                MigrationProtocol.writeJson(
                    output,
                    JSONObject()
                        .put("type", MigrationCommands.FILE_READY)
                        .put("action", "skip")
                        .put("offset", size)
                )
                output.flush()
                return
            }
        }

        val finalTarget = if (requestedTarget.exists()) findConflictTarget(requestedTarget) else requestedTarget
        val partFile = File(
            finalTarget.parentFile,
            ".${finalTarget.name}.${sourceHash.take(12)}.speedshare.part"
        )
        var offset = partFile.length().coerceIn(0L, size)
        if (partFile.length() > size) {
            partFile.delete()
            offset = 0L
        }

        MigrationProtocol.writeJson(
            output,
            JSONObject()
                .put("type", MigrationCommands.FILE_READY)
                .put("action", "send")
                .put("offset", offset)
                .put("target", finalTarget.name)
        )
        output.flush()

        RandomAccessFile(partFile, "rw").use { destination ->
            destination.seek(offset)
            var remaining = size - offset
            val buffer = ByteArray(1024 * 1024)
            while (remaining > 0L) {
                val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                if (read < 0) error("Transfer ended early")
                destination.write(buffer, 0, read)
                remaining -= read
                onReceiveBytes(read.toLong(), finalTarget.name)
            }
            destination.fd.sync()
        }

        if (partFile.length() != size) return sendFileFailure(output, "size_mismatch")
        if (sha256(partFile) != sourceHash) {
            partFile.delete()
            return sendFileFailure(output, "hash_mismatch")
        }

        if (!partFile.renameTo(finalTarget)) {
            partFile.copyTo(finalTarget, overwrite = false)
            partFile.delete()
        }
        if (modifiedAt > 0L) finalTarget.setLastModified(modifiedAt)

        MigrationProtocol.writeJson(
            output,
            JSONObject()
                .put("type", MigrationCommands.FILE_RESULT)
                .put("ok", true)
                .put("path", relativePath)
        )
        output.flush()
    }

    private fun resolveTarget(relativePath: String, kind: String): File? {
        val storageRoot = Environment.getExternalStorageDirectory().canonicalFile
        val base = if (kind == "app") {
            File(storageRoot, "Download/SpeedShare/Apps").apply { mkdirs() }.canonicalFile
        } else {
            storageRoot
        }
        val target = File(base, relativePath).canonicalFile
        return target.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    private fun findConflictTarget(file: File): File {
        val dot = file.name.lastIndexOf('.')
        val stem = if (dot > 0) file.name.substring(0, dot) else file.name
        val extension = if (dot > 0) file.name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = File(file.parentFile, "$stem ($index)$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun sendFileFailure(output: BufferedOutputStream, message: String) {
        MigrationProtocol.writeJson(
            output,
            JSONObject()
                .put("type", MigrationCommands.FILE_RESULT)
                .put("ok", false)
                .put("error", message)
        )
        output.flush()
    }

    private fun normalizeHost(value: String): String? {
        val normalized = value.trim().substringBefore('%').lowercase(Locale.ROOT)
        return normalized.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val MAX_SPEED_TEST_BYTES = 64L * 1024L * 1024L
        private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
        private val PUBLIC_COMMANDS = setOf(MigrationCommands.HELLO, MigrationCommands.PAIR)
    }
}
