package com.petasos.app.planning

import com.petasos.app.location.LandmarkApproach
import com.petasos.app.location.LandmarkApproachState
import com.petasos.app.location.LandmarkDirectionRule
import com.petasos.app.location.LandmarkPassedRule
import com.petasos.app.location.LandmarkPolicy
import com.petasos.app.location.LocationFreshness
import com.petasos.app.location.LocationQuality
import com.petasos.app.location.RecordedLocation
import com.petasos.app.playback.PreparedStory
import org.junit.Assert.*
import org.junit.Test

/** Synthetic equatorial positions test observation boundaries, not road/POI access or field accuracy. */
class LandmarkMovementEvidenceTest {
    @Test fun fiveHertzApproachAccumulatesActualObservationsWhileEveryAdjacentPairOverlaps() {
        val h = Harness()
        var first: LandmarkOffer? = null
        var firstIndex: Int? = null
        var previous: RecordedLocation? = null
        repeat(51) { index ->
            val point = point(400.0 - index, START + index * 200L)
            assertEquals(LandmarkApproachState.HOLDING, assess(previous, point).state)
            val offer = h.update(point)
            if (offer != null) {
                if (first == null) { first = offer; firstIndex = index }
                assertSame(first, offer)
            }
            previous = point
        }
        assertNotNull(first)
        assertTrue(checkNotNull(firstIndex) > 2)
        assertEquals(CandidateState.AVAILABLE, h.planner.state(checkNotNull(first).candidateId))
    }

    @Test fun stationaryBothSignNoiseAndRadiusStraddlingCannotAccumulateApproach() {
        for (center in listOf(300.0, POLICY.approachRadiusMeters, POLICY.approachRadiusMeters + 11)) {
            val h = Harness()
            repeat(100) { index ->
                val noise = if (index % 2 == 0) -10.0 else 10.0
                assertNull(h.update(point(center + noise, START + index * 200L)))
            }
        }
    }

    @Test fun cumulativeRecessionExpiresEvenWhileOldFarBaselineStillShowsNetApproach() {
        val h = Harness()
        val far = point(400.0, START)
        h.update(far)
        val offer = checkNotNull(h.update(point(350.0, START + 200)))
        var terminalPoint: RecordedLocation? = null
        for (index in 1..35) {
            val current = point(350.0 + index, START + 200 + index * 200L)
            if (h.update(current) == null) { terminalPoint = current; break }
        }
        val reversed = checkNotNull(terminalPoint)
        assertEquals(LandmarkApproachState.APPROACHING, assess(far, reversed).state)
        assertEquals(CandidateState.EXPIRED, h.planner.state(offer.candidateId))
        assertFalse(h.session.isCurrent(offer))
        assertNull(h.update(point(300.0, START + 8_000))) // No new outside after cooldown.
    }

    @Test fun reversalBeforeFirstOfferDiscardsOlderRunAndNeedsNewSeparation() {
        val h = Harness()
        assertNull(h.update(point(400.0, START)))
        assertNull(h.update(point(390.0, START + 200)))
        assertNull(h.update(point(430.0, START + 400))) // Definite recession, reset to this point.
        assertNull(h.update(point(420.0, START + 600))) // Only 10m since the turn, not 40m.
        assertNotNull(h.update(point(400.0, START + 800)))
    }

    @Test fun invalidAuthorityInventoryAndUnknownRouteBreakAnUnadmittedRun() {
        for (reason in listOf("quality", "authority", "inventory", "route", "clear")) {
            val h = Harness()
            h.update(point(400.0, START))
            h.update(point(395.0, START + 200))
            val gap = point(360.0, START + 400)
            when (reason) {
                "quality" -> assertNull(h.update(gap.copy(quality = LocationQuality.UNKNOWN)))
                "authority" -> assertNull(h.update(gap, authority = false))
                "inventory" -> assertNull(h.update(gap, inventory = emptyList()))
                "route" -> assertNull(h.update(gap, route = LandmarkRouteContext.Unavailable))
                "clear" -> h.session.clear(START + 400)
            }
            assertNull(reason, h.update(point(359.0, START + 600)))
            assertNotNull(reason, h.update(point(330.0, START + 800)))
        }
    }

    @Test fun candidateEvidenceAndActiveRouteIdentityChangesCannotBorrowPrechangeObservations() {
        val replacement = candidate("new-source-or-policy-evidence")
        val h = Harness()
        h.update(point(400.0, START))
        assertNull(h.update(point(370.0, START + 200), inventory = listOf(replacement)))
        assertNotNull(h.update(point(340.0, START + 400), inventory = listOf(replacement)))

        val routed = Harness()
        val before = LandmarkRouteContext.OnRoute(1, setOf(CANDIDATE.placeId), "first-route")
        val after = LandmarkRouteContext.OnRoute(2, setOf(CANDIDATE.placeId), "second-route")
        routed.update(point(400.0, START), route = before, routeRevision = 1)
        assertNull(routed.update(point(370.0, START + 200), route = after, routeRevision = 2))
        assertNotNull(routed.update(point(340.0, START + 400), route = after, routeRevision = 2))
    }

    @Test fun candidateArrivingLateGetsNoPreinventoryMovementHistory() {
        val h = Harness()
        h.update(point(450.0, START), inventory = emptyList())
        h.update(point(400.0, START + 200), inventory = emptyList())
        assertNull(h.update(point(420.0, START + 400))) // Already receding when first visible.
        assertNull(h.update(point(450.0, START + 600)))
    }

    @Test fun ambiguousPlaceInventoryRevokesEvenOnTheSameCachedPoint() {
        val h = Harness()
        h.update(point(400.0, START))
        val current = point(350.0, START + 200)
        val offer = checkNotNull(h.update(current))
        assertNull(h.update(current, inventory = listOf(CANDIDATE, candidate("different-evidence"))))
        assertFalse(h.session.isCurrent(offer))
        assertEquals(CandidateState.EXPIRED, h.planner.state(offer.candidateId))
    }

    @Test fun missedTimerAndStaleBaselineNeverBridgeFreshnessGap() {
        val h = Harness()
        h.update(point(400.0, START))
        val offer = checkNotNull(h.update(point(350.0, START + 200)))
        assertNull(h.update(point(300.0, START + 10_201)))
        assertEquals(CandidateState.EXPIRED, h.planner.state(offer.candidateId))

        val slow = Harness()
        // The whole trace moves 60m, but no fresh 10-second interval separates 10m accuracy ranges.
        repeat(60) { index -> assertNull(slow.update(point(400.0 - index, START + index * 1000L))) }
    }

    @Test fun boundedWindowCannotUseEvictedObservationAtUnboundedCallbackRate() {
        val h = Harness()
        assertEquals(64, LandmarkMovementEvidence.MAX_POINTS)
        // 64 retained observations cover only 6.4m. No implicit decimation or unlimited history.
        repeat(1_000) { index -> assertNull(h.update(point(400.0 - index / 10.0, START + index))) }
    }

    @Test fun duplicateOrLateMeasurementsNeverAccumulateAndLatePointBreaksHistory() {
        val h = Harness()
        h.update(point(400.0, START))
        repeat(20) { index ->
            assertNull(h.update(point(400.0, START + (index + 1) * 100L, observed = START)))
        }
        assertNull(h.update(point(390.0, START + 2_200)))
        assertNull(h.update(point(350.0, START + 2_400, observed = START + 2_100)))
        assertNull(h.update(point(349.0, START + 2_600)))
        assertNotNull(h.update(point(320.0, START + 2_800)))
    }

    @Test fun renewedEncounterRequiresNewOutsideAndPostexitCumulativeBaseline() {
        val h = Harness()
        h.update(point(400.0, START))
        val old = checkNotNull(h.update(point(350.0, START + 200)))
        h.session.consume(old.candidateId, START + 201)
        repeat(20) { index -> assertNull(h.update(point(340.0 - index, START + 3000 + index * 200L))) }
        assertNull(h.update(point(1200.0, START + 7_000)))
        val fresh = checkNotNull(h.update(point(800.0, START + 7_200)))
        assertNotEquals(old.candidateId, fresh.candidateId)
        assertEquals(CandidateState.CONSUMED, h.planner.state(old.candidateId))
    }

    private class Harness {
        val planner = ContentPlanner(CLOCK)
        val session = LandmarkCandidateSession(planner, "session", CLOCK)
        fun update(point: RecordedLocation, authority: Boolean = true,
            inventory: List<LandmarkStoryCandidate> = listOf(CANDIDATE),
            route: LandmarkRouteContext = LandmarkRouteContext.CurrentPosition, routeRevision: Long? = null): LandmarkOffer? {
            val context = PlanningContext(null, routeRevision, point.receivedAtElapsedMs, CLOCK,
                point.quality == LocationQuality.VALID, false, point.receivedAtElapsedMs,
                locationFreshUntilElapsedMs = LocationFreshness.deadlineElapsedMs(point), locationAuthority = { authority })
            return session.update(inventory, point, context, route)
        }
    }

    private fun assess(previous: RecordedLocation?, point: RecordedLocation) = LandmarkApproach.assess(
        CANDIDATE.latitude, CANDIDATE.longitude, POLICY, previous, point, point.receivedAtElapsedMs, CLOCK)

    private fun point(distanceMeters: Double, elapsed: Long, observed: Long = elapsed) = RecordedLocation(
        "point-$elapsed", "synthetic", null, null, "${EPOCH + observed}", "epoch_ms", EPOCH + observed,
        EPOCH + elapsed, elapsed, CLOCK, 0.0, distanceMeters / 111_320.0, "{}", 10.0, "{}",
        LocationQuality.VALID, "synthetic-adapter",
    )

    companion object {
        private const val CLOCK = "synthetic-clock"
        private const val START = 10_000L
        private const val EPOCH = 1_700_000_000_000L
        private val POLICY = LandmarkPolicy(1_000.0, LandmarkPassedRule.INCREASING_DISTANCE_AFTER_APPROACH,
            LandmarkDirectionRule.DECREASING_DISTANCE, 2_000, "synthetic-window-review", "synthetic-adapter")
        private fun candidate(key: String) = LandmarkStoryCandidate(PreparedStory("story", "version", listOf("paragraph")),
            "place", 0.0, 0.0, "coordinate-source", POLICY, key)
        private val CANDIDATE = candidate("current-evidence")
    }
}
