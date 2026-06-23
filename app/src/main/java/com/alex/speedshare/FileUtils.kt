package com.alex.speedshare

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
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

fun querySharedFile(context: Context, uri: Uri): SharedFile? {
    var name = "shared-file.bin"
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
                    name = cursor.getString(nameIndex)
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

    val mimeType = context.contentResolver.getType(uri) ?: guessMimeType(name)

    return SharedFile(
        uri = uri,
        name = name,
        size = size,
        mimeType = mimeType,
        modifiedAt = modifiedAt
    )
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
