package com.alex.speedshare

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale

fun readHttpRequest(input: InputStream): HttpRequest? {
    val output = ByteArrayOutputStream()
    var matched = 0

    while (output.size() < MAX_HEADER_BYTES) {
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
    val requestLine = lines.firstOrNull()?.split(' ') ?: return null
    if (requestLine.size != 3 || requestLine.any(String::isBlank)) return null

    val method = requestLine[0].uppercase(Locale.ROOT)
    if (method !in SUPPORTED_METHODS) return null
    val target = requestLine[1]
    if (target.length !in 1..MAX_TARGET_CHARS || !target.startsWith('/') || target.startsWith("//")) return null
    val version = requestLine[2]
    if (version != "HTTP/1.1" && version != "HTTP/1.0") return null

    val headers = mutableMapOf<String, String>()
    lines.drop(1).forEach { line ->
        if (line.isEmpty()) return@forEach
        if (line.first().isWhitespace()) return null
        val separator = line.indexOf(':')
        if (separator <= 0) return null
        val name = line.substring(0, separator).trim().lowercase(Locale.ROOT)
        if (name.isEmpty() || name.any { !isHttpTokenCharacter(it) }) return null
        val value = line.substring(separator + 1).trim()
        if (value.any { it.code == 0x7f || (it.code < 0x20 && it != '\t') }) return null
        if (name in SINGLE_VALUE_HEADERS && headers.containsKey(name)) return null
        headers[name] = value
    }

    if (version == "HTTP/1.1" && headers["host"].isNullOrBlank()) return null
    val transferEncoding = headers["transfer-encoding"]
    if (transferEncoding != null && !transferEncoding.equals("identity", ignoreCase = true)) return null
    if (transferEncoding != null && headers.containsKey("content-length")) return null
    headers["content-length"]?.let { value ->
        if (value.isEmpty() || value.any { !it.isDigit() } || value.toLongOrNull() == null) return null
    }

    return HttpRequest(
        method = method,
        target = target,
        headers = headers
    )
}

fun isTrustedMutationRequest(request: HttpRequest, targetPath: String): Boolean {
    if (request.method != "POST" || targetPath == "/login" || targetPath == "/logout") return true
    return request.headers[TRUSTED_MUTATION_HEADER] == TRUSTED_MUTATION_VALUE
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

private fun isHttpTokenCharacter(char: Char): Boolean {
    return char.isLetterOrDigit() || char in "!#$%&'*+-.^_`|~"
}

private const val MAX_HEADER_BYTES = 64 * 1024
private const val MAX_TARGET_CHARS = 8 * 1024
private val SUPPORTED_METHODS = setOf("GET", "HEAD", "POST")
private val SINGLE_VALUE_HEADERS = setOf(
    "host",
    "content-length",
    "transfer-encoding",
    "authorization",
    TRUSTED_MUTATION_HEADER
)

internal const val TRUSTED_MUTATION_HEADER = "x-speedshare-request"
internal const val TRUSTED_MUTATION_VALUE = "1"
