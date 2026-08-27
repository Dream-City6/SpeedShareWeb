package com.alex.speedshare.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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
        assertEquals(50L, result.averageBytesPerSecond)
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
        assertEquals(300L, result.bytes(MigrationCategory.PHOTOS))
        assertEquals(1, result.count(MigrationCategory.DOCUMENTS))
    }

    @Test
    fun resilientPairToken_isRandomAndStrongLength() {
        val first = ResilientMigrationClient.newInboundToken()
        val second = ResilientMigrationClient.newInboundToken()
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(first, second)
    }

    @Test
    fun migrationHashCache_returnsStableSha256() {
        val file = Files.createTempFile("speedshare-hash", ".bin").toFile()
        try {
            file.writeText("SpeedShare migration")
            val first = MigrationHashCache.sha256(file)
            val second = MigrationHashCache.sha256(file)
            assertEquals(64, first.length)
            assertEquals(first, second)
        } finally {
            file.delete()
            MigrationHashCache.clear()
        }
    }
}
