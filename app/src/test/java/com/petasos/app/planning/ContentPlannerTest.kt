package com.petasos.app.planning

import com.petasos.app.playback.PreparedStory
import com.petasos.app.location.LocationInputAuthority
import org.junit.Assert.*
import org.junit.Test

class ContentPlannerTest {
    private fun candidate(id: String, reason: CandidateReason = CandidateReason.CURRENT_REGION, region: String? = "a", route: Long? = null, expiry: Long? = null) =
        StoryCandidate(id, PreparedStory("story-$id", "version-$id", listOf("paragraph-$id")), reason, region, route, expiry)
    private fun context(region: String? = "a", route: Long? = null, elapsed: Long = 0, speaking: Boolean = false) =
        PlanningContext(region, route, elapsed, "clock", true, speaking, elapsed,
            locationFreshUntilElapsedMs = elapsed + 10_000)

    @Test fun regionChangeOnlyChangesSelectionAtNextBoundary() {
        val planner = ContentPlanner("clock")
        planner.offer(candidate("a")); planner.offer(candidate("b", region = "b"))
        assertEquals("a", planner.chooseAtBoundary(context())?.candidateId)
        assertEquals("b", planner.chooseAtBoundary(context("b"))?.candidateId)
        assertEquals(CandidateState.AVAILABLE, planner.state("a"))
    }

    @Test fun routeChangesExpireOnlyDependentCandidatesAndCannotReviveThem() {
        val planner = ContentPlanner("clock")
        val old = candidate("old", route = 1)
        planner.offer(old); planner.offer(candidate("current"))
        planner.update(context(route = 2))
        assertEquals(CandidateState.EXPIRED, planner.state("old"))
        assertFalse(planner.offer(old))
        assertEquals("current", planner.chooseAtBoundary(context(route = 2))?.candidateId)
    }

    @Test fun singleLandmarkPassedWhileSpeakingStaysExpiredAfterSpeech() {
        val planner = ContentPlanner("clock")
        val landmark = candidate("landmark", CandidateReason.LANDMARK, expiry = 1_000)
        assertTrue(planner.offer(landmark))
        assertFalse(planner.offer(candidate("second", CandidateReason.LANDMARK)))
        assertNull(planner.chooseAtBoundary(context(speaking = true)) { true })
        planner.update(context(speaking = true)) { false }
        assertEquals(CandidateState.EXPIRED, planner.state("landmark"))
        assertFalse(planner.offer(landmark))
        assertNull(planner.chooseAtBoundary(context()) { true })
    }

    @Test fun expiryNewClockAndConsumedCandidatesAreTerminal() {
        val planner = ContentPlanner("clock")
        planner.offer(candidate("expired", expiry = 10))
        assertNull(planner.chooseAtBoundary(context(elapsed = 10)))
        planner.offer(candidate("consumed")); planner.consumed("consumed")
        assertFalse(planner.offer(candidate("consumed")))
        planner.offer(candidate("old-clock"))
        planner.update(context().copy(clockScopeId = "new-clock"))
        assertEquals(CandidateState.EXPIRED, planner.state("old-clock"))
    }

    @Test fun unknownLocationDoesNotScheduleAutomaticRegionStoryButKeepsCheckpoint() {
        val planner = ContentPlanner("clock")
        planner.offer(candidate("region"))
        assertNull(planner.chooseAtBoundary(context().copy(locationValid = false)))
        planner.offer(candidate("checkpoint", CandidateReason.CHECKPOINT))
        assertEquals("checkpoint", planner.chooseAtBoundary(context().copy(locationValid = false))?.candidateId)
    }

    @Test fun missingOrExpiredMeasurementDeadlineBlocksRegionsAndExpiresLandmarks() {
        val planner = ContentPlanner("clock")
        planner.offer(candidate("region"))
        assertNull(planner.chooseAtBoundary(context().copy(locationFreshUntilElapsedMs = null)))
        val nearExpiry = context(elapsed = 1_000).copy(locationFreshUntilElapsedMs = 1_001)
        assertEquals("region", planner.chooseAtBoundary(nearExpiry.copy(elapsedMs = 1_001))?.candidateId)
        assertNull(planner.chooseAtBoundary(nearExpiry.copy(elapsedMs = 1_002)))
        val landmark = candidate("landmark", CandidateReason.LANDMARK)
        planner.offer(landmark)
        planner.update(nearExpiry.copy(elapsedMs = 1_002)) { true }
        assertEquals(CandidateState.EXPIRED, planner.state("landmark"))
        assertFalse(planner.offer(landmark))
        planner.offer(candidate("interest", CandidateReason.INTEREST, region = null))
        assertEquals("interest", planner.chooseAtBoundary(nearExpiry.copy(elapsedMs = 1_002))?.candidateId)
    }

    @Test fun missingMeasurementDeadlineAlsoBlocksUpcomingRegionDespitePredictedTarget() {
        val planner = ContentPlanner("clock")
        planner.offer(candidate("upcoming", CandidateReason.UPCOMING_REGION, region = "b", route = 1))
        val target = RegionalStoryTarget("session", "clock", 1, "b", 1, "visit-b", 0, 50_000)
        val predicted = context(route = 1).copy(regionalTarget = target)
        assertEquals("upcoming", planner.chooseAtBoundary(predicted)?.candidateId)
        assertNull(planner.chooseAtBoundary(predicted.copy(locationFreshUntilElapsedMs = null)))
    }

    @Test fun revokedInputExpiresLandmarkBeforeQueuedContextChangesButKeepsInterestAvailable() {
        val authority = LocationInputAuthority()
        val receipt = authority.record(0, admissible = true)
        val snapshot = context().copy(locationAuthority = { authority.canUse(receipt, 0) })
        val planner = ContentPlanner("clock")
        val landmark = candidate("landmark", CandidateReason.LANDMARK)
        planner.offer(landmark)
        planner.offer(candidate("region"))
        planner.offer(candidate("interest", CandidateReason.INTEREST, region = null))
        assertEquals("landmark", planner.chooseAtBoundary(snapshot) { true }?.candidateId)
        authority.invalidate()
        authority.record(0, admissible = true)
        assertEquals("interest", planner.chooseAtBoundary(snapshot) { true }?.candidateId)
        assertEquals(CandidateState.EXPIRED, planner.state("landmark"))
        assertFalse(planner.offer(landmark))
        assertEquals("interest", planner.chooseAtBoundary(snapshot.copy(locationAuthority = { error("test") }))?.candidateId)
    }

    @Test fun interestFollowsCurrentRegionAndWorksWithoutLocation() {
        val planner = ContentPlanner("clock")
        planner.offer(candidate("interest", CandidateReason.INTEREST, region = null))
        planner.offer(candidate("region"))
        assertEquals("region", planner.chooseAtBoundary(context())?.candidateId)
        assertEquals("interest", planner.chooseAtBoundary(context().copy(locationValid = false))?.candidateId)
        assertEquals("interest", planner.chooseAtBoundary(context("other-region"))?.candidateId)
        assertNull(planner.chooseAtBoundary(context(speaking = true)))
    }

    @Test fun checkpointRemainsAheadOfOrdinaryInterestsAndConsumedInterestIsTerminal() {
        val planner = ContentPlanner("clock")
        val interest = candidate("interest", CandidateReason.INTEREST, region = null)
        planner.offer(interest)
        planner.offer(candidate("checkpoint", CandidateReason.CHECKPOINT))
        assertEquals("checkpoint", planner.chooseAtBoundary(context())?.candidateId)
        planner.consumed("checkpoint")
        assertEquals("interest", planner.chooseAtBoundary(context())?.candidateId)
        planner.consumed("interest")
        assertFalse(planner.offer(interest))
        assertNull(planner.chooseAtBoundary(context()))
    }
}
