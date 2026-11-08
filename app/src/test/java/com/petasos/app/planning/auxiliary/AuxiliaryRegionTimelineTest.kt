package com.petasos.app.planning.auxiliary

import com.petasos.app.location.BoundaryCoordinate
import com.petasos.app.location.PolylineRegion
import com.petasos.app.location.RegionPolylineLookup
import org.junit.Assert.*
import org.junit.Test

/** Guide-relative timing only; these tests supply neither a current position nor a live clock. */
class AuxiliaryRegionTimelineTest {
    @Test fun mergesOnlyAdjacentWholeGuideRegionsAndRetainsExactProviderTimes() {
        val lookups = mutableListOf<List<BoundaryCoordinate>>()
        val route = route(listOf(10L, 20L, 40L))
        val timeline = projector { path -> lookups += path; if (path.first() == route.path[2]) B else A }.project(route)!!
        assertEquals(listOf(0L to 30L, 30L to 70L), timeline.windows.map { it.startsAtOffsetMs to it.endsAtOffsetMs })
        assertEquals(listOf(A, B), timeline.windows.map { it.region })
        assertEquals(0 to 1, timeline.windows[0].startGuideIndex to timeline.windows[0].endGuideIndex)
        assertEquals(2 to 2, timeline.windows[1].startGuideIndex to timeline.windows[1].endGuideIndex)
        assertEquals(3, lookups.size)
        assertEquals(0, timeline.unknownGuideCount)
        assertEquals(3, timeline.evaluatedGuideCount)
        assertFalse(timeline.geometryBudgetExhausted)
        assertEquals(KEY, timeline.routeKey)
        assertEquals(EVIDENCE, timeline.geometryEvidenceKey)
    }

    @Test fun unknownCrossingIntervalIsNotRedistributedAndPreventsSameRegionVisitMerge() {
        val route = route(listOf(100L, 900L, 200L))
        val timeline = projector { if (it.first() == route.path[1]) null else A }.project(route)!!
        assertEquals(listOf(0L to 100L, 1000L to 1200L), timeline.windows.map { it.startsAtOffsetMs to it.endsAtOffsetMs })
        assertEquals(1, timeline.unknownGuideCount)
        assertNotEquals(timeline.windows[0].visitId, timeline.windows[1].visitId)
        assertEquals(1200L, timeline.totalDurationMs)
    }

    @Test fun returningToRegionAfterDifferentRegionIsAnotherStableVisit() {
        val route = route(listOf(100L, 200L, 300L))
        val projector = projector { if (it.first() == route.path[1]) B else A }
        val first = projector.project(route)!!
        val second = projector.project(route)!!
        assertEquals(listOf(A, B, A), first.windows.map { it.region })
        assertEquals(3, first.windows.map { it.visitId }.distinct().size)
        assertEquals(first.windows, second.windows)
    }

    @Test fun wholeGuidePathIsPassedIncludingIntermediateVertices() {
        val points = points(4)
        val route = AuxiliaryRouteGeometry(KEY, points, listOf(AuxiliaryGuideInterval(0, 3, 500, 50)), 500)
        var observed: List<BoundaryCoordinate>? = null
        val timeline = projector { observed = it; null }.project(route)!!
        assertEquals(points, observed)
        assertTrue(timeline.windows.isEmpty())
        assertEquals(1, timeline.unknownGuideCount)
    }

    @Test fun incompleteOverlappingSkippedReversedCoverageAndWrongTotalNeverReachGeometry() {
        var calls = 0
        val projector = projector { calls++; A }
        val points = points(4)
        val cases = listOf(
            emptyList(),
            listOf(AuxiliaryGuideInterval(0, 2, 100, 10)), // Unknown tail.
            listOf(AuxiliaryGuideInterval(1, 3, 100, 10)), // Missing origin span.
            listOf(AuxiliaryGuideInterval(0, 2, 50, 10), AuxiliaryGuideInterval(1, 3, 50, 10)),
            listOf(AuxiliaryGuideInterval(0, 1, 50, 10), AuxiliaryGuideInterval(2, 3, 50, 10)),
            listOf(AuxiliaryGuideInterval(0, 3, 101, 10)),
            listOf(AuxiliaryGuideInterval(0, 3, 99, 10)),
            listOf(AuxiliaryGuideInterval(0, 4, 100, 10)),
        )
        cases.forEach { assertNull(projector.project(AuxiliaryRouteGeometry(KEY, points, it, 100))) }
        assertEquals(0, calls)
        rejects { AuxiliaryGuideInterval(2, 1, 100, 10) }
    }

    @Test fun zeroTimeTravelTimedStationaryAndUnexplainedDistanceAreRejected() {
        val points = points(2)
        val projector = projector { A }
        assertNull(projector.project(AuxiliaryRouteGeometry(KEY, points, listOf(AuxiliaryGuideInterval(0, 1, 0, 10)), 0)))
        assertNull(projector.project(AuxiliaryRouteGeometry(KEY, points, listOf(
            AuxiliaryGuideInterval(0, 0, 20, 0), AuxiliaryGuideInterval(0, 1, 80, 10)), 100)))
        assertNull(projector.project(AuxiliaryRouteGeometry(KEY, points, listOf(
            AuxiliaryGuideInterval(0, 0, 0, 1), AuxiliaryGuideInterval(0, 1, 100, 10)), 100)))
        assertNull(projector.project(AuxiliaryRouteGeometry(KEY, listOf(points[0], points[0]),
            listOf(AuxiliaryGuideInterval(0, 1, 100, 10)), 100)))
        val valid = projector.project(AuxiliaryRouteGeometry(KEY, points, listOf(
            AuxiliaryGuideInterval(0, 0, 0, 0), AuxiliaryGuideInterval(0, 1, 100, 10)), 100))!!
        assertEquals(0L to 100L, valid.windows.single().let { it.startsAtOffsetMs to it.endsAtOffsetMs })
        assertEquals(1, valid.windows.single().startGuideIndex)
    }

    @Test fun exactRevisionChangesAreNotMergedEvenForSameStableRegion() {
        val route = route(listOf(100L, 200L))
        val otherRevision = A.copy(regionRevisionId = "a-revision-2")
        val result = projector { if (it.first() == route.path[0]) A else otherRevision }.project(route)!!
        assertEquals(2, result.windows.size)
        assertNotEquals(result.windows[0].visitId, result.windows[1].visitId)
    }

    @Test fun evidenceMutationMixedHierarchyAndThrowingGeometryFailClosed() {
        var evidence = EVIDENCE
        val lookup = object : RegionPolylineLookup {
            override val evidenceKey get() = evidence
            override fun containing(path: List<BoundaryCoordinate>): PolylineRegion { evidence = "e".repeat(64); return A }
        }
        assertNull(AuxiliaryRegionTimelineProjector(lookup).project(route(listOf(100L))))
        val route = route(listOf(100L, 200L))
        assertNull(projector { if (it.first() == route.path[0]) A else A.copy(hierarchyKey = "f".repeat(64)) }.project(route))
        assertNull(projector { if (it.first() == route.path[0]) A else A.copy(datasetVersion = "other-dataset") }.project(route))
        assertNull(projector { throw IllegalStateException("private coordinates") }.project(route))
        assertNull(projector("unreviewed") { A }.project(route))
    }

    @Test fun evidenceAndRouteRevisionBindVisitIdentity() {
        val route = route(listOf(100L))
        val a = projector { A }.project(route)!!.windows.single()
        val b = projector("e".repeat(64)) { A }.project(route)!!.windows.single()
        val changedRoute = AuxiliaryRouteGeometry("c".repeat(64), route.path, route.guides, route.totalDurationMs)
        val c = projector { A }.project(changedRoute)!!.windows.single()
        assertNotEquals(a.visitId, b.visitId)
        assertNotEquals(a.visitId, c.visitId)
    }

    @Test fun geometryWorkBudgetLeavesUnexaminedTailUnknownWithoutExtendingLastRegion() {
        var calls = 0
        val lookup = object : RegionPolylineLookup {
            override val evidenceKey = EVIDENCE
            override fun containing(path: List<BoundaryCoordinate>): PolylineRegion { calls++; return A }
        }
        val result = AuxiliaryRegionTimelineProjector(lookup, maximumGeometryQueries = 2)
            .project(route(listOf(100L, 200L, 300L, 400L)))!!
        assertEquals(2, calls)
        assertEquals(2, result.unknownGuideCount)
        assertEquals(2, result.evaluatedGuideCount)
        assertTrue(result.geometryBudgetExhausted)
        assertEquals(0L to 300L, result.windows.single().let { it.startsAtOffsetMs to it.endsAtOffsetMs })
        assertEquals(1_000L, result.totalDurationMs)
        rejects { AuxiliaryRegionTimelineProjector(lookup, maximumGeometryQueries = 0) }
    }

    @Test fun limitsRejectOverflowAndMalformedCoordinatesWithoutRawMessages() {
        rejects { AuxiliaryGuideInterval(0, 1, Long.MAX_VALUE, 1) }
        rejects { AuxiliaryGuideInterval(0, 1, 1, Long.MAX_VALUE) }
        rejects { AuxiliaryRouteGeometry(KEY, points(2), emptyList(), Long.MAX_VALUE) }
        rejects { AuxiliaryRouteGeometry("bad", points(2), emptyList(), 1) }
        rejects { AuxiliaryRouteGeometry(KEY, listOf(BoundaryCoordinate(37.0, 127.0), BoundaryCoordinate(37.1, 127.1)), emptyList(), 1) }
        rejects { AuxiliaryRouteGeometry(KEY, listOf(BoundaryCoordinate(Double.NaN, 37.0), BoundaryCoordinate(127.1, 37.1)), emptyList(), 1) }
        rejects { AuxiliaryRouteGeometry(KEY, points(2), List(AuxiliaryRouteGeometry.MAX_GUIDES + 1) { AuxiliaryGuideInterval(0, 0, 0, 0) }, 1) }
        val maximum = AuxiliaryRouteGeometry.MAX_DURATION_MS
        assertNotNull(projector { A }.project(AuxiliaryRouteGeometry(KEY, points(2),
            listOf(AuxiliaryGuideInterval(0, 1, maximum, 1)), maximum)))
    }

    @Test fun inputAndOutputCollectionsAreImmutableSnapshotsAndCoordinatesStayRedacted() {
        val path = points(3).toMutableList()
        val guides = mutableListOf(AuxiliaryGuideInterval(0, 2, 100, 10))
        val route = AuxiliaryRouteGeometry(KEY, path, guides, 100)
        path.clear(); guides.clear()
        assertEquals(3, route.path.size)
        assertEquals(1, route.guides.size)
        val result = projector { A }.project(route)!!
        unsupported { (route.path as MutableList<*>).clear() }
        unsupported { (route.guides as MutableList<*>).clear() }
        unsupported { (result.windows as MutableList<*>).clear() }
        assertFalse(listOf(route, route.guides[0], result, result.windows[0], A).joinToString().contains("127."))
        // There is intentionally no absolute time, clock scope or active-navigation authority here.
        assertEquals(0L, result.windows.single().startsAtOffsetMs)
    }

    private fun points(count: Int) = List(count) { BoundaryCoordinate(127.0 + it * 0.001, 37.0) }
    private fun route(durations: List<Long>): AuxiliaryRouteGeometry = AuxiliaryRouteGeometry(KEY, points(durations.size + 1),
        durations.mapIndexed { i, duration -> AuxiliaryGuideInterval(i, i + 1, duration, 10) }, durations.sum())
    private fun projector(evidence: String = EVIDENCE, lookup: (List<BoundaryCoordinate>) -> PolylineRegion?) =
        AuxiliaryRegionTimelineProjector(object : RegionPolylineLookup {
            override val evidenceKey = evidence
            override fun containing(path: List<BoundaryCoordinate>) = lookup(path)
        })
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected fixed invalid input") } catch (e: IllegalArgumentException) {
            assertFalse(e.message.orEmpty().contains("127."))
        }
    }
    private fun unsupported(block: () -> Unit) {
        try { block(); fail("Expected immutable snapshot") } catch (_: UnsupportedOperationException) { }
    }
    companion object {
        private val KEY = "a".repeat(64)
        private val EVIDENCE = "b".repeat(64)
        private val HIERARCHY = "d".repeat(64)
        private val A = PolylineRegion("a", "a-revision", "dataset", HIERARCHY)
        private val B = PolylineRegion("b", "b-revision", "dataset", HIERARCHY)
    }
}
