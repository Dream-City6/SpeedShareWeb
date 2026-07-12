package com.alex.speedshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileSafetyTest {
    private val translator = Localization.translator(ResolvedLanguage.ENGLISH)

    @Test
    fun cancelledTransactionalOverwriteKeepsOriginalDestination() {
        withTempDirectory { root ->
            val source = File(root, "source.txt").apply { writeText("new content") }
            val destination = File(root, "destination.txt").apply { writeText("old content") }
            var cancellationChecks = 0

            runCatching {
                replaceWithCopiedSource(
                    source = source,
                    destination = destination,
                    isCancelled = { ++cancellationChecks >= 4 },
                    translator = translator
                )
            }

            assertEquals("old content", destination.readText())
            assertEquals("new content", source.readText())
            assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".SpeedShareWebReplace-") })
        }
    }

    @Test
    fun successfulTransactionalOverwriteCommitsNewContent() {
        withTempDirectory { root ->
            val source = File(root, "source.txt").apply { writeText("new content") }
            val destination = File(root, "destination.txt").apply { writeText("old content") }

            replaceWithCopiedSource(source, destination, translator = translator)

            assertEquals("new content", destination.readText())
            assertEquals("new content", source.readText())
            assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".SpeedShareWebReplace-") })
        }
    }

    @Test
    fun skippedRestoreStaysInTrashAndReturnsNoDestination() {
        withTempDirectory { root ->
            val manager = TrashManager(root, translator)
            val original = File(root, "note.txt").apply { writeText("trashed") }
            val entry = manager.moveToTrash(original, "note.txt")
            original.writeText("existing")

            val restored = manager.restore(entry.id, ConflictPolicy.SKIP)

            assertNull(restored)
            assertEquals("existing", original.readText())
            assertTrue(manager.listEntries().any { it.id == entry.id })
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("speedshare-safety-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
