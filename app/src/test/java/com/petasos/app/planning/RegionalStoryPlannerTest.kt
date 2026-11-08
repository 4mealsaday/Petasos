package com.petasos.app.planning

import com.petasos.app.location.LocationInputAuthority
import org.junit.Assert.*
import org.junit.Test

class RegionalStoryPlannerTest {
    private fun planner(horizon: Long = 600_000) = RegionalStoryPlanner("session", "clock",
        maximumPlanningHorizonMs = horizon)

    private fun forecast(
        route: Long = 1,
        observed: Long = 0,
        validUntil: Long = 300_000,
        passages: List<RegionPassage> = listOf(
            RegionPassage("a-1", "a", 0, 50_000),
            RegionPassage("b-1", "b", 50_000, 120_000),
            RegionPassage("c-1", "c", 120_000, 250_000),
        ),
    ) = ReviewedRouteForecast("session", "clock", route, observed, validUntil, passages)

    private fun input(
        now: Long = 0,
        start: Long? = now,
        routeForecast: ReviewedRouteForecast? = forecast(),
    ) = RegionalPlanningInput("session", "clock", 1, now, now, true,
        routeForecast?.passages?.firstOrNull { it.entersAtElapsedMs <= now && now < it.exitsAtElapsedMs }?.regionId ?: "a",
        false, start, routeForecast, locationFreshUntilElapsedMs = if (now > Long.MAX_VALUE - 10_000) Long.MAX_VALUE else now + 10_000)

    @Test fun generatedStoryDurationAndRequiredMusicDetermineTheNextRegion() {
        val planner = planner()
        val first = planner.update(input())!!
        assertEquals("a", first.regionId)
        // The same route produces a different next target after the actual generated duration is known.
        val shortStoryThenMusic = planner.update(input(start = 15_000 + 68_545))!!
        assertEquals("b", shortStoryThenMusic.regionId)
        val longStoryThenMusic = planner.update(input(start = 75_000 + 68_545))!!
        assertEquals("c", longStoryThenMusic.regionId)
        assertTrue(first.planningRevision < shortStoryThenMusic.planningRevision)
        assertTrue(shortStoryThenMusic.planningRevision < longStoryThenMusic.planningRevision)
        assertFalse(planner.isCurrent(first, 0))
        assertFalse(planner.isCurrent(shortStoryThenMusic, 0))
    }

    @Test fun firstStoryCanPrepareAnUpcomingRegionWithoutClaimingItIsTheCurrentRegion() {
        val forecast = forecast(passages = listOf(RegionPassage("b-1", "b", 20_000, 70_000)))
        val planner = planner()
        val target = planner.update(input(start = 10_000, routeForecast = forecast))!!
        assertEquals("b", target.regionId)
        assertEquals(10_000L, target.earliestStartElapsedMs)
        assertEquals(1L, target.routeDependency)
        assertEquals("a", input().currentRegionId)
        // A permit allows preparation while the target is still ahead; it is not an arrival event.
        assertTrue(planner.isCurrent(target, 0))
        assertTrue(planner.isPlayableNow(target, 0))
    }

    @Test fun interactionWithdrawsTheOldPlanAndDelaySelectsFromTheNewActualTime() {
        val planner = planner()
        val before = planner.update(input(start = 85_000))!!
        assertEquals("b", before.regionId)
        assertNull(planner.update(input(now = 1_000, start = null).copy(interactionActive = true)))
        assertFalse(planner.isCurrent(before, 1_000))
        val after = planner.update(input(now = 70_000, start = 150_000))!!
        assertEquals("c", after.regionId)
        assertTrue(after.planningRevision > before.planningRevision)
    }

    @Test fun returningToTheSameTargetAfterAnInterruptionDoesNotReviveOldGeneration() {
        val planner = planner()
        val before = planner.update(input(start = 80_000))!!
        planner.update(input(now = 500).copy(interactionActive = true))
        val after = planner.update(input(now = 1_000, start = 85_000))!!
        assertEquals(before.visitId, after.visitId)
        assertTrue(after.planningRevision > before.planningRevision)
        assertFalse(planner.isCurrent(before, 1_000))
        assertTrue(planner.isCurrent(after, 1_000))
    }

    @Test fun etaShiftWithinSameVisitUpdatesTimesWithoutCancellingUsefulGeneration() {
        val planner = planner()
        val before = planner.update(input(start = 80_000))!!
        val delayed = forecast(observed = 1_000, validUntil = 320_000, passages = listOf(
            RegionPassage("a-1", "a", 0, 90_000),
            RegionPassage("b-1", "b", 90_000, 160_000),
        ))
        val after = planner.update(input(now = 1_000, start = 95_000, routeForecast = delayed))!!
        assertEquals(before.planningRevision, after.planningRevision)
        assertEquals(95_000L, after.earliestStartElapsedMs)
        assertEquals(160_000L, after.expiresAtElapsedMs)
        assertTrue(planner.isCurrent(before, 1_000))
    }

    @Test fun sameRouteEtaRevisionCanMoveTheTargetToAnotherRegion() {
        val planner = planner()
        val before = planner.update(input(start = 80_000))!!
        val delayed = forecast(observed = 1_000, passages = listOf(
            RegionPassage("a-1", "a", 0, 100_000),
            RegionPassage("b-1", "b", 100_000, 180_000),
        ))
        val after = planner.update(input(now = 1_000, start = 80_000, routeForecast = delayed))!!
        assertEquals("a", after.regionId)
        assertEquals(before.routeDependency, after.routeDependency)
        assertTrue(after.planningRevision > before.planningRevision)
        assertFalse(planner.isCurrent(before, 1_000))
    }

    @Test fun routeChangeInvalidatesOldGenerationEvenIfRegionAndVisitIdsRemainEqual() {
        val planner = planner()
        val before = planner.update(input(start = 80_000))!!
        val after = planner.update(input(now = 1_000, start = 80_000, routeForecast = forecast(route = 2))
            .copy(routeRevision = 2))!!
        assertEquals(before.regionId, after.regionId)
        assertEquals(2L, after.routeDependency)
        assertFalse(planner.isCurrent(before, 1_000))
        assertTrue(planner.isCurrent(after, 1_000))
    }

    @Test fun confirmedActualDeviationRejectsAnUnchangedRouteForecastAndUsesTheActualRegion() {
        val planner = planner()
        val predicted = planner.update(input(start = 80_000))!!
        assertEquals("b", predicted.regionId)
        val actual = planner.update(input(now = 1_000, start = 80_000).copy(currentRegionId = "off-route"))!!
        assertEquals("off-route", actual.regionId)
        assertNull(actual.routeDependency)
        assertNull(actual.visitId)
        assertTrue(actual.planningRevision > predicted.planningRevision)
        assertFalse(planner.isCurrent(predicted, 1_000))
        assertTrue(planner.isPlayableNow(actual, 1_000))
    }

    @Test fun trafficDelayDoesNotClaimThePredictedRegionWasReachedWhenActualRegionDisagrees() {
        val planner = planner()
        val predicted = planner.update(input(start = 150_000))!!
        assertEquals("c", predicted.regionId)
        val delayed = planner.update(input(now = 60_000, start = 150_000).copy(currentRegionId = "a"))!!
        assertEquals("a", delayed.regionId)
        assertNull(delayed.routeDependency)
        assertFalse(planner.isCurrent(predicted, 60_000))
        // Only a fresh projection consistent with the observed position restores route prediction.
        val corrected = forecast(observed = 61_000, passages = listOf(
            RegionPassage("a-1", "a", 0, 100_000),
            RegionPassage("b-1", "b", 100_000, 180_000),
        ))
        val replanned = planner.update(input(now = 61_000, start = 150_000, routeForecast = corrected))!!
        assertEquals("b", replanned.regionId)
        assertEquals(1L, replanned.routeDependency)
        assertTrue(replanned.planningRevision > delayed.planningRevision)
    }

    @Test fun delayedStartSkipsPassedWindowsAndExactExitBelongsToTheNextPassage() {
        val planner = planner()
        val atBoundary = planner.update(input(now = 50_000, start = 30_000))!!
        assertEquals("b", atBoundary.regionId)
        assertEquals(50_000L, atBoundary.earliestStartElapsedMs)
        val later = planner.update(input(now = 120_000, start = 80_000))!!
        assertEquals("c", later.regionId)
        assertFalse(planner.isCurrent(atBoundary, 120_000))
    }

    @Test fun passageExpiryRejectsAQueuedGenerationEvenWithoutAnotherPositionCallback() {
        val planner = RegionalStoryPlanner("session", "clock", maximumLocationAgeMs = 100_000)
        val target = planner.update(input().copy(locationFreshUntilElapsedMs = 100_000))!!
        assertTrue(planner.isCurrent(target, 49_999))
        assertFalse(planner.isCurrent(target, 50_000))
    }

    @Test fun forecastExpiryRevokesAuthorityAndFallsBackOnlyToConfirmedCurrentRegion() {
        val planner = planner()
        val source = forecast(validUntil = 5_000)
        val target = planner.update(input(start = 80_000, routeForecast = source))!!
        assertEquals("b", target.regionId)
        assertTrue(planner.isCurrent(target, 4_999))
        assertFalse(planner.isCurrent(target, 5_000))
        val fallback = planner.update(input(now = 5_000, start = 80_000, routeForecast = source))!!
        assertEquals("a", fallback.regionId)
        assertNull(fallback.routeDependency)
        assertNull(fallback.visitId)
        assertFalse(planner.isCurrent(target, 5_000))
    }

    @Test fun freshLocationIsRequiredAtPlanningAndAtFinalUse() {
        val planner = planner()
        val target = planner.update(input())!!
        assertTrue(planner.isCurrent(target, 10_000))
        assertFalse(planner.isCurrent(target, 10_001))
        assertNull(planner.update(input(now = 10_001).copy(locationReceivedElapsedMs = 0)))
        assertNull(planner.update(input(now = 10_002).copy(locationValid = false)))
        assertNull(planner.update(input(now = 10_003).copy(locationReceivedElapsedMs = null)))
        assertNull(planner.update(input(now = 10_004).copy(locationReceivedElapsedMs = 10_005)))
    }

    @Test fun recentReceiptCannotRenewAnOldMeasurementDuringGenerationOrPlayback() {
        val planner = planner()
        val accepted = input(now = 1_000).copy(locationFreshUntilElapsedMs = 1_001)
        val target = planner.update(accepted)!!
        assertTrue(planner.isCurrent(target, 1_001))
        assertTrue(planner.isPlayableNow(target, 1_001))
        assertFalse(planner.isCurrent(target, 1_002))
        assertFalse(planner.isPlayableNow(target, 1_002))
        assertNull(planner.update(accepted.copy(elapsedMs = 1_002)))
    }

    @Test fun unknownMeasurementDeadlineFailsClosedAndLongDeadlineCannotBypassReceiptCap() {
        val planner = planner()
        assertNull(planner.update(input().copy(locationFreshUntilElapsedMs = null)))
        val target = planner.update(input().copy(locationFreshUntilElapsedMs = Long.MAX_VALUE))!!
        assertTrue(planner.isCurrent(target, 10_000))
        assertFalse(planner.isCurrent(target, 10_001))
    }

    @Test fun ingressRevocationRejectsAStoredPlanBeforeItsQueuedLocationUpdate() {
        val authority = LocationInputAuthority()
        val receipt = authority.record(0, admissible = true)
        val planner = planner()
        val snapshot = input().copy(locationAuthority = { authority.canUse(receipt, 0) })
        val target = planner.update(snapshot)!!
        assertTrue(planner.isCurrent(target, 0))
        authority.invalidate()
        assertFalse(planner.isCurrent(target, 0))
        assertFalse(planner.isPlayableNow(target, 0))
        authority.record(0, admissible = true) // Equal receipt time is still a different callback.
        assertFalse(planner.isCurrent(target, 0))
        assertNull(planner.update(snapshot))
    }

    @Test fun failingLocationAuthorityCannotSupplyAPlan() {
        assertNull(planner().update(input().copy(locationAuthority = { error("test-ingress-unavailable") })))
    }

    @Test fun unknownOutputBoundaryDoesNotGuessARegionEvenWithAValidForecast() {
        val planner = planner()
        val before = planner.update(input())!!
        assertNull(planner.update(input(now = 100, start = null)))
        assertFalse(planner.isCurrent(before, 100))
    }

    @Test fun absentForecastUsesFreshCurrentRegionWithoutRouteDependency() {
        val planner = planner()
        val target = planner.update(input(routeForecast = null))!!
        assertEquals("a", target.regionId)
        assertNull(target.visitId)
        assertNull(target.routeDependency)
        assertNull(planner.update(input(now = 100, routeForecast = null).copy(currentRegionId = null)))
        assertFalse(planner.isCurrent(target, 100))
    }

    @Test fun oldRouteOrOtherClockForecastIsNotTreatedAsFuturePosition() {
        val planner = planner()
        val oldRoute = planner.update(input(start = 80_000, routeForecast = forecast(route = 0)))!!
        assertEquals("a", oldRoute.regionId)
        assertNull(oldRoute.routeDependency)
        val otherClock = ReviewedRouteForecast("session", "previous-clock", 1, 0, 300_000,
            forecast().passages)
        assertEquals("a", planner.update(input(start = 80_000, routeForecast = otherClock))?.regionId)
    }

    @Test fun otherSessionOrClockAndBackwardTimeRevokeTheCurrentPermit() {
        val planner = planner()
        val first = planner.update(input(now = 1_000))!!
        assertFalse(planner.isCurrent(first, 999))
        assertNull(planner.update(input(now = 1_001).copy(sessionId = "different")))
        assertFalse(planner.isCurrent(first, 1_001))
        val second = planner.update(input(now = 1_002))!!
        assertNull(planner.update(input(now = 1_003).copy(clockScopeId = "different")))
        assertFalse(planner.isCurrent(second, 1_003))
        planner.update(input(now = 1_004))!!
        assertNull(planner.update(input(now = 1_003)))
        assertNull(planner.update(input(now = 1_000)))
    }

    @Test fun repeatedRegionVisitsHaveSeparateGenerationIdentity() {
        val source = forecast(passages = listOf(
            RegionPassage("a-outbound", "a", 0, 50_000),
            RegionPassage("b-turnaround", "b", 50_000, 100_000),
            RegionPassage("a-return", "a", 100_000, 150_000),
        ))
        val planner = planner()
        val outbound = planner.update(input(routeForecast = source))!!
        val returning = planner.update(input(start = 120_000, routeForecast = source))!!
        assertEquals(outbound.regionId, returning.regionId)
        assertNotEquals(outbound.visitId, returning.visitId)
        assertTrue(returning.planningRevision > outbound.planningRevision)
        assertFalse(planner.isCurrent(outbound, 0))
    }

    @Test fun boundedHorizonRejectsBothFarBoundaryAndFarUpcomingRegion() {
        val planner = planner(horizon = 100_000)
        assertNull(planner.update(input(start = 100_001)))
        val distant = forecast(passages = listOf(RegionPassage("distant", "b", 100_001, 200_000)))
        assertNull(planner.update(input(start = 20_000, routeForecast = distant)))
        assertNotNull(planner.update(input(start = 100_000)))
    }

    @Test fun validButExhaustedForecastDoesNotPretendTheOldCurrentRegionIsStillAhead() {
        val planner = planner()
        assertNull(planner.update(input(start = 250_000)))
        assertNull(planner.update(input(routeForecast = forecast(passages = emptyList()))))
    }

    @Test fun preparationMayChooseAFuturePassageButOutputWaitsForActualApproach() {
        val planner = planner()
        val target = planner.update(input(start = 80_000))!!
        assertEquals("b", target.regionId)
        assertTrue(planner.isCurrent(target, 0))
        assertFalse(planner.isPlayableNow(target, 0))
        val refreshed = planner.update(input(now = 20_000, start = 80_000))!!
        assertEquals(target.planningRevision, refreshed.planningRevision)
        assertTrue(planner.isPlayableNow(target, 20_000))
    }

    @Test fun tooDistantNextPassageDoesNotForceALongWaitOrPrematureNarration() {
        val planner = planner()
        val source = forecast(passages = listOf(RegionPassage("b", "b", 40_001, 80_000)))
        assertNull(planner.update(input(start = 10_000, routeForecast = source)))
        val atLimit = planner.update(input(start = 10_001, routeForecast = source))!!
        assertEquals(10_001L, atLimit.earliestStartElapsedMs)
        assertFalse(planner.isPlayableNow(atLimit, 0))
    }

    @Test fun playbackUsesLatestEtaForSameIdentityRatherThanTheOriginalPassageWindow() {
        val planner = planner()
        val original = forecast(passages = listOf(RegionPassage("b", "b", 20_000, 100_000)))
        val target = planner.update(input(start = 60_000, routeForecast = original))!!
        assertTrue(planner.isPlayableNow(target, 0))
        val delayed = forecast(observed = 1_000, passages = listOf(RegionPassage("b", "b", 60_000, 140_000)))
        val updated = planner.update(input(now = 1_000, start = 60_000, routeForecast = delayed))!!
        assertEquals(target.planningRevision, updated.planningRevision)
        assertTrue(planner.isCurrent(target, 1_000))
        assertFalse(planner.isPlayableNow(target, 1_000))
    }

    @Test fun zeroApproachAllowanceRequiresTheActualPredictionWindowAndHandlesLargeTimes() {
        val planner = RegionalStoryPlanner("session", "clock", maximumApproachLeadMs = 0)
        val source = forecast(passages = listOf(RegionPassage("b", "b", 20_000, 100_000)))
        assertNull(planner.update(input(start = 19_999, routeForecast = source)))
        val target = planner.update(input(start = 20_000, routeForecast = source))!!
        assertFalse(planner.isPlayableNow(target, 0))
        planner.update(input(now = 20_000, routeForecast = source))
        assertTrue(planner.isPlayableNow(target, 20_000))
        val nearLimit = Long.MAX_VALUE - 10
        val last = forecast(observed = nearLimit, validUntil = Long.MAX_VALUE,
            passages = listOf(RegionPassage("last", "z", nearLimit, Long.MAX_VALUE)))
        val lastTarget = planner.update(input(now = nearLimit, routeForecast = last))!!
        assertTrue(planner.isPlayableNow(lastTarget, Long.MAX_VALUE - 1))
    }

    @Test fun hugeTimesCannotOverflowIntoAnImmediateOrExpiredTarget() {
        val planner = planner()
        assertNull(planner.update(input(start = Long.MAX_VALUE)))
        val nearLimit = Long.MAX_VALUE - 100
        val source = forecast(observed = nearLimit, validUntil = Long.MAX_VALUE, passages = listOf(
            RegionPassage("last", "z", nearLimit, Long.MAX_VALUE),
        ))
        val target = planner.update(input(now = nearLimit, start = Long.MAX_VALUE - 1,
            routeForecast = source))!!
        assertEquals(Long.MAX_VALUE - 1, target.earliestStartElapsedMs)
        assertTrue(planner.isCurrent(target, Long.MAX_VALUE - 1))
        assertFalse(planner.isCurrent(target, Long.MAX_VALUE))
    }

    @Test fun forecastDefensivelyFreezesItsPassages() {
        val mutable = mutableListOf(RegionPassage("a", "a", 0, 20_000))
        val source = forecast(passages = mutable)
        mutable.clear()
        assertEquals(1, source.passages.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (source.passages as MutableList<RegionPassage>).clear()
        }
    }

    @Test fun malformedAndAmbiguousPassageContractsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { RegionPassage("", "a", 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { RegionPassage("a", "a", 10, 10) }
        assertThrows(IllegalArgumentException::class.java) {
            forecast(passages = listOf(RegionPassage("a", "a", 0, 20), RegionPassage("b", "b", 10, 30)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            forecast(passages = listOf(RegionPassage("a", "a", 0, 20), RegionPassage("a", "a", 20, 30)))
        }
        assertThrows(IllegalArgumentException::class.java) { forecast(observed = 10, validUntil = 10) }
        assertThrows(IllegalArgumentException::class.java) { input().copy(elapsedMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { planner(horizon = 0) }
    }

    @Test fun currentRegionReviewRevocationWithdrawsFallbackWithoutAnotherLocationSample() {
        val planner = planner()
        var current = true
        val supplied = input(routeForecast = null).copy(currentRegionPolicyKey = "policy-a",
            currentRegionAuthority = { current })
        val target = checkNotNull(planner.update(supplied))
        assertTrue(planner.isCurrent(target, 0))
        assertTrue(planner.isPlayableNow(target, 0))
        current = false
        assertFalse(planner.isCurrent(target, 0))
        assertFalse(planner.isPlayableNow(target, 0))
        assertNull(planner.update(supplied)) // The still-fresh raw position does not restore the region.
    }

    @Test fun aNewReviewedPolicyForTheSameRegionCannotKeepAnOldGenerationTargetCurrent() {
        val planner = planner()
        val supplied = input(routeForecast = null).copy(currentRegionPolicyKey = "policy-a")
        val first = checkNotNull(planner.update(supplied))
        val next = checkNotNull(planner.update(supplied.copy(currentRegionPolicyKey = "policy-b")))
        assertEquals(first.regionId, next.regionId)
        assertTrue(next.planningRevision > first.planningRevision)
        assertFalse(planner.isCurrent(first, 0))
        assertTrue(planner.isCurrent(next, 0))
    }

    @Test fun invalidObservedRegionDoesNotOverrideAnIndependentlyReviewedForecast() {
        val planner = planner()
        val target = checkNotNull(planner.update(input(start = 80_000).copy(currentRegionId = "stale-region",
            currentRegionAuthority = { false })))
        assertEquals("b", target.regionId)
        assertEquals(1L, target.routeDependency)
        assertNull(target.regionPolicyKey)
        assertTrue(planner.isCurrent(target, 0))
    }
}
