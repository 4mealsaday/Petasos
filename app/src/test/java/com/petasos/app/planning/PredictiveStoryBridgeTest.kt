package com.petasos.app.planning

import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.location.LocationFreshness
import com.petasos.app.location.LocationMeaningAdapter
import com.petasos.app.location.LocationNormalizer
import com.petasos.app.location.VerifiedLocationMeaning
import com.petasos.app.playback.*
import org.junit.Assert.*
import org.junit.Test

/** Integration of prediction identity, installed inventory selection, and final output authority. */
class PredictiveStoryBridgeTest {
    private fun story(region: String) = PreparedStory("story-$region", "version-$region", listOf("$region-p1", "$region-p2"))
    private fun reference(story: PreparedStory) = OutputReference.StoryParagraph(story.storyId, story.versionId, story.paragraphIds.first())
    private fun forecast(route: Long = 1, observed: Long = 0, aExit: Long = 50_000,
        bExit: Long = 120_000, expiry: Long = 300_000) = ReviewedRouteForecast("session", "clock", route,
        observed, expiry, listOf(RegionPassage("a-visit", "a", 0, aExit),
            RegionPassage("b-visit", "b", aExit, bExit), RegionPassage("c-visit", "c", bExit, 250_000)))

    private inner class Harness {
        var now = 0L
        var currentRegion = "a"
        val planner = RegionalStoryPlanner("session", "clock")
        val offers = mutableListOf<Pair<PreparedStory, String>>()
        val withdrawals = mutableListOf<String>()
        val queriedRegions = mutableListOf<String?>()
        val checkedRegions = mutableListOf<String?>()
        val excludedSnapshots = mutableListOf<Set<String>>()
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { region, excluded ->
            queriedRegions += region
            excludedSnapshots += excluded
            listOfNotNull(region?.let { value ->
                val item = story(value)
                if (item.storyId in excluded) null
                else AutomaticStoryCandidate(item, CandidateReason.CURRENT_REGION, value, "reviewed-$value")
            })
        }, object : PlannedStoryOutput {
            override fun offer(story: PreparedStory, offerId: String) { offers += story to offerId }
            override fun withdraw(storyId: String, versionId: String) { withdrawals += storyId }
        }, { now }, { candidate, region ->
            checkedRegions += region
            candidate.regionId == region
        }, targetCurrent = { planner.isCurrent(it, now) }, targetPlayable = { planner.isPlayableNow(it, now) })

        fun target(start: Long?, source: ReviewedRouteForecast? = forecast(), route: Long? = 1,
            interaction: Boolean = false, freshUntil: Long? = now + 10_000) = planner.update(RegionalPlanningInput("session", "clock", route,
            now, now, true, currentRegion, interaction, start, source, locationFreshUntilElapsedMs = freshUntil))

        fun context(target: RegionalStoryTarget?, route: Long? = 1, interaction: Boolean = false,
            freshUntil: Long? = now + 10_000) =
            PlanningContext(currentRegion, route, now, "clock", target != null, interaction, now, target,
                locationFreshUntilElapsedMs = freshUntil)

        fun offer(start: Long, source: ReviewedRouteForecast = forecast()): RegionalStoryTarget {
            val target = target(start, source)!!
            bridge.update(context(target))
            return target
        }
    }

    @Test fun cachedMeasurementAgeFlowsFromNormalizationThroughPlanAndActualOutputAuthority() {
        val h = Harness()
        h.now = 1_000
        val cached = LocationNormalizer(LocationMeaningAdapter {
            VerifiedLocationMeaning("reviewed-test", "epoch_ms", it.rawTimestampMillis, 2.0, 30.0)
        }).normalize(LocationSample(37.0, 127.0, 0.0, 0f, 2f, 1_000, 10_000, h.now),
            LocationReadingKind.LAST_KNOWN, "point", "provider", "clock")
        val deadline = LocationFreshness.deadlineElapsedMs(cached)
        assertEquals(2_000L, deadline)
        val target = h.target(h.now, freshUntil = deadline)!!
        h.bridge.update(h.context(target, freshUntil = deadline))
        val offered = h.offers.single()
        h.now = 2_000
        assertTrue(h.planner.isCurrent(target, h.now))
        assertTrue(h.bridge.isAutomaticStoryCurrent(offered.second, reference(offered.first)))
        h.now = 2_001
        assertFalse(h.planner.isCurrent(target, h.now))
        assertFalse(h.bridge.isAutomaticStoryCurrent(offered.second, reference(offered.first)))
        assertFalse(h.bridge.isAutomaticStoryOffered(offered.second, reference(offered.first)))
    }

    @Test fun futureRegionQueriesNeverReplaceTheObservedCurrentRegion() {
        val h = Harness()
        val target = h.target(80_000)!!
        val observed = h.context(target)
        h.bridge.update(observed)
        assertEquals("b", target.regionId)
        assertEquals(listOf("b"), h.queriedRegions)
        assertEquals("a", observed.currentRegionId)
        assertEquals("story-b", h.offers.single().first.storyId)
        assertFalse(h.bridge.isAutomaticStoryCurrent(h.offers.single().second, reference(h.offers.single().first)))
        h.now = 20_000
        h.bridge.update(h.context(h.target(80_000)))
        assertTrue(h.bridge.isAutomaticStoryCurrent(h.offers.single().second, reference(h.offers.single().first)))
        assertEquals(listOf("b"), h.checkedRegions)
        assertEquals("a", observed.currentRegionId)
    }

    @Test fun etaShiftWithinTheSameVisitRetainsOneOfferAndChecksTheLatestForecast() {
        val h = Harness()
        val oldTarget = h.offer(80_000)
        val oldOffer = h.offers.single()
        h.now = 1_000
        val shifted = h.target(95_000, forecast(observed = h.now, aExit = 90_000, bExit = 160_000))!!
        assertEquals(oldTarget.planningRevision, shifted.planningRevision)
        assertEquals(95_000L, shifted.earliestStartElapsedMs)
        h.bridge.update(h.context(shifted))
        assertEquals(1, h.offers.size)
        assertTrue(h.withdrawals.isEmpty())
        assertFalse(h.bridge.isAutomaticStoryCurrent(oldOffer.second, reference(oldOffer.first)))
        assertTrue(h.planner.isCurrent(oldTarget, h.now))
        h.now = 60_000
        val approached = h.target(95_000, forecast(observed = h.now, aExit = 90_000, bExit = 160_000))!!
        h.bridge.update(h.context(approached))
        assertEquals(1, h.offers.size)
        assertTrue(h.bridge.isAutomaticStoryCurrent(oldOffer.second, reference(oldOffer.first)))
    }

    @Test fun etaChangeThatSelectsAnotherRegionRevokesOldAuthorityBeforeOfferReplacement() {
        val h = Harness()
        h.offer(80_000)
        val old = h.offers.single()
        h.now = 1_000
        val newTarget = h.target(80_000, forecast(observed = h.now, aExit = 100_000, bExit = 180_000))!!
        assertEquals("a", newTarget.regionId)
        assertFalse(h.bridge.isAutomaticStoryOffered(old.second, reference(old.first)))
        h.bridge.update(h.context(newTarget))
        assertEquals(listOf("story-b"), h.withdrawals)
        assertEquals("story-a", h.offers.last().first.storyId)
        assertFalse(h.bridge.isAutomaticStoryCurrent(old.second, reference(old.first)))
        assertTrue(h.bridge.isAutomaticStoryCurrent(h.offers.last().second, reference(h.offers.last().first)))
    }

    @Test fun samePreparedOfferLosesPlaybackPermissionWhenLatestEtaMovesTheRegionAway() {
        val h = Harness()
        val original = h.offer(80_000, forecast(aExit = 20_000))
        val offered = h.offers.single()
        assertTrue(h.bridge.isAutomaticStoryCurrent(offered.second, reference(offered.first)))
        h.now = 1_000
        val delayed = h.target(80_000, forecast(observed = h.now, aExit = 70_000, bExit = 160_000))!!
        assertEquals(original.planningRevision, delayed.planningRevision)
        assertTrue(h.planner.isCurrent(original, h.now))
        assertFalse(h.bridge.isAutomaticStoryOffered(offered.second, reference(offered.first)))
        h.bridge.update(h.context(delayed))
        assertEquals(1, h.offers.size)
        assertFalse(h.bridge.isAutomaticStoryCurrent(offered.second, reference(offered.first)))
    }

    @Test fun rerouteToTheSameNamedRegionRequiresANewOfferToken() {
        val h = Harness()
        h.offer(80_000)
        val old = h.offers.single()
        h.now = 1_000
        val rerouted = h.target(80_000, forecast(route = 2, observed = h.now), route = 2)!!
        assertEquals("b", rerouted.regionId)
        assertFalse(h.bridge.isAutomaticStoryOffered(old.second, reference(old.first)))
        h.bridge.update(h.context(rerouted, route = 2))
        assertEquals(2, h.offers.size)
        assertEquals(old.first, h.offers.last().first)
        assertNotEquals(old.second, h.offers.last().second)
        assertEquals(listOf("story-b"), h.withdrawals)
        h.bridge.storyStarted(old.second, old.first.versionId)
        h.now = 20_000
        h.bridge.update(h.context(h.target(80_000, forecast(route = 2, observed = h.now), route = 2), route = 2))
        assertTrue(h.bridge.isAutomaticStoryCurrent(h.offers.last().second, reference(old.first)))
    }

    @Test fun actualPositionDeviationChangesInventoryEvenBeforeNavigationReportsANewRoute() {
        val h = Harness()
        h.offer(80_000)
        val previous = h.offers.single()
        h.now = 1_000
        h.currentRegion = "off-route"
        val actual = h.target(80_000)!!
        assertEquals("off-route", actual.regionId)
        assertNull(actual.routeDependency)
        assertFalse(h.bridge.isAutomaticStoryOffered(previous.second, reference(previous.first)))
        h.bridge.update(h.context(actual))
        assertEquals("off-route", h.queriedRegions.last())
        assertEquals("story-off-route", h.offers.last().first.storyId)
        assertTrue(h.bridge.isAutomaticStoryCurrent(h.offers.last().second, reference(h.offers.last().first)))
    }

    @Test fun interactionDelayReplansInsteadOfRevivingTheOldCandidate() {
        val h = Harness()
        h.offer(80_000)
        val old = h.offers.single()
        h.now = 1_000
        assertNull(h.target(null, interaction = true))
        assertFalse(h.bridge.isAutomaticStoryOffered(old.second, reference(old.first)))
        h.bridge.update(h.context(null, interaction = true))
        assertEquals(listOf("story-b"), h.withdrawals)
        h.now = 70_000
        h.currentRegion = "b"
        val after = h.target(150_000)!!
        h.bridge.update(h.context(after))
        assertEquals("story-c", h.offers.last().first.storyId)
        assertFalse(h.bridge.isAutomaticStoryCurrent(old.second, reference(old.first)))
    }

    @Test fun forecastExpiryRevokesBothWorkerAndFinalMainThreadChecksWithoutAnotherUpdate() {
        val h = Harness()
        h.offer(80_000, forecast(aExit = 20_000, expiry = 5_000))
        val old = h.offers.single()
        h.now = 4_999
        assertTrue(h.bridge.isAutomaticStoryCurrent(old.second, reference(old.first)))
        h.now = 5_000
        assertFalse(h.bridge.isAutomaticStoryCurrent(old.second, reference(old.first)))
        assertFalse(h.bridge.isAutomaticStoryOffered(old.second, reference(old.first)))
    }

    @Test fun replanningAfterActualStartDoesNotStopTheCurrentStoryOrReplayItsFirstParagraph() {
        val h = Harness()
        val core = PlaybackCoordinator("trip", "session")
        var eventTime = 0L
        fun send(event: PlaybackEvent) = core.dispatch(event, ++eventTime)
        h.now = 20_000
        h.offer(80_000)
        val selected = h.offers.single()
        send(PlaybackEvent.OfferStory(selected.first, selected.second))
        val resume = send(PlaybackEvent.Play).effects.filterIsInstance<PlaybackEffect.PlanResume>().single()
        val first = send(PlaybackEvent.ResumePlanned(resume.request, resume.candidates.single()))
            .effects.filterIsInstance<PlaybackEffect.StartOutput>().single()
        assertEquals(selected.second, first.automaticOfferId)
        send(PlaybackEvent.OutputStarted(first.attempt))
        h.bridge.storyStarted(selected.second, selected.first.versionId)
        h.now = 22_000
        val nextTarget = h.target(150_000)!!
        h.bridge.update(h.context(nextTarget))
        assertTrue(h.withdrawals.isEmpty())
        assertTrue(selected.first.storyId in h.excludedSnapshots.last())
        assertEquals("story-c", h.offers.last().first.storyId)
        val nextOffer = h.offers.last()
        val pending = send(PlaybackEvent.OfferStory(nextOffer.first, nextOffer.second))
        assertTrue(pending.effects.none { it is PlaybackEffect.StopOutput })
        send(PlaybackEvent.OutputDone(first.attempt))
        val nextParagraph = send(PlaybackEvent.PlaybackCommitted(first.attempt, true))
            .effects.filterIsInstance<PlaybackEffect.StartOutput>().single()
        assertEquals(OutputReference.StoryParagraph("story-b", "version-b", "b-p2"), nextParagraph.reference)
        assertNull(nextParagraph.automaticOfferId)
    }

    @Test fun generatedParagraphDurationsFlowThroughLookaheadIntoFutureInventorySelection() {
        val current = PreparedStory("generated", "generated-version", listOf("generated-p1"))
        fun selectedRegion(generatedDuration: Long): String? {
            val h = Harness()
            val core = PlaybackCoordinator("trip", "session")
            val lookahead = PlaybackLookahead()
            var snapshot: PlaybackSnapshot? = null
            fun send(event: PlaybackEvent): PlaybackTransition = core.dispatch(event, h.now).also {
                snapshot = it.snapshot
                lookahead.onTransition(it, h.now)
            }
            val resume = send(PlaybackEvent.PlayStory(current)).effects.filterIsInstance<PlaybackEffect.PlanResume>().single()
            val output = send(PlaybackEvent.ResumePlanned(resume.request, resume.candidates.single()))
                .effects.filterIsInstance<PlaybackEffect.StartOutput>().single()
            // Only a real accepted start, rather than a queued request, starts the duration estimate.
            lookahead.outputStarted(output.attempt, output.reference, h.now)
            send(PlaybackEvent.OutputStarted(output.attempt))
            val boundary = lookahead.nextStoryStartElapsedMs(snapshot!!, h.now, 68_545) { listOf(generatedDuration) }
            assertNotNull(boundary)
            val target = h.target(boundary)!!
            h.bridge.update(h.context(target))
            return h.queriedRegions.single()
        }
        assertEquals("b", selectedRegion(15_000))
        assertEquals("c", selectedRegion(75_000))
    }

    @Test fun approachingRegionStoryStillWaitsForActualMusicCompletionAndItsCommit() {
        val h = Harness()
        h.now = 20_000
        val core = PlaybackCoordinator("trip", "session")
        var eventTime = 0L
        fun send(event: PlaybackEvent) = core.dispatch(event, ++eventTime)
        val music = PreparedMusic("music", 68_545)
        val resume = send(PlaybackEvent.PlayMusic(music)).effects.filterIsInstance<PlaybackEffect.PlanResume>().single()
        val output = send(PlaybackEvent.ResumePlanned(resume.request, null, resume.musicCandidates.single()))
            .effects.filterIsInstance<PlaybackEffect.StartOutput>().single()
        send(PlaybackEvent.OutputStarted(output.attempt))
        h.offer(80_000)
        val prepared = h.offers.single()
        assertTrue(h.bridge.isAutomaticStoryCurrent(prepared.second, reference(prepared.first)))
        val offered = send(PlaybackEvent.OfferStory(prepared.first, prepared.second))
        assertEquals(PlaybackMode.MUSIC, offered.snapshot.mode)
        assertTrue(offered.effects.none { it is PlaybackEffect.StartOutput || it is PlaybackEffect.StopOutput })
        val done = send(PlaybackEvent.OutputDone(output.attempt, music.durationMs))
        assertTrue(done.effects.none { it is PlaybackEffect.StartOutput })
        val committed = send(PlaybackEvent.PlaybackCommitted(output.attempt, true))
        val storyStart = committed.effects.filterIsInstance<PlaybackEffect.StartOutput>().single()
        assertEquals(reference(prepared.first), storyStart.reference)
        assertEquals(prepared.second, storyStart.automaticOfferId)
    }
}
