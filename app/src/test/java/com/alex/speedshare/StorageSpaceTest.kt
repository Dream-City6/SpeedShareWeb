package com.alex.speedshare

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageSpaceTest {
    @Test
    fun uploadCapacityAlwaysKeepsTheSystemReserve() {
        val available = 2L * 1024L * 1024L * 1024L

        assertEquals(
            available - UPLOAD_STORAGE_RESERVE_BYTES,
            calculateUploadAvailableBytes(available)
        )
    }

    @Test
    fun concurrentReservationsAreSubtractedFromUploadCapacity() {
        val available = 1024L * 1024L * 1024L
        val activeReservations = 300L * 1024L * 1024L

        assertEquals(
            available - UPLOAD_STORAGE_RESERVE_BYTES - activeReservations,
            calculateUploadAvailableBytes(available, activeReservations)
        )
    }

    @Test
    fun capacityNeverBecomesNegative() {
        assertEquals(0L, calculateUploadAvailableBytes(128L * 1024L * 1024L))
        assertEquals(0L, calculateUploadAvailableBytes(512L, 1024L))
    }
}
