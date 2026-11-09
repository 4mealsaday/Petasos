package com.petasos.app.location

import com.petasos.app.capability.location.AndroidLocationMetadata
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.generation.StrictJson
import org.junit.Assert.*
import org.junit.Test

class AndroidLocationMeaningAdapterTest {
    private val adapter = AndroidLocationMeaningAdapter(maximumAccuracyMeters = 100.0)
    private val normalizer = LocationNormalizer(adapter)
    private fun metadata(
        measuredNanos: Long = 9_000_000_000,
        receivedNanos: Long = 10_000_000_000,
        accuracy: Double? = 8.0,
        mock: Boolean = false,
        uncertainty: Double? = null,
    ) = AndroidLocationMetadata("gps", measuredNanos, receivedNanos, accuracy, mock, uncertainty)
    private fun sample(rawEpoch: Long = 99_000, metadata: AndroidLocationMetadata? = this.metadata()) = LocationSample(
        37.0, 127.0, Double.NaN, Float.NaN, Float.NaN, rawEpoch, 100_000,
        metadata?.receivedAtElapsedRealtimeNanos?.div(1_000_000) ?: 10_000, metadata,
    )
    private fun normalize(sample: LocationSample = this.sample()) = normalizer.normalize(sample, LocationReadingKind.UPDATE,
        "point", "generic-input", "clock")
    private fun confidence(point: RecordedLocation) = StrictJson.parse(point.providerConfidenceJson.toByteArray()) as Map<*, *>

    @Test fun officialFieldsPreserveEpochProviderAccuracyAndMonotonicAge() {
        val point = normalize()
        assertEquals(LocationQuality.VALID, point.quality)
        assertEquals("android-location-manager:gps", point.provider)
        assertEquals("99000", point.rawTimestamp)
        assertEquals(99_000L, point.observedAtEpochMs)
        assertEquals("epoch_ms", point.sourceTimeUnit)
        assertEquals(8.0, point.accuracyMeters!!, 0.0)
        assertEquals(1_000L, point.measurementAgeAtReceiptMs)
        assertEquals(19_000L, LocationFreshness.deadlineElapsedMs(point))
        assertEquals(AndroidLocationMeaningAdapter.VERSION, point.adapterVersion)
        val raw = confidence(point)["androidLocation"] as Map<*, *>
        assertEquals(9_000_000_000L, raw["elapsedRealtimeNanos"])
        assertEquals(false, raw["isMock"])
    }

    @Test fun oldWallClockDoesNotMakeFreshMonotonicMeasurementStaleOrRewriteRawEpoch() {
        val point = normalize(sample(rawEpoch = 1_000))
        assertEquals(LocationQuality.VALID, point.quality)
        assertEquals(1_000L, point.observedAtEpochMs)
        assertEquals("1000", point.rawTimestamp)
        assertEquals(19_000L, LocationFreshness.deadlineElapsedMs(point))
    }

    @Test fun recentlyStampedEpochCannotRenewOldMonotonicMeasurement() {
        val raw = metadata(measuredNanos = 1_000_000_000, receivedNanos = 20_000_000_000)
        val point = normalize(sample(rawEpoch = 100_000, metadata = raw))
        assertEquals(LocationQuality.STALE, point.quality)
        assertEquals(19_000L, point.measurementAgeAtReceiptMs)
        assertNull(LocationFreshness.deadlineElapsedMs(point))
    }

    @Test fun futureEpochFailsClosedWithoutInventingNormalizedWallTime() {
        val point = normalize(sample(rawEpoch = 100_001))
        assertEquals(LocationQuality.INVALID, point.quality)
        assertEquals(100_001L, point.observedAtEpochMs)
        assertEquals("100001", point.rawTimestamp)
        assertNull(LocationFreshness.deadlineElapsedMs(point))
    }

    @Test fun nanosecondFutureIsRejectedEvenWithinSameMillisecond() {
        assertNull(adapter.interpret(sample(metadata = metadata(measuredNanos = 10_000_000_001))))
        assertEquals(LocationQuality.UNKNOWN, normalize(sample(metadata = metadata(measuredNanos = 10_000_000_001))).quality)
    }

    @Test fun fractionalNanosecondAgeAndTimestampUncertaintyNeverExtendDeadline() {
        val point = normalize(sample(metadata = metadata(measuredNanos = 9_999_999_999, uncertainty = 1.0)))
        assertEquals(2L, point.measurementAgeAtReceiptMs)
        assertEquals(19_998L, LocationFreshness.deadlineElapsedMs(point))
        assertNull(adapter.interpret(sample(metadata = metadata(uncertainty = Double.NaN))))
        assertNull(adapter.interpret(sample(metadata = metadata(uncertainty = -1.0))))
    }

    @Test fun repeatedFractionalCachedCallbacksKeepOneFixedMeasurementDeadline() {
        val fix = 9_000_500_000L
        val first = normalize(sample(metadata = metadata(measuredNanos = fix, receivedNanos = 10_001_900_000)))
        val next = normalize(sample(metadata = metadata(measuredNanos = fix, receivedNanos = 10_002_100_000)))
        assertEquals(19_000L, LocationFreshness.deadlineElapsedMs(first))
        assertEquals(LocationFreshness.deadlineElapsedMs(first), LocationFreshness.deadlineElapsedMs(next))
        assertTrue(next.measurementAgeAtReceiptMs!! > first.measurementAgeAtReceiptMs!!)
    }

    @Test fun demoBuildAllowsMockWhileMissingAccuracyAndInvalidNumbersStillNeverGainAutomaticQuality() {
        // BuildConfig.DEMO is true for the debug variant these unit tests compile against:
        // mock locations are the demo's virtual-position source, so they now gain normal quality.
        val mocks = normalize(sample(metadata = metadata(mock = true)))
        assertEquals(LocationQuality.VALID, mocks.quality)
        assertEquals("android-location-manager:gps", mocks.provider)
        assertEquals(true, (confidence(mocks)["androidLocation"] as Map<*, *>)["isMock"])
        listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { accuracy ->
            val point = normalize(sample(metadata = metadata(accuracy = accuracy)))
            assertEquals(LocationQuality.UNKNOWN, point.quality)
            assertNull(LocationFreshness.deadlineElapsedMs(point))
        }
    }

    @Test fun explicitAccuracyCapDoesNotConvertLowQualityIntoValidLocation() {
        assertEquals(LocationQuality.VALID, normalize(sample(metadata = metadata(accuracy = 100.0))).quality)
        assertEquals(LocationQuality.INVALID, normalize(sample(metadata = metadata(accuracy = 100.1))).quality)
    }

    @Test fun receiptClockAndOriginMetadataMustBeCoherent() {
        assertNull(adapter.interpret(sample().copy(receivedAtElapsedRealtimeMillis = 9_999)))
        assertNull(adapter.interpret(sample(metadata = metadata(measuredNanos = -1))))
        assertNull(adapter.interpret(sample(metadata = metadata().copy(provider = ""))))
        assertNull(adapter.interpret(sample(metadata = null)))
    }

    @Test fun pleosFieldsAndUnknownAdapterNeverInheritAndroidSemantics() {
        val pleos = sample(metadata = null).copy(rawConfidence = 0.9f, rawTimestampMillis = 99_000)
        val point = normalizer.normalize(pleos, LocationReadingKind.UPDATE, "p", "pleos", "clock")
        assertEquals(LocationQuality.UNKNOWN, point.quality)
        assertEquals("pleos", point.provider)
        assertNull(point.observedAtEpochMs)
        val unknown = LocationNormalizer().normalize(sample(), LocationReadingKind.UPDATE, "p", "pleos", "clock")
        assertEquals(LocationQuality.UNKNOWN, unknown.quality)
        assertEquals("pleos", unknown.provider)
    }

    @Test fun persistedAgeRequiresMatchingOriginalAndroidEvidence() {
        val point = normalize()
        val evidence = confidence(point)
        assertEquals(1_000L, AndroidLocationMeaningAdapter.restoredAgeMs(evidence, 10_000))
        assertNull(AndroidLocationMeaningAdapter.restoredAgeMs(evidence - "measurementAgeAtReceiptMs", 10_000))
        assertNull(AndroidLocationMeaningAdapter.restoredAgeMs(evidence + ("measurementAgeAtReceiptMs" to 0L), 10_000))
        assertNull(AndroidLocationMeaningAdapter.restoredAgeMs(evidence - "androidLocation", 10_000))
        assertNull(AndroidLocationMeaningAdapter.restoredAgeMs(evidence, 9_999))
        val raw = evidence["androidLocation"] as Map<*, *>
        // Demo-build mock locations recompute to the same age; only the DEMO gate changes, not the formula.
        assertEquals(1_000L, AndroidLocationMeaningAdapter.restoredAgeMs(evidence + ("androidLocation" to (raw + ("isMock" to true))), 10_000))
        assertNull(AndroidLocationMeaningAdapter.restoredAgeMs(evidence + ("androidLocation" to (raw + ("elapsedRealtimeNanos" to 10_000_000_001L))), 10_000))
    }

    @Test fun missingOrNegativeRestoredAgeCannotFallBackToEpoch() {
        val point = normalize()
        assertNull(LocationFreshness.deadlineElapsedMs(point.copy(measurementAgeAtReceiptMs = null)))
        assertNull(LocationFreshness.deadlineElapsedMs(point.copy(measurementAgeAtReceiptMs = -1)))
        assertEquals("LocationSample(values=<redacted>)", sample().toString())
        assertEquals("AndroidLocationMetadata([redacted])", metadata().toString())
        assertEquals("RecordedLocation([redacted])", point.toString())
    }
}
