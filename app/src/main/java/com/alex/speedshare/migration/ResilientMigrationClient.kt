package com.alex.speedshare.migration

import android.os.Build
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

internal object ResilientMigrationClient {
    private val random = SecureRandom()

    fun newInboundToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun requestPair(local: MigrationPeer, peer: MigrationPeer, inboundToken: String): PairSessionResult {
        MigrationProtocol.connect(peer.host, peer.port).use { socket ->
            val output = BufferedOutputStream(socket.getOutputStream())
            val input = BufferedInputStream(socket.getInputStream())
            MigrationProtocol.writeJson(
                output,
                JSONObject()
                    .put("type", ResilientCommands.PAIR)
                    .put("requestId", UUID.randomUUID().toString())
                    .put("deviceId", local.deviceId)
                    .put("name", local.name)
                    .put("model", local.model)
                    .put("version", local.appVersion)
                    .put("servicePort", local.port)
                    .put("returnToken", inboundToken)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("abis", Build.SUPPORTED_ABIS.joinToString(","))
            )
            output.flush()
            val response = MigrationProtocol.readJson(input)
            val accepted = response.optBoolean("accepted", false)
            val resolvedPeer = peer.copy(
                deviceId = response.optString("deviceId", peer.deviceId),
                name = response.optString("name", peer.name),
                model = response.optString("model", peer.model),
                appVersion = response.optString("version", peer.appVersion),
                port = response.optInt("servicePort", peer.port),
                androidSdk = response.optInt("sdk", peer.androidSdk),
                supportedAbis = response.optString("abis")
                    .split(',')
                    .filter { it.isNotBlank() }
                    .ifEmpty { peer.supportedAbis }
            )
            return PairSessionResult(
                accepted = accepted,
                peer = resolvedPeer,
                outboundToken = response.optString("sessionToken")
            )
        }
    }

    fun sendRole(session: MigrationSession, role: MigrationRole) {
        command(session, JSONObject().put("type", ResilientCommands.ROLE).put("role", role.name))
    }

    fun storageInfo(session: MigrationSession): ReceiverStorageInfo {
        val response = command(session, JSONObject().put("type", ResilientCommands.STORAGE_INFO))
        ensureOk(response)
        return ReceiverStorageInfo(
            freeBytes = response.optLong("freeBytes").coerceAtLeast(0L),
            totalBytes = response.optLong("totalBytes").coerceAtLeast(0L)
        )
    }

    fun sendTransferPlan(session: MigrationSession, migrationId: String, totalBytes: Long, totalItems: Int): Long {
        val response = command(
            session,
            JSONObject()
                .put("type", ResilientCommands.TRANSFER_PLAN)
                .put("migrationId", migrationId)
                .put("totalBytes", totalBytes.coerceAtLeast(0L))
                .put("totalItems", totalItems.coerceAtLeast(0))
        )
        ensureOk(response)
        return response.optLong("freeBytes", -1L)
    }

    fun testSpeed(session: MigrationSession): SpeedTestResult {
        val latencySamples = mutableListOf<Long>()
        repeat(5) {
            val started = System.nanoTime()
            command(session, JSONObject().put("type", ResilientCommands.HELLO), public = true)
            latencySamples += (System.nanoTime() - started) / 1_000_000L
        }

        // A short single-stream warm-up estimates the link without making the overall test too long.
        val warmup = uploadSpeed(session, 8L * 1024L * 1024L)
        val streams = SPEED_TEST_STREAMS
        val perStreamSize = ((warmup.first * 7L) / streams)
            .coerceIn(32L * 1024L * 1024L, 96L * 1024L * 1024L)

        val upload = multiUploadSpeed(session, perStreamSize, streams)
        val download = multiDownloadSpeed(session, perStreamSize, streams)
        val allSamples = upload.second + download.second
        val stability = stabilityPercent(allSamples)
        return SpeedTestResult(
            latencyMs = latencySamples.sorted()[latencySamples.size / 2],
            uploadBytesPerSecond = upload.first,
            downloadBytesPerSecond = download.first,
            stabilityPercent = stability,
            singleStreamBytesPerSecond = warmup.first,
            streamCount = streams,
            peakBytesPerSecond = allSamples.maxOrNull() ?: max(upload.first, download.first)
        )
    }

    fun sendSpeedResult(session: MigrationSession, result: SpeedTestResult) {
        command(
            session,
            JSONObject()
                .put("type", ResilientCommands.SPEED_RESULT)
                .put("latencyMs", result.latencyMs)
                .put("uploadBps", result.uploadBytesPerSecond)
                .put("downloadBps", result.downloadBytesPerSecond)
                .put("stability", result.stabilityPercent)
                .put("singleStreamBps", result.singleStreamBytesPerSecond)
                .put("streamCount", result.streamCount)
                .put("peakBps", result.peakBytesPerSecond)
        )
    }

    fun sendProgress(session: MigrationSession, progress: MigrationProgress) {
        command(
            session,
            JSONObject()
                .put("type", ResilientCommands.PROGRESS_SYNC)
                .put("totalBytes", progress.totalBytes)
                .put("transferredBytes", progress.transferredBytes)
                .put("totalItems", progress.totalItems)
                .put("completedItems", progress.completedItems)
                .put("skippedItems", progress.skippedItems)
                .put("failedItems", progress.failedItems)
                .put("bytesPerSecond", progress.bytesPerSecond)
                .put("currentName", progress.currentName)
        )
    }

    fun sendReport(session: MigrationSession, report: MigrationReport) {
        command(
            session,
            JSONObject()
                .put("type", ResilientCommands.REPORT)
                .put("totalBytes", report.totalBytes)
                .put("transferredBytes", report.transferredBytes)
                .put("successCount", report.successCount)
                .put("skippedCount", report.skippedCount)
                .put("failedCount", report.failedCount)
                .put("notMigratedCount", report.notMigratedCount)
                .put("durationMs", report.durationMs)
                .put("averageBps", report.averageBytesPerSecond)
        )
    }

    fun sendFile(
        session: MigrationSession,
        migrationId: String,
        item: MigrationFileItem,
        hash: String,
        control: MigrationTransferControl,
        onBytes: (Long) -> Unit,
        parallelStreams: Int = 1
    ): SendFileResult {
        return if (item.size >= LARGE_FILE_THRESHOLD && parallelStreams >= 2) {
            sendFileChunked(
                session = session,
                migrationId = migrationId,
                item = item,
                hash = hash,
                control = control,
                onBytes = onBytes,
                parallelStreams = parallelStreams.coerceIn(2, MAX_CHUNK_STREAMS)
            )
        } else {
            sendFileSingle(session, migrationId, item, hash, control, onBytes)
        }
    }

    private fun sendFileSingle(
        session: MigrationSession,
        migrationId: String,
        item: MigrationFileItem,
        hash: String,
        control: MigrationTransferControl,
        onBytes: (Long) -> Unit
    ): SendFileResult {
        MigrationProtocol.connect(session.peer.host, session.peer.port, 15_000).use { socket ->
            socket.soTimeout = 0
            val output = BufferedOutputStream(socket.getOutputStream(), NETWORK_BUFFER_BYTES)
            val input = BufferedInputStream(socket.getInputStream(), NETWORK_BUFFER_BYTES)
            MigrationProtocol.writeJson(
                output,
                JSONObject()
                    .put("type", ResilientCommands.FILE_OFFER)
                    .put("sessionToken", session.outboundToken)
                    .put("migrationId", migrationId)
                    .put("path", item.relativePath)
                    .put("size", item.size)
                    .put("modifiedAt", item.modifiedAt)
                    .put("sha256", hash)
                    .put("kind", if (item.appPackageName != null) "app" else "file")
            )
            output.flush()
            val ready = MigrationProtocol.readJson(input)
            ensureOk(ready)
            if (ready.optString("action") == "skip") return SendFileResult(skipped = true, sentBytes = 0L)
            check(ready.optString("action") == "send") { ready.optString("error", "receiver_rejected") }
            val offset = ready.optLong("offset", 0L).coerceIn(0L, item.size)
            RandomAccessFile(item.file, "r").use { source ->
                source.seek(offset)
                var remaining = item.size - offset
                val buffer = ByteArray(IO_BLOCK_BYTES)
                while (remaining > 0L) {
                    control.awaitReady()
                    val read = source.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) error("source_ended_early")
                    output.write(buffer, 0, read)
                    remaining -= read
                    onBytes(read.toLong())
                }
            }
            output.flush()
            val result = MigrationProtocol.readJson(input)
            ensureOk(result)
            return SendFileResult(skipped = false, sentBytes = item.size - offset)
        }
    }

    private fun sendFileChunked(
        session: MigrationSession,
        migrationId: String,
        item: MigrationFileItem,
        hash: String,
        control: MigrationTransferControl,
        onBytes: (Long) -> Unit,
        parallelStreams: Int
    ): SendFileResult {
        val chunkSize = LARGE_FILE_CHUNK_BYTES
        val chunkCount = ((item.size + chunkSize - 1L) / chunkSize).toInt()
        val kind = if (item.appPackageName != null) "app" else "file"
        val plan = command(
            session,
            JSONObject()
                .put("type", ResilientCommands.FILE_CHUNK_PLAN)
                .put("migrationId", migrationId)
                .put("path", item.relativePath)
                .put("size", item.size)
                .put("modifiedAt", item.modifiedAt)
                .put("sha256", hash)
                .put("kind", kind)
                .put("chunkSize", chunkSize)
                .put("chunkCount", chunkCount)
        )
        ensureOk(plan)
        if (plan.optString("action") == "skip") return SendFileResult(skipped = true, sentBytes = 0L)

        val completed = plan.optString("completed")
            .split(',')
            .mapNotNull { it.toIntOrNull() }
            .filterTo(mutableSetOf()) { it in 0 until chunkCount }
        val pending = (0 until chunkCount).filterNot { it in completed }
        if (pending.isEmpty()) {
            finalizeChunkedFile(session, migrationId, item, hash, kind, chunkSize, chunkCount)
            return SendFileResult(skipped = false, sentBytes = 0L)
        }

        val sent = AtomicLong(0L)
        val pool = Executors.newFixedThreadPool(parallelStreams.coerceAtMost(pending.size))
        try {
            val futures = pending.map { index ->
                pool.submit {
                    control.awaitReady()
                    val offset = index * chunkSize
                    val length = min(chunkSize, item.size - offset)
                    sendChunk(
                        session = session,
                        migrationId = migrationId,
                        item = item,
                        hash = hash,
                        kind = kind,
                        chunkSize = chunkSize,
                        chunkCount = chunkCount,
                        chunkIndex = index,
                        offset = offset,
                        length = length,
                        control = control
                    ) { delta ->
                        sent.addAndGet(delta)
                        onBytes(delta)
                    }
                }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        finalizeChunkedFile(session, migrationId, item, hash, kind, chunkSize, chunkCount)
        return SendFileResult(skipped = false, sentBytes = sent.get())
    }

    private fun sendChunk(
        session: MigrationSession,
        migrationId: String,
        item: MigrationFileItem,
        hash: String,
        kind: String,
        chunkSize: Long,
        chunkCount: Int,
        chunkIndex: Int,
        offset: Long,
        length: Long,
        control: MigrationTransferControl,
        onBytes: (Long) -> Unit
    ) {
        MigrationProtocol.connect(session.peer.host, session.peer.port, 15_000).use { socket ->
            socket.soTimeout = 0
            val output = BufferedOutputStream(socket.getOutputStream(), NETWORK_BUFFER_BYTES)
            val input = BufferedInputStream(socket.getInputStream(), NETWORK_BUFFER_BYTES)
            MigrationProtocol.writeJson(
                output,
                JSONObject()
                    .put("type", ResilientCommands.FILE_CHUNK_DATA)
                    .put("sessionToken", session.outboundToken)
                    .put("migrationId", migrationId)
                    .put("path", item.relativePath)
                    .put("size", item.size)
                    .put("sha256", hash)
                    .put("kind", kind)
                    .put("chunkSize", chunkSize)
                    .put("chunkCount", chunkCount)
                    .put("chunkIndex", chunkIndex)
                    .put("offset", offset)
                    .put("length", length)
            )
            output.flush()
            ensureOk(MigrationProtocol.readJson(input))
            RandomAccessFile(item.file, "r").use { source ->
                source.seek(offset)
                var remaining = length
                val buffer = ByteArray(IO_BLOCK_BYTES)
                while (remaining > 0L) {
                    control.awaitReady()
                    val read = source.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) error("source_chunk_ended_early")
                    output.write(buffer, 0, read)
                    remaining -= read
                    onBytes(read.toLong())
                }
            }
            output.flush()
            ensureOk(MigrationProtocol.readJson(input))
        }
    }

    private fun finalizeChunkedFile(
        session: MigrationSession,
        migrationId: String,
        item: MigrationFileItem,
        hash: String,
        kind: String,
        chunkSize: Long,
        chunkCount: Int
    ) {
        val response = command(
            session,
            JSONObject()
                .put("type", ResilientCommands.FILE_CHUNK_FINALIZE)
                .put("migrationId", migrationId)
                .put("path", item.relativePath)
                .put("size", item.size)
                .put("modifiedAt", item.modifiedAt)
                .put("sha256", hash)
                .put("kind", kind)
                .put("chunkSize", chunkSize)
                .put("chunkCount", chunkCount)
        )
        ensureOk(response)
    }

    private fun multiUploadSpeed(session: MigrationSession, size: Long, streams: Int): Pair<Long, List<Long>> {
        val pool = Executors.newFixedThreadPool(streams)
        val started = System.nanoTime()
        return try {
            val futures = List(streams) { pool.submit<Pair<Long, List<Long>>> { uploadSpeed(session, size) } }
            val results = futures.map { it.get() }
            val elapsed = max(1L, System.nanoTime() - started)
            bps(size * streams, elapsed) to results.flatMap { it.second }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun multiDownloadSpeed(session: MigrationSession, size: Long, streams: Int): Pair<Long, List<Long>> {
        val pool = Executors.newFixedThreadPool(streams)
        val started = System.nanoTime()
        return try {
            val futures = List(streams) { pool.submit<Pair<Long, List<Long>>> { downloadSpeed(session, size) } }
            val results = futures.map { it.get() }
            val elapsed = max(1L, System.nanoTime() - started)
            bps(size * streams, elapsed) to results.flatMap { it.second }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun uploadSpeed(session: MigrationSession, size: Long): Pair<Long, List<Long>> {
        val samples = mutableListOf<Long>()
        MigrationProtocol.connect(session.peer.host, session.peer.port).use { socket ->
            val output = BufferedOutputStream(socket.getOutputStream(), NETWORK_BUFFER_BYTES)
            val input = BufferedInputStream(socket.getInputStream(), NETWORK_BUFFER_BYTES)
            MigrationProtocol.writeJson(
                output,
                JSONObject()
                    .put("type", ResilientCommands.SPEED_UPLOAD)
                    .put("sessionToken", session.outboundToken)
                    .put("size", size)
            )
            val buffer = ByteArray(IO_BLOCK_BYTES)
            var remaining = size
            val started = System.nanoTime()
            var sampleStart = started
            var sampleBytes = 0L
            while (remaining > 0L) {
                val count = min(buffer.size.toLong(), remaining).toInt()
                output.write(buffer, 0, count)
                remaining -= count
                sampleBytes += count
                val now = System.nanoTime()
                if (now - sampleStart >= SPEED_SAMPLE_NANOS) {
                    samples += bps(sampleBytes, now - sampleStart)
                    sampleStart = now
                    sampleBytes = 0L
                }
            }
            output.flush()
            val elapsed = max(1L, System.nanoTime() - started)
            ensureOk(MigrationProtocol.readJson(input))
            return bps(size, elapsed) to samples
        }
    }

    private fun downloadSpeed(session: MigrationSession, size: Long): Pair<Long, List<Long>> {
        val samples = mutableListOf<Long>()
        MigrationProtocol.connect(session.peer.host, session.peer.port).use { socket ->
            val output = BufferedOutputStream(socket.getOutputStream())
            val input = BufferedInputStream(socket.getInputStream(), NETWORK_BUFFER_BYTES)
            MigrationProtocol.writeJson(
                output,
                JSONObject()
                    .put("type", ResilientCommands.SPEED_DOWNLOAD)
                    .put("sessionToken", session.outboundToken)
                    .put("size", size)
            )
            output.flush()
            val header = MigrationProtocol.readJson(input)
            ensureOk(header)
            var remaining = header.optLong("size", size)
            val buffer = ByteArray(IO_BLOCK_BYTES)
            val started = System.nanoTime()
            var sampleStart = started
            var sampleBytes = 0L
            while (remaining > 0L) {
                val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                if (read < 0) error("speed_download_ended")
                remaining -= read
                sampleBytes += read
                val now = System.nanoTime()
                if (now - sampleStart >= SPEED_SAMPLE_NANOS) {
                    samples += bps(sampleBytes, now - sampleStart)
                    sampleStart = now
                    sampleBytes = 0L
                }
            }
            return bps(size, max(1L, System.nanoTime() - started)) to samples
        }
    }

    private fun command(session: MigrationSession, json: JSONObject, public: Boolean = false): JSONObject {
        if (!public) json.put("sessionToken", session.outboundToken)
        MigrationProtocol.connect(session.peer.host, session.peer.port).use { socket ->
            val output = BufferedOutputStream(socket.getOutputStream())
            val input = BufferedInputStream(socket.getInputStream())
            MigrationProtocol.writeJson(output, json)
            output.flush()
            return MigrationProtocol.readJson(input)
        }
    }

    private fun ensureOk(json: JSONObject) {
        check(json.optBoolean("ok", false)) { json.optString("error", "migration_command_failed") }
    }

    private fun bps(bytes: Long, elapsedNanos: Long): Long =
        (bytes * 1_000_000_000.0 / max(1L, elapsedNanos)).toLong().coerceAtLeast(0L)

    private fun stabilityPercent(samples: List<Long>): Int {
        val values = samples.filter { it > 0L }
        if (values.size < 2) return 100
        val average = values.average()
        if (average <= 0.0) return 100
        val deviation = values.sumOf { kotlin.math.abs(it - average) } / values.size
        return (100.0 - deviation / average * 100.0).toInt().coerceIn(0, 100)
    }

    private const val SPEED_TEST_STREAMS = 4
    private const val MAX_CHUNK_STREAMS = 4
    private const val NETWORK_BUFFER_BYTES = 2 * 1024 * 1024
    private const val IO_BLOCK_BYTES = 1024 * 1024
    private const val SPEED_SAMPLE_NANOS = 500_000_000L
    private const val LARGE_FILE_THRESHOLD = 512L * 1024L * 1024L
    private const val LARGE_FILE_CHUNK_BYTES = 64L * 1024L * 1024L
}
