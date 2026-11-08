package com.petasos.app.location

import com.petasos.app.capability.location.*
import org.junit.Assert.*
import org.junit.Test

class RegionConfirmationTest {
    private val policy = RegionPolicy("synthetic-dataset", 3, 2_000, 10.0, "synthetic-v1", "EPSG:4326")
    private val boundary = RegionBoundaryLookup { _, _ -> listOf(RegionContainment("revision", "synthetic-dataset", "EPSG:4326", 100.0)) }
    private val points get() = (0..2).map { i ->
        val point = LocationNormalizer(LocationMeaningAdapter {
            VerifiedLocationMeaning("synthetic-v1", "epoch_ms", it.rawTimestampMillis, 5.0, 30.0)
        }).normalize(LocationSample(37.0, 127.0, 1.0, 0f, 5f, i * 1_000L, i * 1_000L, i * 1_000L),
            LocationReadingKind.UPDATE, "p$i", "synthetic", "clock")
        RegionPoint(i.toLong(), point)
    }

    @Test fun sameCoordinatesCanConfirmWithDistinctConsecutiveMeasurements() {
        assertTrue(RegionConfirmation.confirmed("revision", policy, points, boundary))
    }

    @Test fun invalidPointAndSequenceGapsCannotBeFilteredOutOfConfirmation() {
        val invalid = points.toMutableList().apply { this[1] = this[1].copy(location = this[1].location.copy(quality = LocationQuality.UNKNOWN)) }
        assertFalse(RegionConfirmation.confirmed("revision", policy, invalid, boundary))
        val missing = points.toMutableList().apply { this[1] = this[1].copy(sequence = 99) }
        assertFalse(RegionConfirmation.confirmed("revision", policy, missing, boundary))
    }

    @Test fun receiptAndObservationClocksMustAdvanceWithinOneClockScope() {
        val cached = points.toMutableList().apply { this[2] = this[2].copy(location = this[2].location.copy(observedAtEpochMs = 0)) }
        assertFalse(RegionConfirmation.confirmed("revision", policy, cached, boundary))
        val scope = points.toMutableList().apply { this[2] = this[2].copy(location = this[2].location.copy(clockScopeId = "reboot")) }
        assertFalse(RegionConfirmation.confirmed("revision", policy, scope, boundary))
        assertFalse(RegionConfirmation.confirmed("revision", policy.copy(minConfirmationDurationMs = 2_001), points, boundary))
    }

    @Test fun boundaryDistanceIncludesAccuracyAndAmbiguousRegionsDoNotConfirm() {
        val near = RegionBoundaryLookup { _, _ -> listOf(RegionContainment("revision", "synthetic-dataset", "EPSG:4326", 14.9)) }
        assertFalse(RegionConfirmation.confirmed("revision", policy, points, near))
        val ambiguous = RegionBoundaryLookup { _, _ -> boundary.containing(0.0, 0.0) + RegionContainment("other", "synthetic-dataset", "EPSG:4326", 100.0) }
        assertFalse(RegionConfirmation.confirmed("revision", policy, points, ambiguous))
        assertFalse(RegionConfirmation.confirmed("revision", policy, points, RegionBoundaryLookup.NONE))
    }

    @Test fun wrongDatasetOrQualityAdapterVersionCannotConfirm() {
        assertFalse(RegionConfirmation.confirmed("revision", policy.copy(datasetVersion = "new-data"), points, boundary))
        assertFalse(RegionConfirmation.confirmed("revision", policy.copy(qualityAdapterVersion = "new-adapter"), points, boundary))
    }

    @Test fun zeroMarginAndZeroAccuracyStillRequireProvenInterior() {
        val coordinates = listOf(
            BoundaryCoordinate(127.0, 37.0), BoundaryCoordinate(127.01, 37.0),
            BoundaryCoordinate(127.01, 37.01), BoundaryCoordinate(127.0, 37.01), BoundaryCoordinate(127.0, 37.0),
        )
        val realEngine = ReviewedPolygonBoundaryLookup("synthetic-dataset", listOf(
            ReviewedRegionGeometry("revision", listOf(RegionPolygon(coordinates))),
        ))
        fun located(longitude: Double) = points.map { it.copy(location = it.location.copy(
            latitude = 37.005, longitude = longitude, accuracyMeters = 0.0,
        )) }
        val zeroMargin = policy.copy(boundaryMarginMeters = 0.0)
        assertFalse(RegionConfirmation.confirmed("revision", zeroMargin, located(127.0), realEngine))
        assertFalse(RegionConfirmation.confirmed("revision", zeroMargin, located(127.0 - 1e-11), realEngine))
        assertTrue(RegionConfirmation.confirmed("revision", zeroMargin, located(127.005), realEngine))
    }
}
