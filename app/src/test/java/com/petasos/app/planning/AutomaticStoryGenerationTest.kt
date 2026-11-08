package com.petasos.app.planning

import com.petasos.app.location.ConfirmedRegion
import com.petasos.app.location.ConfirmedRegionAuthority
import com.petasos.app.runtime.RuntimeCancellation
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class AutomaticStoryGenerationTest {
    private val plan = AutomaticGenerationPlan("decision", "seed-v1", "region", 4)

    @Test fun repeatedRefreshUsesOneRequestAndSuccessAcceptsItsInstalledSeedWithoutRegeneration() {
        val h = Harness()
        h.controller.update(plan)
        repeat(5) { h.controller.update(plan) }
        assertEquals(1, h.calls.size)
        assertTrue(h.controller.isPending)
        assertNull(h.controller.readyVersionId(plan))
        h.calls.single().complete("generated-v2")
        assertTrue(h.controller.isPending) // The callback must re-enter the serial queue.
        h.drain()
        assertFalse(h.controller.isPending)
        assertEquals("generated-v2", h.controller.readyVersionId(plan))
        val installed = plan.copy(seedVersionId = "generated-v2")
        h.controller.update(installed)
        assertTrue(h.controller.isReady(installed))
        assertEquals(1, h.calls.size)
        assertFalse(h.calls.single().current())
    }

    @Test fun replacementCancelsOnlyUnstartedPreparationAndRejectsItsLateResult() {
        val h = Harness()
        h.controller.update(plan)
        val old = h.calls.single()
        val replacement = plan.copy(key = "new-decision", regionId = "next-region")
        h.controller.update(replacement)
        assertEquals(1, old.cancellations)
        assertFalse(old.current())
        old.complete("late-old-result")
        h.drain()
        assertNull(h.controller.readyVersionId(plan))
        assertTrue(h.controller.isPending)
        h.calls.last().complete("new-result")
        h.drain()
        assertEquals("new-result", h.controller.readyVersionId(replacement))
    }

    @Test fun independentSeedReplacementUnderSameKeyStartsOnlyOneNewRequest() {
        val h = Harness()
        h.controller.update(plan)
        val newerSeed = plan.copy(seedVersionId = "independently-installed-v3")
        h.controller.update(newerSeed)
        h.controller.update(newerSeed)
        assertEquals(2, h.calls.size)
        assertEquals(1, h.calls.first().cancellations)
        assertNull(h.controller.readyVersionId(plan))
        h.calls.last().complete("generated-v4")
        h.drain()
        assertEquals("generated-v4", h.controller.readyVersionId(newerSeed))
    }

    @Test fun refreshSeeingInstalledSeedBeforeQueuedCompletionCannotStartRegeneration() {
        val h = Harness()
        h.controller.update(plan)
        h.calls.single().complete("installed-v2")
        val installed = plan.copy(seedVersionId = "installed-v2")
        h.controller.update(installed)
        assertEquals(1, h.calls.size)
        assertEquals(0, h.calls.single().cancellations)
        assertTrue(h.controller.isPending)
        assertFalse(h.controller.isReady(installed))
        h.drain()
        assertEquals("installed-v2", h.controller.readyVersionId(installed))
        assertFalse(h.controller.isPending)
    }

    @Test fun sameKeyCannotRetainAResultAcrossRegionOrRouteChanges() {
        val h = Harness()
        h.controller.update(plan)
        h.calls.single().complete("generated")
        h.drain()
        assertFalse(h.controller.isReady(plan.copy(regionId = "other-region")))
        assertFalse(h.controller.isReady(plan.copy(routeRevision = 5)))
        h.controller.update(plan.copy(routeRevision = 5))
        assertEquals(2, h.calls.size)
        assertTrue(h.controller.isPending)
    }

    @Test fun failureDoesNotRetryOnTimerRefreshOrAfterTemporarilyClearingThePlan() {
        val h = Harness()
        h.controller.update(plan)
        h.calls.single().complete(null)
        h.drain()
        assertFalse(h.controller.isPending)
        repeat(10) { h.controller.update(plan) }
        h.controller.update(null)
        h.controller.update(plan)
        assertEquals(1, h.calls.size)
        assertNull(h.controller.readyVersionId(plan))
        assertFalse(h.controller.isPending)
    }

    @Test fun blankResultIsFailureAndOnlyFirstCallbackCanFinishTheOperation() {
        val h = Harness()
        h.controller.update(plan)
        h.calls.single().complete("  ")
        h.calls.single().complete("duplicate-success")
        h.drain()
        assertFalse(h.controller.isPending)
        assertNull(h.controller.readyVersionId(plan))
        h.controller.update(plan)
        assertEquals(1, h.calls.size)
    }

    @Test fun revokeRemovesAuthorityImmediatelyBeforeItsCleanupQueueRuns() {
        val h = Harness()
        h.controller.update(plan)
        val old = h.calls.single()
        h.controller.revoke()
        assertFalse(old.current())
        assertFalse(h.controller.isPending)
        assertEquals(0, old.cancellations)
        old.complete("late-result")
        h.drain()
        assertEquals(1, old.cancellations)
        assertNull(h.controller.readyVersionId(plan))
        h.controller.update(plan) // Cancellation is not a completed failed attempt.
        assertEquals(2, h.calls.size)
    }

    @Test fun delayedRevokeCleanupCannotClearNewPreparation() {
        val h = Harness()
        h.controller.update(plan)
        h.controller.revoke()
        val next = plan.copy(key = "next-decision")
        h.controller.update(next)
        h.drain()
        assertTrue(h.controller.isPending)
        assertTrue(h.calls.last().current())
        assertEquals(0, h.calls.last().cancellations)
        h.calls.last().complete("next-result")
        h.drain()
        assertEquals("next-result", h.controller.readyVersionId(next))
    }

    @Test fun externalAuthorityRevocationBlocksReadyLookupAndQueuedCompletion() {
        val h = Harness()
        h.controller.update(plan)
        h.calls.single().complete("result")
        h.permitted = false
        assertFalse(h.calls.single().current())
        h.drain()
        assertNull(h.controller.readyVersionId(plan))
        assertEquals(1, h.calls.single().cancellations)
        h.permitted = true
        h.controller.update(plan)
        h.calls.last().complete("current-result")
        h.drain()
        assertTrue(h.controller.isReady(plan))
        h.permitted = false
        assertFalse(h.controller.isReady(plan))
    }

    @Test fun rejectedPlanNeverGeneratesAndThrowingGuardFailsClosed() {
        var guardThrows = false
        var generated = 0
        val controller = AutomaticStoryGeneration(AutomaticStoryGenerator { _, _, _ ->
            generated++
            RuntimeCancellation { }
        }, { it() }, { if (guardThrows) error("synthetic") else false })
        controller.update(plan)
        guardThrows = true
        controller.update(plan)
        assertEquals(0, generated)
        assertFalse(controller.isPending)
    }

    @Test fun synchronousCompletionBeforeHandleAttachIsReadyAndDoesNotLeakCancellation() {
        var calls = 0
        var cancelled = 0
        val observed = mutableListOf<Pair<Boolean, Boolean>>()
        lateinit var controller: AutomaticStoryGeneration
        controller = AutomaticStoryGeneration(AutomaticStoryGenerator { _, current, complete ->
            calls++
            assertTrue(current())
            complete("generated")
            RuntimeCancellation { cancelled++ }
        }, { it() }, { true }, { observed += controller.isPending to controller.isReady(plan) })
        controller.update(plan)
        assertEquals(listOf(true to false, false to true), observed)
        assertEquals("generated", controller.readyVersionId(plan))
        controller.update(plan.copy(seedVersionId = "generated"))
        controller.revoke()
        assertEquals(1, calls)
        assertEquals(0, cancelled)
    }

    @Test fun synchronousRevokeBeforeHandleAttachCancelsReturnedHandleOnce() {
        var cancelled = 0
        lateinit var controller: AutomaticStoryGeneration
        controller = AutomaticStoryGeneration(AutomaticStoryGenerator { _, current, complete ->
            controller.revoke()
            assertFalse(current())
            complete("revoked-result")
            RuntimeCancellation { cancelled++ }
        }, { it() }, { true })
        controller.update(plan)
        assertEquals(1, cancelled)
        assertFalse(controller.isReady(plan))
        assertFalse(controller.isPending)
    }

    @Test fun generatorExceptionIsOneFailedAttemptAndChangedCallbackSeesAssignedState() {
        var calls = 0
        val states = mutableListOf<Boolean>()
        lateinit var controller: AutomaticStoryGeneration
        controller = AutomaticStoryGeneration(AutomaticStoryGenerator { _, _, _ ->
            calls++
            error("synthetic")
        }, { it() }, { true }, { states += controller.isPending })
        controller.update(plan)
        controller.update(plan)
        assertEquals(listOf(true, false), states)
        assertEquals(1, calls)
        assertFalse(controller.isPending)
    }

    @Test fun reentrantChangedCallbackCanRevokeBeforeGeneratorStarts() {
        var calls = 0
        lateinit var controller: AutomaticStoryGeneration
        controller = AutomaticStoryGeneration(AutomaticStoryGenerator { _, _, _ ->
            calls++
            RuntimeCancellation { }
        }, { it() }, { true }, { if (controller.isPending) controller.revoke() })
        controller.update(plan)
        assertEquals(0, calls)
        assertFalse(controller.isPending)
    }

    @Test fun revokeDuringAdmissionCannotBeUndoneByTheSameUpdate() {
        var calls = 0
        var revokeDuringAdmission = true
        lateinit var controller: AutomaticStoryGeneration
        controller = AutomaticStoryGeneration(AutomaticStoryGenerator { _, _, _ ->
            calls++
            RuntimeCancellation { }
        }, { it() }, {
            if (revokeDuringAdmission) {
                revokeDuringAdmission = false
                controller.revoke()
            }
            true
        })
        controller.update(plan)
        assertEquals(0, calls)
        assertFalse(controller.isPending)
        controller.update(plan)
        assertEquals(1, calls)
        assertTrue(controller.isPending)
    }

    @Test fun boundedAttemptHistoryRetainsRecentFailureAndEvictsOldest() {
        val h = Harness(maximumHistory = 2)
        val second = plan.copy(key = "second")
        val third = plan.copy(key = "third")
        listOf(plan, second, third).forEach {
            h.controller.update(it)
            h.calls.last().complete(null)
            h.drain()
        }
        h.controller.update(second)
        assertEquals(3, h.calls.size)
        assertFalse(h.controller.isPending)
        h.controller.update(plan)
        assertEquals(4, h.calls.size)
        assertTrue(h.controller.isPending)
    }

    @Test fun historicalSuccessRecognizesItsInstalledSeedWhenDecisionReturns() {
        val h = Harness()
        h.controller.update(plan)
        h.calls.single().complete("generated")
        h.drain()
        h.controller.update(null)
        val installed = plan.copy(seedVersionId = "generated")
        h.controller.update(installed)
        assertEquals(1, h.calls.size)
        assertEquals("generated", h.controller.readyVersionId(installed))
    }

    @Test fun diagnosticsRedactDecisionAndContentIdentifiers() {
        val diagnostic = plan.toString()
        listOf(plan.key, plan.seedVersionId, plan.regionId).forEach { assertFalse(diagnostic.contains(it)) }
    }

    @Test fun sameParentContentWithDifferentPassageOrHierarchyCannotReuseOldGenerationAuthority() {
        val h = Harness()
        val parent = plan.copy(regionId = "parent-city", passageRegionId = "first-leaf", hierarchyKey = "review-1")
        h.controller.update(parent)
        val first = h.calls.single()
        h.controller.update(parent.copy(passageRegionId = "second-leaf"))
        assertFalse(first.current())
        assertEquals(1, first.cancellations)
        val second = h.calls.last()
        h.controller.update(parent.copy(passageRegionId = "second-leaf", hierarchyKey = "review-2"))
        assertFalse(second.current())
        assertEquals(1, second.cancellations)
        assertEquals(3, h.calls.size)
        assertEquals("parent-city", parent.regionId)
    }

    @Test fun policyRevocationRejectsInFlightRegionalGenerationAndDoesNotRestartFromTheStaleRegion() {
        val proof = ConfirmedRegionAuthority(ConfirmedRegion("region", "revision", "a".repeat(64), "point"))
        var evidence = true
        val planner = RegionalStoryPlanner("session", "clock")
        val input = RegionalPlanningInput("session", "clock", null, 1_000, 1_000, true, "region",
            false, 1_000, null, locationFreshUntilElapsedMs = 11_000,
            currentRegionPolicyKey = proof.confirmation.policySourceKey, currentRegionAuthority = proof::isCurrent)
        val target = checkNotNull(planner.update(input))
        val calls = mutableListOf<Call>()
        val queue = ArrayDeque<() -> Unit>()
        val controller = AutomaticStoryGeneration(AutomaticStoryGenerator { _, current, complete ->
            Call(current, complete).let { calls += it; RuntimeCancellation { it.cancellations++ } }
        }, { queue.addLast(it) }, {
            planner.isCurrent(target, 1_000) && proof.recheck { evidence }
        })
        val regionalPlan = plan.copy(routeRevision = null)
        controller.update(regionalPlan)
        val original = calls.single()
        assertTrue(original.current())
        evidence = false
        assertFalse(original.current()) // Same guard is used before send and before accepting a response.
        assertFalse(planner.isCurrent(target, 1_000))
        original.complete("late-generated-version")
        while (queue.isNotEmpty()) queue.removeFirst().invoke()
        assertNull(controller.readyVersionId(regionalPlan))
        evidence = true
        controller.update(regionalPlan)
        assertEquals(1, calls.size)
        assertNull(planner.update(input)) // A new confirmed position is required after observed revocation.
    }

    private class Call(val current: () -> Boolean, val complete: (String?) -> Unit) {
        var cancellations = 0
    }

    private class Harness(maximumHistory: Int = 64) {
        var permitted = true
        val calls = mutableListOf<Call>()
        private val queue = ArrayDeque<() -> Unit>()
        val controller = AutomaticStoryGeneration(AutomaticStoryGenerator { _, current, complete ->
            val call = Call(current, complete)
            calls += call
            RuntimeCancellation { call.cancellations++ }
        }, { queue.addLast(it) }, { permitted }, maximumHistory = maximumHistory)

        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
    }
}
