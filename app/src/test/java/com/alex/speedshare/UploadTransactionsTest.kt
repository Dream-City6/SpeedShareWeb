package com.alex.speedshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UploadTransactionsTest {
    @Test
    fun stagedUploadCommitsWithoutExposingTheTemporaryName() {
        withTempDirectory { root ->
            val staging = File.createTempFile(".SpeedShareWebUpload-", ".tmp", root).apply {
                writeText("complete payload")
            }

            val committed = commitStagedUpload(staging, root, "photo.jpg")

            assertEquals("photo.jpg", committed.name)
            assertEquals("complete payload", committed.readText())
            assertFalse(staging.exists())
        }
    }

    @Test
    fun existingDestinationIsNeverOverwritten() {
        withTempDirectory { root ->
            File(root, "report.txt").writeText("original")
            val staging = File.createTempFile(".SpeedShareWebUpload-", ".tmp", root).apply {
                writeText("new")
            }

            val committed = commitStagedUpload(staging, root, "report.txt")

            assertEquals("original", File(root, "report.txt").readText())
            assertEquals("report (1).txt", committed.name)
            assertEquals("new", committed.readText())
        }
    }

    @Test
    fun exhaustedNameAttemptsLeaveTheStagingFileIntact() {
        withTempDirectory { root ->
            File(root, "item.bin").writeText("existing")
            val staging = File.createTempFile(".SpeedShareWebUpload-", ".tmp", root).apply {
                writeText("staged")
            }

            val result = runCatching {
                commitStagedUpload(staging, root, "item.bin", maxNameAttempts = 1)
            }

            assertTrue(result.isFailure)
            assertTrue(staging.isFile)
            assertEquals("staged", staging.readText())
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("speedshare-upload-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
