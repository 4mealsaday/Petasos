package com.petasos.app.location

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Synthetic geometry proves engine behavior only; no fixture is a reviewed administrative dataset. */
class ReviewedPolygonBoundaryLookupTest {
    @Test fun concaveExteriorUsesContainmentRatherThanBoundingBox() {
        val boundary = lookup(polygon(
            127.0 to 37.0, 127.03 to 37.0, 127.03 to 37.01,
            127.01 to 37.01, 127.01 to 37.03, 127.0 to 37.03,
        ))
        assertEquals(1, boundary.containing(37.025, 127.005).size)
        assertTrue(boundary.containing(37.025, 127.025).isEmpty())
        assertTrue(boundary.containing(36.99, 127.005).isEmpty())
    }

    @Test fun polygonHolesExcludeInteriorAndContributeNearestClearance() {
        val outer = ring(127.0 to 37.0, 127.04 to 37.0, 127.04 to 37.04, 127.0 to 37.04)
        val hole = ring(127.01 to 37.01, 127.03 to 37.01, 127.03 to 37.03, 127.01 to 37.03)
        val boundary = lookup(RegionPolygon(outer, listOf(hole)))
        assertTrue(boundary.containing(37.02, 127.02).isEmpty())
        val nearHole = boundary.containing(37.02, 127.0099).single()
        assertTrue(nearHole.interiorDistanceMeters in 7.0..8.0)
        assertEquals(0.0, boundary.containing(37.02, 127.01).single().interiorDistanceMeters, 0.0)
    }

    @Test fun multipolygonHasDisjointIslandsWithoutFillingWaterBetweenThem() {
        val first = polygon(126.0 to 34.0, 126.01 to 34.0, 126.005 to 34.01)
        val second = polygon(126.1 to 34.0, 126.11 to 34.0, 126.105 to 34.01)
        val boundary = lookup(first, second)
        assertEquals("synthetic-region", boundary.containing(34.005, 126.005).single().regionRevisionId)
        assertEquals("synthetic-region", boundary.containing(34.005, 126.105).single().regionRevisionId)
        assertTrue(boundary.containing(34.005, 126.055).isEmpty())
    }

    @Test fun islandInsideAnotherPolygonHoleIsValidMultipartGeometry() {
        val shell = RegionPolygon(
            ring(127.0 to 37.0, 127.06 to 37.0, 127.06 to 37.06, 127.0 to 37.06),
            listOf(ring(127.01 to 37.01, 127.05 to 37.01, 127.05 to 37.05, 127.01 to 37.05)),
        )
        val island = polygon(127.02 to 37.02, 127.04 to 37.02, 127.03 to 37.04)
        val boundary = lookup(shell, island)
        assertEquals(1, boundary.containing(37.025, 127.03).size)
        assertTrue(boundary.containing(37.015, 127.03).isEmpty())
    }

    @Test fun overlappingRegionsAreAllReturnedWithoutChoosingPriority() {
        val first = polygon(127.0 to 37.0, 127.02 to 37.0, 127.02 to 37.02, 127.0 to 37.02)
        val second = polygon(127.01 to 37.0, 127.03 to 37.0, 127.03 to 37.02, 127.01 to 37.02)
        val boundary = ReviewedPolygonBoundaryLookup("synthetic-dataset", listOf(
            ReviewedRegionGeometry("a", listOf(first)), ReviewedRegionGeometry("b", listOf(second)),
        ))
        assertEquals(setOf("a", "b"), boundary.containing(37.01, 127.015).map { it.regionRevisionId }.toSet())
        assertEquals("a", boundary.containing(37.01, 127.005).single().regionRevisionId)
    }

    @Test fun sharedAdministrativeBorderReturnsBothRegionsWithZeroClearance() {
        val first = polygon(127.0 to 37.0, 127.01 to 37.0, 127.01 to 37.01, 127.0 to 37.01)
        val second = polygon(127.01 to 37.0, 127.02 to 37.0, 127.02 to 37.01, 127.01 to 37.01)
        val boundary = ReviewedPolygonBoundaryLookup("synthetic-dataset", listOf(
            ReviewedRegionGeometry("a", listOf(first)), ReviewedRegionGeometry("b", listOf(second)),
        ))
        val matches = boundary.containing(37.005, 127.01)
        assertEquals(2, matches.size)
        assertTrue(matches.all { it.interiorDistanceMeters == 0.0 })
    }

    @Test fun verticesAndHorizontalVerticalSlantedEdgesHaveZeroClearance() {
        val boundary = lookup(polygon(127.0 to 37.0, 127.02 to 37.0, 127.0 to 37.02))
        for ((latitude, longitude) in listOf(37.0 to 127.0, 37.0 to 127.01, 37.01 to 127.0, 37.01 to 127.01)) {
            assertEquals(0.0, boundary.containing(latitude, longitude).single().interiorDistanceMeters, 0.0)
        }
        assertTrue(boundary.containing(37.01, 127.0100001).isEmpty())
        assertTrue(boundary.containing(37.01, 127.0099999).single().interiorDistanceMeters in 0.0..0.02)
    }

    @Test fun pointWithinRoundoffMarginHasNoPositiveClearance() {
        val boundary = lookup(polygon(127.0 to 37.0, 127.01 to 37.0, 127.01 to 37.01, 127.0 to 37.01))
        assertEquals(0.0, boundary.containing(37.005, 127.0 + 1e-9).single().interiorDistanceMeters, 0.0)
    }

    @Test fun clearanceNeverOverstatesKnownEquatorialMetricBoundAtKoreanLatitude() {
        // For a latitude-aligned edge, the true nearest distance is a meridian arc. WGS84's
        // meridional radius in Korea exceeds 6,335,439 m; the engine must report less than this bound.
        val boundary = lookup(polygon(126.9 to 37.0, 127.1 to 37.0, 127.1 to 37.02, 126.9 to 37.02))
        val distance = boundary.containing(37.001, 127.0).single().interiorDistanceMeters
        val lowerMeridianArc = 6_335_439.0 * Math.toRadians(0.001)
        assertTrue(distance > 100.0)
        assertTrue(distance < lowerMeridianArc)
    }

    @Test fun slantedSegmentClearanceUsesInteriorProjectionRatherThanOnlyVertices() {
        val boundary = lookup(polygon(127.0 to 37.0, 127.02 to 37.0, 127.0 to 37.02))
        val distance = boundary.containing(37.009, 127.009).single().interiorDistanceMeters
        // Nearest diagonal is about 140 m away; all vertices are over a kilometre away.
        assertTrue(distance in 120.0..135.0)
    }

    @Test fun resultPreservesDatasetRevisionAndCrs() {
        val geometry = polygon(127.0 to 37.0, 127.01 to 37.0, 127.005 to 37.01)
        val boundary = ReviewedPolygonBoundaryLookup("synthetic-data-v2", listOf(ReviewedRegionGeometry("region-r7", listOf(geometry))))
        val result = boundary.containing(37.005, 127.005).single()
        assertEquals("synthetic-data-v2", result.datasetVersion)
        assertEquals("region-r7", result.regionRevisionId)
        assertEquals("EPSG:4326", result.coordinateReferenceSystem)
    }

    @Test fun eitherRingWindingIsSupported() {
        val points = ring(127.0 to 37.0, 127.02 to 37.0, 127.02 to 37.02, 127.0 to 37.02)
        val a = lookup(RegionPolygon(points)).containing(37.005, 127.005).single()
        val b = lookup(RegionPolygon(points.reversed())).containing(37.005, 127.005).single()
        assertEquals(a, b)
    }

    @Test fun straightConsecutiveEdgesAreValidButBacktrackingIsRejected() {
        val valid = polygon(127.0 to 37.0, 127.01 to 37.0, 127.02 to 37.0, 127.02 to 37.02, 127.0 to 37.02)
        assertEquals(1, lookup(valid).containing(37.005, 127.005).size)
        rejects { lookup(polygon(127.0 to 37.0, 127.02 to 37.0, 127.01 to 37.0, 127.02 to 37.02, 127.0 to 37.02)) }
    }

    @Test fun openShortZeroLengthAndCollinearRingsAreRejected() {
        rejects { RegionPolygon(listOf(point(127.0, 37.0), point(127.01, 37.0), point(127.0, 37.01))) }
        rejects { RegionPolygon(listOf(point(127.0, 37.0), point(127.01, 37.0), point(127.01, 37.01), point(127.0, 37.01))) }
        rejects { lookup(polygon(127.0 to 37.0, 127.01 to 37.0, 127.01 to 37.0, 127.0 to 37.01)) }
        rejects { lookup(polygon(127.0 to 37.0, 127.01 to 37.0, 127.02 to 37.0)) }
    }

    @Test fun selfIntersectionNonAdjacentTouchAndRepeatedVerticesAreRejected() {
        rejects { lookup(polygon(127.0 to 37.0, 127.03 to 37.02, 127.0 to 37.02, 127.02 to 37.0)) }
        rejects { lookup(polygon(127.0 to 37.0, 127.02 to 37.0, 127.02 to 37.02, 127.01 to 37.0, 127.0 to 37.02)) }
        rejects { lookup(polygon(127.0 to 37.0, 127.02 to 37.0, 127.02 to 37.02, 127.0 to 37.0, 127.0 to 37.02)) }
    }

    @Test fun holesOutsideCrossingAndTouchingExteriorAreRejected() {
        val exterior = ring(127.0 to 37.0, 127.03 to 37.0, 127.03 to 37.03, 127.0 to 37.03)
        rejects { lookup(RegionPolygon(exterior, listOf(ring(127.04 to 37.01, 127.05 to 37.01, 127.045 to 37.02)))) }
        rejects { lookup(RegionPolygon(exterior, listOf(ring(127.02 to 37.01, 127.04 to 37.01, 127.025 to 37.02)))) }
        rejects { lookup(RegionPolygon(exterior, listOf(ring(127.0 to 37.01, 127.01 to 37.01, 127.005 to 37.02)))) }
    }

    @Test fun nestedIntersectingAndTouchingHolesAreRejected() {
        val exterior = ring(127.0 to 37.0, 127.05 to 37.0, 127.05 to 37.05, 127.0 to 37.05)
        val hole = ring(127.01 to 37.01, 127.03 to 37.01, 127.03 to 37.03, 127.01 to 37.03)
        rejects { lookup(RegionPolygon(exterior, listOf(hole, ring(127.015 to 37.015, 127.025 to 37.015, 127.02 to 37.025)))) }
        rejects { lookup(RegionPolygon(exterior, listOf(hole, ring(127.02 to 37.02, 127.04 to 37.02, 127.03 to 37.04)))) }
        rejects { lookup(RegionPolygon(exterior, listOf(hole, ring(127.03 to 37.02, 127.04 to 37.02, 127.035 to 37.03)))) }
    }

    @Test fun overlappingNestedAndTouchingMultipartPolygonsAreRejected() {
        val first = polygon(127.0 to 37.0, 127.03 to 37.0, 127.03 to 37.03, 127.0 to 37.03)
        rejects { lookup(first, polygon(127.02 to 37.01, 127.04 to 37.01, 127.025 to 37.02)) }
        rejects { lookup(first, polygon(127.01 to 37.01, 127.02 to 37.01, 127.015 to 37.02)) }
        rejects { lookup(first, polygon(127.03 to 37.01, 127.04 to 37.01, 127.035 to 37.02)) }
    }

    @Test fun invalidOrUnsupportedCoordinatesDoNotBecomeMatches() {
        val boundary = lookup(polygon(127.0 to 37.0, 127.01 to 37.0, 127.005 to 37.01))
        for ((latitude, longitude) in listOf(Double.NaN to 127.0, 37.0 to Double.POSITIVE_INFINITY,
            29.0 to 127.0, 46.0 to 127.0, 37.0 to 119.0, 37.0 to 136.0, 127.0 to 37.0)) {
            assertTrue(boundary.containing(latitude, longitude).isEmpty())
            rejects { lookup(polygon(longitude to latitude, 127.01 to 37.0, 127.005 to 37.01)) }
        }
    }

    @Test fun identitiesAreRequiredUniqueAndBounded() {
        val shape = polygon(127.0 to 37.0, 127.01 to 37.0, 127.005 to 37.01)
        for (identity in listOf("", " ", " padded", "control\n", "x".repeat(257))) {
            rejects { ReviewedRegionGeometry(identity, listOf(shape)) }
            rejects { ReviewedPolygonBoundaryLookup(identity, listOf(ReviewedRegionGeometry("valid", listOf(shape)))) }
        }
        val region = ReviewedRegionGeometry("same", listOf(shape))
        rejects { ReviewedPolygonBoundaryLookup("synthetic-dataset", listOf(region, region)) }
        rejects { ReviewedPolygonBoundaryLookup("synthetic-dataset", emptyList()) }
        rejects { ReviewedRegionGeometry("empty", emptyList()) }
    }

    @Test fun geometryCollectionsAreDeeplySnapshottedAndCannotBeMutatedThroughGetters() {
        val exterior = ring(127.0 to 37.0, 127.04 to 37.0, 127.04 to 37.04, 127.0 to 37.04).toMutableList()
        val hole = ring(127.01 to 37.01, 127.03 to 37.01, 127.03 to 37.03, 127.01 to 37.03).toMutableList()
        val holes = mutableListOf<List<BoundaryCoordinate>>(hole)
        val shape = RegionPolygon(exterior, holes)
        val polygons = mutableListOf(shape)
        val region = ReviewedRegionGeometry("immutable", polygons)
        val regions = mutableListOf(region)
        val boundary = ReviewedPolygonBoundaryLookup("synthetic-dataset", regions)
        exterior.clear(); hole.clear(); holes.clear(); polygons.clear(); regions.clear()
        assertEquals(1, boundary.containing(37.005, 127.005).size)
        assertTrue(boundary.containing(37.02, 127.02).isEmpty())
        cannotMutate { (shape.exterior as MutableList<*>).clear() }
        cannotMutate { (shape.holes as MutableList<*>).clear() }
        cannotMutate { (shape.holes.single() as MutableList<*>).clear() }
        cannotMutate { (region.polygons as MutableList<*>).clear() }
        cannotMutate { (boundary.containing(37.005, 127.005) as MutableList<*>).clear() }
    }

    @Test fun diagnosticStringsDoNotContainCoordinatesOrRegionIdentities() {
        val coordinate = point(127.123456, 37.234567)
        val shape = polygon(127.0 to 37.0, 127.01 to 37.0, 127.005 to 37.01)
        val region = ReviewedRegionGeometry("private-revision", listOf(shape))
        val boundary = ReviewedPolygonBoundaryLookup("private-dataset", listOf(region))
        for (value in listOf(coordinate, shape, region, boundary)) {
            assertTrue(value.toString().contains("[redacted]"))
            assertFalse(value.toString().contains("127."))
            assertFalse(value.toString().contains("private"))
        }
    }

    @Test fun sizeLimitsRejectOversizedRingRegionAndDatasetBeforeTopologyWork() {
        val tiny = polygon(127.0 to 37.0, 127.01 to 37.0, 127.005 to 37.01)
        rejects { RegionPolygon(List(ReviewedPolygonBoundaryLookup.MAX_RING_COORDINATES + 1) { point(127.0, 37.0) }) }
        rejects { RegionPolygon(tiny.exterior, List(ReviewedPolygonBoundaryLookup.MAX_RINGS_PER_POLYGON) { tiny.exterior }) }
        rejects { ReviewedRegionGeometry("too-many-polygons", List(ReviewedPolygonBoundaryLookup.MAX_POLYGONS_PER_REGION + 1) { tiny }) }
        rejects { ReviewedPolygonBoundaryLookup("too-many-regions", List(ReviewedPolygonBoundaryLookup.MAX_REGIONS + 1) {
            ReviewedRegionGeometry("r$it", listOf(tiny))
        }) }
        val ring = (0 until 2_000).map {
            val radians = it * 2 * Math.PI / 2_000
            point(127.0 + 0.01 * cos(radians), 37.0 + 0.01 * sin(radians))
        }.let { it + it.first() }
        val larger = RegionPolygon(ring)
        rejects { ReviewedRegionGeometry("too-many-coordinates", List(50) { larger }) }
        val region = ReviewedRegionGeometry("bounded", List(25) { larger })
        rejects { ReviewedPolygonBoundaryLookup("too-many-coordinates", listOf(region, ReviewedRegionGeometry("bounded-2", region.polygons))) }
    }

    @Test fun pathologicalMultipartSweepStopsAtComparisonBudget() {
        // Many disjoint narrow islands have identical x intervals. The budget must fail closed
        // instead of doing unchecked quadratic work; no malformed geometry is silently repaired.
        val islands = (0 until ReviewedPolygonBoundaryLookup.MAX_POLYGONS_PER_REGION).map { index ->
            val latitude = 35.0 + index * 0.001
            polygon(127.0 to latitude, 127.01 to latitude, 127.005 to latitude + 0.0003)
        }
        val error = rejects { lookup(*islands.toTypedArray()) }
        assertEquals("Boundary validation complexity exceeds limit", error.message)
    }

    private fun lookup(vararg polygons: RegionPolygon): ReviewedPolygonBoundaryLookup =
        ReviewedPolygonBoundaryLookup("synthetic-dataset", listOf(ReviewedRegionGeometry("synthetic-region", polygons.toList())))

    private fun polygon(vararg coordinates: Pair<Double, Double>): RegionPolygon = RegionPolygon(ring(*coordinates))
    private fun ring(vararg coordinates: Pair<Double, Double>): List<BoundaryCoordinate> =
        coordinates.map { point(it.first, it.second) }.let { it + it.first() }
    private fun point(longitude: Double, latitude: Double): BoundaryCoordinate = BoundaryCoordinate(longitude, latitude)

    private fun rejects(action: () -> Unit): IllegalArgumentException {
        try { action() } catch (error: IllegalArgumentException) { return error }
        throw AssertionError("Expected invalid boundary geometry to be rejected")
    }

    private fun cannotMutate(action: () -> Unit) {
        try { action() } catch (_: UnsupportedOperationException) { return }
        throw AssertionError("Expected immutable geometry snapshot")
    }
}
