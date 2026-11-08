package com.petasos.app.planning

import com.petasos.app.location.LandmarkDirectionRule
import com.petasos.app.location.LandmarkPassedRule
import com.petasos.app.location.LandmarkPolicy
import com.petasos.app.location.LocationFreshness
import com.petasos.app.location.LocationQuality
import com.petasos.app.location.RecordedLocation
import com.petasos.app.playback.PreparedStory
import org.junit.Assert.*
import org.junit.Test

class LandmarkCandidateSessionTest {
    private val policy = LandmarkPolicy(1_000.0, LandmarkPassedRule.INCREASING_DISTANCE_AFTER_APPROACH,
        LandmarkDirectionRule.DECREASING_DISTANCE, 2_000, "synthetic-v1", "synthetic-adapter")
    private fun candidate(place: String = "place", longitude: Double = 127.0, key: String = "evidence") = LandmarkStoryCandidate(
        PreparedStory("story-$place", "version-$place", listOf("paragraph-$place")), place, 37.0, longitude,
        "coordinate-source-$place", policy, key,
    )
    private fun point(elapsed: Long, longitude: Double, observed: Long = elapsed) = RecordedLocation(
        "p-$elapsed", "synthetic", null, null, "${EPOCH + observed}", "epoch_ms", EPOCH + observed,
        EPOCH + elapsed, elapsed, "clock", 37.0, longitude, "{}", 1.0, "{}", LocationQuality.VALID, "synthetic-adapter",
    )
    private fun context(point: RecordedLocation, speaking: Boolean = false, route: Long? = null, now: Long = point.receivedAtElapsedMs) =
        PlanningContext(null, route, now, "clock", true, speaking, point.receivedAtElapsedMs,
            locationFreshUntilElapsedMs = LocationFreshness.deadlineElapsedMs(point))

    private class Harness(val planner: ContentPlanner = ContentPlanner("clock")) {
        val session = LandmarkCandidateSession(planner, "session", "clock")
    }

    @Test fun landmarkIsOneHighPriorityOfferWithoutPlaybackEffects() {
        val h = Harness()
        val interest = StoryCandidate("interest", PreparedStory("interest", "iv", listOf("ip")), CandidateReason.INTEREST, null, null, null)
        h.planner.offer(interest)
        val candidates = listOf(candidate("farther", longitude = 126.999), candidate("nearer"))
        val before = point(1_000, 127.006)
        assertNull(h.session.update(candidates, before, context(before), LandmarkRouteContext.CurrentPosition))
        val next = point(2_000, 127.004)
        val offered = h.session.update(candidates, next, context(next), LandmarkRouteContext.CurrentPosition)!!
        assertTrue(h.session.isCurrent(offered))
        assertEquals("nearer", offered.candidate.placeId)
        assertEquals(offered.candidateId, h.planner.chooseAtBoundary(context(next), h.session::stillApproaching)?.candidateId)
        assertFalse(h.planner.offer(StoryCandidate("second", candidate("second").story, CandidateReason.LANDMARK, null, null, null)))
        h.session.consume(offered.candidateId, 2_001)
        assertFalse(h.session.isCurrent(offered))
        assertEquals(CandidateState.CONSUMED, h.planner.state(offered.candidateId))
        assertEquals("interest", h.planner.chooseAtBoundary(context(next), h.session::stillApproaching)?.candidateId)
    }

    @Test fun passedDuringListeningStaysTerminalWhenTalkEndsAndOldPositionReturns() {
        val h = Harness()
        val candidates = listOf(candidate())
        val p1 = point(1_000, 127.006)
        val p2 = point(2_000, 127.004)
        h.session.update(candidates, p1, context(p1), LandmarkRouteContext.CurrentPosition)
        val offer = h.session.update(candidates, p2, context(p2, speaking = true), LandmarkRouteContext.CurrentPosition)!!
        assertNull(h.planner.chooseAtBoundary(context(p2, speaking = true), h.session::stillApproaching))
        val passed = point(3_000, 127.005)
        assertNull(h.session.update(candidates, passed, context(passed, speaking = true), LandmarkRouteContext.CurrentPosition))
        assertEquals(CandidateState.EXPIRED, h.planner.state(offer.candidateId))
        assertNull(h.session.update(candidates, passed, context(passed, now = 3_001), LandmarkRouteContext.CurrentPosition))
        assertNull(h.session.update(candidates, p2, context(p2, now = 3_002), LandmarkRouteContext.CurrentPosition))
        assertFalse(h.planner.offer(StoryCandidate(offer.candidateId, offer.candidate.story, CandidateReason.LANDMARK, null, null, null)))
    }

    @Test fun cooldownRequiresLaterOutsideAndNewApproachAndNeverRevivesOldId() {
        val h = Harness()
        val candidates = listOf(candidate())
        fun update(p: RecordedLocation) = h.session.update(candidates, p, context(p), LandmarkRouteContext.CurrentPosition)
        update(point(1_000, 127.006))
        val first = update(point(2_000, 127.004))!!
        assertNull(update(point(3_000, 127.005)))
        assertNull(update(point(3_500, 127.020))) // Outside, but before cooldown.
        assertNull(update(point(4_000, 127.004)))
        val sameMeasurement = point(5_100, 127.020, observed = 4_000)
        assertNull(update(sameMeasurement)) // New callback cannot rearm a cached measurement.
        assertNull(update(point(5_200, 127.004))) // No fresh outside after cooldown yet.
        assertNull(update(point(5_300, 127.020)))
        val second = update(point(5_400, 127.005))!!
        assertNotEquals(first.candidateId, second.candidateId)
        assertEquals(CandidateState.EXPIRED, h.planner.state(first.candidateId))
        assertEquals(CandidateState.AVAILABLE, h.planner.state(second.candidateId))
    }

    @Test fun clearedOfferCannotReturnByTimerRouteChangeOrTalkEnd() {
        val h = Harness()
        val candidates = listOf(candidate())
        val before = point(1_000, 127.006)
        val current = point(2_000, 127.004)
        h.session.update(candidates, before, context(before), LandmarkRouteContext.CurrentPosition)
        val offered = h.session.update(candidates, current, context(current), LandmarkRouteContext.CurrentPosition)!!
        h.session.clear(2_001)
        assertNull(h.session.update(candidates, current, context(current, now = 5_000), LandmarkRouteContext.CurrentPosition))
        assertNull(h.session.update(candidates, point(5_001, 127.003), context(point(5_001, 127.003)), LandmarkRouteContext.CurrentPosition))
        assertEquals(CandidateState.EXPIRED, h.planner.state(offered.candidateId))
    }

    @Test fun delayedOutsideMeasurementTakenDuringCooldownCannotRearm() {
        val h = Harness()
        val candidates = listOf(candidate())
        fun update(p: RecordedLocation) = h.session.update(candidates, p, context(p), LandmarkRouteContext.CurrentPosition)
        update(point(1_000, 127.006))
        val old = update(point(2_000, 127.004))!!
        assertNull(update(point(3_000, 127.005)))
        assertNull(update(point(6_000, 127.020, observed = 4_000))) // Actual exit precedes cooldown end.
        assertNull(update(point(6_100, 127.004)))
        assertNull(update(point(6_200, 127.020)))
        val next = update(point(6_300, 127.005))!!
        assertNotEquals(old.candidateId, next.candidateId)
        assertEquals(CandidateState.EXPIRED, h.planner.state(old.candidateId))
    }

    @Test fun activeRouteRequiresExactMembershipAndRevisionAndDoesNotBecomeCurrentPositionFallback() {
        val h = Harness()
        val candidates = listOf(candidate())
        val before = point(1_000, 127.006)
        val current = point(2_000, 127.004)
        val route = LandmarkRouteContext.OnRoute(7, setOf("place"), "route-evidence")
        h.session.update(candidates, before, context(before, route = 7), route)
        val offered = h.session.update(candidates, current, context(current, route = 7), route)!!
        assertEquals(7L, offered.routeDependency)
        assertNull(h.session.update(candidates, current, context(current, route = 8), LandmarkRouteContext.Unavailable))
        assertEquals(CandidateState.EXPIRED, h.planner.state(offered.candidateId))
        assertNull(h.session.update(candidates, point(3_000, 127.003), context(point(3_000, 127.003)), LandmarkRouteContext.CurrentPosition))
        val other = Harness()
        other.session.update(candidates, before, context(before, route = 7), LandmarkRouteContext.Unavailable)
        assertNull(other.session.update(candidates, current, context(current, route = 7), LandmarkRouteContext.Unavailable))
    }

    @Test fun newerSdkCallbackWithSameMeasurementCannotCreateOrRearm() {
        val h = Harness()
        val candidates = listOf(candidate())
        val before = point(1_000, 127.006)
        h.session.update(candidates, before, context(before), LandmarkRouteContext.CurrentPosition)
        val duplicate = point(2_000, 127.006, observed = 1_000)
        assertNull(h.session.update(candidates, duplicate, context(duplicate), LandmarkRouteContext.CurrentPosition))
        val real = point(3_000, 127.003)
        assertNotNull(h.session.update(candidates, real, context(real), LandmarkRouteContext.CurrentPosition))
    }

    @Test fun invalidPointBreaksConsecutiveEvidenceAndRevokesCurrentCandidate() {
        val h = Harness()
        val candidates = listOf(candidate())
        val before = point(1_000, 127.006)
        val invalid = point(2_000, 127.005).copy(quality = LocationQuality.UNKNOWN)
        h.session.update(candidates, before, context(before), LandmarkRouteContext.CurrentPosition)
        assertNull(h.session.update(candidates, invalid, context(invalid), LandmarkRouteContext.CurrentPosition))
        val firstAfter = point(3_000, 127.004)
        assertNull(h.session.update(candidates, firstAfter, context(firstAfter), LandmarkRouteContext.CurrentPosition))
        val secondAfter = point(4_000, 127.003)
        val offered = h.session.update(candidates, secondAfter, context(secondAfter), LandmarkRouteContext.CurrentPosition)!!
        assertNull(h.session.update(candidates, secondAfter, context(secondAfter).copy(locationAuthority = { false }), LandmarkRouteContext.CurrentPosition))
        assertEquals(CandidateState.EXPIRED, h.planner.state(offered.candidateId))
    }

    @Test fun metadataChangesInvalidateOfferBeforePlaybackAndNoRegionTargetIsNeeded() {
        val h = Harness()
        val before = point(1_000, 127.006)
        val current = point(2_000, 127.004)
        h.session.update(listOf(candidate()), before, context(before), LandmarkRouteContext.CurrentPosition)
        val offered = h.session.update(listOf(candidate()), current, context(current), LandmarkRouteContext.CurrentPosition)!!
        assertNull(h.session.update(listOf(candidate(key = "changed")), current, context(current), LandmarkRouteContext.CurrentPosition))
        assertEquals(CandidateState.EXPIRED, h.planner.state(offered.candidateId))
    }

    @Test fun staleTimerExpiresOfferAndRetainsTerminalState() {
        val h = Harness()
        val candidates = listOf(candidate())
        val before = point(1_000, 127.006)
        val current = point(2_000, 127.004)
        h.session.update(candidates, before, context(before), LandmarkRouteContext.CurrentPosition)
        val offered = h.session.update(candidates, current, context(current), LandmarkRouteContext.CurrentPosition)!!
        assertNull(h.session.update(candidates, current, context(current, now = 12_001), LandmarkRouteContext.CurrentPosition))
        assertEquals(CandidateState.EXPIRED, h.planner.state(offered.candidateId))
    }

    @Test fun candidateAndRouteInputsAreDefensiveCopies() {
        val paragraphs = mutableListOf("p")
        val candidate = LandmarkStoryCandidate(PreparedStory("s", "v", paragraphs), "place", 37.0, 127.0, "source", policy, "key")
        paragraphs.clear()
        assertEquals(listOf("p"), candidate.story.paragraphIds)
        val places = mutableSetOf("place")
        val route = LandmarkRouteContext.OnRoute(1, places, "evidence")
        places.clear()
        assertEquals(setOf("place"), route.eligiblePlaceIds)
        assertFalse(candidate.toString().contains("127"))
    }

    @Test fun missedFreshnessTimerCannotBridgeObservationGap() {
        val h = Harness()
        val candidates = listOf(candidate())
        val before = point(1_000, 127.006)
        val current = point(2_000, 127.004)
        h.session.update(candidates, before, context(before), LandmarkRouteContext.CurrentPosition)
        val offer = h.session.update(candidates, current, context(current), LandmarkRouteContext.CurrentPosition)!!
        val late = point(12_001, 127.003)
        assertNull(h.session.update(candidates, late, context(late), LandmarkRouteContext.CurrentPosition))
        assertFalse(h.session.isCurrent(offer))
        assertEquals(CandidateState.EXPIRED, h.planner.state(offer.candidateId))
    }

    @Test fun failedUpdateRevokesAtomicPermitBeforeFailureEscapes() {
        val h = Harness()
        val candidates = listOf(candidate())
        val before = point(1_000, 127.006)
        val current = point(2_000, 127.004)
        h.session.update(candidates, before, context(before), LandmarkRouteContext.CurrentPosition)
        val offer = h.session.update(candidates, current, context(current), LandmarkRouteContext.CurrentPosition)!!
        val brokenInventory = object : AbstractList<LandmarkStoryCandidate>() {
            override val size: Int get() = 1
            override fun get(index: Int): LandmarkStoryCandidate = throw IllegalStateException("synthetic inventory failure")
        }
        try {
            h.session.update(brokenInventory, current, context(current), LandmarkRouteContext.CurrentPosition)
            fail("inventory failure was swallowed")
        } catch (_: IllegalStateException) { }
        assertFalse(h.session.isCurrent(offer))
        assertEquals(CandidateState.EXPIRED, h.planner.state(offer.candidateId))
    }

    companion object { private const val EPOCH = 1_700_000_000_000L }
}
