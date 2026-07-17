package com.alex.speedshare

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.UUID

const val SPEEDSHAREWEB_TRASH_DIRECTORY = ".SpeedShareWebTrash"
const val LEGACY_SPEEDSHARE_TRASH_DIRECTORY = ".SpeedShareTrash"
internal const val TRASH_DATA_FILE = "data"
internal const val TRASH_METADATA_FILE = "meta.properties"
internal const val TRASH_PENDING_METADATA_FILE = "meta.properties.pending"

class TrashManager(
    private val rootDirectory: File,
    private val translator: Translator
) {
    private val legacyTrashRoot: File = File(rootDirectory, LEGACY_SPEEDSHARE_TRASH_DIRECTORY)
    val trashRoot: File = File(rootDirectory, SPEEDSHAREWEB_TRASH_DIRECTORY)

    init {
        migrateLegacyTrash()
        if (!trashRoot.exists() && !trashRoot.mkdirs()) {
            throw IllegalStateException(translator.text("trash_create_dir_failed", trashRoot.name))
        }
        if (!trashRoot.isDirectory) {
            throw IllegalStateException(translator.text("trash_create_dir_failed", trashRoot.name))
        }
        recoverPendingEntries()
        runCatching {
            File(trashRoot, ".nomedia").apply {
                if (!exists()) createNewFile()
            }
        }
    }

    fun isTrashPath(file: File): Boolean {
        return runCatching {
            val candidate = file.canonicalFile.path
            listOf(trashRoot, legacyTrashRoot).any { root ->
                val trash = root.canonicalFile.path
                candidate == trash || candidate.startsWith(trash + File.separator)
            }
        }.getOrDefault(false)
    }

    private fun migrateLegacyTrash() {
        if (!legacyTrashRoot.exists()) return
        if (!trashRoot.exists() && legacyTrashRoot.renameTo(trashRoot)) return

        trashRoot.mkdirs()
        legacyTrashRoot.listFiles()?.forEach { child ->
            if (child.name == ".nomedia") return@forEach
            val requested = File(trashRoot, child.name)
            val destination = if (requested.exists()) createUniquePath(requested) else requested
            val moved = child.renameTo(destination)
            if (!moved) {
                runCatching {
                    copyRecursivelyControlled(child, destination, translator = translator)
                    child.deleteRecursively()
                }
            }
        }
        runCatching { legacyTrashRoot.deleteRecursively() }
    }

    fun moveToTrash(
        source: File,
        relativePath: String,
        onBytes: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): TrashEntry {
        require(source.exists()) { translator.text("trash_file_missing") }
        require(!isTrashPath(source)) { translator.text("trash_repeat") }
        require(isPathInsideRoot(source)) { translator.text("trash_original_invalid") }

        if (!trashRoot.exists() && !trashRoot.mkdirs()) {
            throw IllegalStateException(translator.text("trash_create_dir_failed", trashRoot.name))
        }
        val id = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val entryDirectory = File(trashRoot, id)
        val dataFile = File(entryDirectory, TRASH_DATA_FILE)
        val pendingMetadataFile = File(entryDirectory, TRASH_PENDING_METADATA_FILE)
        val finalMetadataFile = File(entryDirectory, TRASH_METADATA_FILE)
        if (!entryDirectory.mkdir()) {
            throw IllegalStateException(translator.text("trash_create_dir_failed", entryDirectory.name))
        }

        val expectedSize = recursiveSize(source)
        val expectedItemCount = recursiveItemCount(source)
        val metadata = Properties().apply {
            setProperty("name", source.name)
            setProperty("originalRelativePath", relativePath)
            setProperty("deletedAtMs", System.currentTimeMillis().toString())
            setProperty("size", expectedSize.toString())
            setProperty("itemCount", expectedItemCount.toString())
            setProperty("isDirectory", source.isDirectory.toString())
        }

        var moved = false
        var copiedSafely = false
        try {
            writePropertiesFile(pendingMetadataFile, metadata)
            moved = source.renameTo(dataFile)
            if (!moved) {
                copyRecursivelyControlled(source, dataFile, onBytes, isCancelled, translator)
                if (isCancelled()) throw InterruptedException(translator.text("op_cancelled_exception"))
                copiedSafely = true
                if (!deleteRecursivelyControlled(source, isCancelled)) {
                    // Keep the complete trash copy. The source may already be partially deleted,
                    // so removing this copy here could cause permanent data loss.
                }
            }

            if (!isCompleteTrashData(dataFile, expectedSize, expectedItemCount)) {
                throw IllegalStateException(translator.text("trash_content_missing"))
            }
            if (!pendingMetadataFile.renameTo(finalMetadataFile)) {
                throw IllegalStateException(translator.text("trash_metadata_failed"))
            }
        } catch (error: Throwable) {
            if (!moved && !copiedSafely) {
                runCatching { dataFile.deleteRecursively() }
                runCatching { entryDirectory.deleteRecursively() }
            }
            // If data was already moved or copied completely, keep the pending metadata and data.
            // listEntries() can safely promote it after validating size and item count.
            throw error
        }

        return readEntry(entryDirectory)
            ?: throw IllegalStateException(translator.text("trash_metadata_failed"))
    }

    fun listEntries(): List<TrashEntry> {
        if (!trashRoot.exists()) return emptyList()
        recoverPendingEntries()
        return trashRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull(::readEntry)
            ?.sortedByDescending { it.deletedAtMs }
            .orEmpty()
    }

    fun restore(
        id: String,
        conflictPolicy: ConflictPolicy,
        onBytes: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): File? {
        val entryDirectory = safeEntryDirectory(id)
            ?: throw IllegalArgumentException(translator.text("trash_entry_missing"))
        val entry = readEntry(entryDirectory)
            ?: throw IllegalStateException(translator.text("trash_metadata_corrupt"))
        val dataFile = File(entryDirectory, TRASH_DATA_FILE)
        if (!dataFile.exists()) throw IllegalStateException(translator.text("trash_content_missing"))

        val requested = safeResolve(rootDirectory, entry.originalRelativePath)
            ?: throw IllegalStateException(translator.text("trash_original_invalid"))
        requested.parentFile?.mkdirs()
        val destination = resolveConflict(requested, conflictPolicy, translator)
            ?: return null

        if (conflictPolicy == ConflictPolicy.OVERWRITE && requested.exists()) {
            replaceWithCopiedSource(
                source = dataFile,
                destination = destination,
                onBytes = onBytes,
                isCancelled = isCancelled,
                translator = translator
            )
            cleanupRestoredEntry(entryDirectory, dataFile)
            return destination
        }

        var moved = false
        var copiedSafely = false
        try {
            moved = dataFile.renameTo(destination)
            if (!moved) {
                copyRecursivelyControlled(dataFile, destination, onBytes, isCancelled, translator)
                if (isCancelled()) throw InterruptedException(translator.text("op_cancelled_exception"))
                copiedSafely = true
            }
            cleanupRestoredEntry(entryDirectory, dataFile)
            return destination
        } catch (error: Throwable) {
            if (!moved && !copiedSafely && destination.exists()) runCatching { destination.deleteRecursively() }
            throw error
        }
    }

    fun permanentDelete(id: String): Boolean {
        val entryDirectory = safeEntryDirectory(id) ?: return false
        return entryDirectory.deleteRecursively()
    }

    fun emptyTrash(): Boolean {
        trashRoot.mkdirs()
        var success = true
        trashRoot.listFiles()?.forEach { child ->
            if (child.name != ".nomedia" && !child.deleteRecursively()) success = false
        }
        return success
    }

    private fun safeEntryDirectory(id: String): File? {
        if (!id.matches(Regex("[A-Za-z0-9_]+"))) return null
        val candidate = File(trashRoot, id)
        return if (candidate.isDirectory && isTrashPath(candidate)) candidate else null
    }

    private fun readEntry(entryDirectory: File): TrashEntry? {
        return runCatching {
            val metadataFile = File(entryDirectory, TRASH_METADATA_FILE)
            val dataFile = File(entryDirectory, TRASH_DATA_FILE)
            if (!metadataFile.isFile || !dataFile.exists()) return null
            val properties = readPropertiesFile(metadataFile) ?: return null
            val name = properties.getProperty("name")?.takeIf { it.isNotBlank() } ?: return null
            val originalRelativePath = properties.getProperty("originalRelativePath") ?: return null
            val original = safeResolve(rootDirectory, originalRelativePath) ?: return null
            if (original.canonicalFile == rootDirectory.canonicalFile || isTrashPath(original)) return null
            val deletedAtMs = properties.getProperty("deletedAtMs")?.toLongOrNull()?.takeIf { it > 0L } ?: return null
            val size = properties.getProperty("size")?.toLongOrNull()?.takeIf { it >= 0L } ?: return null
            TrashEntry(
                id = entryDirectory.name,
                name = name,
                originalRelativePath = originalRelativePath,
                deletedAtMs = deletedAtMs,
                size = size,
                isDirectory = properties.getProperty("isDirectory")?.toBooleanStrictOrNull() ?: false
            )
        }.getOrNull()
    }

    private fun recoverPendingEntries() {
        trashRoot.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.forEach(::recoverPendingEntry)
    }

    private fun recoverPendingEntry(entryDirectory: File) {
        val finalMetadataFile = File(entryDirectory, TRASH_METADATA_FILE)
        val pendingMetadataFile = File(entryDirectory, TRASH_PENDING_METADATA_FILE)
        if (finalMetadataFile.exists()) {
            runCatching { pendingMetadataFile.delete() }
            return
        }
        if (!pendingMetadataFile.isFile) return

        val properties = readPropertiesFile(pendingMetadataFile) ?: return
        val expectedSize = properties.getProperty("size")?.toLongOrNull()?.takeIf { it >= 0L } ?: return
        val expectedItemCount = properties.getProperty("itemCount")?.toIntOrNull()?.takeIf { it > 0 } ?: return
        val dataFile = File(entryDirectory, TRASH_DATA_FILE)
        if (!isCompleteTrashData(dataFile, expectedSize, expectedItemCount)) return
        runCatching { pendingMetadataFile.renameTo(finalMetadataFile) }
    }

    private fun cleanupRestoredEntry(entryDirectory: File, dataFile: File) {
        val finalMetadataFile = File(entryDirectory, TRASH_METADATA_FILE)
        val pendingMetadataFile = File(entryDirectory, TRASH_PENDING_METADATA_FILE)
        val metadataHidden = when {
            pendingMetadataFile.exists() -> true
            !finalMetadataFile.exists() -> true
            else -> finalMetadataFile.renameTo(pendingMetadataFile)
        }
        if (!metadataHidden) return

        runCatching { dataFile.deleteRecursively() }
        if (!dataFile.exists()) {
            runCatching { entryDirectory.deleteRecursively() }
        }
    }

    private fun isCompleteTrashData(dataFile: File, expectedSize: Long, expectedItemCount: Int): Boolean {
        return dataFile.exists() &&
            recursiveSize(dataFile) == expectedSize &&
            recursiveItemCount(dataFile) == expectedItemCount
    }

    private fun isPathInsideRoot(file: File): Boolean {
        return runCatching {
            val root = rootDirectory.canonicalFile
            val candidate = file.canonicalFile
            candidate != root && candidate.path.startsWith(root.path + File.separator)
        }.getOrDefault(false)
    }

    private fun writePropertiesFile(file: File, properties: Properties) {
        FileOutputStream(file).use { output ->
            properties.store(output, "SpeedShareWeb Trash Entry")
            output.fd.sync()
        }
    }

    private fun readPropertiesFile(file: File): Properties? {
        return runCatching {
            Properties().apply {
                FileInputStream(file).use(::load)
            }
        }.getOrNull()
    }
}

fun resolveConflict(requested: File, policy: ConflictPolicy, translator: Translator): File? {
    if (!requested.exists()) return requested
    return when (policy) {
        ConflictPolicy.SKIP -> null
        ConflictPolicy.OVERWRITE -> requested
        ConflictPolicy.AUTO_RENAME -> createUniquePath(requested)
    }
}

fun replaceWithCopiedSource(
    source: File,
    destination: File,
    onBytes: (Long) -> Unit = {},
    isCancelled: () -> Boolean = { false },
    translator: Translator
) {
    require(destination.exists()) { translator.text("trash_file_missing") }
    val parent = destination.parentFile
        ?: throw IllegalStateException(translator.text("trash_overwrite_failed", destination.name))
    val token = UUID.randomUUID().toString().replace("-", "")
    val staging = File(parent, ".SpeedShareWebReplace-$token.tmp")
    val backup = File(parent, ".SpeedShareWebReplace-$token.backup")

    try {
        copyRecursivelyControlled(source, staging, onBytes, isCancelled, translator)
        if (isCancelled()) throw InterruptedException(translator.text("op_cancelled_exception"))

        if (!destination.renameTo(backup)) {
            throw IllegalStateException(translator.text("trash_overwrite_failed", destination.name))
        }
        if (!staging.renameTo(destination)) {
            val restored = backup.renameTo(destination)
            if (!restored) {
                throw IllegalStateException(translator.text("trash_overwrite_failed", destination.name))
            }
            throw IllegalStateException(translator.text("trash_overwrite_failed", destination.name))
        }

        // Failure to remove the hidden backup is not a failed replacement: both the new file
        // and the old complete copy are still safe. A later cleanup can remove the stale backup.
        runCatching { backup.deleteRecursively() }
    } catch (error: Throwable) {
        runCatching { staging.deleteRecursively() }
        if (!destination.exists() && backup.exists()) {
            runCatching { backup.renameTo(destination) }
        }
        throw error
    }
}

fun createUniquePath(requested: File): File {
    if (!requested.exists()) return requested
    val parent = requested.parentFile ?: return requested
    val originalName = requested.name
    val dot = if (requested.isDirectory) -1 else originalName.lastIndexOf('.')
    val base = if (dot > 0) originalName.substring(0, dot) else originalName
    val extension = if (dot > 0) originalName.substring(dot) else ""
    var index = 1
    while (true) {
        val candidate = File(parent, "$base ($index)$extension")
        if (!candidate.exists()) return candidate
        index++
    }
}

fun recursiveSize(file: File): Long {
    if (!file.exists()) return 0L
    if (file.isFile) return file.length()
    return file.listFiles()?.sumOf(::recursiveSize) ?: 0L
}

fun recursiveItemCount(file: File): Int {
    if (!file.exists()) return 0
    if (file.isFile) return 1
    return 1 + (file.listFiles()?.sumOf(::recursiveItemCount) ?: 0)
}

fun copyRecursivelyControlled(
    source: File,
    destination: File,
    onBytes: (Long) -> Unit = {},
    isCancelled: () -> Boolean = { false },
    translator: Translator
) {
    if (isCancelled()) throw InterruptedException(translator.text("op_cancelled_exception"))
    if (source.isDirectory) {
        if (!destination.exists() && !destination.mkdirs()) {
            throw IllegalStateException(translator.text("trash_create_dir_failed", destination.name))
        }
        source.listFiles()?.forEach { child ->
            copyRecursivelyControlled(
                source = child,
                destination = File(destination, child.name),
                onBytes = onBytes,
                isCancelled = isCancelled,
                translator = translator
            )
        }
        destination.setLastModified(source.lastModified())
    } else {
        destination.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    if (isCancelled()) throw InterruptedException(translator.text("op_cancelled_exception"))
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    onBytes(read.toLong())
                }
                output.fd.sync()
            }
        }
        destination.setLastModified(source.lastModified())
    }
}

fun deleteRecursivelyControlled(
    file: File,
    isCancelled: () -> Boolean = { false }
): Boolean {
    if (isCancelled()) return false
    if (file.isDirectory) {
        file.listFiles()?.forEach { child ->
            if (!deleteRecursivelyControlled(child, isCancelled)) return false
        }
    }
    return file.delete()
}
