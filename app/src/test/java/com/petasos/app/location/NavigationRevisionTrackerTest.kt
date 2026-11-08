package com.petasos.app.location

import com.petasos.app.capability.navigation.NavigationRevisionTracker
import com.petasos.app.capability.navigation.NavigationRouteState
import com.petasos.app.capability.navigation.NavigationSnapshot
import org.junit.Assert.*
import org.junit.Test

class NavigationRevisionTrackerTest {
    @Test fun sameRouteAndDestinationDoNotIncrementOnRepeatedGpsAndCountdownCallbacks() {
        val tracker = NavigationRevisionTracker()
        tracker.routeState(true)
        assertEquals(1L, tracker.routeIdentity("route-a")?.revision)
        assertEquals(2L, tracker.destination("destination-a")?.revision)
        repeat(100) {
            assertNull(tracker.routeIdentity("route-a"))
            assertNull(tracker.destination("destination-a"))
            assertNull(tracker.routeState(true))
        }
        assertEquals(2L, tracker.snapshot.routeDependency)
        assertEquals(3L, tracker.routeIdentity("route-b")?.revision)
        assertEquals(4L, tracker.destination("destination-b")?.revision)
    }

    @Test fun explicitlyInactiveRouteUsesCurrentPositionModeAndIgnoresLateDestinationResponse() {
        val tracker = NavigationRevisionTracker()
        assertNull(tracker.snapshot.routeDependency)
        assertNull(tracker.destination("late-destination"))
        tracker.routeState(true)
        tracker.routeIdentity("route-a")
        assertNull(tracker.routeState(false)?.routeDependency)
        assertEquals(2L, tracker.snapshot.revision)
        assertNull(tracker.routeIdentity("old-route"))
        assertNull(tracker.destination("old-destination"))
        assertEquals(NavigationRouteState.INACTIVE, tracker.snapshot.observedRouteState)
        assertEquals(NavigationRouteState.UNKNOWN, tracker.unavailable()?.observedRouteState)
        assertEquals(2L, tracker.snapshot.revision)
        assertNull(tracker.unavailable())
    }

    @Test fun explicitRerouteChangesRevisionEvenWhenProviderKeepsItsRouteId() {
        val tracker = NavigationRevisionTracker(10)
        tracker.routeState(true)
        tracker.routeIdentity("route")
        assertEquals(12L, tracker.rerouted()?.revision)
        assertNull(tracker.routeIdentity("route"))
        tracker.unavailable()
        assertNull(tracker.rerouted())
    }

    @Test fun simulationIsLabelledSeparatelyAndNeverCreatesActualLocationPoints() {
        val tracker = NavigationRevisionTracker()
        tracker.routeState(true, simulation = true)
        tracker.routeIdentity("simulated-route")
        assertTrue(tracker.snapshot.simulated)
        tracker.routeState(false)
        assertFalse(tracker.snapshot.simulated)
    }

    @Test fun observedRouteDependencyExcludesSimulationWhilePreservingItsNavigationRevision() {
        val tracker = activeRoute()
        val activeRevision = tracker.snapshot.revision
        assertEquals(activeRevision, tracker.snapshot.observedRouteDependency)

        tracker.routeState(true, simulation = true)
        assertEquals(activeRevision, tracker.snapshot.routeDependency)
        assertNull(tracker.snapshot.observedRouteDependency)

        tracker.routeState(true, simulation = false)
        assertEquals(activeRevision, tracker.snapshot.observedRouteDependency)
        tracker.routeState(false)
        assertNull(tracker.snapshot.routeDependency)
        assertNull(tracker.snapshot.observedRouteDependency)
    }

    @Test fun pathMutationImmediatelyInvalidatesEvenWhenItsRouteAndDestinationWillStayTheSame() {
        val tracker = activeRoute()
        val originalRevision = tracker.snapshot.revision

        val invalidated = tracker.pathChangeStarted()
        assertEquals(originalRevision + 1, invalidated?.revision)
        assertNull(invalidated?.routeDependency)
        assertFalse(tracker.snapshot.hasDestination)
        assertTrue(tracker.awaitingRouteCompletion)
    }

    @Test fun duplicateOptionWaypointAndSearchStartNotificationsOnlyInvalidateOnce() {
        val tracker = activeRoute()
        tracker.pathChangeStarted()
        val invalidated = tracker.snapshot
        repeat(10) { assertNull(tracker.pathChangeStarted()) }
        assertEquals(invalidated, tracker.snapshot)
        assertTrue(tracker.awaitingRouteCompletion)
    }

    @Test fun callbacksAlreadyInFlightCannotRestoreTheOldPathDuringItsMutation() {
        val tracker = activeRoute()
        tracker.pathChangeStarted()
        val invalidated = tracker.snapshot

        repeat(10) {
            assertNull(tracker.routeState(true))
            assertNull(tracker.routeIdentity("route-a"))
            assertNull(tracker.destination("destination-a"))
            assertNull(tracker.rerouted())
        }
        assertEquals(invalidated, tracker.snapshot)
        assertTrue(tracker.awaitingRouteCompletion)
    }

    @Test fun genericUnavailableAndInactiveReadsCannotOpenTheCompletionFence() {
        val tracker = activeRoute()
        tracker.pathChangeStarted()
        tracker.unavailable()
        tracker.routeState(false)
        assertFalse(tracker.acceptsRouteReadings)
        assertNull(tracker.routeState(true, simulation = true))
        assertNull(tracker.routeIdentity("route-a"))
        assertNull(tracker.snapshot.routeDependency)
        assertFalse(tracker.snapshot.simulated)
    }

    @Test fun completionRequiresRefreshedActiveStateAndIdentityBeforeRestoringDependency() {
        val tracker = activeRoute()
        tracker.pathChangeStarted()
        val invalidatedRevision = tracker.snapshot.revision
        tracker.pathChangeCompleted()

        assertFalse(tracker.awaitingRouteCompletion)
        assertNull(tracker.snapshot.routeDependency)
        assertNull(tracker.routeIdentity("route-a"))
        assertNull(tracker.destination("destination-a"))
        tracker.routeState(true)
        assertNull(tracker.snapshot.routeDependency)
        assertEquals(invalidatedRevision + 1, tracker.routeIdentity("route-a")?.routeDependency)
        assertEquals(invalidatedRevision + 2, tracker.destination("destination-a")?.routeDependency)
    }

    @Test fun duplicateCompletionAndRefreshedReadCallbacksDoNotCreateExtraRevisions() {
        val tracker = activeRoute()
        tracker.pathChangeStarted()
        tracker.pathChangeCompleted()
        tracker.routeState(true)
        tracker.routeIdentity("route-a")
        tracker.destination("destination-a")
        val restored = tracker.snapshot

        repeat(10) {
            tracker.pathChangeCompleted()
            assertNull(tracker.routeState(true))
            assertNull(tracker.routeIdentity("route-a"))
            assertNull(tracker.destination("destination-a"))
        }
        assertEquals(restored, tracker.snapshot)
    }

    @Test fun completedRerouteWithoutSearchStartMustStillDropIdentityBeforeRefreshing() {
        val tracker = activeRoute()
        val originalRevision = tracker.snapshot.revision
        // onReRouteEnded executes this sequence even when no preceding start event was delivered.
        tracker.pathChangeStarted()
        tracker.pathChangeCompleted()
        assertEquals(originalRevision + 1, tracker.snapshot.revision)
        assertNull(tracker.snapshot.routeDependency)
        assertNull(tracker.destination("destination-a"))
        tracker.routeState(true)
        assertNull(tracker.snapshot.routeDependency)
        assertEquals(originalRevision + 2, tracker.routeIdentity("route-a")?.routeDependency)
    }

    @Test fun explicitCancellationBlocksLateActiveIdentityAndCompletionCallbacks() {
        val tracker = activeRoute()
        tracker.pathChangeStarted()
        val invalidatedRevision = tracker.snapshot.revision
        assertNull(tracker.pathChangeCancelled())
        assertFalse(tracker.awaitingRouteCompletion)
        assertFalse(tracker.acceptsRouteReadings)
        assertEquals(invalidatedRevision, tracker.snapshot.revision)
        repeat(10) {
            assertNull(tracker.routeState(true))
            assertNull(tracker.routeIdentity("cancelled-route"))
            assertNull(tracker.destination("cancelled-destination"))
            tracker.pathChangeCompleted()
            assertFalse(tracker.acceptsRouteReadings)
            assertNull(tracker.routeState(true))
        }
        assertEquals(invalidatedRevision, tracker.snapshot.revision)
        assertNull(tracker.snapshot.routeDependency)
    }

    @Test fun lateRerouteEndAndOptionOrWaypointChangesCannotManufactureNewSearchAfterCancellation() {
        val tracker = activeRoute()
        tracker.pathChangeCancelled()
        val cancelled = tracker.snapshot
        repeat(10) {
            // The same non-lifecycle mutation method handles option/waypoint updates and the
            // defensive invalidation in onReRouteEnded when a start callback was not observed.
            assertNull(tracker.pathChangeStarted())
            tracker.pathChangeCompleted()
            assertFalse(tracker.acceptsRouteReadings)
            assertNull(tracker.routeState(true))
            assertNull(tracker.routeIdentity("route-a"))
            assertNull(tracker.rerouted())
        }
        assertEquals(cancelled, tracker.snapshot)
    }

    @Test fun cancelledRouteRecoversOnlyAfterExplicitNewStartCompletionAndIdentityReads() {
        val tracker = activeRoute()
        tracker.pathChangeCancelled()
        val cancelledRevision = tracker.snapshot.revision
        tracker.routeStarted()
        assertTrue(tracker.awaitingRouteCompletion)
        assertFalse(tracker.acceptsRouteReadings)
        assertNull(tracker.routeState(true))
        assertNull(tracker.routeIdentity("new-route"))
        tracker.pathChangeCompleted()
        assertTrue(tracker.acceptsRouteReadings)
        assertNull(tracker.snapshot.routeDependency)
        assertNull(tracker.routeIdentity("new-route"))
        tracker.routeState(true)
        assertNull(tracker.snapshot.routeDependency)
        assertEquals(cancelledRevision + 1, tracker.routeIdentity("new-route")?.routeDependency)
    }

    @Test fun inactiveStatusAfterKnownRouteRejectsLateReadsUntilAnotherSearchStarts() {
        val tracker = activeRoute()
        tracker.navigationInactive()
        val inactive = tracker.snapshot
        tracker.pathChangeCompleted()
        assertFalse(tracker.acceptsRouteReadings)
        assertNull(tracker.routeState(true))
        assertNull(tracker.routeIdentity("route-a"))
        assertEquals(inactive, tracker.snapshot)
        tracker.routeStarted()
        tracker.pathChangeCompleted()
        tracker.routeState(true)
        assertNotNull(tracker.routeIdentity("new-route")?.routeDependency)
    }

    @Test fun inactiveRouteStateAfterKnownRouteAlsoPreventsLateStateRestoringIt() {
        val tracker = activeRoute()
        tracker.routeState(false)
        assertFalse(tracker.acceptsRouteReadings)
        tracker.pathChangeCompleted()
        assertNull(tracker.routeState(true))
        assertNull(tracker.routeIdentity("route-a"))
        assertNull(tracker.snapshot.routeDependency)
    }

    @Test fun initialInactiveQueryDoesNotPermanentlyBlockAnExistingNavigationRoute() {
        val tracker = NavigationRevisionTracker()
        assertEquals(NavigationRouteState.INACTIVE, tracker.navigationInactive()?.observedRouteState)
        assertNull(tracker.routeState(false))
        assertTrue(tracker.acceptsRouteReadings)
        tracker.routeState(true)
        assertEquals(1L, tracker.routeIdentity("existing-route")?.routeDependency)
    }

    @Test fun newSourceInstanceDoesNotInheritCancelledLifecycleButKeepsMonotonicRevision() {
        val old = activeRoute()
        old.pathChangeCancelled()
        val tracker = NavigationRevisionTracker(old.snapshot.revision)
        tracker.routeState(true)
        assertEquals(old.snapshot.revision + 1, tracker.routeIdentity("existing-route")?.routeDependency)
    }

    @Test fun mutationBeforeFirstIdentityStillRequiresCompletion() {
        val tracker = NavigationRevisionTracker()
        tracker.pathChangeStarted()
        assertEquals(0L, tracker.snapshot.revision)
        assertNull(tracker.routeState(true))
        assertNull(tracker.routeIdentity("first-route"))
        tracker.pathChangeCompleted()
        tracker.routeState(true)
        assertEquals(1L, tracker.routeIdentity("first-route")?.routeDependency)
    }

    @Test fun initialSnapshotAndLegacyConstructorCannotClaimObservedInactiveRoute() {
        assertEquals(NavigationRouteState.UNKNOWN, NavigationRevisionTracker().snapshot.observedRouteState)
        val legacy = NavigationSnapshot(0, null, false, false)
        assertEquals(NavigationRouteState.UNKNOWN, legacy.routeState)
        assertEquals(NavigationRouteState.UNKNOWN, legacy.observedRouteState)
    }

    @Test fun initialInactiveStateEmitsAChangedSnapshotWithoutInventingAPathRevision() {
        val tracker = NavigationRevisionTracker(12)
        val changed = checkNotNull(tracker.routeState(false))
        assertEquals(NavigationRouteState.INACTIVE, changed.routeState)
        assertEquals(NavigationRouteState.INACTIVE, changed.observedRouteState)
        assertNull(changed.routeDependency)
        assertFalse(changed.hasDestination)
        assertEquals(12L, changed.revision)
        assertNull(tracker.routeState(false))
        assertTrue(tracker.acceptsRouteReadings)
    }

    @Test fun activeStateBeforeIdentityIsActiveRatherThanCurrentPositionMode() {
        val tracker = NavigationRevisionTracker(12)
        val changed = checkNotNull(tracker.routeState(true))
        assertEquals(NavigationRouteState.ACTIVE, changed.observedRouteState)
        assertNull(changed.routeDependency)
        assertEquals(12L, changed.revision)
        assertNull(tracker.routeState(true))
        assertEquals(13L, tracker.routeIdentity("known-route")?.routeDependency)
        assertEquals(NavigationRouteState.ACTIVE, tracker.snapshot.observedRouteState)
    }

    @Test fun genericUnavailableClearsKnownInactiveStateWithoutInventingAnIdentityChange() {
        val tracker = NavigationRevisionTracker(12)
        tracker.navigationInactive()
        val changed = checkNotNull(tracker.unavailable())
        assertEquals(NavigationRouteState.UNKNOWN, changed.observedRouteState)
        assertEquals(12L, changed.revision)
        assertNull(changed.routeDependency)
        assertNull(tracker.unavailable())
    }

    @Test fun pathMutationAndCancelledSearchNeverAssertAnInactiveNavigationRoute() {
        val tracker = NavigationRevisionTracker(12)
        tracker.navigationInactive()
        assertEquals(NavigationRouteState.UNKNOWN, tracker.pathChangeStarted()?.observedRouteState)
        assertEquals(12L, tracker.snapshot.revision)
        tracker.pathChangeCancelled()
        assertEquals(NavigationRouteState.UNKNOWN, tracker.snapshot.observedRouteState)
        assertFalse(tracker.acceptsRouteReadings)
        tracker.pathChangeCompleted()
        assertNull(tracker.routeState(true))
        assertNull(tracker.routeIdentity("late-route"))
        assertEquals(NavigationRouteState.UNKNOWN, tracker.snapshot.observedRouteState)
    }

    @Test fun explicitInactiveAfterCancellationDoesNotReopenTheLateCallbackFence() {
        val tracker = activeRoute()
        tracker.pathChangeCancelled()
        val revision = tracker.snapshot.revision
        assertEquals(NavigationRouteState.UNKNOWN, tracker.snapshot.observedRouteState)
        assertEquals(NavigationRouteState.INACTIVE, tracker.navigationInactive()?.observedRouteState)
        assertFalse(tracker.acceptsRouteReadings)
        repeat(5) {
            tracker.pathChangeCompleted()
            assertNull(tracker.routeState(true))
            assertNull(tracker.routeIdentity("late-route"))
            assertNull(tracker.destination("late-destination"))
        }
        assertEquals(NavigationRouteState.INACTIVE, tracker.snapshot.observedRouteState)
        assertEquals(revision, tracker.snapshot.revision)
        tracker.routeStarted()
        assertEquals(NavigationRouteState.UNKNOWN, tracker.snapshot.observedRouteState)
        tracker.pathChangeCompleted()
        assertEquals(NavigationRouteState.ACTIVE, tracker.routeState(true)?.observedRouteState)
    }

    @Test fun simulatedActiveStateNeverAuthorizesObservedCurrentPositionOrObservedRoute() {
        val tracker = NavigationRevisionTracker()
        val simulation = checkNotNull(tracker.routeState(true, simulation = true))
        assertEquals(NavigationRouteState.ACTIVE, simulation.routeState)
        assertEquals(NavigationRouteState.UNKNOWN, simulation.observedRouteState)
        tracker.routeIdentity("simulation-route")
        assertNotNull(tracker.snapshot.routeDependency)
        assertNull(tracker.snapshot.observedRouteDependency)
        assertEquals(NavigationRouteState.UNKNOWN, tracker.snapshot.observedRouteState)
        tracker.unavailable()
        assertEquals(NavigationRouteState.UNKNOWN, tracker.snapshot.observedRouteState)
    }

    private fun activeRoute() = NavigationRevisionTracker().apply {
        routeState(true)
        routeIdentity("route-a")
        destination("destination-a")
    }
}
