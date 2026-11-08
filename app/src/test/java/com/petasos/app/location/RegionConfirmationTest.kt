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

    @Test fun minimumCountAndDurationSelectExactShortestSuffixAtEveryReceiptRate() {
        for ((interval, expectedSize) in listOf(1_000L to 3, 500L to 5, 200L to 11)) {
            val rows = rows(31, interval)
            val selected = RegionConfirmation.proof("revision", policy, rows, boundary)!!
            assertEquals(expectedSize, selected.size)
            assertEquals(rows.takeLast(expectedSize), selected)
            assertEquals(2_000L, selected.last().location.receivedAtElapsedMs - selected.first().location.receivedAtElapsedMs)
            assertNull(RegionConfirmation.proof("revision", policy, selected.drop(1), boundary))
        }
    }

    @Test fun olderBadRowsAreOutsideTheShortestProofButInterveningBadRowsCannotBeSkipped() {
        val rows = rows(20, 200).toMutableList()
        rows[0] = rows[0].copy(location = rows[0].location.copy(quality = LocationQuality.INVALID))
        assertEquals(rows.takeLast(11), RegionConfirmation.proof("revision", policy, rows, boundary))
        rows[15] = rows[15].copy(location = rows[15].location.copy(quality = LocationQuality.UNKNOWN))
        assertNull(RegionConfirmation.proof("revision", policy, rows, boundary))
        val differentRegion = RegionBoundaryLookup { _, longitude ->
            listOf(RegionContainment(if (longitude == 128.0) "other" else "revision", "synthetic-dataset", "EPSG:4326", 100.0))
        }
        val crossing = rows(11, 200).toMutableList().apply {
            this[5] = this[5].copy(location = this[5].location.copy(longitude = 128.0))
        }
        assertNull(RegionConfirmation.proof("revision", policy, crossing, differentRegion))
    }

    @Test fun proofSizeIsBoundedAndOnlyNecessaryGeometryIsRead() {
        var calls = 0
        val counted = RegionBoundaryLookup { a, b -> calls++; boundary.containing(a, b) }
        val shortPolicy = policy.copy(consecutiveSamples = 2, minConfirmationDurationMs = 0)
        assertEquals(2, RegionConfirmation.proof("revision", shortPolicy, rows(100, 200), counted)!!.size)
        assertEquals(2, calls)
        assertNull(RegionConfirmation.proof("revision", shortPolicy, rows(101, 200), counted))
        assertEquals(2, calls)
        assertNull(RegionConfirmation.proof("revision", policy, rows(100, 10), boundary))
    }

    @Test fun finalSelectionRecheckCannotAcceptWithdrawalAfterAllGeometryReads() {
        var checks = 0
        val changed = object : RegionBoundaryLookup {
            override val selectionKey = "reviewed-selection"
            override fun containing(latitude: Double, longitude: Double) = boundary.containing(latitude, longitude)
            override fun isSelectionCurrent(regionRevisionId: String) = ++checks <= 3
        }
        assertNull(RegionConfirmation.proof("revision", policy, points, changed))
        assertEquals(4, checks)
    }

    @Test fun duplicatePointIdsAndAndroidMeasurementsNeverCountAsNewEvidence() {
        val duplicate = rows(11, 200).toMutableList().apply {
            this[5] = this[5].copy(location = this[5].location.copy(pointId = this[6].location.pointId))
        }
        assertNull(RegionConfirmation.proof("revision", policy, duplicate, boundary))
        val androidPolicy = policy.copy(qualityAdapterVersion = AndroidLocationMeaningAdapter.VERSION)
        val normalizer = LocationNormalizer(AndroidLocationMeaningAdapter(100.0))
        fun androidRows(cached: Boolean) = (0..10).map { index ->
            val receipt = 10_000L + index * 200
            val measured = if (cached) 10_000L else receipt
            val sample = LocationSample(37.0, 127.0, 0.0, 0f, 0f, receipt, receipt, receipt,
                AndroidLocationMetadata("gps", measured * 1_000_000, receipt * 1_000_000, 5.0, false, null))
            RegionPoint(index.toLong(), normalizer.normalize(sample, LocationReadingKind.UPDATE,
                "android-$index", "android", "clock"))
        }
        assertEquals(11, RegionConfirmation.proof("revision", androidPolicy, androidRows(false), boundary)!!.size)
        // Epoch advances, but the independently verified same-boot measurement is unchanged.
        assertNull(RegionConfirmation.proof("revision", androidPolicy, androidRows(true), boundary))
        val corrupt = androidRows(false).toMutableList().apply {
            this[5] = this[5].copy(location = this[5].location.copy(providerConfidenceJson = "{}"))
        }
        assertNull(RegionConfirmation.proof("revision", androidPolicy, corrupt, boundary))
    }

    private fun rows(count: Int, intervalMs: Long): List<RegionPoint> = List(count) { index ->
        val at = 10_000L + index * intervalMs
        RegionPoint(index.toLong(), points.first().location.copy(pointId = "sample-$index",
            observedAtEpochMs = at, receivedAtEpochMs = at, receivedAtElapsedMs = at))
    }
}
