package com.petasos.app.planning

import com.petasos.app.location.*
import com.petasos.app.playback.PreparedStory
import com.petasos.app.playback.OutputReference
import org.junit.Assert.*
import org.junit.Test

class HierarchicalRegionalPlanningTest {
    private val hierarchy = ReviewedRegionHierarchy("review-1", "dataset", listOf(
        RegionHierarchyRelation(RegionHierarchyNode("leaf", "leaf-r"), RegionHierarchyNode("city", "city-r")),
        RegionHierarchyRelation(RegionHierarchyNode("sibling", "sibling-r"), RegionHierarchyNode("city", "city-r"))))
    private fun input(actual: String = "leaf", forecast: ReviewedRouteForecast? = null) =
        RegionalPlanningInput("session", "clock", 1, 1_000, 1_000, true, actual, false, 1_000, forecast,
            locationFreshUntilElapsedMs = 11_000, currentRegionPolicyKey = "policy")
    private fun forecast(expected: String) = ReviewedRouteForecast("session", "clock", 1, 0, 100_000,
        listOf(RegionPassage("visit", expected, 0, 100_000)))

    @Test fun leafFallbackAllowsParentContentWithoutChangingPassageOrProofIdentity() {
        val planner = RegionalStoryPlanner("session", "clock", hierarchy = hierarchy)
        val target = planner.update(input())!!
        assertEquals("leaf", target.regionId)
        assertEquals("policy", target.regionPolicyKey)
        assertTrue(target.allowsStoryRegion("city"))
        assertFalse(target.allowsStoryRegion("sibling"))
        assertNull(target.routeDependency)
        assertTrue(planner.isPlayableNow(target, 1_000))
        assertNull(planner.update(input().copy(currentRegionAuthority = { false })))
        assertFalse(planner.isCurrent(target, 1_000))
    }

    @Test fun parentForecastAgreesWithActualLeafButParentCannotProveSpecificLeaf() {
        val planner = RegionalStoryPlanner("session", "clock", hierarchy = hierarchy)
        val parent = planner.update(input(forecast = forecast("city")))!!
        assertEquals("city", parent.regionId)
        assertEquals(1L, parent.routeDependency)
        val sibling = planner.update(input(forecast = forecast("sibling")))!!
        assertEquals("leaf", sibling.regionId)
        assertNull(sibling.routeDependency)
        val reverse = planner.update(input(actual = "city", forecast = forecast("leaf")))!!
        assertEquals("city", reverse.regionId)
        assertNull(reverse.routeDependency)
    }

    @Test fun nearestContentWinsEvenWhenParentWasOfferedBeforeLeaf() {
        val planner = ContentPlanner("clock")
        val target = RegionalStoryTarget("session", "clock", 1, "leaf", null, null, 1_000, null,
            storyScope = hierarchy.scope("leaf"))
        val context = PlanningContext("leaf", null, 1_000, "clock", true, false, 1_000, target,
            locationFreshUntilElapsedMs = 11_000)
        fun candidate(region: String) = StoryCandidate(region, PreparedStory(region, "$region-v", listOf("p")),
            CandidateReason.UPCOMING_REGION, region, null, null)
        planner.offer(candidate("city"))
        assertEquals("city", planner.chooseAtBoundary(context)?.regionId)
        planner.offer(candidate("leaf"))
        assertEquals("leaf", planner.chooseAtBoundary(context)?.regionId)
    }

    @Test fun bridgePreservesParentContentAndRevokesBeforeSdkStartWhenLeafProofIsLost() {
        val regional = RegionalStoryPlanner("session", "clock", hierarchy = hierarchy)
        var permitted = true
        val target = regional.update(input().copy(currentRegionAuthority = { permitted }))!!
        val parent = PreparedStory("parent-story", "parent-version", listOf("parent-paragraph"))
        val candidate = AutomaticStoryCandidate(parent, CandidateReason.CURRENT_REGION, "city", "eligible")
        val offers = mutableListOf<String>()
        var storeRegion: String? = null
        val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { region, _ ->
            storeRegion = region; listOf(candidate)
        }, object : PlannedStoryOutput {
            override fun offer(story: PreparedStory, offerId: String) { assertEquals(parent, story); offers += offerId }
            override fun withdraw(storyId: String, versionId: String) = Unit
        }, { 1_000 }, { current, region -> current.regionId == "city" && region == "leaf" },
            targetCurrent = { regional.isCurrent(it, 1_000) }, targetPlayable = { regional.isPlayableNow(it, 1_000) },
            requireRegionalTarget = true)
        val context = PlanningContext("leaf", null, 1_000, "clock", true, false, 1_000, target,
            locationFreshUntilElapsedMs = 11_000, currentRegionAuthority = { permitted }, currentRegionEvidenceCurrent = { permitted })
        bridge.update(context)
        val token = offers.single()
        val reference = OutputReference.StoryParagraph(parent.storyId, parent.versionId, parent.paragraphIds.single())
        assertEquals("leaf", storeRegion)
        assertTrue(bridge.isAutomaticStoryCurrent(token, reference))
        assertTrue(bridge.isAutomaticStoryOffered(token, reference))
        permitted = false
        assertFalse(bridge.isAutomaticStoryOffered(token, reference))
        assertFalse(bridge.isAutomaticStoryCurrent(token, reference))
    }

    @Test fun targetCannotCarryAParentScopeUnderALeafPassageIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            RegionalStoryTarget("session", "clock", 1, "leaf", null, null, 1_000, null,
                storyScope = hierarchy.scope("city"))
        }
    }
}
