package com.petasos.app.location

import com.petasos.app.capability.location.*
import org.junit.Assert.*
import org.junit.Test

class ReviewedRegionHierarchyTest {
    private val city = RegionHierarchyNode("city", "city-r1")
    private val first = RegionHierarchyNode("first", "first-r1")
    private val second = RegionHierarchyNode("second", "second-r1")
    private val hierarchy = ReviewedRegionHierarchy("review-1", "dataset", listOf(
        RegionHierarchyRelation(first, city), RegionHierarchyRelation(second, city)))
    private fun contains(node: RegionHierarchyNode, distance: Double = 100.0) =
        RegionContainment(node.regionRevisionId, "dataset", "EPSG:4326", distance)

    @Test fun parentChildSelectionIsOrderIndependentAndKeepsTheLeafClearance() {
        val leaf = contains(first, 3.0)
        assertEquals(leaf, hierarchy.select(listOf(contains(city), leaf)))
        assertEquals(leaf, hierarchy.select(listOf(leaf, contains(city))))
        assertEquals(listOf("first", "city"), hierarchy.scope("first").eligibleStoryRegionIds)
        assertEquals(listOf("city"), hierarchy.scope("city").eligibleStoryRegionIds)
        assertEquals("city-r1", hierarchy.reviewedRevision("city"))
        assertEquals("first-r1", hierarchy.reviewedRevision("first"))
        assertNull(hierarchy.reviewedRevision("unknown"))
        assertNull(ReviewedRegionHierarchy.NONE.reviewedRevision("city"))
    }

    @Test fun siblingUnknownOverlapMissingCoverageAndDatasetMismatchStayAmbiguous() {
        assertNull(hierarchy.select(listOf(contains(first), contains(second), contains(city))))
        assertNull(hierarchy.select(listOf(contains(first), contains(city), contains(RegionHierarchyNode("other", "other-r")))))
        assertNull(hierarchy.select(listOf(contains(city)))) // No city-card fallback across a geometry gap.
        assertNull(hierarchy.select(listOf(contains(first)))) // The reviewed parent must also be present.
        assertNull(hierarchy.select(listOf(contains(first), contains(first), contains(city))))
        assertNull(hierarchy.select(listOf(contains(first).copy(datasetVersion = "changed"), contains(city))))
        assertNull(hierarchy.select(listOf(contains(first), contains(city, 0.0))))
        assertEquals("unrelated", hierarchy.select(listOf(RegionContainment("unrelated", "dataset", "EPSG:4326", 100.0)))?.regionRevisionId)
    }

    @Test fun cyclesAndMultipleParentsAreInvalidInsteadOfFirstRowWins() {
        assertThrows(IllegalArgumentException::class.java) {
            ReviewedRegionHierarchy("review", "dataset", listOf(RegionHierarchyRelation(first, city), RegionHierarchyRelation(city, first)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReviewedRegionHierarchy("review", "dataset", listOf(RegionHierarchyRelation(first, city), RegionHierarchyRelation(first, second)))
        }
    }

    @Test fun sameSharedWrapperConfirmsOnlyLeafAndRetainsQualityBoundaryAndContinuityRules() {
        var distance = 100.0
        var parentAuthorized = true
        val wrapped = HierarchicalRegionBoundaryLookup(RegionBoundaryLookup { _, _ ->
            listOf(contains(city), contains(first, distance))
        }, hierarchy) { it != city.regionRevisionId || parentAuthorized }
        val policy = RegionPolicy("dataset", 3, 2_000, 10.0, "synthetic-v1", "EPSG:4326")
        val points = (0..2).map { i ->
            val point = LocationNormalizer(LocationMeaningAdapter {
                VerifiedLocationMeaning("synthetic-v1", "epoch_ms", it.rawTimestampMillis, 5.0, 30.0)
            }).normalize(LocationSample(37.0, 127.0, 1.0, 0f, 5f, i * 1_000L, i * 1_000L, i * 1_000L),
                LocationReadingKind.UPDATE, "p$i", "synthetic", "clock")
            RegionPoint(i.toLong(), point)
        }
        assertTrue(RegionConfirmation.confirmed(first.regionRevisionId, policy, points, wrapped))
        assertFalse(RegionConfirmation.confirmed(city.regionRevisionId, policy, points, wrapped))
        distance = 14.9
        assertFalse(RegionConfirmation.confirmed(first.regionRevisionId, policy, points, wrapped))
        distance = 100.0
        val interrupted = points.toMutableList().apply { this[1] = this[1].copy(location = this[1].location.copy(quality = LocationQuality.UNKNOWN)) }
        assertFalse(RegionConfirmation.confirmed(first.regionRevisionId, policy, interrupted, wrapped))
        parentAuthorized = false
        assertTrue(wrapped.containing(37.0, 127.0).isEmpty())
        assertFalse(wrapped.isSelectionCurrent(first.regionRevisionId))
    }

    @Test fun forecastAgreementIsDirectionalAndScopeIsImmutable() {
        assertTrue(hierarchy.agrees("first", "city"))
        assertFalse(hierarchy.agrees("city", "first"))
        assertFalse(hierarchy.agrees("first", "second"))
        assertThrows(UnsupportedOperationException::class.java) {
            (hierarchy.scope("first").eligibleStoryRegionIds as MutableList<String>).add("forged")
        }
    }
}
