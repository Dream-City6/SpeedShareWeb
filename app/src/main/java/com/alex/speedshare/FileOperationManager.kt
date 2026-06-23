package com.alex.speedshare

import java.io.File
import java.util.concurrent.Executors

class FileOperationManager(
    private val rootDirectory: File,
    private val trashManager: TrashManager,
    private val tracker: FileOperationTracker,
    private val translator: Translator,
    private val onContentChanged: () -> Unit
) {
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "SpeedShareWeb-FileOperation").apply { isDaemon = true }
    }

    fun submitCopy(
        relativePaths: List<String>,
        destinationRelativePath: String,
        policy: ConflictPolicy
    ): Long {
        return submitTransfer(
            kind = FileOperationKind.COPY,
            relativePaths = relativePaths,
            destinationRelativePath = destinationRelativePath,
            policy = policy,
            move = false
        )
    }

    fun submitMove(
        relativePaths: List<String>,
        destinationRelativePath: String,
        policy: ConflictPolicy
    ): Long {
        return submitTransfer(
            kind = FileOperationKind.MOVE,
            relativePaths = relativePaths,
            destinationRelativePath = destinationRelativePath,
            policy = policy,
            move = true
        )
    }

    fun submitDelete(relativePaths: List<String>, permanent: Boolean): Long {
        val kind = if (permanent) FileOperationKind.DELETE else FileOperationKind.TRASH
        val handle = tracker.create(kind, if (permanent) translator.text("op_delete") else translator.text("op_trash"))
        executor.execute {
            try {
                val sources = resolveSources(relativePaths)
                val totalBytes = sources.sumOf(::recursiveSize)
                val totalItems = sources.sumOf(::recursiveItemCount)
                tracker.start(handle, totalBytes, totalItems)

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

                tracker.complete(handle)
                onContentChanged()
            } catch (_: InterruptedException) {
                tracker.cancelled(handle)
            } catch (error: Throwable) {
                tracker.fail(handle, error.message ?: error.javaClass.simpleName)
            }
        }
        return handle.id
    }

    fun submitRestore(ids: List<String>, policy: ConflictPolicy): Long {
        val handle = tracker.create(FileOperationKind.RESTORE, translator.text("op_restore"))
        executor.execute {
            try {
                val entryMap = trashManager.listEntries().associateBy { it.id }
                val selected = ids.mapNotNull(entryMap::get)
                tracker.start(
                    handle = handle,
                    totalBytes = selected.sumOf { it.size },
                    totalItems = selected.size
                )

                selected.forEach { entry ->
                    ensureNotCancelled(handle)
                    tracker.setMessage(handle, translator.text("op_restoring", entry.name))
                    trashManager.restore(
                        id = entry.id,
                        conflictPolicy = policy,
                        onBytes = { tracker.addBytes(handle, it) },
                        isCancelled = { handle.isCancelled() }
                    )
                    tracker.itemFinished(handle)
                }

                tracker.complete(handle)
                onContentChanged()
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
        move: Boolean
    ): Long {
        val handle = tracker.create(kind, operationKindText(kind, translator))
        executor.execute {
            try {
                val sources = resolveSources(relativePaths)
                val destinationDirectory = safeResolve(rootDirectory, destinationRelativePath)
                    ?.takeIf { it.isDirectory }
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

                sources.forEach { source ->
                    ensureNotCancelled(handle)
                    if (move && source.parentFile?.canonicalFile == destinationDirectory.canonicalFile) {
                        tracker.itemFinished(handle, translator.text("op_skipped_same", source.name))
                        return@forEach
                    }
                    val requested = File(destinationDirectory, source.name)
                    val destination = resolveConflict(requested, policy, translator)
                    if (destination == null) {
                        tracker.itemFinished(handle, translator.text("op_skipped", source.name))
                        return@forEach
                    }

                    tracker.setMessage(handle, translator.text("op_working", operationKindText(kind, translator), source.name))
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
                            runCatching { destination.deleteRecursively() }
                            throw error
                        }
                        if (move && !deleteRecursivelyControlled(source) { handle.isCancelled() }) {
                            throw IllegalStateException(translator.text("op_copy_delete_failed", source.name))
                        }
                    } else {
                        tracker.addBytes(handle, recursiveSize(destination))
                    }
                    tracker.itemFinished(handle)
                }

                tracker.complete(handle)
                onContentChanged()
            } catch (_: InterruptedException) {
                tracker.cancelled(handle)
            } catch (error: Throwable) {
                tracker.fail(handle, error.message ?: error.javaClass.simpleName)
            }
        }
        return handle.id
    }

    private fun resolveSources(relativePaths: List<String>): List<File> {
        val seen = linkedSetOf<String>()
        return relativePaths.mapNotNull { relative ->
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
        }.ifEmpty { throw IllegalArgumentException(translator.text("op_no_valid_files")) }
    }

    private fun ensureNotCancelled(handle: FileOperationTracker.Handle) {
        if (handle.isCancelled()) throw InterruptedException(translator.text("op_cancelled_exception"))
    }
}
