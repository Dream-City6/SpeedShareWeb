package com.alex.speedshare

import android.content.Context
import android.media.MediaScannerConnection
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.channels.ClosedChannelException
import java.nio.charset.StandardCharsets
import java.net.StandardSocketOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.math.min

class SpeedShareServer(
    private val context: Context,
    private val mode: ShareMode,
    private val selectedFiles: List<SharedFile>,
    private val rootDirectory: File,
    private val uploadEnabled: Boolean,
    private val remoteManagementEnabled: Boolean,
    private val deleteToTrashByDefault: Boolean,
    private val language: ResolvedLanguage,
    private val port: Int,
    private val onMetrics: (TransferSnapshot) -> Unit = {}
) {
    @Volatile
    private var running = false

    private var serverChannel: ServerSocketChannel? = null
    private var acceptThread: Thread? = null
    private val clients = ConcurrentHashMap.newKeySet<SocketChannel>()
    private val translator = Localization.translator(language)
    private val thumbnailManager = ThumbnailManager(context)
    private val contentRevision = AtomicLong(1L)
    private val transferTracker = TransferTracker(onMetrics)
    private val trashManager = TrashManager(rootDirectory, translator)
    private val operationTracker = FileOperationTracker(translator = translator)
    private val fileOperationManager = FileOperationManager(
        rootDirectory = rootDirectory,
        trashManager = trashManager,
        tracker = operationTracker,
        translator = translator,
        onContentChanged = { contentRevision.incrementAndGet() }
    )
    private val zipSelections = ConcurrentHashMap<String, ZipSelection>()

    // 每次服务器实例都会生成新的版本号。浏览器看到新的查询参数后，
    // 会重新请求缩略图和预览文件，而不是继续使用上一轮分享的缓存。
    private val contentVersion = System.nanoTime().toString(36)

    private data class ZipSelection(
        val paths: List<String>,
        val compress: Boolean,
        val fileName: String,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    @Synchronized
    fun start() {
        if (running) return

        val channel = ServerSocketChannel.open()
        try {
            channel.configureBlocking(true)
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
            channel.bind(InetSocketAddress(port))
        } catch (e: Exception) {
            runCatching { channel.close() }
            throw e
        }

        serverChannel = channel
        running = true

        acceptThread = thread(name = "SpeedShare-Accept", isDaemon = true) {
            while (running) {
                try {
                    val client = channel.accept()
                    clients.add(client)
                    transferTracker.setConnectionCount(clients.size)
                    thread(name = "SpeedShare-Client", isDaemon = true) {
                        try {
                            handleClient(client)
                        } finally {
                            clients.remove(client)
                            transferTracker.setConnectionCount(clients.size)
                            runCatching { client.close() }
                        }
                    }
                } catch (_: ClosedChannelException) {
                    // stop() 正常关闭监听端口时会进入这里。
                    break
                } catch (e: Exception) {
                    if (running) e.printStackTrace()
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!running && serverChannel == null) {
            transferTracker.close()
            return
        }
        running = false

        val channel = serverChannel
        serverChannel = null
        runCatching { channel?.close() }

        clients.toList().forEach { client ->
            runCatching { client.close() }
        }
        clients.clear()
        transferTracker.setConnectionCount(0)
        transferTracker.close()
        fileOperationManager.close()
        zipSelections.clear()

        val threadToJoin = acceptThread
        acceptThread = null
        if (threadToJoin != null && threadToJoin !== Thread.currentThread()) {
            runCatching { threadToJoin.join(500L) }
        }
    }

    private fun handleClient(client: SocketChannel) {
        client.use { socket ->
            try {
                socket.configureBlocking(true)
                try {
                    socket.socket().sendBufferSize = 1024 * 1024
                    socket.socket().receiveBufferSize = 1024 * 1024
                    socket.socket().tcpNoDelay = true
                } catch (_: Exception) {
                }

                val input = socket.socket().getInputStream()
                val request = readHttpRequest(input) ?: return
                val target = parseTarget(request.target)
                val isGetOrHead = request.method == "GET" || request.method == "HEAD"

                when {
                    isGetOrHead && target.path == "/" -> {
                        if (mode == ShareMode.SELECTED_FILES) {
                            sendSelectedFilesPage(socket, request.method)
                        } else {
                            sendDirectoryPage(
                                socket = socket,
                                method = request.method,
                                relativePath = target.query["path"].orEmpty()
                            )
                        }
                    }

                    isGetOrHead &&
                        (target.path == "/selected" || target.path == "/selected-download") &&
                        mode == ShareMode.SELECTED_FILES -> {
                        withSelectedFile(target.query["id"], socket) { sharedFile ->
                            sendSelectedFile(
                                socket = socket,
                                method = request.method,
                                sharedFile = sharedFile,
                                rangeHeader = request.headers["range"],
                                disposition = "attachment",
                                cacheControl = "no-store"
                            )
                        }
                    }

                    isGetOrHead && target.path == "/selected-view" &&
                        mode == ShareMode.SELECTED_FILES -> {
                        withSelectedFile(target.query["id"], socket) { sharedFile ->
                            sendSelectedFile(
                                socket = socket,
                                method = request.method,
                                sharedFile = sharedFile,
                                rangeHeader = request.headers["range"],
                                disposition = "inline",
                                cacheControl = "private, max-age=300"
                            )
                        }
                    }

                    isGetOrHead && target.path == "/selected-thumb" &&
                        mode == ShareMode.SELECTED_FILES -> {
                        withSelectedFile(target.query["id"], socket) { sharedFile ->
                            val thumbnail = thumbnailManager.thumbnailForSharedFile(sharedFile)
                            if (thumbnail == null) {
                                sendTextResponse(socket, "404 Not Found", "Thumbnail unavailable")
                            } else {
                                sendLocalFile(
                                    socket = socket,
                                    method = request.method,
                                    file = thumbnail,
                                    rangeHeader = request.headers["range"],
                                    disposition = "inline",
                                    cacheControl = "public, max-age=604800, immutable",
                                    mimeTypeOverride = "image/jpeg",
                                    fileNameOverride = "thumbnail.jpg",
                                    trackTransfer = false
                                )
                            }
                        }
                    }

                    isGetOrHead && target.path == "/download" && mode == ShareMode.WHOLE_STORAGE -> {
                        withLocalFile(target.query["path"].orEmpty(), socket) { file ->
                            sendLocalFile(
                                socket = socket,
                                method = request.method,
                                file = file,
                                rangeHeader = request.headers["range"],
                                disposition = "attachment",
                                cacheControl = "no-store"
                            )
                        }
                    }

                    isGetOrHead && target.path == "/view" && mode == ShareMode.WHOLE_STORAGE -> {
                        withLocalFile(target.query["path"].orEmpty(), socket) { file ->
                            sendLocalFile(
                                socket = socket,
                                method = request.method,
                                file = file,
                                rangeHeader = request.headers["range"],
                                disposition = "inline",
                                cacheControl = "private, max-age=300"
                            )
                        }
                    }

                    isGetOrHead && target.path == "/thumb" && mode == ShareMode.WHOLE_STORAGE -> {
                        withLocalFile(target.query["path"].orEmpty(), socket) { file ->
                            val thumbnail = thumbnailManager.thumbnailForFile(
                                file = file,
                                mimeType = guessMimeType(file.name)
                            )
                            if (thumbnail == null) {
                                sendTextResponse(socket, "404 Not Found", "Thumbnail unavailable")
                            } else {
                                sendLocalFile(
                                    socket = socket,
                                    method = request.method,
                                    file = thumbnail,
                                    rangeHeader = request.headers["range"],
                                    disposition = "inline",
                                    cacheControl = "public, max-age=604800, immutable",
                                    mimeTypeOverride = "image/jpeg",
                                    fileNameOverride = "thumbnail.jpg",
                                    trackTransfer = false
                                )
                            }
                        }
                    }

                    request.method == "POST" && target.path == "/api/mkdir" -> {
                        requireManagement(socket) {
                            createDirectory(
                                socket = socket,
                                relativeDirectory = target.query["path"].orEmpty(),
                                requestedName = target.query["name"].orEmpty()
                            )
                        }
                    }

                    request.method == "POST" && target.path == "/api/rename" -> {
                        requireManagement(socket) {
                            renamePath(
                                socket = socket,
                                relativePath = target.query["path"].orEmpty(),
                                requestedName = target.query["name"].orEmpty()
                            )
                        }
                    }

                    request.method == "POST" && target.path == "/api/copy" -> {
                        requireManagement(socket) {
                            val paths = readPathListBody(input, request)
                            val id = fileOperationManager.submitCopy(
                                relativePaths = paths,
                                destinationRelativePath = target.query["dest"].orEmpty(),
                                policy = ConflictPolicy.fromWeb(target.query["conflict"])
                            )
                            sendJsonResponse(socket, request.method, "{\"operationId\":$id}", "202 Accepted")
                        }
                    }

                    request.method == "POST" && target.path == "/api/move" -> {
                        requireManagement(socket) {
                            val paths = readPathListBody(input, request)
                            val id = fileOperationManager.submitMove(
                                relativePaths = paths,
                                destinationRelativePath = target.query["dest"].orEmpty(),
                                policy = ConflictPolicy.fromWeb(target.query["conflict"])
                            )
                            sendJsonResponse(socket, request.method, "{\"operationId\":$id}", "202 Accepted")
                        }
                    }

                    request.method == "POST" && target.path == "/api/delete" -> {
                        requireManagement(socket) {
                            val paths = readPathListBody(input, request)
                            val permanent = target.query["permanent"] == "1"
                            val id = fileOperationManager.submitDelete(paths, permanent)
                            sendJsonResponse(socket, request.method, "{\"operationId\":$id}", "202 Accepted")
                        }
                    }

                    request.method == "POST" && target.path == "/api/restore" -> {
                        requireManagement(socket) {
                            val ids = readPathListBody(input, request)
                            val id = fileOperationManager.submitRestore(
                                ids = ids,
                                policy = ConflictPolicy.fromWeb(target.query["conflict"])
                            )
                            sendJsonResponse(socket, request.method, "{\"operationId\":$id}", "202 Accepted")
                        }
                    }

                    request.method == "POST" && target.path == "/api/trash/delete" -> {
                        requireManagement(socket) {
                            val ids = readPathListBody(input, request)
                            ids.forEach { trashManager.permanentDelete(it) }
                            contentRevision.incrementAndGet()
                            sendJsonResponse(socket, request.method, "{\"ok\":true}")
                        }
                    }

                    request.method == "POST" && target.path == "/api/trash/empty" -> {
                        requireManagement(socket) {
                            val ok = trashManager.emptyTrash()
                            contentRevision.incrementAndGet()
                            sendJsonResponse(socket, request.method, "{\"ok\":$ok}")
                        }
                    }

                    isGetOrHead && target.path == "/api/trash" -> {
                        requireManagement(socket) {
                            sendTrashJson(socket, request.method)
                        }
                    }

                    isGetOrHead && target.path == "/api/tree" -> {
                        if (mode != ShareMode.WHOLE_STORAGE) {
                            sendTextResponse(socket, "403 Forbidden", "Directory mode required")
                        } else {
                            sendTreeJson(socket, request.method, target.query["path"].orEmpty())
                        }
                    }

                    isGetOrHead && target.path == "/api/operations" -> {
                        sendOperationsJson(socket, request.method)
                    }

                    request.method == "POST" && target.path == "/api/operations/cancel" -> {
                        val id = target.query["id"]?.toLongOrNull()
                        val ok = id != null && fileOperationManager.requestCancel(id)
                        sendJsonResponse(socket, request.method, "{\"ok\":$ok}")
                    }

                    request.method == "POST" && target.path == "/api/zip/prepare" -> {
                        if (mode != ShareMode.WHOLE_STORAGE) {
                            sendTextResponse(socket, "403 Forbidden", "Directory mode required")
                        } else {
                            prepareZipSelection(socket, input, request, target)
                        }
                    }

                    isGetOrHead && target.path == "/zip" -> {
                        if (mode != ShareMode.WHOLE_STORAGE) {
                            sendTextResponse(socket, "403 Forbidden", "Directory mode required")
                        } else {
                            sendPreparedZip(socket, request.method, target.query["id"].orEmpty())
                        }
                    }

                    request.method == "POST" && target.path == "/upload" -> {
                        if (mode != ShareMode.WHOLE_STORAGE || !uploadEnabled) {
                            sendTextResponse(socket, "403 Forbidden", "Upload is disabled")
                        } else {
                            receiveUpload(
                                socket = socket,
                                input = input,
                                request = request,
                                relativeDirectory = target.query["path"].orEmpty(),
                                requestedName = target.query["name"].orEmpty()
                            )
                        }
                    }

                    isGetOrHead && target.path == "/events" -> {
                        handleEventStream(socket)
                    }

                    isGetOrHead && target.path == "/api/status" -> {
                        sendStatusJson(socket, request.method)
                    }

                    target.path == "/favicon.ico" -> {
                        sendEmptyResponse(socket, "204 No Content")
                    }

                    else -> {
                        sendTextResponse(socket, "404 Not Found", "Not found")
                    }
                }
            } catch (_: Exception) {
                // 浏览器取消下载、网络切换或关闭连接时，结束当前连接。
            }
        }
    }

    private fun withSelectedFile(
        rawId: String?,
        socket: SocketChannel,
        action: (SharedFile) -> Unit
    ) {
        val id = rawId?.toIntOrNull()
        if (id == null || id !in selectedFiles.indices) {
            sendTextResponse(socket, "404 Not Found", "File not found")
            return
        }
        action(selectedFiles[id])
    }

    private fun withLocalFile(
        relativePath: String,
        socket: SocketChannel,
        action: (File) -> Unit
    ) {
        val file = safeResolve(rootDirectory, relativePath)
        if (file == null || !file.isFile || !file.canRead()) {
            sendTextResponse(socket, "404 Not Found", "File not found")
            return
        }
        action(file)
    }

    private fun sendSelectedFilesPage(socket: SocketChannel, method: String) {
        val items = selectedFiles.mapIndexed { index, file ->
            val previewKind = previewKindFor(file.mimeType, file.name)
            WebItem(
                name = file.name,
                isDirectory = false,
                mimeType = file.mimeType,
                size = file.size,
                modifiedAt = file.modifiedAt,
                openUrl = "/selected-download?id=$index",
                previewUrl = if (previewKind == PreviewKind.DOWNLOAD) {
                    null
                } else {
                    "/selected-view?id=$index&v=$contentVersion-${file.size}-${file.modifiedAt}"
                },
                downloadUrl = "/selected-download?id=$index",
                thumbnailUrl = if (canGenerateThumbnail(file.mimeType)) {
                    "/selected-thumb?id=$index&v=$contentVersion-${file.size}-${file.modifiedAt}"
                } else {
                    null
                },
                displayPath = file.name,
                relativePath = "",
                previewKind = previewKind
            )
        }

        val html = WebPageBuilder.buildSelectedPage(
            items = items,
            language = language,
            pageVersion = contentVersion
        )
        sendHtmlResponse(socket, method, html)
    }

    private fun sendDirectoryPage(
        socket: SocketChannel,
        method: String,
        relativePath: String
    ) {
        val directory = safeResolve(rootDirectory, relativePath)

        if (directory == null || !directory.isDirectory || !directory.canRead()) {
            sendTextResponse(socket, "404 Not Found", "Directory not found")
            return
        }

        val normalizedRelative = relativePath.trim('/').replace('\\', '/')
        val entries = directory.listFiles()
            ?.filter { it.canRead() && !trashManager.isTrashPath(it) }
            ?.sortedWith(
                compareBy<File> { !it.isDirectory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
            .orEmpty()

        val items = entries.map { file ->
            val childRelative = if (normalizedRelative.isEmpty()) {
                file.name
            } else {
                "$normalizedRelative/${file.name}"
            }

            if (file.isDirectory) {
                WebItem(
                    name = file.name,
                    isDirectory = true,
                    mimeType = "inode/directory",
                    size = 0L,
                    modifiedAt = file.lastModified(),
                    openUrl = "/?path=${urlEncode(childRelative)}",
                    previewUrl = null,
                    downloadUrl = null,
                    thumbnailUrl = null,
                    displayPath = "/storage/emulated/0/$childRelative",
                    relativePath = childRelative,
                    previewKind = PreviewKind.DOWNLOAD
                )
            } else {
                val mimeType = guessMimeType(file.name)
                val previewKind = previewKindFor(mimeType, file.name)
                WebItem(
                    name = file.name,
                    isDirectory = false,
                    mimeType = mimeType,
                    size = file.length(),
                    modifiedAt = file.lastModified(),
                    openUrl = "/download?path=${urlEncode(childRelative)}",
                    previewUrl = if (previewKind == PreviewKind.DOWNLOAD) {
                        null
                    } else {
                        "/view?path=${urlEncode(childRelative)}&v=$contentVersion-${file.length()}-${file.lastModified()}"
                    },
                    downloadUrl = "/download?path=${urlEncode(childRelative)}",
                    thumbnailUrl = if (canGenerateThumbnail(mimeType)) {
                        "/thumb?path=${urlEncode(childRelative)}&v=$contentVersion-${file.length()}-${file.lastModified()}"
                    } else {
                        null
                    },
                    displayPath = "/storage/emulated/0/$childRelative",
                    relativePath = childRelative,
                    previewKind = previewKind
                )
            }
        }

        val displayPath = if (normalizedRelative.isEmpty()) {
            "/storage/emulated/0"
        } else {
            "/storage/emulated/0/$normalizedRelative"
        }

        val html = WebPageBuilder.buildDirectoryPage(
            displayPath = displayPath,
            relativePath = normalizedRelative,
            items = items,
            uploadEnabled = uploadEnabled,
            remoteManagementEnabled = remoteManagementEnabled,
            deleteToTrashByDefault = deleteToTrashByDefault,
            language = language,
            pageVersion = contentVersion
        )

        sendHtmlResponse(socket, method, html)
    }

    private fun requireManagement(socket: SocketChannel, action: () -> Unit) {
        if (mode != ShareMode.WHOLE_STORAGE || !remoteManagementEnabled) {
            sendTextResponse(socket, "403 Forbidden", "Remote management is disabled")
            return
        }
        try {
            action()
        } catch (error: IllegalArgumentException) {
            sendTextResponse(socket, "400 Bad Request", error.message ?: "Invalid request")
        } catch (error: Throwable) {
            sendTextResponse(socket, "500 Internal Server Error", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun createDirectory(
        socket: SocketChannel,
        relativeDirectory: String,
        requestedName: String
    ) {
        val directory = safeResolve(rootDirectory, relativeDirectory)
        val safeName = validateSimpleName(requestedName)
        if (directory == null || !directory.isDirectory || trashManager.isTrashPath(directory)) {
            sendTextResponse(socket, "404 Not Found", "Directory not found")
            return
        }
        if (safeName == null) {
            sendTextResponse(socket, "400 Bad Request", "Invalid folder name")
            return
        }
        val destination = File(directory, safeName)
        if (destination.exists()) {
            sendTextResponse(socket, "409 Conflict", "Name already exists")
            return
        }
        val ok = destination.mkdirs()
        if (ok) contentRevision.incrementAndGet()
        sendJsonResponse(socket, "POST", "{\"ok\":$ok}", if (ok) "201 Created" else "500 Internal Server Error")
    }

    private fun renamePath(
        socket: SocketChannel,
        relativePath: String,
        requestedName: String
    ) {
        val source = safeResolve(rootDirectory, relativePath)
        val safeName = validateSimpleName(requestedName)
        if (
            source == null || !source.exists() || source.canonicalFile == rootDirectory.canonicalFile ||
            trashManager.isTrashPath(source)
        ) {
            sendTextResponse(socket, "404 Not Found", "Path not found")
            return
        }
        if (safeName == null) {
            sendTextResponse(socket, "400 Bad Request", "Invalid name")
            return
        }
        val destination = File(source.parentFile, safeName)
        if (destination.exists()) {
            sendTextResponse(socket, "409 Conflict", "Name already exists")
            return
        }
        val ok = source.renameTo(destination)
        if (ok) contentRevision.incrementAndGet()
        sendJsonResponse(socket, "POST", "{\"ok\":$ok}", if (ok) "200 OK" else "500 Internal Server Error")
    }

    private fun validateSimpleName(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return null
        if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.indexOf('\u0000') >= 0) return null
        return if (File(trimmed).name == trimmed) trimmed else null
    }

    private fun readPathListBody(input: InputStream, request: HttpRequest): List<String> {
        val length = request.headers["content-length"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Content-Length is required")
        if (length < 0 || length > 4 * 1024 * 1024) {
            throw IllegalArgumentException("Request body is too large")
        }
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) throw IllegalStateException("Request body ended early")
            offset += read
        }
        return String(bytes, StandardCharsets.UTF_8)
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::urlDecode)
            .distinct()
            .toList()
    }

    private fun sendTreeJson(socket: SocketChannel, method: String, relativePath: String) {
        val directory = safeResolve(rootDirectory, relativePath)
        if (directory == null || !directory.isDirectory || trashManager.isTrashPath(directory)) {
            sendTextResponse(socket, "404 Not Found", "Directory not found")
            return
        }
        val normalized = relativePath.trim('/').replace('\\', '/')
        val children = directory.listFiles()
            ?.filter { it.isDirectory && it.canRead() && !trashManager.isTrashPath(it) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        val jsonItems = children.joinToString(",") { child ->
            val childRelative = if (normalized.isEmpty()) child.name else "$normalized/${child.name}"
            "{\"name\":\"${jsonEscape(child.name)}\",\"path\":\"${jsonEscape(childRelative)}\"}"
        }
        sendJsonResponse(
            socket,
            method,
            "{\"path\":\"${jsonEscape(normalized)}\",\"items\":[$jsonItems]}"
        )
    }

    private fun sendTrashJson(socket: SocketChannel, method: String) {
        val items = trashManager.listEntries().joinToString(",") { entry ->
            buildString {
                append("{")
                append("\"id\":\"${jsonEscape(entry.id)}\",")
                append("\"name\":\"${jsonEscape(entry.name)}\",")
                append("\"originalPath\":\"${jsonEscape(entry.originalRelativePath)}\",")
                append("\"deletedAtMs\":${entry.deletedAtMs},")
                append("\"size\":${entry.size},")
                append("\"isDirectory\":${entry.isDirectory}")
                append("}")
            }
        }
        sendJsonResponse(socket, method, "{\"items\":[$items]}")
    }

    private fun sendOperationsJson(socket: SocketChannel, method: String) {
        val items = fileOperationManager.snapshots().joinToString(",") { operation ->
            buildString {
                append("{")
                append("\"id\":${operation.id},")
                append("\"kind\":\"${operation.kind.webValue}\",")
                append("\"kindName\":\"${jsonEscape(operationKindText(operation.kind, translator))}\",")
                append("\"state\":\"${operation.state.webValue}\",")
                append("\"title\":\"${jsonEscape(operation.title)}\",")
                append("\"totalBytes\":${operation.totalBytes},")
                append("\"processedBytes\":${operation.processedBytes},")
                append("\"totalItems\":${operation.totalItems},")
                append("\"processedItems\":${operation.processedItems},")
                append("\"bytesPerSecond\":${operation.bytesPerSecond},")
                append("\"message\":\"${jsonEscape(operation.message)}\",")
                append("\"cancellable\":${operation.cancellable},")
                append("\"startedAtMs\":${operation.startedAtMs},")
                append("\"updatedAtMs\":${operation.updatedAtMs}")
                append("}")
            }
        }
        sendJsonResponse(socket, method, "{\"items\":[$items]}")
    }

    private fun prepareZipSelection(
        socket: SocketChannel,
        input: InputStream,
        request: HttpRequest,
        target: ParsedTarget
    ) {
        val paths = readPathListBody(input, request)
            .filter { relative ->
                val file = safeResolve(rootDirectory, relative)
                file != null && file.exists() && !trashManager.isTrashPath(file)
            }
        if (paths.isEmpty()) {
            sendTextResponse(socket, "400 Bad Request", "No valid files")
            return
        }
        zipSelections.entries.removeIf { System.currentTimeMillis() - it.value.createdAtMs > 15 * 60_000L }
        val id = UUID.randomUUID().toString().replace("-", "")
        val requestedName = validateSimpleName(target.query["name"].orEmpty()) ?: "SpeedShareWeb.zip"
        val fileName = if (requestedName.endsWith(".zip", true)) requestedName else "$requestedName.zip"
        zipSelections[id] = ZipSelection(
            paths = paths,
            compress = target.query["mode"] == "compress",
            fileName = fileName
        )
        sendJsonResponse(socket, request.method, "{\"url\":\"/zip?id=$id\"}", "201 Created")
    }

    private fun sendPreparedZip(socket: SocketChannel, method: String, id: String) {
        val selection = zipSelections[id]
        if (selection == null) {
            sendTextResponse(socket, "404 Not Found", "ZIP request expired")
            return
        }
        val sources = selection.paths.mapNotNull { relative ->
            safeResolve(rootDirectory, relative)?.takeIf { it.exists() && !trashManager.isTrashPath(it) }
        }
        if (sources.isEmpty()) {
            sendTextResponse(socket, "404 Not Found", "Files not found")
            return
        }

        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/zip\r\n")
            append("Content-Disposition: attachment; filename*=UTF-8''${urlEncode(selection.fileName)}\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        writeAll(socket, header.toByteArray(StandardCharsets.US_ASCII))
        if (method == "HEAD") return

        val totalSize = sources.sumOf(::recursiveSize)
        val transferId = transferTracker.begin(
            direction = TransferDirection.DOWNLOAD,
            fileName = selection.fileName,
            clientAddress = clientAddress(socket),
            totalBytes = totalSize
        )
        val chunked = ChunkedSocketOutputStream(socket) { count ->
            transferTracker.addBytes(transferId, count)
        }
        try {
            ZipOutputStream(chunked).use { zip ->
                zip.setLevel(if (selection.compress) Deflater.DEFAULT_COMPRESSION else Deflater.NO_COMPRESSION)
                val usedNames = linkedSetOf<String>()
                sources.forEach { source ->
                    val initialName = source.relativeTo(rootDirectory).invariantSeparatorsPath
                    addFileToZip(zip, source, uniqueZipName(initialName, usedNames))
                }
            }
        } finally {
            chunked.finish()
            transferTracker.finish(transferId)
        }
    }

    private fun uniqueZipName(name: String, usedNames: MutableSet<String>): String {
        if (usedNames.add(name)) return name
        var index = 1
        while (true) {
            val candidate = "$name ($index)"
            if (usedNames.add(candidate)) return candidate
            index++
        }
    }

    private fun addFileToZip(zip: ZipOutputStream, source: File, entryName: String) {
        val cleanName = entryName.trimStart('/').replace('\\', '/')
        if (source.isDirectory) {
            val directoryName = if (cleanName.endsWith('/')) cleanName else "$cleanName/"
            zip.putNextEntry(ZipEntry(directoryName).apply { time = source.lastModified() })
            zip.closeEntry()
            source.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { child ->
                addFileToZip(zip, child, "$directoryName${child.name}")
            }
        } else {
            zip.putNextEntry(ZipEntry(cleanName).apply { time = source.lastModified() })
            FileInputStream(source).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    zip.write(buffer, 0, read)
                }
            }
            zip.closeEntry()
        }
    }

    private inner class ChunkedSocketOutputStream(
        private val socket: SocketChannel,
        private val onBytes: (Long) -> Unit
    ) : OutputStream() {
        private var finished = false
        private val oneByte = ByteArray(1)

        override fun write(value: Int) {
            oneByte[0] = value.toByte()
            write(oneByte, 0, 1)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length <= 0 || finished) return
            val prefix = Integer.toHexString(length) + "\r\n"
            writeAll(socket, prefix.toByteArray(StandardCharsets.US_ASCII))
            val buffer = ByteBuffer.wrap(bytes, offset, length)
            while (buffer.hasRemaining()) socket.write(buffer)
            writeAll(socket, "\r\n".toByteArray(StandardCharsets.US_ASCII))
            onBytes(length.toLong())
        }

        override fun flush() = Unit

        override fun close() {
            finish()
        }

        fun finish() {
            if (finished) return
            finished = true
            runCatching { writeAll(socket, "0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)) }
        }
    }

    private fun receiveUpload(
        socket: SocketChannel,
        input: InputStream,
        request: HttpRequest,
        relativeDirectory: String,
        requestedName: String
    ) {
        val contentLength = request.headers["content-length"]?.toLongOrNull()
        if (contentLength == null || contentLength < 0L) {
            sendTextResponse(socket, "411 Length Required", "Content-Length is required")
            return
        }

        val directory = safeResolve(rootDirectory, relativeDirectory)
        if (directory == null || !directory.isDirectory || !directory.canWrite()) {
            sendTextResponse(socket, "403 Forbidden", "Directory is not writable")
            return
        }

        val safeName = File(requestedName).name.trim()
        if (safeName.isEmpty() || safeName == "." || safeName == "..") {
            sendTextResponse(socket, "400 Bad Request", "Invalid file name")
            return
        }

        val destination = createUniqueFile(directory, safeName)
        var success = false
        val transferId = transferTracker.begin(
            direction = TransferDirection.UPLOAD,
            fileName = destination.name,
            clientAddress = clientAddress(socket),
            totalBytes = contentLength
        )

        try {
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(1024 * 1024)
                var remaining = contentLength

                while (remaining > 0L) {
                    val wanted = min(remaining, buffer.size.toLong()).toInt()
                    val read = input.read(buffer, 0, wanted)
                    if (read < 0) throw IllegalStateException("Upload ended early")
                    output.write(buffer, 0, read)
                    transferTracker.addBytes(transferId, read.toLong())
                    remaining -= read.toLong()
                }

                output.fd.sync()
            }

            success = true
            contentRevision.incrementAndGet()
            MediaScannerConnection.scanFile(
                context,
                arrayOf(destination.absolutePath),
                null,
                null
            )

            sendTextResponse(
                socket,
                "201 Created",
                "Uploaded: ${destination.name}"
            )
        } finally {
            transferTracker.finish(transferId)
            if (!success) {
                try {
                    destination.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun sendSelectedFile(
        socket: SocketChannel,
        method: String,
        sharedFile: SharedFile,
        rangeHeader: String?,
        disposition: String,
        cacheControl: String
    ) {
        val pfd = context.contentResolver.openFileDescriptor(sharedFile.uri, "r")
        if (pfd == null) {
            sendTextResponse(socket, "500 Internal Server Error", "Unable to open file")
            return
        }

        val totalSize = when {
            sharedFile.size >= 0L -> sharedFile.size
            pfd.statSize >= 0L -> pfd.statSize
            else -> -1L
        }

        if (totalSize < 0L) {
            pfd.close()
            sendTextResponse(socket, "500 Internal Server Error", "Unknown file size")
            return
        }

        var bodyStreamOpened = false
        try {
            sendFileHeadersAndBody(
                socket = socket,
                method = method,
                fileName = sharedFile.name,
                mimeType = sharedFile.mimeType,
                totalSize = totalSize,
                rangeHeader = rangeHeader,
                disposition = disposition,
                cacheControl = cacheControl
            ) { start, length ->
                bodyStreamOpened = true
                val transferId = transferTracker.begin(
                    direction = TransferDirection.DOWNLOAD,
                    fileName = sharedFile.name,
                    clientAddress = clientAddress(socket),
                    totalBytes = length
                )
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                        transferFile(
                            fileChannel = input.channel,
                            socket = socket,
                            start = start,
                            length = length,
                            onBytes = { count -> transferTracker.addBytes(transferId, count) }
                        )
                    }
                } finally {
                    transferTracker.finish(transferId)
                }
            }
        } finally {
            if (!bodyStreamOpened) {
                try {
                    pfd.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun sendLocalFile(
        socket: SocketChannel,
        method: String,
        file: File,
        rangeHeader: String?,
        disposition: String,
        cacheControl: String,
        mimeTypeOverride: String? = null,
        fileNameOverride: String? = null,
        trackTransfer: Boolean = true
    ) {
        val totalSize = file.length()

        sendFileHeadersAndBody(
            socket = socket,
            method = method,
            fileName = fileNameOverride ?: file.name,
            mimeType = mimeTypeOverride ?: guessMimeType(file.name),
            totalSize = totalSize,
            rangeHeader = rangeHeader,
            disposition = disposition,
            cacheControl = cacheControl
        ) { start, length ->
            val transferId = if (trackTransfer) {
                transferTracker.begin(
                    direction = TransferDirection.DOWNLOAD,
                    fileName = fileNameOverride ?: file.name,
                    clientAddress = clientAddress(socket),
                    totalBytes = length
                )
            } else {
                null
            }

            try {
                FileInputStream(file).use { input ->
                    transferFile(
                        fileChannel = input.channel,
                        socket = socket,
                        start = start,
                        length = length,
                        onBytes = { count ->
                            if (transferId != null) transferTracker.addBytes(transferId, count)
                        }
                    )
                }
            } finally {
                if (transferId != null) transferTracker.finish(transferId)
            }
        }
    }

    private fun sendFileHeadersAndBody(
        socket: SocketChannel,
        method: String,
        fileName: String,
        mimeType: String,
        totalSize: Long,
        rangeHeader: String?,
        disposition: String,
        cacheControl: String,
        bodySender: (start: Long, length: Long) -> Unit
    ) {
        val range = parseRange(rangeHeader, totalSize)
        val start = range?.first ?: 0L
        val end = range?.last ?: (totalSize - 1L)
        val responseLength = if (totalSize == 0L) 0L else end - start + 1L
        val status = if (range == null) "200 OK" else "206 Partial Content"

        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $mimeType\r\n")
            append("Content-Length: $responseLength\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (range != null) {
                append("Content-Range: bytes $start-$end/$totalSize\r\n")
            }
            append(
                "Content-Disposition: $disposition; filename*=UTF-8''${urlEncode(fileName)}\r\n"
            )
            append("Cache-Control: $cacheControl\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }

        writeAll(socket, header.toByteArray(StandardCharsets.US_ASCII))

        if (method != "HEAD" && responseLength > 0L) {
            bodySender(start, responseLength)
        }
    }

    private fun transferFile(
        fileChannel: FileChannel,
        socket: SocketChannel,
        start: Long,
        length: Long,
        onBytes: (Long) -> Unit = {}
    ) {
        var position = start
        var remaining = length
        var zeroTransferCount = 0

        while (remaining > 0L && socket.isOpen) {
            val chunkSize = min(remaining, 32L * 1024L * 1024L)
            val transferred = fileChannel.transferTo(position, chunkSize, socket)

            if (transferred > 0L) {
                position += transferred
                remaining -= transferred
                onBytes(transferred)
                zeroTransferCount = 0
            } else {
                zeroTransferCount++
                if (zeroTransferCount >= 3) {
                    bufferedFallback(fileChannel, socket, position, remaining, onBytes)
                    return
                }
                Thread.yield()
            }
        }
    }

    private fun bufferedFallback(
        fileChannel: FileChannel,
        socket: SocketChannel,
        start: Long,
        length: Long,
        onBytes: (Long) -> Unit
    ) {
        fileChannel.position(start)
        var remaining = length
        val buffer = ByteBuffer.allocateDirect(1024 * 1024)

        while (remaining > 0L && socket.isOpen) {
            buffer.clear()
            if (remaining < buffer.capacity()) {
                buffer.limit(remaining.toInt())
            }

            val read = fileChannel.read(buffer)
            if (read < 0) break

            buffer.flip()
            while (buffer.hasRemaining()) {
                socket.write(buffer)
            }
            remaining -= read.toLong()
            onBytes(read.toLong())
        }
    }


    private fun handleEventStream(socket: SocketChannel) {
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: keep-alive\r\n")
            append("X-Accel-Buffering: no\r\n")
            append("\r\n")
        }
        writeAll(socket, header.toByteArray(StandardCharsets.US_ASCII))
        writeAll(socket, "retry: 300\n\n".toByteArray(StandardCharsets.UTF_8))
        writeSseEvent(socket, "hello", contentVersion)

        var observedRevision = contentRevision.get()
        var lastHeartbeat = System.currentTimeMillis()

        while (running && socket.isOpen) {
            val revision = contentRevision.get()
            if (revision != observedRevision) {
                observedRevision = revision
                writeSseEvent(socket, "content-changed", revision.toString())
            }

            val now = System.currentTimeMillis()
            if (now - lastHeartbeat >= 10_000L) {
                writeAll(socket, ": keep-alive\n\n".toByteArray(StandardCharsets.UTF_8))
                lastHeartbeat = now
            }

            Thread.sleep(350L)
        }
    }

    private fun writeSseEvent(socket: SocketChannel, event: String, data: String) {
        val payload = "event: $event\ndata: ${data.replace("\n", " ")}\n\n"
        writeAll(socket, payload.toByteArray(StandardCharsets.UTF_8))
    }

    private fun sendStatusJson(socket: SocketChannel, method: String) {
        val snapshot = transferTracker.snapshot()
        val transfersJson = snapshot.activeTransfers.joinToString(",") { transfer ->
            buildString {
                append("{")
                append("\"id\":${transfer.id},")
                append("\"direction\":\"${transfer.direction.webValue}\",")
                append("\"fileName\":\"${jsonEscape(transfer.fileName)}\",")
                append("\"clientAddress\":\"${jsonEscape(transfer.clientAddress)}\",")
                append("\"totalBytes\":${transfer.totalBytes},")
                append("\"transferredBytes\":${transfer.transferredBytes},")
                append("\"bytesPerSecond\":${transfer.bytesPerSecond}")
                append("}")
            }
        }

        val operationCount = fileOperationManager.snapshots().count {
            it.state == FileOperationState.RUNNING || it.state == FileOperationState.QUEUED
        }

        val json = buildString {
            append("{")
            append("\"version\":\"$contentVersion\",")
            append("\"activeConnections\":${snapshot.activeConnections},")
            append("\"downloadBytesPerSecond\":${snapshot.downloadBytesPerSecond},")
            append("\"uploadBytesPerSecond\":${snapshot.uploadBytesPerSecond},")
            append("\"totalDownloadedBytes\":${snapshot.totalDownloadedBytes},")
            append("\"totalUploadedBytes\":${snapshot.totalUploadedBytes},")
            append("\"activeFileOperations\":$operationCount,")
            append("\"activeTransfers\":[$transfersJson]")
            append("}")
        }

        val body = json.toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        writeAll(socket, header.toByteArray(StandardCharsets.US_ASCII))
        if (method != "HEAD") writeAll(socket, body)
    }

    private fun clientAddress(socket: SocketChannel): String {
        return runCatching {
            (socket.remoteAddress as? InetSocketAddress)?.address?.hostAddress
                ?: socket.remoteAddress.toString()
        }.getOrDefault(translator.text("unknown_device"))
    }

    private fun jsonEscape(value: String): String {
        return buildString(value.length + 16) {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }

    private fun sendJsonResponse(
        socket: SocketChannel,
        method: String,
        json: String,
        status: String = "200 OK"
    ) {
        val body = json.toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        writeAll(socket, header.toByteArray(StandardCharsets.US_ASCII))
        if (method != "HEAD") writeAll(socket, body)
    }

    private fun sendHtmlResponse(socket: SocketChannel, method: String, html: String) {
        val body = html.toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }

        writeAll(socket, header.toByteArray(StandardCharsets.US_ASCII))
        if (method != "HEAD") writeAll(socket, body)
    }

    private fun sendTextResponse(socket: SocketChannel, status: String, text: String) {
        val body = text.toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }

        writeAll(socket, header.toByteArray(StandardCharsets.US_ASCII))
        writeAll(socket, body)
    }

    private fun sendEmptyResponse(socket: SocketChannel, status: String) {
        val header = "HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        writeAll(socket, header.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun writeAll(socket: SocketChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            socket.write(buffer)
        }
    }
}
