package com.alex.speedshare

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class FileOperationManager(
    private val rootDirectory: File,
    private val trashManager: TrashManager,
    private val tracker: FileOperationTracker,
    private val historyTracker: TransferHistoryTracker,
    private val translator: Translator,
    private val onContentChanged: () -> Unit
) {
    // File-management operations mutate shared directory state. Running them in order avoids
    // cross-operation rename, overwrite and delete races while transfers remain concurrent.
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SpeedShareWeb-FileOperation").apply { isDaemon = true }
    }

    fun submitCopy(
        relativePaths: List<String>,
        destinationRelativePath: String,
        policy: ConflictPolicy,
        clientAddress: String
    ): Long {
        return submitTransfer(
            kind = FileOperationKind.COPY,
            relativePaths = relativePaths,
            destinationRelativePath = destinationRelativePath,
            policy = policy,
            move = false,
            clientAddress = clientAddress
        )
    }

    fun submitMove(
        relativePaths: List<String>,
        destinationRelativePath: String,
        policy: ConflictPolicy,
        clientAddress: String
    ): Long {
        return submitTransfer(
            kind = FileOperationKind.MOVE,
            relativePaths = relativePaths,
            destinationRelativePath = destinationRelativePath,
            policy = policy,
            move = true,
            clientAddress = clientAddress
        )
    }

    fun submitDelete(relativePaths: List<String>, permanent: Boolean, clientAddress: String): Long {
        val kind = if (permanent) FileOperationKind.DELETE else FileOperationKind.TRASH
        val handle = tracker.create(kind, if (permanent) translator.text("op_delete") else translator.text("op_trash"))
        execute(handle) {
            try {
                ensureNotCancelled(handle)
                val sources = resolveSources(relativePaths)
                val totalBytes = sources.sumOf(::recursiveSize)
                val totalItems = sources.sumOf(::recursiveItemCount)
                tracker.start(handle, totalBytes, totalItems)
                ensureNotCancelled(handle)

                sources.forEach { source ->
                    ensureNotCancelled(handle)
                    if (permanent) {
                        tracker.setMessage(handle, translator.text("op_deleting", source.name))
                        if (!deleteRecursivelyControlled(source) { handle.isCancelled() }) {
                            if (handle.isCancelled()) throw InterruptedException(translator.text("op_cancelled_exception"))
                            throw IllegalStateException(translator.text("web_delete_failed", source.name))
                        }
                    } else {
                        val relative = source.relativeTo(rootDirectory).invariantSeparatorsPath
                        tracker.setMessage(handle, translator.text("op_trashing", source.name))
                        trashManager.moveToTrash(
                            source = source,
                            relativePath = relative,
                            onBytes = { tracker.addBytes(handle, it) },
                            isCancelled = { handle.isCancelled() }
                        )
                    }
                    tracker.itemFinished(handle)
                }

                ensureNotCancelled(handle)
                tracker.complete(handle)
                historyTracker.add(
                    kind = if (permanent) TransferHistoryKind.DELETE else TransferHistoryKind.TRASH,
                    name = summarizeSources(sources),
                    path = summarizeRelativePaths(sources),
                    clientAddress = clientAddress,
                    bytes = totalBytes,
                    itemCount = totalItems
                )
                onContentChanged()
            } catch (_: InterruptedException) {
                tracker.cancelled(handle)
            } catch (error: Throwable) {
                tracker.fail(handle, error.message ?: error.javaClass.simpleName)
            }
        }
        return handle.id
    }

    fun submitRestore(ids: List<String>, policy: ConflictPolicy, clientAddress: String): Long {
        val handle = tracker.create(FileOperationKind.RESTORE, translator.text("op_restore"))
        execute(handle) {
            try {
                ensureNotCancelled(handle)
                val entryMap = trashManager.listEntries().associateBy { it.id }
                val selected = ids.distinct().mapNotNull(entryMap::get)
                if (selected.isEmpty()) {
                    throw IllegalArgumentException(translator.text("trash_entry_missing"))
                }
                val totalBytes = selected.sumOf { it.size }
                val totalItems = selected.size
                tracker.start(
                    handle = handle,
                    totalBytes = totalBytes,
                    totalItems = totalItems
                )
                ensureNotCancelled(handle)
                val restored = mutableListOf<TrashEntry>()

                selected.forEach { entry ->
                    ensureNotCancelled(handle)
                    tracker.setMessage(handle, translator.text("op_restoring", entry.name))
                    val destination = trashManager.restore(
                        id = entry.id,
                        conflictPolicy = policy,
                        onBytes = { tracker.addBytes(handle, it) },
                        isCancelled = { handle.isCancelled() }
                    )
                    if (destination == null) {
                        tracker.itemFinished(handle, translator.text("op_skipped", entry.name))
                    } else {
                        restored += entry
                        tracker.itemFinished(handle)
                    }
                }

                ensureNotCancelled(handle)
                tracker.complete(handle)
                if (restored.isNotEmpty()) {
                    historyTracker.add(
                        kind = TransferHistoryKind.RESTORE,
                        name = summarizeNames(restored.map { it.name }),
                        path = restored.joinToString(", ") { it.originalRelativePath }.take(240),
                        clientAddress = clientAddress,
                        bytes = restored.sumOf { it.size },
                        itemCount = restored.size
                    )
                    onContentChanged()
                }
            } catch (_: InterruptedException) {
                tracker.cancelled(handle)
            } catch (error: Throwable) {
                tracker.fail(handle, error.message ?: error.javaClass.simpleName)
            }
        }
        return handle.id
    }

    fun requestCancel(id: Long): Boolean = tracker.requestCancel(id)

    fun snapshots(): List<FileOperationSnapshot> = tracker.snapshots()

    fun close() {
        tracker.requestCancelAll()
        executor.shutdownNow()
    }

    private fun submitTransfer(
        kind: FileOperationKind,
        relativePaths: List<String>,
        destinationRelativePath: String,
        policy: ConflictPolicy,
        move: Boolean,
        clientAddress: String
    ): Long {
        val handle = tracker.create(kind, operationKindText(kind, translator))
        execute(handle) {
            try {
                ensureNotCancelled(handle)
                val sources = resolveSources(relativePaths)
                val destinationDirectory = safeResolve(rootDirectory, destinationRelativePath)
                    ?.takeIf { it.isDirectory && !trashManager.isTrashPath(it) }
                    ?: throw IllegalArgumentException(translator.text("op_target_missing"))

                sources.forEach { source ->
                    if (source == destinationDirectory) {
                        throw IllegalArgumentException(translator.text("op_target_self"))
                    }
                    if (source.isDirectory) {
                        val sourcePath = source.canonicalFile.path + File.separator
                        val destinationPath = destinationDirectory.canonicalFile.path + File.separator
                        if (destinationPath.startsWith(sourcePath)) {
                            throw IllegalArgumentException(translator.text("op_target_inside"))
                        }
                    }
                }

                val totalBytes = sources.sumOf(::recursiveSize)
                val totalItems = sources.sumOf(::recursiveItemCount)
                tracker.start(handle, totalBytes, totalItems)
                ensureNotCancelled(handle)

                sources.forEach { source ->
                    ensureNotCancelled(handle)
                    if (move && source.parentFile?.canonicalFile == destinationDirectory.canonicalFile) {
                        tracker.itemFinished(handle, translator.text("op_skipped_same", source.name))
                        return@forEach
                    }
                    val requested = File(destinationDirectory, source.name)
                    val transactionalOverwrite = policy == ConflictPolicy.OVERWRITE && requested.exists()
                    val destination = resolveConflict(requested, policy, translator)
                    if (destination == null) {
                        tracker.itemFinished(handle, translator.text("op_skipped", source.name))
                        return@forEach
                    }

                    tracker.setMessage(handle, translator.text("op_working", operationKindText(kind, translator), source.name))
                    if (transactionalOverwrite) {
                        replaceWithCopiedSource(
                            source = source,
                            destination = destination,
                            onBytes = { tracker.addBytes(handle, it) },
                            isCancelled = { handle.isCancelled() },
                            translator = translator
                        )
                        ensureNotCancelled(handle)
                        if (move && !deleteRecursivelyControlled(source) { handle.isCancelled() }) {
                            if (handle.isCancelled()) throw InterruptedException(translator.text("op_cancelled_exception"))
                            throw IllegalStateException(translator.text("op_copy_delete_failed", source.name))
                        }
                        tracker.itemFinished(handle)
                        return@forEach
                    }
                    var renamed = false
                    if (move) {
                        renamed = source.renameTo(destination)
                    }

                    if (!renamed) {
                        try {
                            copyRecursivelyControlled(
                                source = source,
                                destination = destination,
                                onBytes = { tracker.addBytes(handle, it) },
                                isCancelled = { handle.isCancelled() },
                                translator = translator
                            )
                            ensureNotCancelled(handle)
                        } catch (error: Throwable) {
                            runCatching { deleteRecursivelyControlled(destination) }
                            throw error
                        }
                        if (move && !deleteRecursivelyControlled(source) { handle.isCancelled() }) {
                            if (handle.isCancelled()) throw InterruptedException(translator.text("op_cancelled_exception"))
                            throw IllegalStateException(translator.text("op_copy_delete_failed", source.name))
                        }
                    } else {
                        tracker.addBytes(handle, recursiveSize(destination))
                    }
                    tracker.itemFinished(handle)
                }

                ensureNotCancelled(handle)
                tracker.complete(handle)
                historyTracker.add(
                    kind = if (move) TransferHistoryKind.MOVE else TransferHistoryKind.COPY,
                    name = summarizeSources(sources),
                    path = destinationRelativePath.ifBlank { "/" },
                    clientAddress = clientAddress,
                    bytes = totalBytes,
                    itemCount = totalItems
                )
                onContentChanged()
            } catch (_: InterruptedException) {
                tracker.cancelled(handle)
            } catch (error: Throwable) {
                tracker.fail(handle, error.message ?: error.javaClass.simpleName)
            }
        }
        return handle.id
    }

    private fun execute(handle: FileOperationTracker.Handle, action: () -> Unit) {
        try {
            executor.execute(action)
        } catch (_: RejectedExecutionException) {
            tracker.cancelled(handle, translator.text("op_server_stopping"))
        }
    }

    private fun resolveSources(relativePaths: List<String>): List<File> {
        val seen = linkedSetOf<String>()
        val candidates = relativePaths.mapNotNull { relative ->
            val file = safeResolve(rootDirectory, relative)
            if (
                file == null ||
                !file.exists() ||
                file.canonicalFile == rootDirectory.canonicalFile ||
                trashManager.isTrashPath(file)
            ) {
                null
            } else if (seen.add(file.canonicalPath)) {
                file
            } else {
                null
            }
        }
        return collapseNestedOperationSources(candidates)
            .ifEmpty { throw IllegalArgumentException(translator.text("op_no_valid_files")) }
    }

    private fun ensureNotCancelled(handle: FileOperationTracker.Handle) {
        if (handle.isCancelled()) throw InterruptedException(translator.text("op_cancelled_exception"))
    }

    private fun summarizeSources(sources: List<File>): String = summarizeNames(sources.map { it.name })

    private fun summarizeNames(names: List<String>): String {
        val first = names.firstOrNull().orEmpty()
        return if (names.size <= 1) first else "$first +${names.size - 1}"
    }

    private fun summarizeRelativePaths(sources: List<File>): String {
        return sources.joinToString(", ") { source ->
            source.relativeTo(rootDirectory).invariantSeparatorsPath
        }.take(240)
    }
}

internal fun collapseNestedOperationSources(files: List<File>): List<File> {
    data class Candidate(val originalIndex: Int, val file: File, val canonicalPath: String)

    val distinct = linkedMapOf<String, Candidate>()
    files.forEachIndexed { index, file ->
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@forEachIndexed
        distinct.putIfAbsent(canonical.path, Candidate(index, canonical, canonical.path))
    }

    val roots = mutableListOf<Candidate>()
    distinct.values
        .sortedWith(compareBy<Candidate> { it.canonicalPath.length }.thenBy { it.originalIndex })
        .forEach { candidate ->
            val nested = roots.any { root ->
                candidate.canonicalPath.startsWith(root.canonicalPath + File.separator)
            }
            if (!nested) roots += candidate
        }

    return roots.sortedBy { it.originalIndex }.map { it.file }
}
