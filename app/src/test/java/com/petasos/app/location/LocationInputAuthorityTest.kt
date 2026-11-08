package com.petasos.app.location

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class LocationInputAuthorityTest {
    @Test fun commitAfterNoLocationGapCannotRestorePendingValidSample() {
        val authority = LocationInputAuthority()
        val pending = authority.record(1_000, admissible = true)
        assertTrue(authority.canUse(pending, 1_000))
        authority.invalidate() // NoLocation ingress, before gap() flushes the pending batch.
        assertFalse(authority.canUse(pending, 1_000))
        assertFalse(authority.canUse(null, 1_000))
    }

    @Test fun doubtfulSampleRevokesAuthorityBeforeItsQueuedCallbackRuns() {
        val authority = LocationInputAuthority()
        val queuedValid = authority.record(1_000, admissible = true)
        assertNull(authority.record(1_001, admissible = false))
        assertFalse(authority.canUse(queuedValid, 1_000))
        // A delayed valid callback may persist its row, but cannot renew its receipt permit.
        assertFalse(authority.canUse(queuedValid, 1_000))
    }

    @Test fun laterAdmissibleInputCanReopenWithoutRevivingOldQueuedWork() {
        val authority = LocationInputAuthority()
        val beforeGap = authority.record(1_000, admissible = true)
        authority.invalidate()
        val afterGap = authority.record(2_000, admissible = true)
        assertTrue(authority.canUse(afterGap, 2_000))
        assertFalse(authority.canUse(beforeGap, 1_000))
        assertFalse(authority.canUse(beforeGap, 2_000))
        assertFalse(authority.canUse(afterGap, 1_000))
    }

    @Test fun callbacksSharingAMillisecondStillHaveDifferentAuthority() {
        val authority = LocationInputAuthority()
        val first = authority.record(1_000, admissible = true)
        val second = authority.record(1_000, admissible = true)
        assertNotSame(first, second)
        assertFalse(authority.canUse(first, 1_000))
        assertTrue(authority.canUse(second, 1_000))
        authority.invalidate()
        val reopened = authority.record(1_000, admissible = true)
        assertFalse(authority.canUse(first, 1_000))
        assertFalse(authority.canUse(second, 1_000))
        assertTrue(authority.canUse(reopened, 1_000))
    }

    @Test fun invalidReceiptRevokesRatherThanLeavingEarlierAuthorityAvailable() {
        val authority = LocationInputAuthority()
        val old = authority.record(1_000, admissible = true)
        assertNull(authority.record(-1, admissible = true))
        assertFalse(authority.canUse(old, 1_000))
    }

    @Test fun sessionsCannotBorrowOneAnothersPermitEvenWithTheSameTimestamp() {
        val firstSession = LocationInputAuthority()
        val secondSession = LocationInputAuthority()
        val first = firstSession.record(1_000, admissible = true)
        val second = secondSession.record(1_000, admissible = true)
        assertFalse(firstSession.canUse(second, 1_000))
        assertFalse(secondSession.canUse(first, 1_000))
        assertTrue(firstSession.canUse(first, 1_000))
        assertTrue(secondSession.canUse(second, 1_000))
    }

    @Test fun ingressRevocationIsVisibleToAWorkerHoldingAnOldCallback() {
        val authority = LocationInputAuthority()
        val queued = authority.record(1_000, admissible = true)
        val workerReady = CountDownLatch(1)
        val resumeWorker = CountDownLatch(1)
        val result = AtomicReference<Boolean?>()
        val worker = Thread {
            workerReady.countDown()
            if (resumeWorker.await(5, TimeUnit.SECONDS)) result.set(authority.canUse(queued, 1_000))
        }
        worker.start()
        try {
            assertTrue(workerReady.await(5, TimeUnit.SECONDS))
            authority.invalidate()
        } finally { resumeWorker.countDown() }
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertEquals(false, result.get())
    }

    @Test fun receiptPermitsDoNotExposeTheirTimestampInDiagnostics() {
        val authority = LocationInputAuthority()
        val permit = authority.record(987_654_321, admissible = true)!!
        assertFalse(permit.toString().contains("987654321"))
        assertTrue(authority.canUse(permit, 987_654_321))
    }
}
