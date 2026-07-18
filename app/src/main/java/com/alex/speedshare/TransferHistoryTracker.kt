package com.alex.speedshare

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

class TransferHistoryTracker(
    private val onChanged: (List<TransferHistoryItem>) -> Unit = {},
    private val maxItems: Int = 80
) {
    init {
        require(maxItems > 0) { "maxItems must be greater than zero" }
    }

    private val nextId = AtomicLong(1L)
    private val items = ArrayDeque<TransferHistoryItem>()

    fun add(
        kind: TransferHistoryKind,
        name: String,
        path: String,
        clientAddress: String,
        bytes: Long,
        itemCount: Int = 1,
        status: FileOperationState = FileOperationState.COMPLETED,
        openTarget: String? = null
    ) {
        val snapshot = synchronized(this) {
            items.addFirst(
                TransferHistoryItem(
                    id = nextId.getAndIncrement(),
                    kind = kind,
                    name = name.ifBlank { kind.webValue },
                    path = path,
                    clientAddress = clientAddress,
                    bytes = bytes.coerceAtLeast(0L),
                    itemCount = itemCount.coerceAtLeast(0),
                    timestampMs = System.currentTimeMillis(),
                    status = status,
                    openTarget = openTarget
                )
            )
            while (items.size > maxItems) items.removeLast()
            items.toList()
        }
        notifyChanged(snapshot)
    }

    @Synchronized
    fun snapshot(): List<TransferHistoryItem> = items.toList()

    fun clear() {
        val changed = synchronized(this) {
            if (items.isEmpty()) {
                false
            } else {
                items.clear()
                true
            }
        }
        if (changed) notifyChanged(emptyList())
    }

    private fun notifyChanged(snapshot: List<TransferHistoryItem>) {
        // UI or service observers must not turn a completed file operation into a failure.
        runCatching { onChanged(snapshot) }
    }
}
