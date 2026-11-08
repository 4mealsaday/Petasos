package com.petasos.app.planning

import com.petasos.app.location.LocationInputAuthority
import com.petasos.app.location.ConfirmedRegion
import com.petasos.app.location.ConfirmedRegionAuthority
import com.petasos.app.playback.*
import com.petasos.app.runtime.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticStoryPlaybackBridgeTest {
    private val story = PreparedStory("story", "version", listOf("p1", "p2"))
    private val context = PlanningContext("region", null, 1_000, "clock", true, false, 1_000,
        locationFreshUntilElapsedMs = 11_000)
    private fun region(key: String = "region-key", selected: PreparedStory = story) =
        AutomaticStoryCandidate(selected, CandidateReason.CURRENT_REGION, "region", key)
    private fun interest(key: String = "interest-key", selected: PreparedStory = story) =
        AutomaticStoryCandidate(selected, CandidateReason.INTEREST, null, key)
    private fun reference(selected: PreparedStory = story) =
        OutputReference.StoryParagraph(selected.storyId, selected.versionId, selected.paragraphIds.first())

    @Test fun interestNeedsNoLocationPermissionRegionOrReceipt() {
        var offeredToken = ""
        var suppliedRegion: String? = "not-called"
        var checkedRegion: String? = "not-called"
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { currentRegion, _ ->
            suppliedRegion = currentRegion
            listOf(interest())
        }, output { _, token -> offeredToken = token }, { 1_000 }, { _, region -> checkedRegion = region; true })
        bridge.update(context.copy(currentRegionId = null, locationValid = false, locationReceivedElapsedMs = null))
        assertNull(suppliedRegion)
        assertTrue(bridge.isAutomaticStoryCurrent(offeredToken, reference()))
        assertNull(checkedRegion)
    }

    @Test fun currentRegionPrecedesInterestAndInvalidLocationFallsBackWithoutStoppingStartedContent() {
        val interestStory = PreparedStory("interest", "interest-version", listOf("ip"))
        val offered = mutableListOf<Pair<PreparedStory, String>>()
        val withdrawn = mutableListOf<String>()
        lateinit var bridge: AutomaticStoryPlaybackBridge
        bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ ->
            listOf(interest(selected = interestStory), region())
        }, object : PlannedStoryOutput {
            override fun offer(story: PreparedStory, offerId: String) { offered += story to offerId }
            override fun withdraw(storyId: String, versionId: String) {
                assertFalse(bridge.isAutomaticStoryCurrent(offered.last().second, reference(offered.last().first)))
                withdrawn += versionId
            }
        }, { 1_000 }, { _, _ -> true })
        bridge.update(context)
        assertEquals(story, offered.last().first)
        bridge.update(context.copy(locationValid = false))
        assertEquals(interestStory, offered.last().first)
        assertEquals(listOf(story.versionId), withdrawn)
        bridge.storyStarted(offered.last().second, interestStory.versionId)
        bridge.clear()
        assertEquals(1, withdrawn.size) // Started content is owned by the coordinator, not a remaining offer.
    }

    @Test fun freshnessIsCheckedWhenSelectingAndAgainAtTheOutputBoundary() {
        var now = 1_000L
        var token = ""
        var storeChecks = 0
        val receivedRegions = mutableListOf<String?>()
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { currentRegion, _ ->
            receivedRegions += currentRegion
            if (currentRegion == null) emptyList() else listOf(region())
        }, output { _, id -> token = id }, { now }, { _, _ -> storeChecks++; true })
        bridge.update(context.copy(locationReceivedElapsedMs = null))
        assertEquals("", token)
        bridge.update(context.copy(locationReceivedElapsedMs = 1_001))
        assertEquals("", token)
        bridge.update(context)
        now = 11_000
        assertTrue(bridge.isAutomaticStoryCurrent(token, reference()))
        now = 11_001
        assertFalse(bridge.isAutomaticStoryCurrent(token, reference()))
        assertEquals(1, storeChecks)
        bridge.update(context)
        assertEquals(listOf(null, null, "region", null), receivedRegions)
    }

    @Test fun interestFinalCheckUsesNullRegionIfLocationAgedWhileWaitingForMusic() {
        var now = 1_000L
        var token = ""
        var checkedRegion: String? = "unset"
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> listOf(interest()) },
            output { _, id -> token = id }, { now }, { _, region -> checkedRegion = region; true })
        bridge.update(context)
        now = 11_001
        assertTrue(bridge.isAutomaticStoryCurrent(token, reference()))
        assertNull(checkedRegion)
    }

    @Test fun cachedMeasurementDeadlineRevokesBothOutputChecksWithoutASecondLocationCallback() {
        var now = 1_000L
        var token = ""
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> listOf(region()) },
            output { _, id -> token = id }, { now }, { _, _ -> true })
        bridge.update(context.copy(locationFreshUntilElapsedMs = 1_001))
        now = 1_001
        assertTrue(bridge.isAutomaticStoryCurrent(token, reference()))
        assertTrue(bridge.isAutomaticStoryOffered(token, reference()))
        now = 1_002
        assertFalse(bridge.isAutomaticStoryCurrent(token, reference()))
        assertFalse(bridge.isAutomaticStoryOffered(token, reference()))
    }

    @Test fun measurementExpiryDuringEvidenceLookupCannotAuthorizeTheQueuedStart() {
        var now = 1_000L
        var token = ""
        var checks = 0
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> listOf(region()) },
            output { _, id -> token = id }, { now }, { _, _ -> checks++; now = 1_002; true })
        bridge.update(context.copy(locationFreshUntilElapsedMs = 1_001))
        assertFalse(bridge.isAutomaticStoryCurrent(token, reference()))
        assertEquals(1, checks)
        assertFalse(bridge.isAutomaticStoryOffered(token, reference()))
    }

    @Test fun absentMeasurementDeadlineBlocksPredictedRegionEvenIfTargetAuthorityReturnsTrue() {
        var offers = 0
        val regions = mutableListOf<String?>()
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { selected, _ ->
            regions += selected
            listOf(region())
        }, output { _, _ -> offers++ }, { 1_000 }, { _, _ -> true },
            targetCurrent = { true }, targetPlayable = { true })
        val target = RegionalStoryTarget("session", "clock", 1, "region", 1, "visit", 1_000, 20_000)
        bridge.update(context.copy(currentRegionId = null, routeRevision = 1, regionalTarget = target,
            locationFreshUntilElapsedMs = null))
        assertEquals(listOf<String?>(null), regions)
        assertEquals(0, offers)
        bridge.update(context.copy(currentRegionId = null, routeRevision = 1, regionalTarget = target))
        assertEquals("region", regions.last())
        assertEquals(1, offers)
    }

    @Test fun missingMeasurementDeadlineStillPermitsIndependentInterestSelection() {
        var token = ""
        var checkedRegion: String? = "unset"
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> listOf(region(), interest()) },
            output { _, id -> token = id }, { 1_000 }, { _, region -> checkedRegion = region; true })
        bridge.update(context.copy(locationFreshUntilElapsedMs = null))
        assertTrue(bridge.isAutomaticStoryCurrent(token, reference()))
        assertNull(checkedRegion)
    }

    @Test fun ingressRevocationClosesBothFinalChecksAndAnotherCallbackDoesNotReviveOldContext() {
        val authority = LocationInputAuthority()
        val receipt = authority.record(1_000, admissible = true)
        val snapshot = context.copy(locationAuthority = { authority.canUse(receipt, 1_000) })
        val offers = mutableListOf<String>()
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> listOf(region(), interest()) },
            output { _, id -> offers += id }, { 1_000 }, { _, _ -> true })
        bridge.update(snapshot)
        val old = offers.single()
        assertTrue(bridge.isAutomaticStoryCurrent(old, reference()))
        authority.invalidate()
        assertFalse(bridge.isAutomaticStoryCurrent(old, reference()))
        assertFalse(bridge.isAutomaticStoryOffered(old, reference()))
        authority.record(1_000, admissible = true)
        assertFalse(bridge.isAutomaticStoryCurrent(old, reference()))
        assertFalse(bridge.isAutomaticStoryOffered(old, reference()))
        bridge.update(snapshot) // The revoked location still permits an independent interest.
        assertEquals(2, offers.size)
        assertTrue(bridge.isAutomaticStoryCurrent(offers.last(), reference()))
    }

    @Test fun ingressRevocationDuringEvidenceLookupWinsOverItsSuccessfulResult() {
        val authority = LocationInputAuthority()
        val receipt = authority.record(1_000, admissible = true)
        var token = ""
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> listOf(region()) },
            output { _, id -> token = id }, { 1_000 }, { _, _ -> authority.invalidate(); true })
        bridge.update(context.copy(locationAuthority = { authority.canUse(receipt, 1_000) }))
        assertFalse(bridge.isAutomaticStoryCurrent(token, reference()))
        assertFalse(bridge.isAutomaticStoryOffered(token, reference()))
    }

    @Test fun throwingLiveAuthorityDoesNotPermitARegionOrBlockAnIndependentInterest() {
        var token = ""
        val checkedReasons = mutableListOf<CandidateReason>()
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> listOf(region(), interest()) },
            output { _, id -> token = id }, { 1_000 }, { candidate, _ -> checkedReasons += candidate.reason; true })
        bridge.update(context.copy(locationAuthority = { error("test-input-unavailable") }))
        assertTrue(bridge.isAutomaticStoryCurrent(token, reference()))
        assertEquals(listOf(CandidateReason.INTEREST), checkedReasons)
    }

    @Test fun changedSnapshotReplacesPermitAndLateStartedCannotConsumeReplacement() {
        var key = "first"
        val tokens = mutableListOf<String>()
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> listOf(interest(key)) },
            output { _, id -> tokens += id }, { 1_000 }, { _, _ -> true })
        bridge.update(context)
        key = "second"
        bridge.update(context)
        assertEquals(2, tokens.size)
        assertNotEquals(tokens[0], tokens[1])
        assertFalse(bridge.isAutomaticStoryCurrent(tokens[0], reference()))
        assertFalse(bridge.isAutomaticStoryOffered(tokens[0], reference()))
        assertTrue(bridge.isAutomaticStoryOffered(tokens[1], reference()))
        bridge.storyStarted(tokens[0], story.versionId)
        bridge.update(context)
        assertEquals(2, tokens.size)
        assertTrue(bridge.isAutomaticStoryCurrent(tokens[1], reference()))
        bridge.storyStarted(tokens[1], story.versionId)
        key = "third"
        bridge.update(context)
        assertEquals(2, tokens.size)
    }

    @Test fun sessionExclusionsPrecedeSupplierCapAndPermitTheSixtyFifthUnheardStory() {
        val available = (0..64).map { index -> interest(selected =
            PreparedStory("story-$index", "version-$index", listOf("paragraph-$index"))) }
        val exclusionSnapshots = mutableListOf<Set<String>>()
        var offered: PreparedStory? = null
        var token = ""
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, excluded ->
            exclusionSnapshots += excluded
            available.filter { it.story.storyId !in excluded }.take(64)
        }, output { story, id -> offered = story; token = id }, { 1_000 }, { _, _ -> true })
        repeat(64) { index ->
            bridge.update(context)
            assertEquals("story-$index", offered?.storyId)
            bridge.storyStarted(token, offered!!.versionId)
            // No completed history is added: this models interruption before the first paragraph finishes.
        }
        bridge.update(context)
        assertEquals("story-64", offered?.storyId)
        assertEquals(64, exclusionSnapshots.last().size)
        assertTrue(exclusionSnapshots.first().isEmpty()) // The supplier received copies, never the mutable live set.
        assertTrue(runCatching { (exclusionSnapshots.last() as MutableSet<String>).clear() }.isFailure)
    }

    @Test fun ingressRevocationIsReadOnlyAndSameCandidateReturnsOnlyWithANewToken() {
        val tokens = mutableListOf<String>()
        var reads = 0
        var checks = 0
        var withdrawals = 0
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> reads++; listOf(interest()) },
            object : PlannedStoryOutput {
                override fun offer(story: PreparedStory, offerId: String) { tokens += offerId }
                override fun withdraw(storyId: String, versionId: String) { withdrawals++ }
            }, { 1_000 }, { _, _ -> checks++; true })
        bridge.update(context)
        val old = tokens.single()
        bridge.revokeAuthority()
        assertEquals(1, reads)
        assertEquals(0, checks)
        assertEquals(0, withdrawals)
        assertFalse(bridge.isAutomaticStoryOffered(old, reference()))
        bridge.storyStarted(old, story.versionId)
        bridge.update(context)
        assertEquals(2, tokens.size)
        assertNotEquals(old, tokens.last())
        assertFalse(bridge.isAutomaticStoryCurrent(old, reference()))
        assertTrue(bridge.isAutomaticStoryOffered(tokens.last(), reference()))
        bridge.storyStarted(old, story.versionId) // A late callback cannot consume the replacement.
        bridge.update(context)
        assertEquals(2, tokens.size)
        bridge.storyStarted(tokens.last(), story.versionId)
        bridge.revokeAuthority()
        bridge.update(context)
        assertEquals(2, tokens.size)
        assertEquals(1, withdrawals)
    }

    @Test fun revokingEligibilityAfterActualStartDoesNotInterruptFollowingParagraph() {
        var time = 0L
        val core = PlaybackCoordinator("trip", "session")
        fun send(event: PlaybackEvent) = core.dispatch(event, ++time)
        var eligible = true
        var token = ""
        var withdrawals = 0
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ ->
            if (eligible) listOf(interest()) else emptyList()
        }, object : PlannedStoryOutput {
            override fun offer(story: PreparedStory, offerId: String) {
                token = offerId
                send(PlaybackEvent.OfferStory(story, offerId))
            }
            override fun withdraw(storyId: String, versionId: String) { withdrawals++ }
        }, { 1_000 }, { _, _ -> eligible })
        bridge.update(context)
        val plan = send(PlaybackEvent.Play).effects.filterIsInstance<PlaybackEffect.PlanResume>().single()
        val first = send(PlaybackEvent.ResumePlanned(plan.request, plan.candidates.single()))
            .effects.filterIsInstance<PlaybackEffect.StartOutput>().single()
        assertEquals(token, first.automaticOfferId)
        send(PlaybackEvent.OutputStarted(first.attempt))
        bridge.storyStarted(token, story.versionId)
        bridge.revokeAuthority()
        eligible = false
        bridge.update(context)
        assertEquals(0, withdrawals)
        send(PlaybackEvent.OutputDone(first.attempt))
        val next = send(PlaybackEvent.PlaybackCommitted(first.attempt, true))
            .effects.filterIsInstance<PlaybackEffect.StartOutput>().single()
        assertEquals(reference().copy(paragraphId = "p2"), next.reference)
        assertNull(next.automaticOfferId)
    }

    @Test fun explicitStartedWithdrawsSameAutomaticCandidateSoNextDoesNotReplayIt() {
        var time = 0L
        val core = PlaybackCoordinator("trip", "session")
        fun send(event: PlaybackEvent) = core.dispatch(event, ++time)
        var token = ""
        var withdrawals = 0
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, excluded ->
            if (story.storyId in excluded) emptyList() else listOf(interest())
        }, object : PlannedStoryOutput {
            override fun offer(story: PreparedStory, offerId: String) {
                token = offerId
                send(PlaybackEvent.OfferStory(story, offerId))
            }
            override fun withdraw(storyId: String, versionId: String) {
                withdrawals++
                send(PlaybackEvent.ClearOfferedStory(storyId, versionId))
            }
        }, { 1_000 }, { _, _ -> true })
        fun playExplicit(): PlaybackEffect.StartOutput {
            val plan = send(PlaybackEvent.PlayStory(story)).effects.filterIsInstance<PlaybackEffect.PlanResume>().single()
            return send(PlaybackEvent.ResumePlanned(plan.request, plan.candidates.single()))
                .effects.filterIsInstance<PlaybackEffect.StartOutput>().single()
        }
        bridge.update(context)
        val first = playExplicit()
        assertNull(first.automaticOfferId)
        send(PlaybackEvent.OutputStarted(first.attempt))
        bridge.storyStartedWithoutOffer(story.storyId)
        assertEquals(1, withdrawals)
        assertFalse(bridge.isAutomaticStoryOffered(token, reference()))
        bridge.update(context)
        send(PlaybackEvent.Next)
        send(PlaybackEvent.OutputStopped(first.attempt))
        val skipped = send(PlaybackEvent.PlaybackCommitted(first.attempt, true))
        assertEquals(PlaybackMode.IDLE, skipped.snapshot.mode)
        assertTrue(skipped.effects.none { it is PlaybackEffect.StartOutput })
        val replay = playExplicit()
        assertEquals(reference(), replay.reference)
        assertNull(replay.automaticOfferId)
        assertNotEquals(first.attempt, replay.attempt)
    }

    @Test fun explicitStableStoryExclusionCoversOtherVersionsButPreservesAnUnrelatedOffer() {
        val other = PreparedStory("other", "other-version", listOf("other-p"))
        var token = ""
        var offers = 0
        var withdrawals = 0
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ ->
            listOf(interest(selected = other), interest(selected = story.copy(versionId = "new-version")))
        }, object : PlannedStoryOutput {
            override fun offer(story: PreparedStory, offerId: String) { token = offerId; offers++ }
            override fun withdraw(storyId: String, versionId: String) { withdrawals++ }
        }, { 1_000 }, { _, _ -> true })
        bridge.update(context)
        bridge.storyStartedWithoutOffer(story.storyId)
        assertEquals(0, withdrawals)
        assertTrue(bridge.isAutomaticStoryOffered(token, reference(other)))
        bridge.storyStarted(token, other.versionId)
        bridge.update(context)
        assertEquals(1, offers)
        assertFalse(bridge.isAutomaticStoryOffered(token, reference()))
        assertEquals(0, withdrawals)
    }

    @Test fun startedStableStoryCannotReturnUnderNewVersionReasonOrEligibilitySnapshot() {
        var candidate = interest()
        var token = ""
        var offers = 0
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> listOf(candidate) },
            output { _, id -> token = id; offers++ }, { 1_000 }, { _, _ -> true })
        bridge.update(context)
        bridge.storyStarted(token, story.versionId)
        candidate = region("changed", story.copy(versionId = "new-version", paragraphIds = listOf("new-p")))
        bridge.update(context)
        bridge.clear()
        bridge.update(context)
        assertEquals(1, offers)
    }

    @Test fun currentnessDenialOrFailureCannotAuthorizeButWrongReferencesDoNotQueryStore() {
        var permitted = true
        var throws = false
        var checks = 0
        var token = ""
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> listOf(interest()) },
            output { _, id -> token = id }, { 1_000 }, { _, _ ->
                checks++
                if (throws) error("fixed-test-failure")
                permitted
            })
        bridge.update(context)
        assertFalse(bridge.isAutomaticStoryCurrent("wrong-offer", reference()))
        assertFalse(bridge.isAutomaticStoryCurrent(token, reference().copy(storyId = "wrong-story")))
        assertFalse(bridge.isAutomaticStoryCurrent(token, reference().copy(versionId = "wrong-version")))
        assertFalse(bridge.isAutomaticStoryCurrent(token, reference().copy(paragraphId = "wrong-paragraph")))
        assertEquals(0, checks)
        assertTrue(bridge.isAutomaticStoryCurrent(token, reference()))
        permitted = false
        assertFalse(bridge.isAutomaticStoryCurrent(token, reference()))
        throws = true
        assertFalse(bridge.isAutomaticStoryCurrent(token, reference()))
        assertEquals(3, checks)
    }

    @Test fun authorityRevokedDuringCurrentnessReadCannotReachSdk() {
        var token = ""
        lateinit var bridge: AutomaticStoryPlaybackBridge
        bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> listOf(interest()) },
            output { _, id -> token = id }, { 1_000 }, { _, _ -> bridge.clear(); true })
        bridge.update(context)
        assertFalse(bridge.isAutomaticStoryCurrent(token, reference()))
    }

    @Test fun cheapFinalGuardDoesNotReadDatabaseAndRejectsRevokedInputAndWrongOffer() {
        var token = ""
        var storeChecks = 0
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> listOf(interest()) },
            output { _, id -> token = id }, { 1_000 }, { _, _ -> storeChecks++; true })
        bridge.update(context.copy(locationValid = false, locationReceivedElapsedMs = null))
        assertTrue(bridge.isAutomaticStoryOffered(token, reference()))
        assertFalse(bridge.isAutomaticStoryOffered("wrong-offer", reference()))
        assertFalse(bridge.isAutomaticStoryOffered(token, reference().copy(paragraphId = "wrong-paragraph")))
        bridge.update(context.copy(userSpeaking = true))
        assertFalse(bridge.isAutomaticStoryOffered(token, reference()))
        bridge.update(context)
        assertTrue(bridge.isAutomaticStoryOffered(token, reference()))
        bridge.clear()
        assertFalse(bridge.isAutomaticStoryOffered(token, reference()))
        assertEquals(0, storeChecks)
    }

    @Test fun cheapGuardAndSlowEvidenceLookupBothRejectAgedRegion() {
        var token = ""
        var now = 1_000L
        var storeChecks = 0
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> listOf(region()) },
            output { _, id -> token = id }, { now }, { _, _ -> storeChecks++; now = 11_001; true })
        bridge.update(context)
        assertTrue(bridge.isAutomaticStoryOffered(token, reference()))
        assertEquals(0, storeChecks)
        assertFalse(bridge.isAutomaticStoryCurrent(token, reference()))
        assertEquals(1, storeChecks)
        assertFalse(bridge.isAutomaticStoryOffered(token, reference()))
        assertEquals(1, storeChecks)
    }

    @Test fun removedCandidateCannotSurviveInPlannerCacheAndFreshEligibilityCanReturn() {
        var permitted = true
        var offers = 0
        var withdrawals = 0
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> if (permitted) listOf(interest()) else emptyList() },
            object : PlannedStoryOutput {
                override fun offer(story: PreparedStory, offerId: String) { offers++ }
                override fun withdraw(storyId: String, versionId: String) { withdrawals++ }
            }, { 1_000 }, { _, _ -> permitted })
        bridge.update(context)
        permitted = false
        bridge.update(context)
        bridge.update(context)
        assertEquals(1, offers)
        assertEquals(1, withdrawals)
        permitted = true
        bridge.update(context)
        assertEquals(2, offers)
    }

    @Test fun speakingWithdrawsThenReoffersWhileRouteChangesDoNotExpireGenericInterest() {
        val tokens = mutableListOf<String>()
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> listOf(interest()) },
            output { _, id -> tokens += id }, { 1_000 }, { _, _ -> true })
        bridge.update(context)
        bridge.update(context.copy(userSpeaking = true))
        assertFalse(bridge.isAutomaticStoryCurrent(tokens.single(), reference()))
        bridge.update(context.copy(routeRevision = 5))
        assertEquals(2, tokens.size)
        assertTrue(bridge.isAutomaticStoryCurrent(tokens.last(), reference()))
        bridge.update(context.copy(clockScopeId = "another-clock"))
        assertFalse(bridge.isAutomaticStoryCurrent(tokens.last(), reference()))
    }

    @Test fun supplierFailureRevokesPreviouslyOfferedAuthority() {
        var throws = false
        var token = ""
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ ->
            if (throws) error("fixed-test-failure")
            listOf(interest())
        }, output { _, id -> token = id }, { 1_000 }, { _, _ -> true })
        bridge.update(context)
        throws = true
        assertTrue(runCatching { bridge.update(context) }.isFailure)
        assertFalse(bridge.isAutomaticStoryCurrent(token, reference()))
    }

    @Test fun overlappingReasonsForOneStableStoryOfferOnlyTheRegionCandidate() {
        var token = ""
        var offers = 0
        var checkedReason: CandidateReason? = null
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> listOf(interest(), region()) },
            output { _, id -> token = id; offers++ }, { 1_000 }, { candidate, _ -> checkedReason = candidate.reason; true })
        bridge.update(context)
        assertTrue(bridge.isAutomaticStoryCurrent(token, reference()))
        assertEquals(CandidateReason.CURRENT_REGION, checkedReason)
        bridge.storyStarted(token, story.versionId)
        bridge.update(context.copy(locationValid = false))
        assertEquals(1, offers)
    }

    @Test fun candidateOwnsParagraphIdsAndDoesNotRevealContentOrEligibilityInDiagnostics() {
        val ids = mutableListOf("private-paragraph-id")
        val candidate = AutomaticStoryCandidate(PreparedStory("private-story", "private-version", ids),
            CandidateReason.CURRENT_REGION, "private-region", "private-eligibility")
        ids.clear()
        assertEquals(listOf("private-paragraph-id"), candidate.story.paragraphIds)
        assertTrue(runCatching { (candidate.story.paragraphIds as MutableList<String>).clear() }.isFailure)
        assertFalse(candidate.toString().contains("private"))
        assertTrue(runCatching { AutomaticStoryCandidate(story, CandidateReason.LANDMARK, "region", "key") }.isFailure)
        assertTrue(runCatching { AutomaticStoryCandidate(story, CandidateReason.INTEREST, "region", "key") }.isFailure)
        assertTrue(runCatching { AutomaticStoryCandidate(story, CandidateReason.CURRENT_REGION, null, "key") }.isFailure)
    }

    @Test fun resumePlannerCannotInventTargetsAndRechecksPermissionAfterDelay() {
        var permitted = true
        lateinit var delivered: (RuntimeResumeSelection) -> Unit
        val delegate = object : RuntimeResumePlanner {
            override fun plan(effect: PlaybackEffect.PlanResume, callback: (RuntimeResumeSelection) -> Unit): RuntimeCancellation {
                delivered = callback
                return RuntimeCancellation { }
            }
        }
        val planner = ContentCheckedResumePlanner(delegate, { permitted }, { true })
        val checkpoint = StoryCheckpoint(story, 1)
        val effect = PlaybackEffect.PlanResume(OperationToken("session", 0, "request"), listOf(checkpoint))
        var result: RuntimeResumeSelection? = null
        planner.plan(effect) { result = it }
        delivered(RuntimeResumeSelection(StoryCheckpoint(story, 0)))
        assertEquals(RuntimeResumeSelection(), result)
        planner.plan(effect) { result = it }
        permitted = false
        delivered(RuntimeResumeSelection(checkpoint))
        assertEquals(RuntimeResumeSelection(), result)
    }

    @Test fun cancelledResumePlanCannotDeliverLaterCallback() {
        lateinit var delivered: (RuntimeResumeSelection) -> Unit
        var cancelled = false
        val planner = ContentCheckedResumePlanner(object : RuntimeResumePlanner {
            override fun plan(effect: PlaybackEffect.PlanResume, callback: (RuntimeResumeSelection) -> Unit): RuntimeCancellation {
                delivered = callback
                return RuntimeCancellation { cancelled = true }
            }
        }, { true }, { true })
        var calls = 0
        val cancellation = planner.plan(PlaybackEffect.PlanResume(OperationToken("session", 0, "request"), listOf(StoryCheckpoint(story, 0)))) { calls++ }
        cancellation.cancel()
        delivered(RuntimeResumeSelection(StoryCheckpoint(story, 0)))
        assertTrue(cancelled)
        assertEquals(0, calls)
    }

    private fun output(onOffer: (PreparedStory, String) -> Unit) = object : PlannedStoryOutput {
        override fun offer(story: PreparedStory, offerId: String) { onOffer(story, offerId) }
        override fun withdraw(storyId: String, versionId: String) = Unit
    }

    @Test fun finalRegionEvidenceCheckRejectsRevokedConfirmationAndNeverQueriesStorageOnCheapPath() {
        var evidenceCurrent = true
        var evidenceReads = 0
        var token = ""
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> listOf(region()) },
            output { _, id -> token = id }, { 1_000 }, { _, _ -> true })
        val checked = context.copy(currentRegionEvidenceCurrent = { evidenceReads++; evidenceCurrent })
        bridge.update(checked)
        assertTrue(bridge.isAutomaticStoryOffered(token, reference()))
        assertEquals(0, evidenceReads)
        assertTrue(bridge.isAutomaticStoryCurrent(token, reference()))
        assertEquals(1, evidenceReads)
        evidenceCurrent = false
        assertFalse(bridge.isAutomaticStoryCurrent(token, reference()))
        assertEquals(2, evidenceReads)
        assertFalse(bridge.isAutomaticStoryOffered(token, reference()))
        assertEquals(2, evidenceReads)
    }

    @Test fun staleRegionIdCannotMintANewCandidateAfterReviewFailureButInterestRemainsAvailable() {
        val proof = ConfirmedRegionAuthority(ConfirmedRegion("region", "revision", "a".repeat(64), "point"))
        val interestStory = PreparedStory("interest", "interest-version", listOf("interest-p"))
        val offers = mutableListOf<Pair<PreparedStory, String>>()
        val regions = mutableListOf<String?>()
        var metadataVersion = "a".repeat(64)
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { suppliedRegion, _ ->
            regions += suppliedRegion
            listOf(region(metadataVersion), interest(selected = interestStory))
        }, output { selected, id -> offers += selected to id }, { 1_000 }, { _, _ -> true })
        val checked = context.copy(currentRegionAuthority = proof::isCurrent,
            currentRegionEvidenceCurrent = { proof.recheck { it.policySourceKey == metadataVersion } })
        bridge.update(checked)
        val first = offers.single()
        assertEquals(story, first.first)
        assertTrue(bridge.isAutomaticStoryCurrent(first.second, reference()))
        metadataVersion = "b".repeat(64)
        assertFalse(bridge.isAutomaticStoryCurrent(first.second, reference()))
        // A fresh candidate fingerprint cannot restore the old region proof.
        bridge.update(checked)
        assertEquals(listOf("region", null), regions)
        assertEquals(interestStory, offers.last().first)
        assertTrue(bridge.isAutomaticStoryCurrent(offers.last().second, reference(interestStory)))
        assertTrue(checked.locationValid)
    }

    @Test fun interestNeverConsultsTheConfirmedRegionEvidencePort() {
        var token = ""
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, _ -> listOf(interest()) },
            output { _, id -> token = id }, { 1_000 }, { _, _ -> true })
        bridge.update(context.copy(currentRegionAuthority = { false },
            currentRegionEvidenceCurrent = { error("region storage must not be consulted") }))
        assertTrue(bridge.isAutomaticStoryCurrent(token, reference()))
        assertTrue(bridge.isAutomaticStoryOffered(token, reference()))
    }
}
