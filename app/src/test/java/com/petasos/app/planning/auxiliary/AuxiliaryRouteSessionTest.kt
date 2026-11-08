package com.petasos.app.planning.auxiliary

import com.petasos.app.capability.location.AndroidLocationMetadata
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.capability.navigation.NavigationAuxiliaryInput
import com.petasos.app.capability.navigation.NavigationAuxiliaryInputController
import com.petasos.app.capability.navigation.NavigationRouteState
import com.petasos.app.capability.navigation.NavigationSnapshot
import com.petasos.app.location.*
import com.petasos.app.runtime.RuntimeCancellation
import com.petasos.app.runtime.RuntimeClock
import com.petasos.app.runtime.RuntimeQueue
import org.junit.Assert.*
import org.junit.Test

/** Synthetic official Android metadata and deterministic queues; no network, SDK or product DB. */
class AuxiliaryRouteSessionTest {
    @Test fun disabledMissingMismatchedAndUnusableInputsNeverSubmit() {
        val h = Harness()
        h.update(enabled = false)
        h.update(point = null)
        h.update(navigation = null)
        h.update(revision = null)
        h.update(revision = 2)
        h.update(sessionId = "")
        val point = h.point
        listOf(
            point.copy(latitude = null), point.copy(longitude = Double.NaN),
            point.copy(longitude = -122.0), point.copy(latitude = 60.0),
            point.copy(quality = LocationQuality.UNKNOWN), point.copy(accuracyMeters = 101.0),
            point.copy(adapterVersion = "unverified"), point.copy(measurementAgeAtReceiptMs = null),
            point.copy(clockScopeId = "other-clock"), point.copy(routeRevision = 2),
            point.copy(receivedAtElapsedMs = h.now - 10_001),
        ).forEach { h.update(point = it) }
        h.allowed = false
        h.update()
        assertTrue(h.calls.isEmpty())
        assertNull(h.forecast())
        h.session.close()
    }

    @Test fun navigationInvalidationAndLateResponseCannotPublishAnAnchor() {
        val h = Harness()
        h.update()
        val call = h.calls.single()
        assertTrue(call.current())
        h.navigationController.invalidate()
        assertFalse(call.current())
        h.update(navigation = null)
        assertTrue(call.cancelled)
        call.complete(success())
        h.drainProjection()
        assertNull(h.forecast())
        assertEquals(0, h.geometryQueries)
        h.session.close()
    }

    @Test fun consentRevocationCancelsInflightAndDoesNotReviveAfterConsentReturns() {
        val h = Harness()
        h.update()
        val call = h.calls.single()
        h.allowed = false
        h.session.revoke()
        assertTrue(call.cancelled)
        assertFalse(call.current())
        h.allowed = true
        call.complete(success())
        h.drainProjection()
        assertNull(h.forecast())
        assertFalse(call.current())
        h.session.close()
    }

    @Test fun validCurrentResponseUsesLatestCommittedPointAndIndependentObservationPermit() {
        val h = Harness()
        var originPermit = true
        h.update(authority = { originPermit })
        h.now += 100
        h.point = point(h.now, "new-point", longitude = 127.0003)
        originPermit = false
        h.update(authority = { true })
        // A superseded origin fix does not invalidate an otherwise current road response.
        assertTrue(h.calls.single().current())
        h.calls.single().complete(success())
        h.drainProjection()
        val forecast = h.forecast()!!
        assertEquals(h.now, forecast.observedAtElapsedMs)
        assertEquals("session", forecast.sessionId)
        assertEquals(1L, forecast.routeRevision)
        assertTrue(h.session.isCurrent(forecast))
        assertEquals(1, h.calls.size)
        h.session.close()
    }

    @Test fun wrongOwnerClockRevisionExpiryAndMissingInputCannotUseCachedForecast() {
        val h = Harness()
        h.establish()
        assertNotNull(h.forecast())
        assertNull(h.session.forecast("other-session", "clock", 1, h.now))
        assertNull(h.session.forecast("session", "other-clock", 1, h.now))
        assertNull(h.session.forecast("session", "clock", 2, h.now))
        assertNull(h.session.forecast("session", "clock", null, h.now))
        h.now += AuxiliaryRouteSession.SOURCE_FRESHNESS_MS
        assertNull(h.forecast())
        h.update(point = null)
        assertNull(h.forecast())
        h.session.close()
    }

    @Test fun pendingProjectionCannotUseWithdrawnPointAndOnlyLatestQueuedPointIsApplied() {
        val h = Harness()
        h.update()
        h.calls.single().complete(success())
        h.projectOne() // Builds the timeline, then schedules the initial point.
        var oldPermit = true
        h.now += 10
        h.point = point(h.now, "point-old")
        h.update(authority = { oldPermit })
        oldPermit = false
        h.drainProjection()
        assertNull(h.forecast())
        h.now += 10
        h.point = point(h.now, "point-new")
        h.update()
        h.drainProjection()
        assertEquals(h.now, h.forecast()!!.observedAtElapsedMs)
        assertTrue(h.droppedProjectionTasks >= 1)
        assertTrue(h.maximumPendingProjection <= 1)
        h.session.close()
    }

    @Test fun samePointRefreshDoesNotRecursivelyScheduleProjectionOrFetchAgain() {
        val h = Harness()
        h.onChanged = { h.update() }
        h.establish()
        val changes = h.changedCount
        val geometryQueries = h.geometryQueries
        repeat(20) { h.update(); h.drainProjection() }
        assertEquals(1, h.calls.size)
        assertEquals(changes, h.changedCount)
        assertEquals(geometryQueries, h.geometryQueries)
        assertEquals(0, h.pendingProjection)
        h.session.close()
    }

    @Test fun evidenceWithdrawalInvalidatesPreviouslyDeliveredForecastPermanently() {
        val h = Harness()
        h.establish()
        val forecast = h.forecast()!!
        h.evidenceAllowed = false
        assertNull(h.forecast())
        assertFalse(h.session.isCurrent(forecast))
        h.evidenceAllowed = true
        assertNull(h.forecast())
        assertFalse(h.session.isCurrent(forecast))
        h.session.close()
    }

    @Test fun failureAndUnusableTimingRespectRetryIntervalWithoutAutomaticFetch() {
        val h = Harness()
        h.update()
        h.calls.single().complete(AuxiliaryRouteOutcome.Failure(AuxiliaryRouteFailureKind.UNAVAILABLE))
        repeat(5) { h.update() }
        assertEquals(1, h.calls.size)
        h.now += AuxiliaryRouteSession.RETRY_INTERVAL_MS - 1
        h.point = point(h.now, "point-before-retry")
        h.update()
        assertEquals(1, h.calls.size)
        h.now++
        h.point = point(h.now, "point-retry")
        h.update()
        assertEquals(2, h.calls.size)
        h.calls.last().complete(success(timingUsable = false))
        h.drainProjection()
        assertNull(h.forecast())
        assertEquals(0, h.geometryQueries)
        h.session.close()
    }

    @Test fun closeCancelsPendingWorkAndLateCallbacksNeverReopenTheSession() {
        val h = Harness()
        h.update()
        val call = h.calls.single()
        call.complete(success())
        h.session.close()
        assertTrue(call.cancelled)
        assertTrue(h.projectionClosed)
        h.drainProjection()
        call.complete(success())
        h.update()
        assertNull(h.forecast())
        assertEquals(1, h.calls.size)
        assertEquals(0, h.geometryQueries)
    }

    @Test fun offPathWithdrawsImmediatelyAndRefreshesFromANewOriginAfterTheMinimumInterval() {
        val h = Harness()
        h.establish()
        val prior = h.forecast()!!
        h.now += 100
        h.point = point(h.now, "off-path", longitude = 128.0)
        h.update(); h.drainProjection()
        assertNull(h.forecast())
        assertFalse(h.session.isCurrent(prior))
        assertEquals(1, h.calls.size)
        h.now += AuxiliaryRouteSession.RETRY_INTERVAL_MS
        h.point = point(h.now, "off-path-new-origin", longitude = 128.0)
        h.update()
        assertEquals(2, h.calls.size)
        assertTrue(h.calls.first().cancelled)
        assertTrue(h.calls.last().current())
        h.session.close()
    }

    private class Call(val current: () -> Boolean, val complete: (AuxiliaryRouteOutcome) -> Unit) {
        var cancelled = false
    }

    private class Harness {
        var now = 100_000L
        var point = point(now)
        var allowed = true
        var evidenceAllowed = true
        var geometryQueries = 0
        var changedCount = 0
        var onChanged: (() -> Unit)? = null
        var projectionClosed = false
        var droppedProjectionTasks = 0
        var maximumPendingProjection = 0
        private val projections = ArrayDeque<() -> Unit>()
        val pendingProjection get() = projections.size
        val calls = mutableListOf<Call>()
        private val navigationSnapshot = NavigationSnapshot(1, 1, true, false, NavigationRouteState.ACTIVE)
        val navigationController = NavigationAuxiliaryInputController(navigationSnapshot, { true }, {})
        val navigation: NavigationAuxiliaryInput
        init {
            val token = navigationController.beginRead()!!
            navigationController.destination(token, BoundaryCoordinate(127.003, 37.0), navigationSnapshot)
            navigationController.waypoints(token, emptyList())
            navigation = navigationController.input()!!
        }
        private val owner = object : RuntimeQueue {
            override fun execute(task: () -> Unit) = task()
            override fun schedule(delayMs: Long, task: () -> Unit): RuntimeCancellation = error("Unexpected timer")
        }
        private val lookup = object : RegionPolylineLookup {
            override val evidenceKey = "b".repeat(64)
            override fun containing(path: List<BoundaryCoordinate>): PolylineRegion {
                geometryQueries++
                return PolylineRegion("region-a", "region-a-v1", "dataset", "c".repeat(64))
            }
        }
        val session = AuxiliaryRouteSession(
            AuxiliaryRouteLookup { _, current, complete ->
                val call = Call(current, complete)
                calls += call
                RuntimeCancellation { call.cancelled = true }
            }, { lookup }, { evidenceAllowed }, owner, RuntimeClock { now },
            { changedCount++; onChanged?.invoke() },
            { task ->
                // Deterministic model of the production single pending slot and discard-oldest policy.
                if (projections.size == 1) { projections.removeFirst(); droppedProjectionTasks++ }
                projections.addLast(task)
                maximumPendingProjection = maxOf(maximumPendingProjection, projections.size)
            }, { projectionClosed = true },
        )
        fun update(point: RecordedLocation? = this.point, navigation: NavigationAuxiliaryInput? = this.navigation,
                   revision: Long? = 1, enabled: Boolean = true, sessionId: String = "session",
                   authority: () -> Boolean = { true }) = session.update(sessionId, "clock", revision,
            navigation, point, authority, enabled, { allowed })
        fun forecast() = session.forecast("session", "clock", 1, now)
        fun projectOne() { projections.removeFirst().invoke() }
        fun drainProjection() {
            var completed = 0
            while (projections.isNotEmpty()) {
                check(++completed <= 20) { "Projection callback loop" }
                projectOne()
            }
        }
        fun establish() { update(); calls.last().complete(success()); drainProjection() }
    }

    companion object {
        private fun point(now: Long, id: String = "point-initial", longitude: Double = 127.0002): RecordedLocation {
            val metadata = AndroidLocationMetadata("gps", now * 1_000_000, now * 1_000_000, 1.0, false, null)
            val sample = LocationSample(37.0, longitude, Double.NaN, Float.NaN, Float.NaN,
                now, now, now, metadata)
            return LocationNormalizer(AndroidLocationMeaningAdapter(100.0)).normalize(sample,
                LocationReadingKind.UPDATE, id, "synthetic", "clock", routeRevision = 1)
        }
        private fun success(timingUsable: Boolean = true): AuxiliaryRouteOutcome.Success {
            val path = List(4) { BoundaryCoordinate(127.0 + it * 0.001, 37.0) }
            val route = AuxiliaryRouteGeometry("a".repeat(64), path,
                List(3) { AuxiliaryGuideInterval(it, it + 1, 1_000, 100) }, 3_000)
            return AuxiliaryRouteOutcome.Success(AuxiliaryRouteResponse(route, 300, timingUsable))
        }
    }
}
