package com.alex.speedshare.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class MigrationCoreTest {
    @Test
    fun normalizeRelativePath_rejectsTraversal() {
        assertNull(normalizeRelativePath("../secret.txt"))
        assertNull(normalizeRelativePath("DCIM/../../secret.txt"))
        assertNull(normalizeRelativePath("./photo.jpg"))
    }

    @Test
    fun normalizeRelativePath_normalizesSeparators() {
        assertEquals("DCIM/Camera/a.jpg", normalizeRelativePath("/DCIM\\Camera/a.jpg/"))
    }

    @Test
    fun speedResult_averageUsesBothDirections() {
        val result = SpeedTestResult(
            latencyMs = 10,
            uploadBytesPerSecond = 40,
            downloadBytesPerSecond = 60,
            stabilityPercent = 95
        )
        assertEquals(50, result.averageBytesPerSecond)
    }

    @Test
    fun scanResult_countsCategories() {
        val files = listOf(
            MigrationFileItem(File("a.jpg"), "DCIM/a.jpg", 100, 0, MigrationCategory.PHOTOS),
            MigrationFileItem(File("b.jpg"), "DCIM/b.jpg", 200, 0, MigrationCategory.PHOTOS),
            MigrationFileItem(File("c.pdf"), "Documents/c.pdf", 50, 0, MigrationCategory.DOCUMENTS)
        )
        val result = MigrationScanResult(files = files)
        assertEquals(2, result.count(MigrationCategory.PHOTOS))
        assertEquals(300, result.bytes(MigrationCategory.PHOTOS))
        assertEquals(1, result.count(MigrationCategory.DOCUMENTS))
    }
}
