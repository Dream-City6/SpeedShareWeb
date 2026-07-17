package com.alex.speedshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.Properties

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

    @Test
    fun trashMoveAndRestoreRoundTripLeavesNoHiddenEntry() {
        withTempDirectory { root ->
            val manager = TrashManager(root, translator)
            val original = File(root, "roundtrip.txt").apply { writeText("safe content") }

            val entry = manager.moveToTrash(original, "roundtrip.txt")

            assertFalse(original.exists())
            assertTrue(File(manager.trashRoot, "${entry.id}/$TRASH_METADATA_FILE").isFile)
            assertFalse(File(manager.trashRoot, "${entry.id}/$TRASH_PENDING_METADATA_FILE").exists())

            val restored = manager.restore(entry.id, ConflictPolicy.AUTO_RENAME)

            assertEquals(original.canonicalPath, restored?.canonicalPath)
            assertEquals("safe content", original.readText())
            assertTrue(manager.listEntries().isEmpty())
        }
    }

    @Test
    fun completePendingTrashEntryIsRecoveredAfterRestart() {
        withTempDirectory { root ->
            val trashRoot = File(root, SPEEDSHAREWEB_TRASH_DIRECTORY).apply { mkdirs() }
            val entryDirectory = File(trashRoot, "1000_recovery").apply { mkdirs() }
            val data = File(entryDirectory, TRASH_DATA_FILE).apply { writeText("complete") }
            writePendingMetadata(
                entryDirectory = entryDirectory,
                name = "recovered.txt",
                originalRelativePath = "recovered.txt",
                size = data.length(),
                itemCount = 1,
                isDirectory = false
            )

            val manager = TrashManager(root, translator)
            val entries = manager.listEntries()

            assertEquals(1, entries.size)
            assertEquals("recovered.txt", entries.single().name)
            assertTrue(File(entryDirectory, TRASH_METADATA_FILE).isFile)
            assertFalse(File(entryDirectory, TRASH_PENDING_METADATA_FILE).exists())
        }
    }

    @Test
    fun incompletePendingTrashEntryRemainsHidden() {
        withTempDirectory { root ->
            val trashRoot = File(root, SPEEDSHAREWEB_TRASH_DIRECTORY).apply { mkdirs() }
            val entryDirectory = File(trashRoot, "1000_incomplete").apply { mkdirs() }
            File(entryDirectory, TRASH_DATA_FILE).writeText("partial")
            writePendingMetadata(
                entryDirectory = entryDirectory,
                name = "partial.txt",
                originalRelativePath = "partial.txt",
                size = 10_000L,
                itemCount = 1,
                isDirectory = false
            )

            val manager = TrashManager(root, translator)

            assertTrue(manager.listEntries().isEmpty())
            assertTrue(File(entryDirectory, TRASH_PENDING_METADATA_FILE).isFile)
            assertFalse(File(entryDirectory, TRASH_METADATA_FILE).exists())
        }
    }

    @Test
    fun nestedOperationSelectionsCollapseToTheParent() {
        withTempDirectory { root ->
            val parent = File(root, "folder").apply { mkdirs() }
            val child = File(parent, "child.txt").apply { writeText("child") }
            val siblingPrefix = File(root, "folder-copy").apply { mkdirs() }

            val collapsed = collapseNestedOperationSources(listOf(child, parent, siblingPrefix, child))

            assertEquals(
                listOf(parent.canonicalPath, siblingPrefix.canonicalPath),
                collapsed.map { it.canonicalPath }
            )
        }
    }

    private fun writePendingMetadata(
        entryDirectory: File,
        name: String,
        originalRelativePath: String,
        size: Long,
        itemCount: Int,
        isDirectory: Boolean
    ) {
        val properties = Properties().apply {
            setProperty("name", name)
            setProperty("originalRelativePath", originalRelativePath)
            setProperty("deletedAtMs", "1000")
            setProperty("size", size.toString())
            setProperty("itemCount", itemCount.toString())
            setProperty("isDirectory", isDirectory.toString())
        }
        FileOutputStream(File(entryDirectory, TRASH_PENDING_METADATA_FILE)).use { output ->
            properties.store(output, "test")
            output.fd.sync()
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
