package com.alex.speedshare

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.os.Environment
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

fun querySharedFile(context: Context, uri: Uri, mimeTypeHint: String? = null): SharedFile? {
    var queriedName: String? = null
    var size = -1L
    var modifiedAt = 0L

    try {
        context.contentResolver.query(
            uri,
            arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                "last_modified"
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndex("last_modified")

                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    queriedName = cursor.getString(nameIndex)
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
                if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                    modifiedAt = cursor.getLong(modifiedIndex)
                }
            }
        }
    } catch (_: Exception) {
    }

    val uriName = runCatching {
        URLDecoder.decode(uri.lastPathSegment.orEmpty(), StandardCharsets.UTF_8.name())
            .substringAfterLast('/')
            .substringAfterLast(':')
            .takeIf { it.isNotBlank() }
    }.getOrNull()
    val initialName = queriedName?.takeIf { it.isNotBlank() } ?: uriName.orEmpty()
    val resolvedMimeType = context.contentResolver.getType(uri)?.trim().orEmpty()
    val hintedMimeType = mimeTypeHint?.trim().orEmpty()
    val guessedMimeType = guessMimeType(initialName)
    val mimeType = resolvedMimeType.takeIf { it.isConcreteMimeType() }
        ?: hintedMimeType.takeIf { it.isConcreteMimeType() }
        ?: guessedMimeType.takeIf { it.isConcreteMimeType() }
        ?: sniffMimeType(context, uri)
        ?: resolvedMimeType.takeIf { it.isNotBlank() && it != "*/*" }
        ?: hintedMimeType.takeIf { it.isNotBlank() && it != "*/*" }
        ?: guessedMimeType
    val name = normalizeSharedFileName(initialName, mimeType)

    return SharedFile(
        uri = uri,
        name = name,
        size = size,
        mimeType = mimeType,
        modifiedAt = modifiedAt
    )
}


private fun String.isConcreteMimeType(): Boolean {
    val normalized = lowercase(Locale.ROOT).substringBefore(';').trim()
    return normalized.isNotBlank() &&
        normalized != "*/*" &&
        normalized != "application/octet-stream" &&
        normalized != "binary/octet-stream" &&
        !normalized.contains('*')
}

private fun sniffMimeType(context: Context, uri: Uri): String? {
    val header = ByteArray(32)
    val count = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.read(header)
        } ?: -1
    }.getOrDefault(-1)
    if (count < 4) return null

    fun byte(index: Int): Int = header[index].toInt() and 0xFF
    fun ascii(start: Int, length: Int): String? {
        if (count < start + length) return null
        return String(header, start, length, StandardCharsets.US_ASCII)
    }

    return when {
        byte(0) == 0xFF && byte(1) == 0xD8 && byte(2) == 0xFF -> "image/jpeg"
        byte(0) == 0x89 && ascii(1, 3) == "PNG" -> "image/png"
        ascii(0, 6) == "GIF87a" || ascii(0, 6) == "GIF89a" -> "image/gif"
        ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> "image/webp"
        ascii(0, 4) == "%PDF" -> "application/pdf"
        ascii(4, 4) == "ftyp" -> {
            when (ascii(8, 4)?.lowercase(Locale.ROOT)) {
                "heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1" -> "image/heic"
                "avif", "avis" -> "image/avif"
                else -> "video/mp4"
            }
        }
        else -> null
    }
}

private fun normalizeSharedFileName(rawName: String, mimeType: String): String {
    val cleaned = rawName
        .replace('\r', '_')
        .replace('\n', '_')
        .replace('/', '_')
        .replace('\\', '_')
        .trim()
    val extension = extensionForMimeType(mimeType)
    if (cleaned.isBlank()) return if (extension != null) "shared-file.$extension" else "shared-file.bin"

    val dot = cleaned.lastIndexOf('.')
    val currentExtension = cleaned.substringAfterLast('.', "")
    return when {
        extension != null && (dot <= 0 || currentExtension.equals("bin", ignoreCase = true)) -> {
            val base = if (dot > 0) cleaned.substring(0, dot) else cleaned
            "$base.$extension"
        }
        else -> cleaned
    }
}

private fun extensionForMimeType(mimeType: String): String? {
    return when (mimeType.lowercase(Locale.ROOT).substringBefore(';')) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        "image/gif" -> "gif"
        "image/avif" -> "avif"
        "video/mp4" -> "mp4"
        "audio/mpeg" -> "mp3"
        "application/pdf" -> "pdf"
        else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType.substringBefore(';'))
    }
}

fun openSpeedShareTrashInFileManager(context: Context): Boolean {
    val trashDirectory = File(Environment.getExternalStorageDirectory(), SPEEDSHAREWEB_TRASH_DIRECTORY)
    runCatching {
        trashDirectory.mkdirs()
        File(trashDirectory, ".nomedia").apply { if (!exists()) createNewFile() }
    }

    val documentUri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:$SPEEDSHAREWEB_TRASH_DIRECTORY"
    )
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    if (viewIntent.resolveActivity(context.packageManager) != null) {
        val opened = runCatching {
            context.startActivity(viewIntent)
            true
        }.getOrDefault(false)
        if (opened) return true
    }

    val treeIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, documentUri)
        }
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
    }
    return runCatching {
        context.startActivity(treeIntent)
        true
    }.getOrDefault(false)
}

fun openAllFilesAccessSettings(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}

fun safeResolve(root: File, relativePath: String): File? {
    return try {
        val rootCanonical = root.canonicalFile
        val normalized = relativePath.trim('/').replace('\\', '/')
        val candidate = if (normalized.isEmpty()) {
            rootCanonical
        } else {
            File(rootCanonical, normalized).canonicalFile
        }

        val rootPath = rootCanonical.path
        val candidatePath = candidate.path
        if (candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)) {
            candidate
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

fun createUniqueFile(directory: File, originalName: String): File {
    var candidate = File(directory, originalName)
    if (!candidate.exists()) return candidate

    val dot = originalName.lastIndexOf('.')
    val base = if (dot > 0) originalName.substring(0, dot) else originalName
    val extension = if (dot > 0) originalName.substring(dot) else ""

    var index = 1
    while (candidate.exists()) {
        candidate = File(directory, "$base ($index)$extension")
        index++
    }
    return candidate
}

fun guessMimeType(name: String): String {
    return URLConnection.guessContentTypeFromName(name) ?: when {
        name.endsWith(".apk", ignoreCase = true) -> "application/vnd.android.package-archive"
        name.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
        name.endsWith(".flac", ignoreCase = true) -> "audio/flac"
        name.endsWith(".7z", ignoreCase = true) -> "application/x-7z-compressed"
        name.endsWith(".rar", ignoreCase = true) -> "application/vnd.rar"
        else -> "application/octet-stream"
    }
}

fun previewKindFor(mimeType: String, name: String): PreviewKind {
    val lower = name.lowercase(Locale.ROOT)
    return when {
        mimeType.startsWith("image/") -> PreviewKind.IMAGE
        mimeType.startsWith("video/") -> PreviewKind.VIDEO
        mimeType.startsWith("audio/") -> PreviewKind.AUDIO
        mimeType == "application/pdf" || lower.endsWith(".pdf") -> PreviewKind.PDF
        else -> PreviewKind.DOWNLOAD
    }
}

fun canGenerateThumbnail(mimeType: String): Boolean {
    return mimeType.startsWith("image/") || mimeType.startsWith("video/")
}

fun iconForName(name: String, mimeType: String): String {
    val lower = name.lowercase(Locale.ROOT)
    return when {
        mimeType.startsWith("image/") -> "🖼️"
        mimeType.startsWith("video/") -> "🎬"
        mimeType.startsWith("audio/") -> "🎵"
        lower.endsWith(".apk") -> "📦"
        lower.endsWith(".pdf") -> "📕"
        lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") -> "🗜️"
        lower.endsWith(".doc") || lower.endsWith(".docx") -> "📝"
        lower.endsWith(".xls") || lower.endsWith(".xlsx") -> "📊"
        lower.endsWith(".ppt") || lower.endsWith(".pptx") -> "📽️"
        lower.endsWith(".txt") || lower.endsWith(".md") -> "📃"
        else -> "📄"
    }
}

fun findLocalIpv4Address(): String? {
    return try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan", ignoreCase = true)) 0 else 1 }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}

fun formatBytes(size: Long): String {
    if (size < 0L) return "—"

    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = size.toDouble()
    var index = 0

    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }

    return String.format(Locale.US, "%.2f %s", value, units[index])
}

fun formatDate(timestamp: Long): String {
    if (timestamp <= 0L) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}

fun urlEncode(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

fun urlDecode(value: String): String {
    return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

fun escapeHtml(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

fun jsString(value: String): String {
    return "'" + value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\r", "\\r")
        .replace("\n", "\\n") + "'"
}

fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

fun hasManageAllFilesAccessGlobal(): Boolean {
    return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
        android.os.Environment.isExternalStorageManager()
}
