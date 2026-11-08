package com.petasos.app.location

import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import org.junit.Assert.*
import org.junit.Test

class LocationFreshnessTest {
    private fun point(observed: Long = 1_000, received: Long = 10_000, elapsed: Long = 50_000,
        kind: LocationReadingKind = LocationReadingKind.LAST_KNOWN): RecordedLocation {
        val sample = LocationSample(37.0, 127.0, 0.0, 0f, 2f, observed, received, elapsed)
        return LocationNormalizer(LocationMeaningAdapter {
            VerifiedLocationMeaning("reviewed-test", "epoch_ms", it.rawTimestampMillis, 2.0, 30.0)
        }).normalize(sample, kind, "point", "reviewed-provider", "clock")
    }

    @Test fun oldCachedMeasurementSpendsItsAgeBeforeTheReceiptDeadlineStarts() {
        val cached = point()
        assertEquals(LocationQuality.VALID, cached.quality)
        assertEquals(51_000L, LocationFreshness.deadlineElapsedMs(cached))
        assertTrue(LocationFreshness.isFresh(cached.receivedAtElapsedMs, 51_000, 51_000, 10_000))
        assertFalse(LocationFreshness.isFresh(cached.receivedAtElapsedMs, 51_000, 51_001, 10_000))
        assertEquals(1_000L, cached.observedAtEpochMs)
        assertEquals(10_000L, cached.receivedAtEpochMs)
        assertEquals(50_000L, cached.receivedAtElapsedMs)
        assertEquals("1000", cached.rawTimestamp)
        assertEquals(51_000L, LocationFreshness.deadlineElapsedMs(point(kind = LocationReadingKind.UPDATE)))
    }

    @Test fun exactMaximumMeasurementAgeAllowsOnlyTheReceiptInstant() {
        val cached = point(observed = 0)
        val deadline = LocationFreshness.deadlineElapsedMs(cached)
        assertEquals(50_000L, deadline)
        assertTrue(LocationFreshness.isFresh(50_000, deadline, 50_000, 10_000))
        assertFalse(LocationFreshness.isFresh(50_000, deadline, 50_001, 10_000))
    }

    @Test fun freshMeasurementGetsTheFullBudgetAndCustomBudgetStaysExplicit() {
        val fresh = point(observed = 10_000)
        assertEquals(60_000L, LocationFreshness.deadlineElapsedMs(fresh))
        assertEquals(51_000L, LocationFreshness.deadlineElapsedMs(fresh, freshnessMs = 1_000))
        assertNull(LocationFreshness.deadlineElapsedMs(point(), freshnessMs = 1_000))
        assertThrows(IllegalArgumentException::class.java) { LocationFreshness.deadlineElapsedMs(fresh, freshnessMs = 0) }
    }

    @Test fun doubtfulAndIncompleteRecordsNeverAcquireADeadline() {
        val valid = point()
        val doubtful = listOf(
            valid.copy(quality = LocationQuality.UNKNOWN), valid.copy(quality = LocationQuality.INVALID),
            valid.copy(quality = LocationQuality.STALE), valid.copy(observedAtEpochMs = null),
            valid.copy(observedAtEpochMs = -1), valid.copy(observedAtEpochMs = 10_001),
            valid.copy(receivedAtEpochMs = -1), valid.copy(receivedAtElapsedMs = -1),
            valid.copy(adapterVersion = null), valid.copy(adapterVersion = " "),
            valid.copy(sourceTimeUnit = "unknown"), valid.copy(clockScopeId = ""), valid.copy(provider = ""),
            valid.copy(accuracyMeters = null), valid.copy(accuracyMeters = -1.0),
            valid.copy(accuracyMeters = Double.POSITIVE_INFINITY), valid.copy(latitude = null),
            valid.copy(latitude = Double.NaN), valid.copy(latitude = 91.0),
            valid.copy(longitude = null), valid.copy(longitude = -181.0),
            point(observed = 0, received = 10_001).copy(quality = LocationQuality.VALID),
        )
        doubtful.forEach { assertNull(LocationFreshness.deadlineElapsedMs(it)) }
    }

    @Test fun additionOverflowDoesNotWrapOrSaturateIntoAValidPermit() {
        assertNull(LocationFreshness.deadlineElapsedMs(point(observed = 10_000, elapsed = Long.MAX_VALUE - 9_999)))
        assertEquals(Long.MAX_VALUE, LocationFreshness.deadlineElapsedMs(point(observed = 10_000, elapsed = Long.MAX_VALUE - 10_000)))
        assertEquals(Long.MAX_VALUE, LocationFreshness.deadlineElapsedMs(point(observed = 0, elapsed = Long.MAX_VALUE)))
    }

    @Test fun missingDeadlineBackwardTimeAndConsumerAgeCapFailClosed() {
        assertFalse(LocationFreshness.isFresh(50_000, null, 50_000, 10_000))
        assertFalse(LocationFreshness.isFresh(null, 60_000, 50_000, 10_000))
        assertFalse(LocationFreshness.isFresh(50_000, 60_000, 49_999, 10_000))
        assertFalse(LocationFreshness.isFresh(50_000, 49_999, 50_000, 10_000))
        assertFalse(LocationFreshness.isFresh(-1, 60_000, 50_000, 10_000))
        assertTrue(LocationFreshness.isFresh(50_000, Long.MAX_VALUE, 60_000, 10_000))
        assertFalse(LocationFreshness.isFresh(50_000, Long.MAX_VALUE, 60_001, 10_000))
        assertFalse(LocationFreshness.isFresh(0, Long.MAX_VALUE, Long.MAX_VALUE, 10_000))
    }
}
