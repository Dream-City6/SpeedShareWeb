package com.alex.speedshare.migration

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

internal class MigrationPeerServer(
    private val context: Context,
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
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "SpeedShare-MigrationPeer").apply { isDaemon = true }
    }
    private val pairDecisions = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    val port: Int get() = serverSocket?.localPort ?: 0

    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket(0)
        socket.reuseAddress = true
        serverSocket = socket
        running = true
        pool.execute {
            while (running) {
                try {
                    val client = socket.accept()
                    pool.execute { handle(client) }
                } catch (_: Throwable) {
                    if (!running) break
                }
            }
        }
    }

    fun respondPair(requestId: String, accepted: Boolean) {
        pairDecisions.remove(requestId)?.complete(accepted)
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        pairDecisions.values.forEach { it.complete(false) }
        pairDecisions.clear()
        pool.shutdownNow()
    }

    private fun handle(raw: Socket) {
        raw.use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = 120_000
            val input = BufferedInputStream(socket.getInputStream(), 1024 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 1024 * 1024)
            val request = MigrationProtocol.readJson(input)
            when (request.optString("type")) {
                MigrationCommands.HELLO -> {
                    MigrationProtocol.writeJson(output, JSONObject().put("ok", true).put("deviceId", localDeviceId))
                    output.flush()
                }
                MigrationCommands.PAIR -> handlePair(socket, output, request)
                MigrationCommands.ROLE -> {
                    val role = runCatching { MigrationRole.valueOf(request.optString("role")) }.getOrDefault(MigrationRole.UNSET)
                    onRole(role)
                    MigrationProtocol.writeJson(output, JSONObject().put("ok", true))
                    output.flush()
                }
                MigrationCommands.SPEED_UPLOAD -> handleSpeedUpload(input, output, request)
                MigrationCommands.SPEED_DOWNLOAD -> handleSpeedDownload(output, request)
                MigrationCommands.SPEED_RESULT -> {
                    val result = speedResultFromJson(request)
                    onSpeedResult(result)
                    MigrationProtocol.writeJson(output, JSONObject().put("ok", true))
                    output.flush()
                }
                MigrationCommands.FILE_OFFER -> handleFileOffer(input, output, request)
                MigrationCommands.REPORT -> {
                    val report = reportFromJson(request)
                    onReport(report)
                    MigrationProtocol.writeJson(output, JSONObject().put("ok", true))
                    output.flush()
                }
                else -> {
                    MigrationProtocol.writeJson(output, JSONObject().put("ok", false).put("error", "unknown_command"))
                    output.flush()
                }
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
        if (peer.deviceId.isBlank() || peer.port <= 0) {
            MigrationProtocol.writeJson(output, JSONObject().put("type", MigrationCommands.PAIR_RESULT).put("accepted", false))
            output.flush()
            return
        }
        val decision = CompletableFuture<Boolean>()
        pairDecisions[requestId] = decision
        onPairRequest(IncomingPairRequest(requestId, peer))
        val accepted = runCatching { decision.get(60, TimeUnit.SECONDS) }.getOrDefault(false)
        pairDecisions.remove(requestId)
        if (accepted) onPeerConnected(peer)
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

    private fun handleSpeedUpload(input: BufferedInputStream, output: BufferedOutputStream, request: JSONObject) {
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
        val zeros = ByteArray(1024 * 1024)
        var remaining = size
        while (remaining > 0L) {
            val count = min(zeros.size.toLong(), remaining).toInt()
            output.write(zeros, 0, count)
            remaining -= count
        }
        output.flush()
    }

    private fun handleFileOffer(input: BufferedInputStream, output: BufferedOutputStream, request: JSONObject) {
        val relativePath = normalizeRelativePath(request.optString("path"))
            ?: return sendFileFailure(output, "invalid_path")
        val size = request.optLong("size", -1L)
        if (size < 0L) return sendFileFailure(output, "invalid_size")
        val modifiedAt = request.optLong("modifiedAt", 0L)
        val sourceHash = request.optString("sha256")
        if (sourceHash.length != 64) return sendFileFailure(output, "invalid_hash")
        val kind = request.optString("kind", "file")
        val requestedTarget = targetFor(relativePath, kind) ?: return sendFileFailure(output, "invalid_target")
        requestedTarget.parentFile?.mkdirs()

        if (requestedTarget.isFile && requestedTarget.length() == size) {
            val existingHash = runCatching { sha256(requestedTarget) }.getOrNull()
            if (existingHash == sourceHash) {
                MigrationProtocol.writeJson(
                    output,
                    JSONObject().put("type", MigrationCommands.FILE_READY).put("action", "skip").put("offset", size)
                )
                output.flush()
                return
            }
        }

        val finalTarget = if (requestedTarget.exists()) autoRename(requestedTarget) else requestedTarget
        val part = File(finalTarget.parentFile, ".${finalTarget.name}.${sourceHash.take(12)}.speedshare.part")
        var offset = part.length().coerceIn(0L, size)
        if (part.length() > size) {
            part.delete()
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

        RandomAccessFile(part, "rw").use { random ->
            random.seek(offset)
            var remaining = size - offset
            val buffer = ByteArray(1024 * 1024)
            while (remaining > 0L) {
                val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                if (read < 0) error("Transfer ended early")
                random.write(buffer, 0, read)
                remaining -= read
                onReceiveBytes(read.toLong(), finalTarget.name)
            }
            random.fd.sync()
        }

        if (part.length() != size) return sendFileFailure(output, "size_mismatch")
        val receivedHash = sha256(part)
        if (receivedHash != sourceHash) {
            part.delete()
            return sendFileFailure(output, "hash_mismatch")
        }
        if (!part.renameTo(finalTarget)) {
            part.copyTo(finalTarget, overwrite = false)
            part.delete()
        }
        if (modifiedAt > 0L) finalTarget.setLastModified(modifiedAt)
        MigrationProtocol.writeJson(
            output,
            JSONObject().put("type", MigrationCommands.FILE_RESULT).put("ok", true).put("path", relativePath)
        )
        output.flush()
    }

    private fun sendFileFailure(output: BufferedOutputStream, error: String) {
        MigrationProtocol.writeJson(
            output,
            JSONObject().put("type", MigrationCommands.FILE_RESULT).put("ok", false).put("error", error)
        )
        output.flush()
    }

    private fun targetFor(relativePath: String, kind: String): File? {
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val base = if (kind == "app") {
            File(root, "Download/SpeedShare/Apps").apply { mkdirs() }.canonicalFile
        } else root
        val target = File(base, relativePath).canonicalFile
        val allowedPrefix = base.path + File.separator
        return target.takeIf { it.path.startsWith(allowedPrefix) }
    }

    private fun autoRename(file: File): File {
        val name = file.name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = File(file.parentFile, "$base ($index)$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    companion object {
        const val MAX_SPEED_TEST_BYTES = 64L * 1024L * 1024L
    }
}

internal object MigrationClient {
    fun requestPair(local: MigrationPeer, peer: MigrationPeer): Boolean {
        MigrationProtocol.connect(peer.host, peer.port).use { socket ->
            val output = BufferedOutputStream(socket.getOutputStream())
            val input = BufferedInputStream(socket.getInputStream())
            MigrationProtocol.writeJson(
                output,
                JSONObject()
                    .put("type", MigrationCommands.PAIR)
                    .put("requestId", UUID.randomUUID().toString())
                    .put("deviceId", local.deviceId)
                    .put("name", local.name)
                    .put("model", local.model)
                    .put("version", local.appVersion)
                    .put("servicePort", local.port)
            )
            output.flush()
            return MigrationProtocol.readJson(input).optBoolean("accepted", false)
        }
    }

    fun sendRole(peer: MigrationPeer, role: MigrationRole) {
        simpleCommand(peer, JSONObject().put("type", MigrationCommands.ROLE).put("role", role.name))
    }

    fun testSpeed(peer: MigrationPeer): SpeedTestResult {
        val latencySamples = mutableListOf<Long>()
        repeat(3) {
            val started = System.nanoTime()
            simpleCommand(peer, JSONObject().put("type", MigrationCommands.HELLO))
            latencySamples += (System.nanoTime() - started) / 1_000_000L
        }
        val size = SPEED_TEST_BYTES
        val uploadSamples = mutableListOf<Long>()
        val uploadBps: Long
        MigrationProtocol.connect(peer.host, peer.port).use { socket ->
            val output = BufferedOutputStream(socket.getOutputStream(), 1024 * 1024)
            val input = BufferedInputStream(socket.getInputStream(), 1024 * 1024)
            MigrationProtocol.writeJson(output, JSONObject().put("type", MigrationCommands.SPEED_UPLOAD).put("size", size))
            val zeros = ByteArray(1024 * 1024)
            var remaining = size
            val started = System.nanoTime()
            var sampleStarted = started
            var sampleBytes = 0L
            while (remaining > 0) {
                val count = min(zeros.size.toLong(), remaining).toInt()
                output.write(zeros, 0, count)
                remaining -= count
                sampleBytes += count
                val now = System.nanoTime()
                if (now - sampleStarted >= 250_000_000L) {
                    uploadSamples += bytesPerSecond(sampleBytes, now - sampleStarted)
                    sampleStarted = now
                    sampleBytes = 0
                }
            }
            output.flush()
            val elapsed = max(1L, System.nanoTime() - started)
            MigrationProtocol.readJson(input)
            uploadBps = bytesPerSecond(size, elapsed)
        }

        val downloadSamples = mutableListOf<Long>()
        val downloadBps: Long
        MigrationProtocol.connect(peer.host, peer.port).use { socket ->
            val output = BufferedOutputStream(socket.getOutputStream())
            val input = BufferedInputStream(socket.getInputStream(), 1024 * 1024)
            MigrationProtocol.writeJson(output, JSONObject().put("type", MigrationCommands.SPEED_DOWNLOAD).put("size", size))
            output.flush()
            val header = MigrationProtocol.readJson(input)
            var remaining = header.optLong("size", size)
            val buffer = ByteArray(1024 * 1024)
            val started = System.nanoTime()
            var sampleStarted = started
            var sampleBytes = 0L
            while (remaining > 0) {
                val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                if (read < 0) error("Speed download ended early")
                remaining -= read
                sampleBytes += read
                val now = System.nanoTime()
                if (now - sampleStarted >= 250_000_000L) {
                    downloadSamples += bytesPerSecond(sampleBytes, now - sampleStarted)
                    sampleStarted = now
                    sampleBytes = 0
                }
            }
            downloadBps = bytesPerSecond(size, max(1L, System.nanoTime() - started))
        }
        val stability = stabilityPercent(uploadSamples + downloadSamples)
        return SpeedTestResult(
            latencyMs = latencySamples.sorted()[latencySamples.size / 2],
            uploadBytesPerSecond = uploadBps,
            downloadBytesPerSecond = downloadBps,
            stabilityPercent = stability
        )
    }

    fun sendSpeedResult(peer: MigrationPeer, result: SpeedTestResult) {
        simpleCommand(peer, speedResultToJson(result).put("type", MigrationCommands.SPEED_RESULT))
    }

    fun sendReport(peer: MigrationPeer, report: MigrationReport) {
        simpleCommand(peer, reportToJson(report).put("type", MigrationCommands.REPORT))
    }

    fun sendFile(
        peer: MigrationPeer,
        item: MigrationFileItem,
        kind: String,
        hash: String,
        onBytes: (Long) -> Unit
    ): SendFileResult {
        MigrationProtocol.connect(peer.host, peer.port, 15_000).use { socket ->
            socket.soTimeout = 180_000
            val output = BufferedOutputStream(socket.getOutputStream(), 1024 * 1024)
            val input = BufferedInputStream(socket.getInputStream(), 1024 * 1024)
            MigrationProtocol.writeJson(
                output,
                JSONObject()
                    .put("type", MigrationCommands.FILE_OFFER)
                    .put("path", item.relativePath)
                    .put("size", item.size)
                    .put("modifiedAt", item.modifiedAt)
                    .put("sha256", hash)
                    .put("kind", kind)
            )
            output.flush()
            val ready = MigrationProtocol.readJson(input)
            if (ready.optString("action") == "skip") return SendFileResult(skipped = true, sentBytes = 0L)
            if (ready.optString("action") != "send") error(ready.optString("error", "receiver_rejected"))
            val offset = ready.optLong("offset", 0L).coerceIn(0L, item.size)
            RandomAccessFile(item.file, "r").use { source ->
                source.seek(offset)
                var remaining = item.size - offset
                val buffer = ByteArray(1024 * 1024)
                while (remaining > 0L) {
                    val read = source.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) error("Source ended early")
                    output.write(buffer, 0, read)
                    remaining -= read
                    onBytes(read.toLong())
                }
            }
            output.flush()
            val result = MigrationProtocol.readJson(input)
            if (!result.optBoolean("ok", false)) error(result.optString("error", "transfer_failed"))
            return SendFileResult(skipped = false, sentBytes = item.size - offset)
        }
    }

    private fun simpleCommand(peer: MigrationPeer, json: JSONObject): JSONObject {
        MigrationProtocol.connect(peer.host, peer.port).use { socket ->
            val output = BufferedOutputStream(socket.getOutputStream())
            val input = BufferedInputStream(socket.getInputStream())
            MigrationProtocol.writeJson(output, json)
            output.flush()
            return MigrationProtocol.readJson(input)
        }
    }

    private fun bytesPerSecond(bytes: Long, elapsedNanos: Long): Long =
        (bytes * 1_000_000_000.0 / max(1L, elapsedNanos)).toLong().coerceAtLeast(0L)

    private fun stabilityPercent(samples: List<Long>): Int {
        val positive = samples.filter { it > 0L }
        if (positive.size < 2) return 100
        val average = positive.average()
        if (average <= 0.0) return 100
        val meanDeviation = positive.sumOf { kotlin.math.abs(it - average) } / positive.size
        return (100.0 - (meanDeviation / average * 100.0)).toInt().coerceIn(0, 100)
    }

    private const val SPEED_TEST_BYTES = 16L * 1024L * 1024L
}

internal data class SendFileResult(val skipped: Boolean, val sentBytes: Long)

internal class MigrationTransferManager {
    fun transfer(
        peer: MigrationPeer,
        items: List<MigrationFileItem>,
        concurrency: Int,
        onProgress: (MigrationProgress) -> Unit
    ): MigrationReport {
        val totalBytes = items.sumOf { it.size }
        val totalItems = items.size
        val transferred = AtomicLong(0L)
        val completed = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val started = System.currentTimeMillis()
        val lastSampleAt = AtomicLong(System.nanoTime())
        val lastSampleBytes = AtomicLong(0L)
        val speed = AtomicLong(0L)
        val pool = Executors.newFixedThreadPool(concurrency.coerceIn(1, 6))

        fun publish(name: String) {
            val now = System.nanoTime()
            val previousAt = lastSampleAt.get()
            if (now - previousAt >= 300_000_000L && lastSampleAt.compareAndSet(previousAt, now)) {
                val bytesNow = transferred.get()
                val oldBytes = lastSampleBytes.getAndSet(bytesNow)
                speed.set(((bytesNow - oldBytes).coerceAtLeast(0L) * 1_000_000_000.0 / max(1L, now - previousAt)).toLong())
            }
            onProgress(
                MigrationProgress(
                    totalBytes = totalBytes,
                    transferredBytes = transferred.get().coerceAtMost(totalBytes),
                    totalItems = totalItems,
                    completedItems = completed.get(),
                    skippedItems = skipped.get(),
                    failedItems = failed.get(),
                    bytesPerSecond = speed.get(),
                    currentName = name
                )
            )
        }

        val futures = items.map { item ->
            pool.submit {
                try {
                    val hash = sha256(item.file)
                    val result = MigrationClient.sendFile(
                        peer = peer,
                        item = item,
                        kind = if (item.appPackageName != null) "app" else "file",
                        hash = hash,
                        onBytes = { delta ->
                            transferred.addAndGet(delta)
                            publish(item.file.name)
                        }
                    )
                    if (result.skipped) {
                        skipped.incrementAndGet()
                        transferred.addAndGet(item.size)
                    }
                    completed.incrementAndGet()
                } catch (_: Throwable) {
                    failed.incrementAndGet()
                    completed.incrementAndGet()
                } finally {
                    publish(item.file.name)
                }
            }
        }
        futures.forEach { runCatching { it.get() } }
        pool.shutdown()
        val duration = (System.currentTimeMillis() - started).coerceAtLeast(1L)
        val transferredBytes = transferred.get().coerceAtMost(totalBytes)
        val report = MigrationReport(
            totalBytes = totalBytes,
            transferredBytes = transferredBytes,
            successCount = (totalItems - failed.get()).coerceAtLeast(0),
            skippedCount = skipped.get(),
            failedCount = failed.get(),
            durationMs = duration,
            averageBytesPerSecond = (transferredBytes * 1000L / duration).coerceAtLeast(0L)
        )
        publish("")
        return report
    }
}

internal object MigrationScanner {
    fun scan(context: Context): MigrationScanResult {
        val root = Environment.getExternalStorageDirectory()
        val files = mutableListOf<MigrationFileItem>()
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val directory = stack.removeLast()
            val relativeDir = runCatching { directory.relativeTo(root).invariantSeparatorsPath }.getOrDefault("")
            if (shouldSkipDirectory(relativeDir)) continue
            directory.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    stack.add(child)
                } else if (child.isFile && child.canRead()) {
                    val relative = runCatching { child.relativeTo(root).invariantSeparatorsPath }.getOrNull() ?: return@forEach
                    files += MigrationFileItem(
                        file = child,
                        relativePath = relative,
                        size = child.length(),
                        modifiedAt = child.lastModified(),
                        category = categoryFor(relative, child.name)
                    )
                }
            }
        }
        return MigrationScanResult(files = files, apps = scanApps(context))
    }

    private fun scanApps(context: Context): List<MigrationAppItem> {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION") pm.getInstalledPackages(0)
        }
        return packages.asSequence()
            .filter { info ->
                val app = info.applicationInfo ?: return@filter false
                (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && info.packageName != context.packageName
            }
            .mapNotNull { info ->
                val app = info.applicationInfo ?: return@mapNotNull null
                val apkPaths = buildList {
                    add(app.sourceDir)
                    app.splitSourceDirs?.forEach(::add)
                }.map(::File).filter { it.isFile && it.canRead() }
                if (apkPaths.isEmpty()) return@mapNotNull null
                MigrationAppItem(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(info.packageName),
                    versionName = info.versionName.orEmpty(),
                    apkFiles = apkPaths,
                    totalBytes = apkPaths.sumOf { it.length() }
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun appTransferItems(apps: List<MigrationAppItem>): List<MigrationFileItem> = apps.flatMap { app ->
        app.apkFiles.mapIndexed { index, apk ->
            val name = if (index == 0) "base.apk" else apk.name.ifBlank { "split-$index.apk" }
            MigrationFileItem(
                file = apk,
                relativePath = "${app.packageName}/$name",
                size = apk.length(),
                modifiedAt = apk.lastModified(),
                category = MigrationCategory.APPS,
                appPackageName = app.packageName
            )
        }
    }

    private fun shouldSkipDirectory(relative: String): Boolean {
        val path = relative.trim('/').lowercase()
        if (path.isBlank()) return false
        return path == "android/data" || path.startsWith("android/data/") ||
            path == "android/obb" || path.startsWith("android/obb/") ||
            path.startsWith("download/speedshare/apps") ||
            path.contains("/.speedshare-trash") || path.startsWith(".speedshare-trash")
    }

    private fun categoryFor(relative: String, name: String): MigrationCategory {
        val lowerPath = relative.lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            lowerPath.startsWith("dcim/") || lowerPath.startsWith("pictures/") || ext in IMAGE_EXTENSIONS -> MigrationCategory.PHOTOS
            lowerPath.startsWith("movies/") || ext in VIDEO_EXTENSIONS -> MigrationCategory.VIDEOS
            lowerPath.startsWith("music/") || ext in AUDIO_EXTENSIONS -> MigrationCategory.MUSIC
            lowerPath.startsWith("documents/") || ext in DOCUMENT_EXTENSIONS -> MigrationCategory.DOCUMENTS
            lowerPath.startsWith("download/") -> MigrationCategory.DOWNLOADS
            else -> MigrationCategory.OTHER
        }
    }

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "dng", "bmp")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "ts")
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma")
    private val DOCUMENT_EXTENSIONS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "md")
}

internal fun normalizeRelativePath(value: String): String? {
    val normalized = value.trim().replace('\\', '/').trim('/')
    if (normalized.isBlank() || normalized.contains('\u0000')) return null
    val parts = normalized.split('/').filter { it.isNotBlank() }
    if (parts.isEmpty() || parts.any { it == "." || it == ".." }) return null
    return parts.joinToString("/")
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun speedResultToJson(result: SpeedTestResult): JSONObject = JSONObject()
    .put("latencyMs", result.latencyMs)
    .put("uploadBps", result.uploadBytesPerSecond)
    .put("downloadBps", result.downloadBytesPerSecond)
    .put("stability", result.stabilityPercent)

private fun speedResultFromJson(json: JSONObject): SpeedTestResult = SpeedTestResult(
    latencyMs = json.optLong("latencyMs"),
    uploadBytesPerSecond = json.optLong("uploadBps"),
    downloadBytesPerSecond = json.optLong("downloadBps"),
    stabilityPercent = json.optInt("stability", 100).coerceIn(0, 100)
)

private fun reportToJson(report: MigrationReport): JSONObject = JSONObject()
    .put("totalBytes", report.totalBytes)
    .put("transferredBytes", report.transferredBytes)
    .put("successCount", report.successCount)
    .put("skippedCount", report.skippedCount)
    .put("failedCount", report.failedCount)
    .put("durationMs", report.durationMs)
    .put("averageBps", report.averageBytesPerSecond)

private fun reportFromJson(json: JSONObject): MigrationReport = MigrationReport(
    totalBytes = json.optLong("totalBytes"),
    transferredBytes = json.optLong("transferredBytes"),
    successCount = json.optInt("successCount"),
    skippedCount = json.optInt("skippedCount"),
    failedCount = json.optInt("failedCount"),
    durationMs = json.optLong("durationMs"),
    averageBytesPerSecond = json.optLong("averageBps")
)
