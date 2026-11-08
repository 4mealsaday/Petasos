package com.petasos.app.planning.auxiliary

import com.petasos.app.capability.location.AndroidLocationMetadata
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.location.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic Android metadata and route geometry; no device, server request or collection evidence. */
class AuxiliaryRouteAnchorTest {
    @Test fun wholeGuideUncertaintyShrinksFutureWindowWithoutDistanceInterpolation() {
        val fixture = fixture()
        val first = fixture.anchor.update(point(longitude = 127.0005), { true }, 10_000)!!
        assertEquals(11_000L to 30_000L, first.passages.single().let { it.entersAtElapsedMs to it.exitsAtElapsedMs })
        assertEquals("b", first.passages.single().regionId)
        assertEquals(10_000L, first.observedAtElapsedMs)
        assertEquals(20_000L, first.validUntilElapsedMs)
        val otherPositionInSameGuide = fixture().anchor.update(point(longitude = 127.0015), { true }, 10_000)!!
        assertEquals(first.passages, otherPositionInSameGuide.passages)
    }

    @Test fun measurementAgeAndTimestampUncertaintyArePreservedRatherThanReceiptAnchoring() {
        val forecast = fixture().anchor.update(point(measured = 10_000, received = 11_000, uncertainty = 200_000_000.0),
            { true }, 11_000)!!
        assertEquals(11_200L to 29_800L, forecast.passages.single().let { it.entersAtElapsedMs to it.exitsAtElapsedMs })
        assertEquals(10_000L, forecast.observedAtElapsedMs)
        assertEquals(19_800L, forecast.validUntilElapsedMs)
    }

    @Test fun sourceExpiryAndExactPointFreshnessBoundCheapReadAndNeverRenewCachedFix() {
        val anchor = fixture(sourceUntil = 15_000).anchor
        val original = point()
        val forecast = anchor.update(original, { true }, 10_000)!!
        assertEquals(15_000L, forecast.validUntilElapsedMs)
        assertSame(forecast, anchor.current(CONTEXT, 14_999))
        assertNull(anchor.current(CONTEXT, 15_000))
        assertNull(anchor.update(point(id = "new", measured = 15_000, received = 15_000), { true }, 15_000))
        val cachedAnchor = fixture().anchor
        assertNotNull(cachedAnchor.update(original, { true }, 10_000))
        assertNull(cachedAnchor.update(point(id = "cached", measured = 10_000, received = 11_000), { true }, 11_000))
        assertNull(cachedAnchor.current(CONTEXT, 11_000))
        assertNull(cachedAnchor.update(original, { true }, 11_000))
        assertNotNull(cachedAnchor.update(point(id = "fresh", measured = 12_000, received = 12_000), { true }, 12_000))
    }

    @Test fun sourceAndRequestIdentityChangesCannotReuseVisitOrOldClock() {
        val original = fixture().anchor.update(point(), { true }, 10_000)!!.passages.single()
        val changed = fixture(context = CONTEXT.copy(requestGeneration = 2)).anchor.update(point(), { true }, 10_000)!!.passages.single()
        assertNotEquals(original.visitId, changed.visitId)
        val anchor = fixture().anchor
        assertNotNull(anchor.update(point(), { true }, 10_000))
        assertNull(anchor.current(CONTEXT.copy(clockScopeId = "new-clock"), 10_001))
        assertNull(anchor.current(CONTEXT, 10_002))
        assertNull(fixture().anchor.update(point().copy(clockScopeId = "other"), { true }, 10_000))
        assertNull(fixture().anchor.update(point().copy(routeRevision = 2), { true }, 10_000))
    }

    @Test fun authorityWithdrawnDuringProjectionOrLaterCannotPublishOrReviveOldPoint() {
        val anchor = fixture().anchor
        var allowed = true
        val original = point()
        assertNotNull(anchor.update(original, { allowed }, 10_000))
        allowed = false
        assertNull(anchor.current(CONTEXT, 10_001))
        allowed = true
        assertNull(anchor.current(CONTEXT, 10_002))
        assertNull(anchor.update(original, { allowed }, 10_002))
        assertNotNull(anchor.update(point(id = "fresh", measured = 11_000, received = 11_000), { allowed }, 11_000))
        var calls = 0
        val raced = fixture().anchor
        assertNull(raced.update(point(), { ++calls == 1 }, 10_000))
        assertNull(raced.current(CONTEXT, 10_001))
        calls = 0
        val offPathRace = fixture().anchor
        assertNull(offPathRace.update(point(latitude = 37.01), { ++calls == 1 }, 10_000))
        assertFalse(offPathRace.needsNewRoute)
    }

    @Test fun offPathAndDefiniteReverseProgressRequestNewRouteAndStayWithdrawn() {
        val offPath = fixture().anchor
        assertNull(offPath.update(point(latitude = 37.01), { true }, 10_000))
        assertTrue(offPath.needsNewRoute)
        assertNull(offPath.update(point(id = "return", measured = 11_000, received = 11_000), { true }, 11_000))
        val reverse = fixture().anchor
        assertNotNull(reverse.update(point(longitude = 127.0015), { true }, 10_000))
        assertNull(reverse.update(point(id = "reverse", longitude = 127.0005, measured = 11_000, received = 11_000), { true }, 11_000))
        assertTrue(reverse.needsNewRoute)
        assertNull(reverse.current(CONTEXT, 11_000))
    }

    @Test fun accuracyOverlapJitterDoesNotPretendToProveReverseTravel() {
        val anchor = fixture().anchor
        assertNotNull(anchor.update(point(longitude = 127.001), { true }, 10_000))
        assertNotNull(anchor.update(point(id = "jitter", longitude = 127.00099, measured = 11_000, received = 11_000), { true }, 11_000))
        assertFalse(anchor.needsNewRoute)
    }

    @Test fun invalidQualityOrTamperedAndroidEvidenceWithholdsWithoutRequestingRoute() {
        val original = point()
        val cases = listOf(
            original.copy(adapterVersion = "pleos-unknown"), original.copy(quality = LocationQuality.STALE),
            original.copy(accuracyMeters = null), original.copy(accuracyMeters = 1.0),
            original.copy(measurementAgeAtReceiptMs = 1), original.copy(provider = "another-provider"),
            original.copy(providerConfidenceJson = "{}"), original.copy(sourceTimeUnit = "unknown"),
            point(mock = true), point(accuracy = 101.0), point(measured = 0, received = 11_000),
        )
        cases.forEach {
            val anchor = fixture().anchor
            assertNull(anchor.update(it, { true }, maxOf(10_000, it.receivedAtElapsedMs)))
            assertFalse(anchor.needsNewRoute)
        }
    }

    @Test fun ambiguousParallelOrLoopSegmentsNeverChooseNearestPhase() {
        val path = listOf(BoundaryCoordinate(127.0, 37.0), BoundaryCoordinate(127.01, 37.0),
            BoundaryCoordinate(127.01, 37.00005), BoundaryCoordinate(127.0, 37.00005))
        val route = geometry(path, listOf(1_000L, 1_000L, 20_000L))
        val anchor = anchor(route, timeline(route))
        assertNull(anchor.update(point(longitude = 127.005, latitude = 37.00001, accuracy = 10.0), { true }, 10_000))
        assertFalse(anchor.needsNewRoute)
        assertNull(anchor.current(CONTEXT, 10_001))
        val loop = path + BoundaryCoordinate(127.0, 37.0)
        val loopRoute = geometry(loop, listOf(1_000L, 1_000L, 20_000L, 1_000L))
        assertNull(anchor(loopRoute, timeline(loopRoute)).update(point(longitude = 127.00001, latitude = 37.0, accuracy = 10.0), { true }, 10_000))
    }

    @Test fun adjacentGuidesSharePhaseUncertaintyAndSmallWindowMayBecomeEmpty() {
        val fixture = fixture()
        val atBoundary = fixture.anchor.update(point(longitude = 127.002), { true }, 10_000)!!
        // At this guide boundary either adjoining guide may contain the measured position.
        assertEquals(11_000L to 20_000L, atBoundary.passages.single().let { it.entersAtElapsedMs to it.exitsAtElapsedMs })
        val route = geometry(listOf(BoundaryCoordinate(127.0, 37.0), BoundaryCoordinate(127.002, 37.0)), listOf(1_000))
        val noWindow = anchor(route, timeline(route))
        assertNull(noWindow.update(point(), { true }, 10_000))
        assertFalse(noWindow.needsNewRoute)
        // Even without an emitted window, observed progress is retained for later reverse checks.
        assertNull(noWindow.update(point(id = "reverse", longitude = 127.0001, measured = 11_000, received = 11_000), { true }, 11_000))
        assertTrue(noWindow.needsNewRoute)
    }

    @Test fun explicitInvalidationClockRegressionAndOverflowNeverYieldForecast() {
        val anchor = fixture().anchor
        assertNotNull(anchor.update(point(), { true }, 10_000))
        assertNull(anchor.update(point(id = "old", measured = 9_999, received = 9_999), { true }, 9_999))
        assertNull(anchor.current(CONTEXT, 10_001))
        val explicit = fixture().anchor
        explicit.invalidate()
        assertNull(explicit.update(point(), { true }, 10_000))
        assertNull(fixture().anchor.update(point().copy(receivedAtElapsedMs = Long.MAX_VALUE), { true }, Long.MAX_VALUE))
        assertFalse(anchor.toString().contains("127."))
    }

    @Test fun mismatchedTimelineAndRouteIdentityAreRejectedBeforeObservations() {
        val route = fixture().geometry
        val other = AuxiliaryRouteGeometry("f".repeat(64), route.path, route.guides, route.totalDurationMs)
        rejects { anchor(other, timeline(route)) }
        rejects { AuxiliaryRouteAnchor(CONTEXT, route, timeline(route), 10_000, 10_000) }
        rejects { AuxiliaryRouteAnchor(CONTEXT, route, timeline(route), 0, 60_000, maximumAccuracyMeters = Double.NaN) }
    }

    private data class Fixture(val geometry: AuxiliaryRouteGeometry, val anchor: AuxiliaryRouteAnchor)
    private fun fixture(context: AuxiliaryRouteAnchorContext = CONTEXT, sourceUntil: Long = 60_000): Fixture {
        val route = geometry(List(4) { BoundaryCoordinate(127.0 + it * 0.002, 37.0) }, listOf(1_000, 10_000, 10_000))
        return Fixture(route, AuxiliaryRouteAnchor(context, route, timeline(route), 0, sourceUntil))
    }
    private fun geometry(points: List<BoundaryCoordinate>, durations: List<Long>) = AuxiliaryRouteGeometry(KEY, points,
        durations.mapIndexed { i, duration -> AuxiliaryGuideInterval(i, i + 1, duration, 100) }, durations.sum())
    private fun timeline(route: AuxiliaryRouteGeometry): AuxiliaryRegionTimeline = AuxiliaryRegionTimelineProjector(object : RegionPolylineLookup {
        override val evidenceKey = EVIDENCE
        override fun containing(path: List<BoundaryCoordinate>) = if (path.first() == route.path.first()) A else B
    }).project(route)!!
    private fun anchor(route: AuxiliaryRouteGeometry, timeline: AuxiliaryRegionTimeline) =
        AuxiliaryRouteAnchor(CONTEXT, route, timeline, 0, 60_000)
    private fun point(id: String = "point", longitude: Double = 127.001, latitude: Double = 37.0,
                      measured: Long = 10_000, received: Long = measured, uncertainty: Double? = null,
                      accuracy: Double = 5.0, mock: Boolean = false): RecordedLocation {
        val metadata = AndroidLocationMetadata("gps", measured * 1_000_000, received * 1_000_000, accuracy, mock, uncertainty)
        return LocationNormalizer(AndroidLocationMeaningAdapter(100.0)).normalize(LocationSample(latitude, longitude,
            0.0, 0f, 0f, 1_000_000 + measured, 1_000_000 + received, received, metadata), LocationReadingKind.UPDATE,
            id, "android", "clock", routeRevision = 1)
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } }
    companion object {
        private val KEY = "a".repeat(64)
        private val EVIDENCE = "b".repeat(64)
        private val HIERARCHY = "c".repeat(64)
        private val CONTEXT = AuxiliaryRouteAnchorContext("session", "clock", 1, 1)
        private val A = PolylineRegion("a", "a-r1", "dataset", HIERARCHY)
        private val B = PolylineRegion("b", "b-r1", "dataset", HIERARCHY)
    }
}
