package com.petasos.app.planning

import com.petasos.app.location.LandmarkDirectionRule
import com.petasos.app.location.LandmarkPassedRule
import com.petasos.app.location.LandmarkPolicy
import com.petasos.app.location.LocationFreshness
import com.petasos.app.location.LocationInputAuthority
import com.petasos.app.location.LocationQuality
import com.petasos.app.location.RecordedLocation
import com.petasos.app.playback.*
import org.junit.Assert.*
import org.junit.Test

class LandmarkPlaybackBridgeTest {
    private val landmark = LandmarkStoryCandidate(
        PreparedStory("landmark", "landmark-version", listOf("landmark-p1", "landmark-p2")),
        "place", 37.0, 127.0, "coordinate-source",
        LandmarkPolicy(1_000.0, LandmarkPassedRule.INCREASING_DISTANCE_AFTER_APPROACH,
            LandmarkDirectionRule.DECREASING_DISTANCE, 2_000, "synthetic-v1", "synthetic-adapter"), "evidence",
    )
    private val interest = AutomaticStoryCandidate(PreparedStory("interest", "interest-version", listOf("interest-p")),
        CandidateReason.INTEREST, null, "interest-evidence")
    private fun point(elapsed: Long, longitude: Double) = RecordedLocation(
        "point-$elapsed", "synthetic", null, null, "${EPOCH + elapsed}", "epoch_ms", EPOCH + elapsed,
        EPOCH + elapsed, elapsed, "clock", 37.0, longitude, "{}", 1.0, "{}", LocationQuality.VALID, "synthetic-adapter",
    )
    private fun context(point: RecordedLocation, speaking: Boolean = false, now: Long = point.receivedAtElapsedMs) =
        PlanningContext(null, null, now, "clock", true, speaking, point.receivedAtElapsedMs,
            locationFreshUntilElapsedMs = LocationFreshness.deadlineElapsedMs(point))
    private fun reference(story: PreparedStory = landmark.story) = OutputReference.StoryParagraph(story.storyId, story.versionId, story.paragraphIds.first())

    private inner class Harness(
        onOffer: (PreparedStory, String) -> Unit = { _, _ -> },
        onWithdraw: (String, String) -> Unit = { _, _ -> },
        requireRegionalTarget: Boolean = false,
    ) {
        var now = 0L
        var landmarks = listOf(landmark)
        var ordinary = listOf(interest)
        var currentEvidence = true
        var evidenceHook: () -> Unit = { }
        val offers = mutableListOf<Pair<PreparedStory, String>>()
        val withdrawals = mutableListOf<Pair<String, String>>()
        val planner = ContentPlanner("clock")
        val session = LandmarkCandidateSession(planner, "session", "clock")
        val exclusions = mutableListOf<Set<String>>()
        val bridge = AutomaticStoryPlaybackBridge(planner,
            { _, excluded -> ordinary.filter { it.story.storyId !in excluded } },
            object : PlannedStoryOutput {
                override fun offer(story: PreparedStory, offerId: String) {
                    offers += story to offerId
                    onOffer(story, offerId)
                }
                override fun withdraw(storyId: String, versionId: String) {
                    withdrawals += storyId to versionId
                    onWithdraw(storyId, versionId)
                }
            }, { now }, { _, _ -> true }, landmarkSession = session,
            landmarkCandidates = { excluded -> exclusions += excluded.toSet(); landmarks.filter { it.story.storyId !in excluded } },
            landmarkCurrent = { evidenceHook(); currentEvidence },
            requireRegionalTarget = requireRegionalTarget,
        )
        fun update(point: RecordedLocation, planningContext: PlanningContext = context(point), allowOffer: Boolean = true,
                   route: LandmarkRouteContext = LandmarkRouteContext.CurrentPosition) {
            now = planningContext.elapsedMs
            bridge.update(planningContext, point, route, allowOffer)
        }
        fun admit(): Pair<PreparedStory, String> {
            update(point(1_000, 127.006))
            update(point(2_000, 127.004))
            return offers.last().also { assertEquals(landmark.story, it.first) }
        }
    }

    @Test fun landmarkWinsOneOfferWithoutRegionalTargetOrCurrentRegion() {
        val h = Harness()
        val (story, token) = h.admit()
        assertEquals(listOf(interest.story, landmark.story), h.offers.map { it.first })
        assertEquals(listOf(interest.story.storyId to interest.story.versionId), h.withdrawals)
        assertTrue(h.bridge.isAutomaticStoryCurrent(token, reference(story)))
        assertTrue(h.bridge.isAutomaticStoryOffered(token, reference(story)))
        val same = point(2_000, 127.004)
        h.update(same, context(same, now = 2_001))
        assertEquals(2, h.offers.size)
    }

    @Test fun pausedAndInteractionUpdatesExpirePassedCandidateWithoutResurrection() {
        for (speaking in listOf(false, true)) {
            val h = Harness()
            val (_, token) = h.admit()
            val current = point(2_000, 127.004)
            val id = h.planner.chooseAtBoundary(context(current), h.session::stillApproaching)!!.candidateId
            h.update(current, context(current, speaking = speaking, now = 2_001), allowOffer = speaking)
            assertFalse(h.bridge.isAutomaticStoryOffered(token, reference()))
            val passed = point(3_000, 127.005)
            h.update(passed, context(passed, speaking = speaking), allowOffer = speaking)
            assertEquals(CandidateState.EXPIRED, h.planner.state(id))
            h.update(passed, context(passed, now = 3_001))
            assertEquals(interest.story, h.offers.last().first)
            assertEquals(1, h.offers.count { it.first.storyId == landmark.story.storyId })
        }
    }

    @Test fun requiredRegionalTargetDoesNotGateObservedLandmarkApproach() {
        val h = Harness(requireRegionalTarget = true)
        val regional = AutomaticStoryCandidate(PreparedStory("regional", "regional-version", listOf("regional-p")),
            CandidateReason.CURRENT_REGION, "region", "region-evidence")
        h.ordinary = listOf(regional, interest)
        val before = point(1_000, 127.006)
        val current = point(2_000, 127.004)
        h.update(before, context(before).copy(currentRegionId = "region"))
        assertEquals(interest.story, h.offers.last().first)
        h.update(current, context(current).copy(currentRegionId = "region"))
        assertEquals(landmark.story, h.offers.last().first)
        assertTrue(h.bridge.isAutomaticStoryCurrent(h.offers.last().second, reference()))
        assertTrue(h.offers.none { it.first.storyId == regional.story.storyId })
    }

    @Test fun onlyExactStartedOfferConsumesAndExcludesLandmarkAcrossInventories() {
        val h = Harness()
        val (_, token) = h.admit()
        val current = point(2_000, 127.004)
        val id = h.planner.chooseAtBoundary(context(current), h.session::stillApproaching)!!.candidateId
        h.bridge.storyStarted("late-other-token", landmark.story.versionId)
        h.bridge.storyStarted(token, "wrong-version")
        assertEquals(CandidateState.AVAILABLE, h.planner.state(id))
        assertTrue(h.bridge.isAutomaticStoryCurrent(token, reference()))
        h.bridge.storyStarted(token, landmark.story.versionId)
        assertEquals(CandidateState.CONSUMED, h.planner.state(id))
        assertFalse(h.bridge.isAutomaticStoryOffered(token, reference()))
        h.update(current, context(current, now = 2_001))
        assertTrue(landmark.story.storyId in h.exclusions.last())
        assertEquals(interest.story, h.offers.last().first)
    }

    @Test fun metadataEvidenceIsRereadBeforeOutputAndRemovedInventoryWithdraws() {
        val h = Harness()
        val (_, token) = h.admit()
        assertTrue(h.bridge.isAutomaticStoryCurrent(token, reference()))
        h.currentEvidence = false
        assertFalse(h.bridge.isAutomaticStoryCurrent(token, reference()))
        h.landmarks = emptyList()
        val current = point(2_000, 127.004)
        h.update(current, context(current, now = 2_001))
        assertFalse(h.bridge.isAutomaticStoryOffered(token, reference()))
        assertTrue((landmark.story.storyId to landmark.story.versionId) in h.withdrawals)
    }

    @Test fun revokedLocationOrNavigationIngressClosesFinalPermitBeforeWorkerRefresh() {
        for (revokeLocation in listOf(false, true)) {
            val h = Harness()
            val authority = LocationInputAuthority()
            var navigationRevision = 7L
            val route = LandmarkRouteContext.OnRoute(7, setOf("place"), "reviewed-route")
            fun update(p: RecordedLocation) {
                val permit = authority.record(p.receivedAtElapsedMs, admissible = true)
                val capturedRevision = navigationRevision
                h.update(p, context(p).copy(routeRevision = 7, locationAuthority = {
                    authority.canUse(permit, p.receivedAtElapsedMs) && navigationRevision == capturedRevision
                }), route = route)
            }
            update(point(1_000, 127.006))
            update(point(2_000, 127.004))
            val token = h.offers.last().second
            assertEquals(landmark.story, h.offers.last().first)
            assertTrue(h.bridge.isAutomaticStoryCurrent(token, reference()))
            if (revokeLocation) authority.invalidate() else navigationRevision = 8
            assertFalse(h.bridge.isAutomaticStoryCurrent(token, reference()))
            assertFalse(h.bridge.isAutomaticStoryOffered(token, reference()))
            authority.record(2_000, admissible = true)
            assertFalse(h.bridge.isAutomaticStoryOffered(token, reference()))
        }
    }

    @Test fun regionPolicyRevocationDoesNotInvalidateAnIndependentLandmarkApproach() {
        val h = Harness(requireRegionalTarget = true)
        fun update(p: RecordedLocation) = h.update(p, context(p).copy(currentRegionId = "unreviewed-region",
            currentRegionAuthority = { false }, currentRegionEvidenceCurrent = { error("region lookup is unrelated") }))
        update(point(1_000, 127.006))
        update(point(2_000, 127.004))
        val (story, token) = h.offers.last()
        assertEquals(landmark.story, story)
        assertTrue(h.bridge.isAutomaticStoryCurrent(token, reference()))
        assertTrue(h.bridge.isAutomaticStoryOffered(token, reference()))
    }

    @Test fun ingressRevocationDuringEvidenceLookupWinsOverSuccessfulRead() {
        val h = Harness()
        val (_, token) = h.admit()
        h.evidenceHook = { h.bridge.revokeAuthority() }
        assertFalse(h.bridge.isAutomaticStoryCurrent(token, reference()))
        assertFalse(h.bridge.isAutomaticStoryOffered(token, reference()))
    }

    @Test fun expiredMeasurementCannotStartEvenBeforeFreshnessTimerRuns() {
        val h = Harness()
        val (_, token) = h.admit()
        h.now = 12_001
        assertFalse(h.bridge.isAutomaticStoryCurrent(token, reference()))
        assertFalse(h.bridge.isAutomaticStoryOffered(token, reference()))
    }

    @Test fun pendingLandmarkPreservesCurrentStoryAndWaitsForNaturalMusicCompletion() {
        var time = 0L
        val core = PlaybackCoordinator("trip", "session")
        val offerTransitions = mutableListOf<PlaybackTransition>()
        fun send(event: PlaybackEvent) = core.dispatch(event, ++time)
        val h = Harness(
            onOffer = { story, token -> offerTransitions += send(PlaybackEvent.OfferStory(story, token)) },
            onWithdraw = { story, version -> offerTransitions += send(PlaybackEvent.ClearOfferedStory(story, version)) },
        )
        val background = PreparedStory("background", "background-version", listOf("background-p1", "background-p2"))
        val resume = send(PlaybackEvent.PlayStory(background)).effects.filterIsInstance<PlaybackEffect.PlanResume>().single()
        val first = send(PlaybackEvent.ResumePlanned(resume.request, resume.candidates.single()))
            .effects.filterIsInstance<PlaybackEffect.StartOutput>().single()
        send(PlaybackEvent.OutputStarted(first.attempt))
        send(PlaybackEvent.OfferMusic(PreparedMusic("music-track", 20_000)))
        val (_, token) = h.admit()
        assertTrue(offerTransitions.flatMap { it.effects }.none { it is PlaybackEffect.StopOutput || it is PlaybackEffect.StartOutput })
        send(PlaybackEvent.OutputDone(first.attempt))
        val second = send(PlaybackEvent.PlaybackCommitted(first.attempt, true))
            .effects.filterIsInstance<PlaybackEffect.StartOutput>().single()
        assertEquals(OutputReference.StoryParagraph(background.storyId, background.versionId, "background-p2"), second.reference)
        send(PlaybackEvent.OutputStarted(second.attempt))
        send(PlaybackEvent.OutputDone(second.attempt))
        val music = send(PlaybackEvent.PlaybackCommitted(second.attempt, true))
            .effects.filterIsInstance<PlaybackEffect.StartOutput>().single()
        assertTrue(music.reference is OutputReference.MusicTrack)
        assertNull(music.automaticOfferId)
        val current = point(2_000, 127.004)
        h.update(current, context(current, now = 2_001))
        send(PlaybackEvent.OutputStarted(music.attempt))
        val completed = send(PlaybackEvent.OutputDone(music.attempt, 20_000))
            .effects.filterIsInstance<PlaybackEffect.CommitPlayback>().single()
        assertEquals(AttemptOutcome.COMPLETED, completed.outcome)
        val nextStory = send(PlaybackEvent.PlaybackCommitted(music.attempt, true))
            .effects.filterIsInstance<PlaybackEffect.StartOutput>().single()
        assertEquals(reference(), nextStory.reference)
        assertEquals(token, nextStory.automaticOfferId)
        assertTrue(h.bridge.isAutomaticStoryCurrent(token, reference()))
    }

    companion object { private const val EPOCH = 1_700_000_000_000L }
}
