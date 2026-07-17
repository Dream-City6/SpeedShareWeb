package com.alex.speedshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOperationTrackerTest {
    private val translator = Localization.translator(ResolvedLanguage.ENGLISH)

    @Test
    fun cancellingQueuedOperationImmediatelyFinalizesItsState() {
        val tracker = FileOperationTracker(translator)
        val handle = tracker.create(FileOperationKind.COPY, "Copy")

        assertTrue(tracker.requestCancel(handle.id))

        val snapshot = tracker.snapshots().single()
        assertEquals(FileOperationState.CANCELLED, snapshot.state)
        assertFalse(snapshot.cancellable)
    }

    @Test
    fun completeDoesNotOverwriteCancellationRequestedAtTheEnd() {
        val tracker = FileOperationTracker(translator)
        val handle = tracker.create(FileOperationKind.MOVE, "Move")
        tracker.start(handle, totalBytes = 100L, totalItems = 1)
        tracker.requestCancel(handle.id)

        tracker.complete(handle)

        val snapshot = tracker.snapshots().single()
        assertEquals(FileOperationState.CANCELLED, snapshot.state)
        assertFalse(snapshot.cancellable)
    }

    @Test
    fun shutdownFinalizesQueuedOperationsAndRequestsRunningCancellation() {
        val tracker = FileOperationTracker(translator)
        val queued = tracker.create(FileOperationKind.COPY, "Queued")
        val running = tracker.create(FileOperationKind.MOVE, "Running")
        tracker.start(running, totalBytes = 10L, totalItems = 1)

        tracker.requestCancelAll()

        val snapshots = tracker.snapshots().associateBy { it.id }
        assertEquals(FileOperationState.CANCELLED, snapshots.getValue(queued.id).state)
        assertFalse(snapshots.getValue(queued.id).cancellable)
        assertEquals(FileOperationState.RUNNING, snapshots.getValue(running.id).state)
        assertTrue(running.isCancelled())
    }
}
