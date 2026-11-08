package com.petasos.app.location

import com.petasos.app.capability.navigation.NavigationAuxiliaryInput
import com.petasos.app.capability.navigation.NavigationAuxiliaryInputController
import com.petasos.app.capability.navigation.NavigationRevisionTracker
import com.petasos.app.capability.navigation.NavigationRouteState
import com.petasos.app.capability.navigation.NavigationSnapshot
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class NavigationAuxiliaryInputTest {
    private val destination = BoundaryCoordinate(127.4, 37.4)
    private val first = BoundaryCoordinate(127.1, 37.1)
    private val second = BoundaryCoordinate(127.2, 37.2)
    private fun snapshot(revision: Long = 10) = NavigationSnapshot(revision, revision, true, false, NavigationRouteState.ACTIVE)
    private fun controller(events: MutableList<NavigationAuxiliaryInput?> = mutableListOf(), allowed: () -> Boolean = { true }) =
        NavigationAuxiliaryInputController(snapshot(), allowed) { events += it }
    private fun complete(controller: NavigationAuxiliaryInputController, waypoints: List<BoundaryCoordinate> = emptyList(),
                         state: NavigationSnapshot = snapshot()): NavigationAuxiliaryInput {
        val read = checkNotNull(controller.beginRead())
        controller.destination(read, destination, state)
        controller.waypoints(read, waypoints)
        return checkNotNull(controller.input())
    }

    @Test fun destinationWithoutExplicitWaypointReplyNeverMeansNoWaypoints() {
        val events = mutableListOf<NavigationAuxiliaryInput?>()
        val controller = controller(events)
        val read = checkNotNull(controller.beginRead())
        controller.destination(read, destination, snapshot())
        assertNull(controller.input())
        assertNull(controller.beginRead())
        assertTrue(events.isEmpty())
        controller.waypoints(read, emptyList())
        val input = checkNotNull(controller.input())
        assertTrue(input.waypoints.isEmpty())
        assertEquals(10L, input.routeRevision)
        assertEquals(1, events.size)
        assertTrue(input.isCurrent())
    }

    @Test fun waypointFirstAndDestinationFirstHaveSameMeaningAndDoNotInventOrder() {
        val one = controller()
        val read = checkNotNull(one.beginRead())
        val supplied = mutableListOf(second, first)
        one.waypoints(read, supplied)
        supplied.clear()
        assertNull(one.input())
        one.destination(read, destination, snapshot())
        val input = checkNotNull(one.input())
        assertEquals(listOf(second, first), input.waypoints)
        val matching = complete(controller(), listOf(second, first))
        assertEquals(input.inputKey, matching.inputKey)
        assertNotEquals(input.inputKey, complete(controller(), listOf(first, second)).inputKey)
        try { (input.waypoints as MutableList<BoundaryCoordinate>).clear(); fail("Mutable input") }
        catch (_: UnsupportedOperationException) { }
        assertFalse(input.toString().contains("127"))
        assertFalse(input.toString().contains(input.inputKey))
        assertFalse(read.toString().contains("127"))
    }

    @Test fun refreshWithdrawsPreviousLeaseUntilBothNewRepliesArrive() {
        val events = mutableListOf<NavigationAuxiliaryInput?>()
        val controller = controller(events)
        val old = complete(controller)
        val next = checkNotNull(controller.beginRead())
        assertNull(controller.input())
        assertFalse(old.isCurrent())
        assertNull(events.last())
        controller.destination(next, destination, snapshot())
        assertNull(controller.input())
        controller.waypoints(next, emptyList())
        val fresh = checkNotNull(controller.input())
        assertNotSame(old, fresh)
        assertEquals(old.inputKey, fresh.inputKey)
        assertFalse(old.isCurrent())
    }

    @Test fun invalidatedInFlightPairMustDrainBeforeFreshReadCanBegin() {
        val controller = controller()
        val old = checkNotNull(controller.beginRead())
        controller.waypoints(old, listOf(first))
        controller.observe(snapshot(11))
        assertFalse(controller.accepts(old))
        assertNull(controller.beginRead())
        assertTrue(controller.destination(old, destination, snapshot(10)))
        assertNull(controller.input())
        val fresh = checkNotNull(controller.beginRead())
        assertNotSame(old, fresh)
        controller.destination(fresh, destination, snapshot(11))
        controller.waypoints(fresh, emptyList())
        assertEquals(11L, controller.input()?.routeRevision)
    }

    @Test fun callbacksCapturedBeforeMutationCannotPopulateTheNextPair() {
        val controller = controller()
        val old = checkNotNull(controller.beginRead())
        controller.invalidate()
        controller.destination(old, destination, snapshot())
        assertTrue(controller.waypoints(old, listOf(first)))
        val next = checkNotNull(controller.beginRead())
        assertFalse(controller.destination(old, destination, snapshot()))
        assertFalse(controller.waypoints(old, listOf(second)))
        assertSame(next, controller.callbackRead())
        assertNull(controller.input())
        controller.waypoints(next, emptyList())
        controller.destination(next, destination, snapshot())
        assertTrue(checkNotNull(controller.input()).waypoints.isEmpty())
    }

    @Test fun duplicateOrUnrequestedRepliesCannotReplaceAnAcceptedHalf() {
        val controller = controller()
        val read = checkNotNull(controller.beginRead())
        controller.destination(read, destination, snapshot())
        assertFalse(controller.accepts(read))
        controller.destination(read, first, snapshot())
        controller.waypoints(null, listOf(first))
        assertNull(controller.input())
        controller.waypoints(read, emptyList())
        assertEquals(destination, controller.input()?.destination)
    }

    @Test fun ordinaryDestinationFingerprintMayAdvanceOnceWithinItsRequestedPair() {
        val tracker = NavigationRevisionTracker()
        tracker.routeState(true)
        tracker.routeIdentity("synthetic-route")
        val controller = NavigationAuxiliaryInputController(tracker.snapshot, { true }) { }
        val read = checkNotNull(controller.beginRead())
        tracker.destination("synthetic-destination-digest")
        controller.destination(read, destination, tracker.snapshot)
        controller.observe(tracker.snapshot)
        controller.waypoints(read, emptyList())
        assertEquals(2L, controller.input()?.routeRevision)
        repeat(5) {
            assertNull(tracker.routeIdentity("synthetic-route"))
            assertNull(tracker.destination("synthetic-destination-digest"))
            controller.observe(tracker.snapshot)
        }
        assertTrue(checkNotNull(controller.input()).isCurrent())
        assertEquals(2L, tracker.snapshot.revision)
    }

    @Test fun routeIdentityArrivingDuringFirstReadDropsThatPairAndKeepsMonotonicTracker() {
        val tracker = NavigationRevisionTracker()
        tracker.routeState(true)
        val controller = NavigationAuxiliaryInputController(tracker.snapshot, { true }) { }
        val old = checkNotNull(controller.beginRead())
        tracker.routeIdentity("synthetic-route")
        controller.observe(tracker.snapshot)
        assertFalse(controller.accepts(old))
        controller.destination(old, null, tracker.snapshot)
        assertTrue(controller.waypoints(old, emptyList()))
        val fresh = checkNotNull(controller.beginRead())
        tracker.destination("synthetic-destination")
        controller.destination(fresh, destination, tracker.snapshot)
        controller.waypoints(fresh, emptyList())
        assertEquals(2L, controller.input()?.routeRevision)
    }

    @Test fun simulationInactiveUnknownAndMissingIdentityCannotPublishObservedInput() {
        val variants = listOf(NavigationSnapshot(10, 10, true, true, NavigationRouteState.ACTIVE),
            NavigationSnapshot(10, null, false, false, NavigationRouteState.INACTIVE),
            NavigationSnapshot(10, null, false, false, NavigationRouteState.UNKNOWN))
        for (state in variants) {
            val controller = controller()
            val old = complete(controller)
            controller.observe(state)
            assertFalse(old.isCurrent())
            assertNull(controller.input())
            assertNull(controller.beginRead())
        }
        val controller = controller()
        val read = checkNotNull(controller.beginRead())
        controller.destination(read, destination, NavigationSnapshot(10, null, false, false, NavigationRouteState.ACTIVE))
        controller.waypoints(read, emptyList())
        assertNull(controller.input())
    }

    @Test fun invalidOrIncompleteCoordinatesAndOversizedWaypointListStayNullWithoutAutomaticRetry() {
        for (points in listOf<List<BoundaryCoordinate>?>(null, listOf(first, second, destination),
            listOf(BoundaryCoordinate(Double.NaN, 37.0)), listOf(BoundaryCoordinate(37.0, 127.0)),
            listOf(BoundaryCoordinate(127.0, 45.1)))) {
            val controller = controller()
            val read = checkNotNull(controller.beginRead())
            controller.destination(read, destination, snapshot())
            assertFalse(controller.waypoints(read, points))
            assertNull(controller.input())
        }
        for (point in listOf(null, BoundaryCoordinate(127.0, Double.POSITIVE_INFINITY), BoundaryCoordinate(119.0, 37.0))) {
            val controller = controller()
            val read = checkNotNull(controller.beginRead())
            controller.destination(read, point, snapshot())
            assertFalse(controller.waypoints(read, emptyList()))
            assertNull(controller.input())
        }
    }

    @Test fun unrelatedRevisionJumpCannotBeAdoptedAsTheRequestedDestination() {
        val controller = controller()
        val read = checkNotNull(controller.beginRead())
        controller.destination(read, destination, snapshot(12))
        controller.waypoints(read, emptyList())
        assertNull(controller.input())
    }

    @Test fun externalRevocationCheckedFromWorkerIsTerminalForCapturedInstance() {
        val allowed = AtomicBoolean(true)
        val controller = controller(allowed = { allowed.get() })
        val old = complete(controller)
        allowed.set(false)
        assertFalse(CompletableFuture.supplyAsync { old.isCurrent() }.get(3, TimeUnit.SECONDS))
        allowed.set(true)
        assertFalse(old.isCurrent())
        assertNull(controller.input())
        val fresh = complete(controller)
        assertTrue(fresh.isCurrent())
        assertFalse(old.isCurrent())
    }

    @Test fun failedPermissionCheckAndCloseCannotBeUndoneByLateCallbacks() {
        val fail = AtomicBoolean(false)
        val controller = controller(allowed = { if (fail.get()) throw IllegalStateException("PRIVATE_DETAIL") else true })
        val old = complete(controller)
        fail.set(true)
        assertFalse(old.isCurrent())
        assertNull(controller.beginRead())
        fail.set(false)
        val read = checkNotNull(controller.beginRead())
        controller.close()
        assertNull(controller.callbackRead())
        controller.destination(read, destination, snapshot())
        controller.waypoints(read, emptyList())
        assertNull(controller.input())
        assertNull(controller.beginRead())
        assertFalse(old.isCurrent())
    }

    @Test fun sinkMayCloseReentrantlyWhenRefreshWithdrawsInput() {
        lateinit var controller: NavigationAuxiliaryInputController
        var closeOnNull = false
        controller = NavigationAuxiliaryInputController(snapshot(), { true }) {
            if (it == null && closeOnNull) controller.close()
        }
        val old = complete(controller)
        closeOnNull = true
        assertNull(controller.beginRead())
        assertFalse(old.isCurrent())
        assertNull(controller.callbackRead())
    }
}
