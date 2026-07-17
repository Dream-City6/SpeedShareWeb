package com.alex.speedshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class HttpUtilsTest {
    @Test
    fun parsesOpenEndedVideoRange() {
        assertEquals(100L..999L, parseRange("bytes=100-", 1_000L))
    }

    @Test
    fun parsesSuffixVideoRange() {
        assertEquals(900L..999L, parseRange("bytes=-100", 1_000L))
    }

    @Test
    fun acceptsCaseInsensitiveRangeUnit() {
        assertEquals(0L..1L, parseRange("Bytes=0-1", 1_000L))
    }

    @Test
    fun rejectsUnsatisfiableRange() {
        assertNull(parseRange("bytes=1000-", 1_000L))
    }

    @Test
    fun clampsRangeEndToFileSize() {
        assertEquals(900L..999L, parseRange("bytes=900-2000", 1_000L))
    }

    @Test
    fun parsesAValidHttpRequest() {
        val request = readRequest(
            "GET /api/status HTTP/1.1\r\n" +
                "Host: 192.168.1.10:9999\r\n" +
                "Connection: close\r\n\r\n"
        )

        assertEquals("GET", request?.method)
        assertEquals("/api/status", request?.target)
        assertEquals("192.168.1.10:9999", request?.headers?.get("host"))
    }

    @Test
    fun rejectsDuplicateContentLengthHeaders() {
        assertNull(
            readRequest(
                "POST /upload HTTP/1.1\r\n" +
                    "Host: phone\r\n" +
                    "Content-Length: 10\r\n" +
                    "Content-Length: 20\r\n\r\n"
            )
        )
    }

    @Test
    fun trustedMutationHeaderIsRequiredOnlyForNonAuthPosts() {
        val trusted = readRequest(
            "POST /api/delete HTTP/1.1\r\n" +
                "Host: phone\r\n" +
                "Content-Length: 0\r\n" +
                "X-SpeedShare-Request: 1\r\n\r\n"
        )!!
        val untrusted = readRequest(
            "POST /api/delete HTTP/1.1\r\n" +
                "Host: phone\r\n" +
                "Content-Length: 0\r\n\r\n"
        )!!
        val login = readRequest(
            "POST /login HTTP/1.1\r\n" +
                "Host: phone\r\n" +
                "Content-Length: 0\r\n\r\n"
        )!!

        assertTrue(isTrustedMutationRequest(trusted, "/api/delete"))
        assertFalse(isTrustedMutationRequest(untrusted, "/api/delete"))
        assertTrue(isTrustedMutationRequest(login, "/login"))
    }

    @Test
    fun rejectsDuplicateTrustedMutationHeaders() {
        assertNull(
            readRequest(
                "POST /api/delete HTTP/1.1\r\n" +
                    "Host: phone\r\n" +
                    "Content-Length: 0\r\n" +
                    "X-SpeedShare-Request: 1\r\n" +
                    "X-SpeedShare-Request: 1\r\n\r\n"
            )
        )
    }

    @Test
    fun rejectsUnsupportedChunkedRequestBodies() {
        assertNull(
            readRequest(
                "POST /upload HTTP/1.1\r\n" +
                    "Host: phone\r\n" +
                    "Transfer-Encoding: chunked\r\n\r\n"
            )
        )
    }

    @Test
    fun rejectsHttp11RequestsWithoutHost() {
        assertNull(readRequest("GET / HTTP/1.1\r\nConnection: close\r\n\r\n"))
    }

    @Test
    fun acceptsHttp10RequestsWithoutHost() {
        assertTrue(readRequest("GET / HTTP/1.0\r\n\r\n") != null)
    }

    @Test
    fun rejectsUnsupportedMethodsAndAbsoluteFormTargets() {
        assertNull(readRequest("PUT /upload HTTP/1.1\r\nHost: phone\r\n\r\n"))
        assertNull(readRequest("GET http://example.com/ HTTP/1.1\r\nHost: phone\r\n\r\n"))
    }

    private fun readRequest(value: String): HttpRequest? {
        return readHttpRequest(ByteArrayInputStream(value.toByteArray(StandardCharsets.ISO_8859_1)))
    }
}
