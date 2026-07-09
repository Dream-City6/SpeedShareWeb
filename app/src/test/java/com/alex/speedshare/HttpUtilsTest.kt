package com.alex.speedshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
