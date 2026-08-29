package com.alex.speedshare.migration

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import kotlin.math.max
import kotlin.math.min

internal object ResilientCommands {
    const val HELLO = "v2_hello"
    const val PAIR = "v2_pair"
    const val ROLE = "v2_role"
    const val SPEED_UPLOAD = "v2_speed_upload"
    const val SPEED_DOWNLOAD = "v2_speed_download"
    const val SPEED_RESULT = "v2_speed_result"
    const val STORAGE_INFO = "v2_storage_info"
    const val APP_VERSIONS = "v2_app_versions"
    const val TRANSFER_PLAN = "v2_transfer_plan"
    const val FILE_OFFER = "v2_file_offer"
    const val FILE_CHUNK_PLAN = "v2_file_chunk_plan"
    const val FILE_CHUNK_DATA = "v2_file_chunk_data"
    const val FILE_CHUNK_FINALIZE = "v2_file_chunk_finalize"
    const val CLEANUP_TEMP = "v2_cleanup_temp"
    const val PROGRESS_SYNC = "v2_progress_sync"
    const val REPORT = "v2_report"
}

internal class ResilientMigrationPeerServer(
    private val context: Context,
    private val localDeviceId: String,
    private val localDeviceName: String,
    private val appVersion: String,
    private val onPairRequest: (IncomingPairRequest) -> Unit,
    private val onPeerConnected: (MigrationPeer, String) -> Unit,
    private val onRole: (MigrationRole) -> Unit,
    private val onSpeedResult: (SpeedTestResult) -> Unit,
    private val onTransferPlan: (String, Long, Int) -> Unit,
    private val onProgressSync: (MigrationProgress) -> Unit,
    private val onReport: (MigrationReport) -> Unit
) {
    private data class PendingPair(
        val decision: CompletableFuture<Boolean>,
        val peer: MigrationPeer,
        val sharedToken: String
    )

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "SpeedShare-ResilientPeer").apply { isDaemon = true }
    }
    private val pendingPairs = ConcurrentHashMap<String, PendingPair>()
    private val acceptedTokens = ConcurrentHashMap.newKeySet<String>()
    private val duplicatePolicies = ConcurrentHashMap<String, MigrationDuplicatePolicy>()
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
        pendingPairs[requestId]?.decision?.complete(accepted)
    }

    fun acceptInboundToken(token: String) {
        if (isValidToken(token)) acceptedTokens.add(token)
    }

    fun clearSessions() {
        acceptedTokens.forEach(MigrationCryptoSessionRegistry::remove)
        acceptedTokens.clear()
        duplicatePolicies.clear()
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        pendingPairs.values.forEach { it.decision.complete(false) }
        pendingPairs.clear()
        acceptedTokens.forEach(MigrationCryptoSessionRegistry::remove)
        acceptedTokens.clear()
        duplicatePolicies.clear()
        executor.shutdownNow()
    }

    private fun handle(raw: Socket) {
        raw.use { socket ->
            try {
                socket.tcpNoDelay = true
                socket.sendBufferSize = NETWORK_BUFFER_BYTES
                socket.receiveBufferSize = NETWORK_BUFFER_BYTES
                socket.soTimeout = 120_000
                val input = BufferedInputStream(socket.getInputStream(), NETWORK_BUFFER_BYTES)
                val output = BufferedOutputStream(socket.getOutputStream(), NETWORK_BUFFER_BYTES)
                val request = MigrationProtocol.readJson(input)
                val type = request.optString("type")
                if (type !in PUBLIC_COMMANDS && request.optString("sessionToken") !in acceptedTokens) {
                    sendError(output, "session_required")
                    return
                }
                when (type) {
                    ResilientCommands.HELLO -> sendOk(output, JSONObject().put("deviceId", localDeviceId))
                    ResilientCommands.PAIR -> handlePair(socket, output, request)
                    ResilientCommands.ROLE -> {
                        val role = runCatching { MigrationRole.valueOf(request.optString("role")) }
                            .getOrDefault(MigrationRole.UNSET)
                        onRole(role)
                        sendOk(output)
                    }
                    ResilientCommands.SPEED_UPLOAD -> handleSpeedUpload(input, output, request)
                    ResilientCommands.SPEED_DOWNLOAD -> handleSpeedDownload(output, request)
                    ResilientCommands.SPEED_RESULT -> {
                        onSpeedResult(speedFromJson(request))
                        sendOk(output)
                    }
                    ResilientCommands.STORAGE_INFO -> handleStorageInfo(output)
                    ResilientCommands.APP_VERSIONS -> handleAppVersions(output, request)
                    ResilientCommands.TRANSFER_PLAN -> handleTransferPlan(output, request)
                    ResilientCommands.FILE_OFFER -> handleFileOffer(socket, input, output, request)
                    ResilientCommands.FILE_CHUNK_PLAN -> handleChunkPlan(output, request)
                    ResilientCommands.FILE_CHUNK_DATA -> handleChunkData(socket, input, output, request)
                    ResilientCommands.FILE_CHUNK_FINALIZE -> handleChunkFinalize(output, request)
                    ResilientCommands.CLEANUP_TEMP -> handleCleanupTemporary(output, request)
                    ResilientCommands.PROGRESS_SYNC -> {
                        onProgressSync(progressFromJson(request))
                        sendOk(output)
                    }
                    ResilientCommands.REPORT -> {
                        onReport(reportFromJsonV2(request))
                        sendOk(output)
                    }
                    else -> sendError(output, "unknown_command")
                }
            } catch (_: Throwable) {
                // Resume files remain in Download/SpeedShareWeb/Temporary for the next attempt.
            }
        }
    }

    private fun handlePair(socket: Socket, output: BufferedOutputStream, request: JSONObject) {
        val requestId = request.optString("requestId").ifBlank { UUID.randomUUID().toString() }
        val sharedToken = request.optString("returnToken")
        val peer = MigrationPeer(
            deviceId = request.optString("deviceId"),
            name = request.optString("name").ifBlank { "SpeedShare" },
            host = socket.inetAddress.hostAddress.orEmpty(),
            port = request.optInt("servicePort"),
            model = request.optString("model"),
            appVersion = request.optString("version"),
            androidSdk = request.optInt("sdk", 0),
            supportedAbis = request.optString("abis").split(',').filter { it.isNotBlank() }
        )
        if (peer.deviceId.isBlank() || peer.port !in 1..65535 || !isValidToken(sharedToken)) {
            MigrationProtocol.writeJson(
                output,
                JSONObject().put("accepted", false).put("error", "invalid_pair")
            )
            output.flush()
            return
        }

        val requestedCrypto = request.optInt("cryptoVersion", 0) == CRYPTO_VERSION
        val peerPublicEncoded = if (requestedCrypto) {
            runCatching { Base64.getDecoder().decode(request.getString("cryptoPublicKey")) }.getOrNull()
        } else {
            null
        }
        if (requestedCrypto && peerPublicEncoded == null) {
            MigrationProtocol.writeJson(
                output,
                JSONObject().put("accepted", false).put("error", "invalid_crypto_public_key")
            )
            output.flush()
            return
        }

        val pending = PendingPair(CompletableFuture(), peer, sharedToken)
        pendingPairs[requestId] = pending
        onPairRequest(IncomingPairRequest(requestId, peer))
        val accepted = runCatching { pending.decision.get(60, TimeUnit.SECONDS) }.getOrDefault(false)
        pendingPairs.remove(requestId)

        var responsePublicKey = ""
        var securityCode = ""
        if (accepted) {
            acceptedTokens.add(sharedToken)
            if (peerPublicEncoded != null) {
                val serverKeyPair = MigrationCrypto.generateEphemeralKeyPair()
                val serverPublicEncoded = MigrationCrypto.encodePublicKey(serverKeyPair.public)
                val cryptoKey = runCatching {
                    val transcript = MigrationCrypto.transcript(
                        localDeviceId,
                        peer.deviceId,
                        serverPublicEncoded,
                        peerPublicEncoded
                    )
                    MigrationCrypto.deriveSessionKey(
                        serverKeyPair.private,
                        MigrationCrypto.decodePublicKey(peerPublicEncoded),
                        transcript
                    )
                }.getOrNull()
                if (cryptoKey != null) {
                    val info = MigrationCryptoSessionRegistry.register(sharedToken, peer.deviceId, cryptoKey)
                    responsePublicKey = Base64.getEncoder().encodeToString(serverPublicEncoded)
                    securityCode = info.securityCode
                }
            }
            onPeerConnected(peer, sharedToken)
        }

        MigrationProtocol.writeJson(
            output,
            JSONObject()
                .put("accepted", accepted)
                .put("sessionToken", if (accepted) sharedToken else "")
                .put("deviceId", localDeviceId)
                .put("name", localDeviceName)
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                .put("version", appVersion)
                .put("servicePort", port)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("abis", Build.SUPPORTED_ABIS.joinToString(","))
                .put("cryptoVersion", if (securityCode.isNotBlank()) CRYPTO_VERSION else 0)
                .put("cryptoPublicKey", responsePublicKey)
                .put("securityCode", securityCode)
        )
        output.flush()
    }

    private fun handleStorageInfo(output: BufferedOutputStream) {
        val root = Environment.getExternalStorageDirectory()
        sendOk(
            output,
            JSONObject()
                .put("freeBytes", root.usableSpace.coerceAtLeast(0L))
                .put("totalBytes", root.totalSpace.coerceAtLeast(0L))
        )
    }

    private fun handleAppVersions(output: BufferedOutputStream, request: JSONObject) {
        val requested = request.optJSONArray("packages")
            ?: return sendError(output, "invalid_app_version_query")
        val versions = JSONObject()
        val count = min(requested.length(), MAX_APP_VERSION_QUERY)
        for (index in 0 until count) {
            val packageName = requested.optString(index).trim()
            if (!PACKAGE_NAME_REGEX.matches(packageName)) continue
            val info = runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(0L)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(packageName, 0)
                }
            }.getOrNull() ?: continue
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            versions.put(packageName, versionCode)
        }
        sendOk(output, JSONObject().put("versions", versions))
    }

    private fun handleTransferPlan(output: BufferedOutputStream, request: JSONObject) {
        if (!hasStorageAccess()) return sendError(output, "receiver_storage_permission_required")
        val migrationId = normalizeId(request.optString("migrationId"))
            ?: return sendError(output, "invalid_transfer_plan")
        val totalBytes = request.optLong("totalBytes", -1L)
        val totalItems = request.optInt("totalItems", -1)
        val root = Environment.getExternalStorageDirectory()
        val free = root.usableSpace.coerceAtLeast(0L)
        if (totalBytes < 0L || totalItems < 0) {
            return sendError(output, "invalid_transfer_plan")
        }
        if (free < totalBytes + STORAGE_RESERVE_BYTES) {
            return sendError(output, "insufficient_space", JSONObject().put("freeBytes", free))
        }
        val policy = runCatching {
            MigrationDuplicatePolicy.valueOf(request.optString("duplicatePolicy"))
        }.getOrDefault(MigrationDuplicatePolicy.SKIP_IDENTICAL_KEEP_CONFLICT)
        duplicatePolicies[migrationId] = policy
        onTransferPlan(migrationId, totalBytes, totalItems)
        sendOk(output, JSONObject().put("freeBytes", free))
    }

    private fun handleSpeedUpload(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        request: JSONObject
    ) {
        val size = request.optLong("size").coerceIn(1L, MAX_SPEED_TEST_BYTES)
        val buffer = ByteArray(IO_BLOCK_BYTES)
        var remaining = size
        val started = System.nanoTime()
        while (remaining > 0) {
            val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
            if (read < 0) error("speed_upload_ended")
            remaining -= read
        }
        val elapsed = max(1L, System.nanoTime() - started)
        sendOk(output, JSONObject().put("elapsedNanos", elapsed).put("size", size))
    }

    private fun handleSpeedDownload(output: BufferedOutputStream, request: JSONObject) {
        val size = request.optLong("size").coerceIn(1L, MAX_SPEED_TEST_BYTES)
        MigrationProtocol.writeJson(output, JSONObject().put("ok", true).put("size", size))
        val buffer = ByteArray(IO_BLOCK_BYTES)
        var remaining = size
        while (remaining > 0) {
            val count = min(buffer.size.toLong(), remaining).toInt()
            output.write(buffer, 0, count)
            remaining -= count
        }
        output.flush()
    }

    private fun handleFileOffer(
        socket: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        request: JSONObject
    ) {
        if (!hasStorageAccess()) return sendFileFailure(output, "receiver_storage_permission_required")
        val migrationId = normalizeId(request.optString("migrationId"))
            ?: return sendFileFailure(output, "invalid_migration_id")
        val relativePath = normalizeRelativePath(request.optString("path"))
            ?: return sendFileFailure(output, "invalid_path")
        val size = request.optLong("size", -1L)
        val modifiedAt = request.optLong("modifiedAt", 0L)
        val sourceHash = request.optString("sha256")
        val kind = request.optString("kind", "file")
        val cryptoKey = encryptedSessionKey(request, output) ?: if (request.optBoolean("encrypted", false)) return else null
        if (size < 0L || !sourceHash.matches(SHA256_REGEX)) {
            return sendFileFailure(output, "invalid_file")
        }
        val requestedTarget = resolveTarget(migrationId, relativePath, kind)
            ?: return sendFileFailure(output, "invalid_target")
        requestedTarget.parentFile?.mkdirs()
        val policy = duplicatePolicies[migrationId]
            ?: MigrationDuplicatePolicy.SKIP_IDENTICAL_KEEP_CONFLICT

        val exactTarget = requestedTarget.isFile &&
            requestedTarget.length() == size &&
            runCatching { MigrationHashCache.sha256(requestedTarget) }.getOrNull() == sourceHash
        if (exactTarget && policy != MigrationDuplicatePolicy.KEEP_BOTH) {
            MigrationStorageLayout.singlePartFile(migrationId, relativePath, sourceHash)?.let { stale ->
                MigrationHashCache.invalidate(stale)
                stale.delete()
                MigrationStorageLayout.pruneEmptyTemporaryParents(stale, migrationId)
            }
            MigrationProtocol.writeJson(
                output,
                JSONObject().put("ok", true).put("action", "skip").put("offset", size)
            )
            output.flush()
            return
        }

        val finalTarget = when (policy) {
            MigrationDuplicatePolicy.OVERWRITE -> requestedTarget
            MigrationDuplicatePolicy.SKIP_IDENTICAL_KEEP_CONFLICT,
            MigrationDuplicatePolicy.KEEP_BOTH -> if (requestedTarget.exists()) conflictTarget(requestedTarget) else requestedTarget
        }
        val part = MigrationStorageLayout.singlePartFile(migrationId, relativePath, sourceHash)
            ?: return sendFileFailure(output, "invalid_temporary_path")
        if (part.length() > size) part.delete()
        part.parentFile?.mkdirs()
        val offset = part.length().coerceIn(0L, size)
        MigrationProtocol.writeJson(
            output,
            JSONObject().put("ok", true).put("action", "send").put("offset", offset)
        )
        output.flush()

        socket.soTimeout = 0
        RandomAccessFile(part, "rw").use { destination ->
            destination.seek(offset)
            var remaining = size - offset
            var absoluteOffset = offset
            val buffer = ByteArray(IO_BLOCK_BYTES)
            while (remaining > 0) {
                if (cryptoKey != null) {
                    val plaintext = MigrationEncryptedTransport.readFrame(
                        input,
                        cryptoKey,
                        migrationId,
                        relativePath,
                        absoluteOffset,
                        min(MigrationEncryptedTransport.FRAME_PLAINTEXT_BYTES.toLong(), remaining).toInt()
                    )
                    destination.write(plaintext)
                    remaining -= plaintext.size
                    absoluteOffset += plaintext.size
                } else {
                    val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) error("transfer_ended")
                    destination.write(buffer, 0, read)
                    remaining -= read
                    absoluteOffset += read
                }
            }
            destination.fd.sync()
        }
        if (part.length() != size) return sendFileFailure(output, "size_mismatch")
        if (MigrationHashCache.sha256(part) != sourceHash) {
            MigrationHashCache.invalidate(part)
            part.delete()
            MigrationStorageLayout.pruneEmptyTemporaryParents(part, migrationId)
            return sendFileFailure(output, "hash_mismatch")
        }
        if (policy == MigrationDuplicatePolicy.OVERWRITE && finalTarget.exists()) {
            MigrationHashCache.invalidate(finalTarget)
            if (!finalTarget.delete()) return sendFileFailure(output, "overwrite_delete_failed")
        }
        if (!part.renameTo(finalTarget)) {
            part.copyTo(finalTarget, overwrite = policy == MigrationDuplicatePolicy.OVERWRITE)
            part.delete()
        }
        MigrationHashCache.invalidate(part)
        MigrationHashCache.invalidate(finalTarget)
        MigrationStorageLayout.pruneEmptyTemporaryParents(part, migrationId)
        if (modifiedAt > 0L) finalTarget.setLastModified(modifiedAt)
        MigrationProtocol.writeJson(
            output,
            JSONObject().put("ok", true).put("action", "complete").put("path", relativePath)
        )
        output.flush()
    }

    private fun handleChunkPlan(output: BufferedOutputStream, request: JSONObject) {
        if (!hasStorageAccess()) return sendFileFailure(output, "receiver_storage_permission_required")
        applyDuplicatePolicy(request)
        runCatching { ChunkedFileReceiver.plan(request) }
            .onSuccess {
                MigrationProtocol.writeJson(output, it)
                output.flush()
            }
            .onFailure { sendFileFailure(output, it.message ?: "chunk_plan_failed") }
    }

    private fun handleChunkData(
        socket: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        request: JSONObject
    ) {
        if (!hasStorageAccess()) return sendFileFailure(output, "receiver_storage_permission_required")
        val migrationId = normalizeId(request.optString("migrationId"))
            ?: return sendFileFailure(output, "invalid_migration_id")
        val relativePath = normalizeRelativePath(request.optString("path"))
            ?: return sendFileFailure(output, "invalid_path")
        val cryptoKey = encryptedSessionKey(request, output) ?: if (request.optBoolean("encrypted", false)) return else null
        applyDuplicatePolicy(request)
        val plan = runCatching { ChunkedFileReceiver.prepareChunk(request) }
            .getOrElse { return sendFileFailure(output, it.message ?: "chunk_prepare_failed") }
        if (plan.alreadyComplete) {
            sendOk(output, JSONObject().put("action", "skip"))
            return
        }
        sendOk(output, JSONObject().put("action", "send"))
        socket.soTimeout = 0
        runCatching {
            ChunkedFileReceiver.writeChunk(plan) { destination ->
                var remaining = plan.length
                var absoluteOffset = plan.offset
                val buffer = ByteArray(IO_BLOCK_BYTES)
                while (remaining > 0L) {
                    if (cryptoKey != null) {
                        val plaintext = MigrationEncryptedTransport.readFrame(
                            input,
                            cryptoKey,
                            migrationId,
                            relativePath,
                            absoluteOffset,
                            min(MigrationEncryptedTransport.FRAME_PLAINTEXT_BYTES.toLong(), remaining).toInt()
                        )
                        destination.write(plaintext)
                        remaining -= plaintext.size
                        absoluteOffset += plaintext.size
                    } else {
                        val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) error("chunk_ended_early")
                        destination.write(buffer, 0, read)
                        remaining -= read
                        absoluteOffset += read
                    }
                }
            }
        }.onSuccess {
            sendOk(output, JSONObject().put("chunkIndex", plan.chunkIndex))
        }.onFailure {
            sendFileFailure(output, it.message ?: "chunk_write_failed")
        }
    }

    private fun handleChunkFinalize(output: BufferedOutputStream, request: JSONObject) {
        if (!hasStorageAccess()) return sendFileFailure(output, "receiver_storage_permission_required")
        applyDuplicatePolicy(request)
        runCatching { ChunkedFileReceiver.finalize(request) }
            .onSuccess {
                MigrationProtocol.writeJson(output, it)
                output.flush()
            }
            .onFailure { sendFileFailure(output, it.message ?: "chunk_finalize_failed") }
    }

    private fun handleCleanupTemporary(output: BufferedOutputStream, request: JSONObject) {
        val migrationId = normalizeId(request.optString("migrationId"))
            ?: return sendError(output, "invalid_migration_id")
        val removed = MigrationStorageLayout.cleanupTemporary(migrationId)
        duplicatePolicies.remove(migrationId)
        sendOk(output, JSONObject().put("removed", removed))
    }

    private fun encryptedSessionKey(request: JSONObject, output: BufferedOutputStream): SecretKey? {
        if (!request.optBoolean("encrypted", false)) return null
        val token = request.optString("sessionToken")
        val key = MigrationCryptoSessionRegistry.key(token)
        if (key == null) sendFileFailure(output, "encrypted_session_required")
        return key
    }

    private fun applyDuplicatePolicy(request: JSONObject) {
        val migrationId = normalizeId(request.optString("migrationId"))
        val policy = migrationId?.let { duplicatePolicies[it] }
            ?: MigrationDuplicatePolicy.SKIP_IDENTICAL_KEEP_CONFLICT
        request.put("duplicatePolicy", policy.name)
    }

    private fun resolveTarget(migrationId: String, relativePath: String, kind: String): File? {
        val base = if (kind == "app") {
            MigrationStorageLayout.appsMigrationDir(migrationId)?.canonicalFile ?: return null
        } else {
            Environment.getExternalStorageDirectory().canonicalFile
        }
        val target = File(base, relativePath).canonicalFile
        return target.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    private fun conflictTarget(file: File): File {
        val dot = file.name.lastIndexOf('.')
        val stem = if (dot > 0) file.name.substring(0, dot) else file.name
        val ext = if (dot > 0) file.name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = File(file.parentFile, "$stem ($index)$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun normalizeId(value: String): String? = value.takeIf {
        it.length in 8..80 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' }
    }

    private fun isValidToken(token: String): Boolean =
        token.length == 64 && token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun hasStorageAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun sendOk(output: BufferedOutputStream, extra: JSONObject = JSONObject()) {
        extra.put("ok", true)
        MigrationProtocol.writeJson(output, extra)
        output.flush()
    }

    private fun sendError(output: BufferedOutputStream, error: String, extra: JSONObject = JSONObject()) {
        extra.put("ok", false).put("error", error)
        MigrationProtocol.writeJson(output, extra)
        output.flush()
    }

    private fun sendFileFailure(output: BufferedOutputStream, error: String) = sendError(output, error)

    companion object {
        private val PUBLIC_COMMANDS = setOf(ResilientCommands.HELLO, ResilientCommands.PAIR)
        private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
        private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
        private const val CRYPTO_VERSION = 1
        private const val NETWORK_BUFFER_BYTES = 2 * 1024 * 1024
        private const val IO_BLOCK_BYTES = 1024 * 1024
        private const val MAX_SPEED_TEST_BYTES = 96L * 1024L * 1024L
        private const val MAX_APP_VERSION_QUERY = 1000
        private const val STORAGE_RESERVE_BYTES = 256L * 1024L * 1024L
    }
}

private fun speedFromJson(json: JSONObject) = SpeedTestResult(
    latencyMs = json.optLong("latencyMs"),
    uploadBytesPerSecond = json.optLong("uploadBps"),
    downloadBytesPerSecond = json.optLong("downloadBps"),
    stabilityPercent = json.optInt("stability", 100).coerceIn(0, 100),
    singleStreamBytesPerSecond = json.optLong("singleStreamBps"),
    streamCount = json.optInt("streamCount", 1).coerceAtLeast(1),
    peakBytesPerSecond = json.optLong("peakBps")
)

private fun progressFromJson(json: JSONObject) = MigrationProgress(
    totalBytes = json.optLong("totalBytes"),
    transferredBytes = json.optLong("transferredBytes"),
    totalItems = json.optInt("totalItems"),
    completedItems = json.optInt("completedItems"),
    skippedItems = json.optInt("skippedItems"),
    failedItems = json.optInt("failedItems"),
    bytesPerSecond = json.optLong("bytesPerSecond"),
    currentName = json.optString("currentName")
)

private fun reportFromJsonV2(json: JSONObject) = MigrationReport(
    totalBytes = json.optLong("totalBytes"),
    transferredBytes = json.optLong("transferredBytes"),
    successCount = json.optInt("successCount"),
    skippedCount = json.optInt("skippedCount"),
    failedCount = json.optInt("failedCount"),
    durationMs = json.optLong("durationMs"),
    averageBytesPerSecond = json.optLong("averageBps"),
    notMigratedCount = json.optInt("notMigratedCount")
)
