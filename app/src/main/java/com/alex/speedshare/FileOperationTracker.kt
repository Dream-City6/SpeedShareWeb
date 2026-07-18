package com.alex.speedshare

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class FileOperationTracker(
    private val translator: Translator,
    private val onChanged: () -> Unit = {}
) {
    class Handle internal constructor(
        val id: Long,
        internal val cancelled: AtomicBoolean
    ) {
        fun isCancelled(): Boolean = cancelled.get()
    }

    private data class MutableOperation(
        val id: Long,
        val kind: FileOperationKind,
        val title: String,
        val startedAtMs: Long,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        @Volatile var state: FileOperationState = FileOperationState.QUEUED,
        @Volatile var totalBytes: Long = 0L,
        @Volatile var processedBytes: Long = 0L,
        @Volatile var totalItems: Int = 0,
        @Volatile var processedItems: Int = 0,
        @Volatile var message: String,
        @Volatile var updatedAtMs: Long = startedAtMs,
        @Volatile var lastSpeedBytes: Long = 0L,
        @Volatile var lastSpeedAtMs: Long = startedAtMs,
        @Volatile var bytesPerSecond: Long = 0L
    )

    private val nextId = AtomicLong(1L)
    private val operations = ConcurrentHashMap<Long, MutableOperation>()

    fun create(kind: FileOperationKind, title: String): Handle {
        val id = nextId.getAndIncrement()
        val now = System.currentTimeMillis()
        val operation = MutableOperation(
            id = id,
            kind = kind,
            title = title,
            startedAtMs = now,
            message = translator.text("op_waiting")
        )
        operations[id] = operation
        prune()
        onChanged()
        return Handle(id, operation.cancelled)
    }

    fun start(handle: Handle, totalBytes: Long, totalItems: Int, message: String = translator.text("op_processing")) {
        val operation = operations[handle.id] ?: return
        if (operation.cancelled.get()) {
            markCancelled(operation, translator.text("op_cancelled"))
            onChanged()
            return
        }
        operation.state = FileOperationState.RUNNING
        operation.totalBytes = totalBytes.coerceAtLeast(0L)
        operation.totalItems = totalItems.coerceAtLeast(0)
        operation.message = message
        operation.updatedAtMs = System.currentTimeMillis()
        onChanged()
    }

    fun updateTotals(handle: Handle, totalBytes: Long, totalItems: Int) {
        val operation = operations[handle.id] ?: return
        operation.totalBytes = totalBytes.coerceAtLeast(0L)
        operation.totalItems = totalItems.coerceAtLeast(0)
        operation.updatedAtMs = System.currentTimeMillis()
        onChanged()
    }

    fun addBytes(handle: Handle, count: Long) {
        if (count <= 0L) return
        val operation = operations[handle.id] ?: return
        operation.processedBytes += count
        updateSpeed(operation)
    }

    fun itemFinished(handle: Handle, message: String? = null) {
        val operation = operations[handle.id] ?: return
        operation.processedItems += 1
        if (message != null) operation.message = message
        operation.updatedAtMs = System.currentTimeMillis()
        onChanged()
    }

    fun setMessage(handle: Handle, message: String) {
        val operation = operations[handle.id] ?: return
        operation.message = message
        operation.updatedAtMs = System.currentTimeMillis()
        onChanged()
    }

    fun complete(handle: Handle, message: String = translator.text("op_complete")) {
        val operation = operations[handle.id] ?: return
        if (operation.cancelled.get()) {
            markCancelled(operation, translator.text("op_cancelled"))
            onChanged()
            return
        }
        operation.state = FileOperationState.COMPLETED
        operation.message = message
        if (operation.totalBytes > 0L) operation.processedBytes = operation.totalBytes
        if (operation.totalItems > 0) operation.processedItems = operation.totalItems
        operation.bytesPerSecond = 0L
        operation.updatedAtMs = System.currentTimeMillis()
        onChanged()
    }

    fun fail(handle: Handle, message: String) {
        val operation = operations[handle.id] ?: return
        operation.state = FileOperationState.FAILED
        operation.message = message
        operation.bytesPerSecond = 0L
        operation.updatedAtMs = System.currentTimeMillis()
        onChanged()
    }

    fun cancelled(handle: Handle, message: String = translator.text("op_cancelled")) {
        val operation = operations[handle.id] ?: return
        markCancelled(operation, message)
        onChanged()
    }

    fun requestCancel(id: Long): Boolean {
        val operation = operations[id] ?: return false
        if (operation.state != FileOperationState.RUNNING && operation.state != FileOperationState.QUEUED) {
            return false
        }
        operation.cancelled.set(true)
        if (operation.state == FileOperationState.QUEUED) {
            markCancelled(operation, translator.text("op_cancelled"))
        } else {
            operation.message = translator.text("op_cancelling")
            operation.updatedAtMs = System.currentTimeMillis()
        }
        onChanged()
        return true
    }

    fun requestCancelAll() {
        var changed = false
        operations.values.forEach { operation ->
            when (operation.state) {
                FileOperationState.QUEUED -> {
                    operation.cancelled.set(true)
                    markCancelled(operation, translator.text("op_server_stopping"))
                    changed = true
                }
                FileOperationState.RUNNING -> {
                    operation.cancelled.set(true)
                    operation.message = translator.text("op_server_stopping")
                    operation.updatedAtMs = System.currentTimeMillis()
                    changed = true
                }
                else -> Unit
            }
        }
        if (changed) onChanged()
    }

    fun snapshots(): List<FileOperationSnapshot> {
        return operations.values
            .sortedByDescending { it.id }
            .map { operation ->
                FileOperationSnapshot(
                    id = operation.id,
                    kind = operation.kind,
                    state = operation.state,
                    title = operation.title,
                    totalBytes = operation.totalBytes,
                    processedBytes = operation.processedBytes,
                    totalItems = operation.totalItems,
                    processedItems = operation.processedItems,
                    bytesPerSecond = operation.bytesPerSecond,
                    message = operation.message,
                    cancellable = operation.state == FileOperationState.RUNNING ||
                        operation.state == FileOperationState.QUEUED,
                    startedAtMs = operation.startedAtMs,
                    updatedAtMs = operation.updatedAtMs
                )
            }
    }

    private fun markCancelled(operation: MutableOperation, message: String) {
        operation.state = FileOperationState.CANCELLED
        operation.message = message
        operation.bytesPerSecond = 0L
        operation.updatedAtMs = System.currentTimeMillis()
    }

    private fun updateSpeed(operation: MutableOperation) {
        val now = System.currentTimeMillis()
        val elapsed = now - operation.lastSpeedAtMs
        if (elapsed >= 400L) {
            val delta = operation.processedBytes - operation.lastSpeedBytes
            operation.bytesPerSecond = if (elapsed > 0L) delta * 1000L / elapsed else 0L
            operation.lastSpeedBytes = operation.processedBytes
            operation.lastSpeedAtMs = now
            operation.updatedAtMs = now
            onChanged()
        }
    }

    private fun prune() {
        val completed = operations.values
            .filter {
                it.state == FileOperationState.COMPLETED ||
                    it.state == FileOperationState.FAILED ||
                    it.state == FileOperationState.CANCELLED
            }
            .sortedByDescending { it.id }

        completed.drop(40).forEach { operations.remove(it.id) }
    }
}
