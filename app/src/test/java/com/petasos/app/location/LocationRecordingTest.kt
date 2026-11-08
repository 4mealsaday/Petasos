package com.petasos.app.location

import com.petasos.app.capability.location.*
import org.junit.Assert.*
import org.junit.Test

class LocationRecordingTest {
    private val verified = LocationNormalizer(LocationMeaningAdapter {
        VerifiedLocationMeaning("synthetic-v1", "epoch_ms", it.rawTimestampMillis, it.rawConfidence.toDouble(), 30.0)
    })
    private fun sample(elapsed: Long = 1_000, observed: Long = 1_000, received: Long = 1_000, latitude: Double = 37.0, confidence: Float = 2f) =
        LocationSample(latitude, 127.0, 1.0, 0f, confidence, observed, received, elapsed)
    private fun normalize(sample: LocationSample, normalizer: LocationNormalizer = verified) =
        normalizer.normalize(sample, LocationReadingKind.UPDATE, "point", "synthetic", "clock")

    @Test fun unverifiedPleosValuesStayUnknownIncludingRecentLastKnownReceipt() {
        val point = LocationNormalizer().normalize(sample(observed = 3, received = 900_000), LocationReadingKind.LAST_KNOWN, "p", "pleos", "c")
        assertEquals(LocationQuality.UNKNOWN, point.quality)
        assertNull(point.observedAtEpochMs)
        assertNull(point.accuracyMeters)
        assertEquals("unknown", point.sourceTimeUnit)
        assertEquals("3", point.rawTimestamp)
        assertFalse(point.toString().contains("127"))
    }

    @Test fun invalidStaleFutureAndUnknownQualityCannotBecomeValid() {
        assertEquals(LocationQuality.VALID, normalize(sample()).quality)
        assertEquals(LocationQuality.STALE, normalize(sample(observed = 1, received = 10_002)).quality)
        assertEquals(LocationQuality.INVALID, normalize(sample(observed = 1_001)).quality)
        assertEquals(LocationQuality.INVALID, normalize(sample(confidence = 40f)).quality)
        val invalid = normalize(sample(latitude = Double.NaN))
        assertEquals(LocationQuality.INVALID, invalid.quality)
        assertNull(invalid.latitude)
        assertTrue(invalid.rawCoordinatesJson.contains("NaN"))
        assertEquals(LocationQuality.UNKNOWN, normalize(sample(), LocationNormalizer(LocationMeaningAdapter { error("private error") })).quality)
    }

    @Test fun coordinatesAndDuplicateTimesAreNotDeduplicatedAndBatchIsBounded() {
        val storage = Store()
        val recorder = recorder(storage)
        recorder.begin("trip", "session")
        repeat(20) { recorder.receive(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample())) }
        assertEquals(20, storage.batches.single().size)
        assertEquals(20, storage.batches.single().map { it.pointId }.distinct().size)
        assertEquals(0, recorder.pendingCount)
        assertEquals(1, storage.opened.size)
    }

    @Test fun monotonicTickFlushesAtOneSecondAndStopFlushesImmediately() {
        val storage = Store()
        val recorder = recorder(storage)
        recorder.begin("trip", "session")
        recorder.receive(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample()))
        assertNull(recorder.tick(1_999))
        assertNotNull(recorder.tick(2_000))
        recorder.receive(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample(elapsed = 2_100)))
        recorder.stop()
        assertEquals(listOf(1, 1), storage.batches.map { it.size })
        assertEquals(false, storage.closed.single().second)
        recorder.receive(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample(elapsed = 2_200)))
        assertEquals(2, storage.batches.size)
    }

    @Test fun permissionAndReceiptGapsNeverConnectSegments() {
        val storage = Store()
        val recorder = recorder(storage)
        recorder.begin("trip", "session")
        recorder.receive(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample()))
        recorder.receive(LocationEvent.PermissionDenied)
        assertEquals(LocationRecordingStatus.PERMISSION_DENIED, recorder.status)
        recorder.receive(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample(elapsed = 1_100)))
        recorder.tick(8_000)
        assertEquals(LocationRecordingStatus.DISCONNECTED, recorder.status)
        recorder.receive(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample(elapsed = 9_000)))
        assertEquals(3, storage.opened.size)
        assertEquals(listOf(true, true), storage.closed.map { it.second })
    }

    @Test fun absentCachedReadingDoesNotCloseLiveUpdateSegment() {
        val storage = Store()
        val recorder = recorder(storage)
        recorder.begin("trip", "session")
        recorder.receive(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample()))
        recorder.receive(LocationEvent.NoLocation(LocationReadingKind.LAST_KNOWN))
        assertTrue(storage.closed.isEmpty())
        assertEquals(LocationRecordingStatus.RECEIVING, recorder.status)
    }

    @Test fun failedFlushRetainsIdsAndCannotGrowPastTwentyPoints() {
        val storage = Store().apply { fail = true }
        val recorder = recorder(storage)
        recorder.begin("trip", "session")
        repeat(19) { recorder.receive(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample())) }
        try { recorder.receive(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample())); fail() } catch (_: IllegalStateException) { }
        assertEquals(20, recorder.pendingCount)
        try { recorder.receive(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample())); fail() } catch (_: IllegalStateException) { }
        assertEquals(20, recorder.pendingCount)
        val failedIds = storage.attempted.last().map { it.pointId }
        storage.fail = false
        recorder.flush()
        assertEquals(failedIds, storage.batches.single().map { it.pointId })
    }

    @Test fun routeRevisionIsCapturedAtReceiptRatherThanAtBatchCommit() {
        var route = 1L
        val storage = Store()
        var sequence = 0
        val recorder = LocationRecorder(storage, verified, { "id-${sequence++}" }, "clock", 5_000, routeRevision = { route })
        recorder.begin("trip", "session")
        recorder.receive(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample()))
        route = 2
        recorder.receive(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample(elapsed = 1_100)))
        recorder.flush()
        assertEquals(listOf(1L, 2L), storage.batches.single().map { it.routeRevision })
    }

    private fun recorder(storage: Store): LocationRecorder {
        var id = 0
        return LocationRecorder(storage, verified, { "id-${id++}" }, "clock", 5_000)
    }

    private class Store : LocationRecordingStore {
        val opened = mutableListOf<String>()
        val closed = mutableListOf<Pair<String, Boolean>>()
        val batches = mutableListOf<List<RecordedLocation>>()
        val attempted = mutableListOf<List<RecordedLocation>>()
        var fail = false
        override fun openSegment(tripId: String, sessionId: String, segmentId: String) { opened += segmentId }
        override fun append(segmentId: String, points: List<RecordedLocation>): LocationCommit {
            attempted += points
            check(!fail)
            batches += points
            return LocationCommit(points.size, 0, emptySet())
        }
        override fun finishSegment(segmentId: String, reason: String, interrupted: Boolean) { closed += segmentId to interrupted }
    }
}
