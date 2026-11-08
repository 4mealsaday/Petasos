package com.petasos.app.location

import com.petasos.app.capability.location.LocationEvent
import com.petasos.app.capability.location.LocationOperation
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import org.junit.Assert.*
import org.junit.Test

/** Real recorder batching with a fake durable store; ingress and worker delivery are independently controlled. */
class LocationRecorderAuthorityIntegrationTest {
    @Test fun noLocationGapPersistsPendingPointWithoutRestoringItsAuthority() {
        val harness = Harness()
        harness.deliver(harness.sampleIngress())
        assertEquals(1, harness.recorder.pendingCount)
        harness.deliver(harness.failureIngress(LocationEvent.NoLocation(LocationReadingKind.UPDATE)))

        assertEquals(1, harness.batches.single().size)
        assertEquals(LocationQuality.VALID, harness.batches.single().single().quality)
        assertEquals(listOf(false), harness.authorizedAtCommit)
        assertFalse(harness.latestAuthority())
        assertEquals(LocationRecordingStatus.NO_LOCATION, harness.recorder.status)
        assertEquals(listOf("no_location"), harness.finishedReasons)
    }

    @Test fun permissionAndUpdateFailureGapCannotGrantFromTheirFinalFlush() {
        listOf(LocationEvent.PermissionDenied,
            LocationEvent.Failure(LocationOperation.UPDATES, "SyntheticFailure")).forEach { failure ->
            val harness = Harness()
            harness.deliver(harness.sampleIngress())
            harness.deliver(harness.failureIngress(failure))

            assertEquals(1, harness.batches.single().size)
            assertEquals(listOf(false), harness.authorizedAtCommit)
            assertFalse(harness.latestAuthority())
            assertEquals(0, harness.recorder.pendingCount)
        }
    }

    @Test fun simultaneousNewSampleRevokesCommittedAuthorityBeforeWorkerDelivery() {
        val harness = Harness()
        harness.deliver(harness.sampleIngress())
        harness.recorder.flush()
        val previousOutputAuthority = harness.latestAuthority
        assertTrue(previousOutputAuthority())

        val next = harness.sampleIngress() // Same millisecond; queued worker has not seen it yet.
        assertFalse(previousOutputAuthority())
        assertEquals(1, harness.batches.size)
        harness.deliver(next)
        harness.recorder.flush()

        assertTrue(harness.latestAuthority())
        assertFalse(previousOutputAuthority())
        assertNotEquals(harness.batches[0].single().pointId, harness.batches[1].single().pointId)
        assertEquals(harness.batches[0].single().receivedAtElapsedMs, harness.batches[1].single().receivedAtElapsedMs)
    }

    @Test fun pendingOldPointCannotBorrowNewerSameMillisecondIngressPermit() {
        val harness = Harness()
        harness.deliver(harness.sampleIngress())
        val next = harness.sampleIngress()
        harness.recorder.flush() // New receipt exists, but this batch contains only the older point.
        val previousOutputAuthority = harness.latestAuthority
        assertEquals(listOf(false), harness.authorizedAtCommit)

        harness.deliver(next)
        harness.recorder.flush()
        assertEquals(listOf(false, true), harness.authorizedAtCommit)
        assertFalse(previousOutputAuthority())
        assertTrue(harness.latestAuthority())
    }

    @Test fun oldQueuedSampleDeliveredAfterErrorCanPersistButCannotRenewItsToken() {
        val harness = Harness()
        val oldQueued = harness.sampleIngress()
        harness.deliver(harness.failureIngress(LocationEvent.NoLocation(LocationReadingKind.UPDATE)))
        harness.deliver(oldQueued)
        harness.recorder.flush()
        val oldOutputAuthority = harness.latestAuthority

        assertEquals(LocationQuality.VALID, harness.batches.single().single().quality)
        assertFalse(oldOutputAuthority())
        // Only a later ingress can reopen; worker delivery does not call record() again.
        harness.deliver(harness.sampleIngress())
        harness.recorder.flush()
        assertTrue(harness.latestAuthority())
        assertFalse(oldOutputAuthority())
    }

    @Test fun failedBatchRetryKeepsPointIdentityAndCannotTakeAQueuedNewSamplesPermit() {
        val harness = Harness()
        harness.deliver(harness.sampleIngress())
        harness.failAppend = true
        assertThrows(IllegalStateException::class.java) { harness.recorder.flush() }
        val firstAttemptIds = harness.attempted.single().map { it.pointId }
        assertEquals(1, harness.recorder.pendingCount)

        val next = harness.sampleIngress()
        harness.failAppend = false
        harness.recorder.flush()
        assertEquals(firstAttemptIds, harness.batches.single().map { it.pointId })
        assertEquals(listOf(false), harness.authorizedAtCommit)
        harness.deliver(next)
        harness.recorder.flush()
        assertEquals(listOf(false, true), harness.authorizedAtCommit)
    }

    @Test fun validLatestReceiptDoesNotExtendAnOldMeasurementsDeadlineAtDelayedCommit() {
        val harness = Harness()
        harness.deliver(harness.sampleIngress(observed = 1_000, received = 10_000))
        harness.recorder.flush()
        val stored = harness.batches.single().single()
        assertTrue(harness.latestAuthority())
        assertEquals(2_000L, LocationFreshness.deadlineElapsedMs(stored))
        assertTrue(LocationFreshness.isFresh(stored.receivedAtElapsedMs,
            LocationFreshness.deadlineElapsedMs(stored), 2_000, 10_000))
        assertFalse(LocationFreshness.isFresh(stored.receivedAtElapsedMs,
            LocationFreshness.deadlineElapsedMs(stored), 2_001, 10_000))
    }

    private data class Queued(
        val event: LocationEvent,
        val permit: LocationInputAuthority.ReceiptPermit?,
    )

    private class Harness {
        private val guard = LocationInputAuthority()
        private var sequence = 0
        private var deliveringPermit: LocationInputAuthority.ReceiptPermit? = null
        private val pointPermits = mutableMapOf<String, LocationInputAuthority.ReceiptPermit?>()
        val batches = mutableListOf<List<RecordedLocation>>()
        val attempted = mutableListOf<List<RecordedLocation>>()
        val authorizedAtCommit = mutableListOf<Boolean>()
        val finishedReasons = mutableListOf<String>()
        var latestAuthority: () -> Boolean = { false }
            private set
        var failAppend = false
        private val verified = LocationNormalizer(LocationMeaningAdapter {
            VerifiedLocationMeaning("synthetic-reviewed", "epoch_ms", it.rawTimestampMillis, 2.0, 30.0)
        })
        private val store = object : LocationRecordingStore {
            override fun openSegment(tripId: String, sessionId: String, segmentId: String) = Unit

            override fun append(segmentId: String, points: List<RecordedLocation>): LocationCommit {
                attempted += points.toList()
                check(!failAppend)
                batches += points.toList()
                val last = points.last()
                val capturedPermit = pointPermits[last.pointId]
                val capturedReceipt = last.receivedAtElapsedMs
                latestAuthority = { guard.canUse(capturedPermit, capturedReceipt) }
                authorizedAtCommit += latestAuthority()
                pointPermits.clear()
                return LocationCommit(points.size, 0, emptySet(), "synthetic-region")
            }

            override fun finishSegment(segmentId: String, reason: String, interrupted: Boolean) {
                finishedReasons += reason
            }
        }
        val recorder = LocationRecorder(store, verified, {
            "record-${sequence++}".also { pointPermits[it] = deliveringPermit }
        }, "synthetic-clock", 10_000).also { it.begin("trip", "session") }

        fun sampleIngress(elapsed: Long = 1_000, observed: Long = 1_000, received: Long = 1_000): Queued {
            val sample = LocationSample(37.0, 127.0, 0.0, 0f, 2f, observed, received, elapsed)
            return Queued(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample),
                guard.record(elapsed, admissible = true))
        }

        fun failureIngress(event: LocationEvent): Queued {
            guard.invalidate()
            return Queued(event, null)
        }

        fun deliver(queued: Queued) {
            deliveringPermit = queued.permit
            try { recorder.receive(queued.event) }
            finally { deliveringPermit = null }
        }
    }
}
