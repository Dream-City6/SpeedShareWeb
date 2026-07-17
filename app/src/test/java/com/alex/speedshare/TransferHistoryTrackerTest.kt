package com.alex.speedshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferHistoryTrackerTest {
    @Test
    fun observerFailureDoesNotRejectCompletedHistoryItem() {
        val tracker = TransferHistoryTracker(onChanged = { error("observer failed") })

        tracker.add(
            kind = TransferHistoryKind.UPLOAD,
            name = "file.txt",
            path = "file.txt",
            clientAddress = "127.0.0.1",
            bytes = 10L
        )

        val item = tracker.snapshot().single()
        assertEquals("file.txt", item.name)
        assertEquals(10L, item.bytes)
    }

    @Test
    fun historyIsBoundedAndNewestItemsStayFirst() {
        val tracker = TransferHistoryTracker(maxItems = 2)

        repeat(3) { index ->
            tracker.add(
                kind = TransferHistoryKind.DOWNLOAD,
                name = "file-$index",
                path = "file-$index",
                clientAddress = "127.0.0.1",
                bytes = index.toLong()
            )
        }

        val snapshot = tracker.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals(listOf("file-2", "file-1"), snapshot.map { it.name })
        assertTrue(snapshot.first().id > snapshot.last().id)
    }
}
