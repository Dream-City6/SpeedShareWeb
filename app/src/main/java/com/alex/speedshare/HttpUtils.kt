package com.alex.speedshare

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale

fun readHttpRequest(input: InputStream): HttpRequest? {
    val output = ByteArrayOutputStream()
    var matched = 0

    while (output.size() < 64 * 1024) {
        val value = input.read()
        if (value < 0) return null
        output.write(value)

        matched = when {
            matched == 0 && value == '\r'.code -> 1
            matched == 1 && value == '\n'.code -> 2
            matched == 2 && value == '\r'.code -> 3
            matched == 3 && value == '\n'.code -> 4
            value == '\r'.code -> 1
            else -> 0
        }

        if (matched == 4) break
    }

    if (matched != 4) return null

    val headerText = output.toString(StandardCharsets.ISO_8859_1.name())
    val lines = headerText.split("\r\n")
    val requestLine = lines.firstOrNull()?.split(" ", limit = 3) ?: return null
    if (requestLine.size < 2) return null

    val headers = mutableMapOf<String, String>()
    lines.drop(1).forEach { line ->
        val separator = line.indexOf(':')
        if (separator > 0) {
            headers[line.substring(0, separator).trim().lowercase(Locale.ROOT)] =
                line.substring(separator + 1).trim()
        }
    }

    return HttpRequest(
        method = requestLine[0].uppercase(Locale.ROOT),
        target = requestLine[1],
        headers = headers
    )
}

fun parseTarget(target: String): ParsedTarget {
    return try {
        val uri = URI(target)
        val query = mutableMapOf<String, String>()
        uri.rawQuery?.split('&')?.forEach { item ->
            if (item.isNotEmpty()) {
                val parts = item.split('=', limit = 2)
                val key = urlDecode(parts[0])
                val value = if (parts.size > 1) urlDecode(parts[1]) else ""
                query[key] = value
            }
        }

        ParsedTarget(
            path = urlDecode(uri.rawPath ?: "/"),
            query = query
        )
    } catch (_: Exception) {
        ParsedTarget("/", emptyMap())
    }
}

fun parseRange(rangeHeader: String?, totalSize: Long): LongRange? {
    if (totalSize <= 0L || rangeHeader == null || !rangeHeader.startsWith("bytes=", ignoreCase = true)) {
        return null
    }

    val value = rangeHeader.substringAfter('=').substringBefore(',').trim()
    val separator = value.indexOf('-')
    if (separator < 0) return null

    val startText = value.substring(0, separator).trim()
    val endText = value.substring(separator + 1).trim()

    if (startText.isEmpty()) {
        val suffixLength = endText.toLongOrNull() ?: return null
        if (suffixLength <= 0L) return null
        val start = (totalSize - suffixLength).coerceAtLeast(0L)
        return start..(totalSize - 1L)
    }

    val start = startText.toLongOrNull() ?: return null
    val end = (endText.toLongOrNull() ?: totalSize - 1L).coerceAtMost(totalSize - 1L)

    if (start < 0L || start >= totalSize || end < start) return null
    return start..end
}
