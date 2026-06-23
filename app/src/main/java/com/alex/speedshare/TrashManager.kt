package com.alex.speedshare

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.UUID

class TrashManager(
    private val rootDirectory: File,
    private val translator: Translator
) {
    val trashRoot: File = File(rootDirectory, ".SpeedShareTrash")

    init {
        trashRoot.mkdirs()
        runCatching {
            File(trashRoot, ".nomedia").apply {
                if (!exists()) createNewFile()
            }
        }
    }

    fun isTrashPath(file: File): Boolean {
        return runCatching {
            val trash = trashRoot.canonicalFile.path
            val candidate = file.canonicalFile.path
            candidate == trash || candidate.startsWith(trash + File.separator)
        }.getOrDefault(false)
    }

    fun moveToTrash(
        source: File,
        relativePath: String,
        onBytes: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): TrashEntry {
        require(source.exists()) { translator.text("trash_file_missing") }
        require(!isTrashPath(source)) { translator.text("trash_repeat") }

        trashRoot.mkdirs()
        val id = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val entryDirectory = File(trashRoot, id)
        val dataFile = File(entryDirectory, "data")
        entryDirectory.mkdirs()

        val metadata = Properties().apply {
            setProperty("name", source.name)
            setProperty("originalRelativePath", relativePath)
            setProperty("deletedAtMs", System.currentTimeMillis().toString())
            setProperty("size", recursiveSize(source).toString())
            setProperty("isDirectory", source.isDirectory.toString())
        }

        var moved = false
        try {
            moved = source.renameTo(dataFile)
            if (!moved) {
                copyRecursivelyControlled(source, dataFile, onBytes, isCancelled, translator)
                if (isCancelled()) throw InterruptedException(translator.text("op_cancelled_exception"))
                if (!deleteRecursivelyControlled(source, isCancelled)) {
                    throw IllegalStateException(translator.text("trash_source_delete_failed"))
                }
            }

            FileOutputStream(File(entryDirectory, "meta.properties")).use { output ->
                metadata.store(output, "SpeedShare Trash Entry")
            }
        } catch (error: Throwable) {
            if (!moved) runCatching { dataFile.deleteRecursively() }
            runCatching { entryDirectory.deleteRecursively() }
            throw error
        }

        return readEntry(entryDirectory)
            ?: throw IllegalStateException(translator.text("trash_metadata_failed"))
    }

    fun listEntries(): List<TrashEntry> {
        if (!trashRoot.exists()) return emptyList()
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
    ): File {
        val entryDirectory = safeEntryDirectory(id)
            ?: throw IllegalArgumentException(translator.text("trash_entry_missing"))
        val entry = readEntry(entryDirectory)
            ?: throw IllegalStateException(translator.text("trash_metadata_corrupt"))
        val dataFile = File(entryDirectory, "data")
        if (!dataFile.exists()) throw IllegalStateException(translator.text("trash_content_missing"))

        val requested = safeResolve(rootDirectory, entry.originalRelativePath)
            ?: throw IllegalStateException(translator.text("trash_original_invalid"))
        requested.parentFile?.mkdirs()
        val destination = resolveConflict(requested, conflictPolicy, translator)
            ?: return requested

        var moved = false
        try {
            moved = dataFile.renameTo(destination)
            if (!moved) {
                copyRecursivelyControlled(dataFile, destination, onBytes, isCancelled, translator)
                if (isCancelled()) throw InterruptedException(translator.text("op_cancelled_exception"))
                if (!deleteRecursivelyControlled(dataFile, isCancelled)) {
                    throw IllegalStateException(translator.text("trash_source_remove_failed"))
                }
            }
            entryDirectory.deleteRecursively()
            return destination
        } catch (error: Throwable) {
            if (!moved && destination.exists()) runCatching { destination.deleteRecursively() }
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
            val properties = Properties()
            FileInputStream(File(entryDirectory, "meta.properties")).use(properties::load)
            TrashEntry(
                id = entryDirectory.name,
                name = properties.getProperty("name") ?: return null,
                originalRelativePath = properties.getProperty("originalRelativePath") ?: return null,
                deletedAtMs = properties.getProperty("deletedAtMs")?.toLongOrNull() ?: 0L,
                size = properties.getProperty("size")?.toLongOrNull() ?: 0L,
                isDirectory = properties.getProperty("isDirectory")?.toBooleanStrictOrNull() ?: false
            )
        }.getOrNull()
    }
}

fun resolveConflict(requested: File, policy: ConflictPolicy, translator: Translator): File? {
    if (!requested.exists()) return requested
    return when (policy) {
        ConflictPolicy.SKIP -> null
        ConflictPolicy.OVERWRITE -> {
            if (!requested.deleteRecursively()) {
                throw IllegalStateException(translator.text("trash_overwrite_failed", requested.name))
            }
            requested
        }
        ConflictPolicy.AUTO_RENAME -> createUniquePath(requested)
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
