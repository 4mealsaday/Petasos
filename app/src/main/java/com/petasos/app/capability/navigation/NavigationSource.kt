package com.petasos.app.capability.navigation

/** Read-only navigation context. Navigation coordinates are never appended as actual received GPS points. */
interface NavigationSource : AutoCloseable {
    fun start(onEvent: (NavigationEvent) -> Unit)
    fun refresh()
    override fun close()
}

enum class NavigationAvailability { CONNECTING, READY, PERMISSION_DENIED, UNAVAILABLE, CLOSED }
enum class NavigationReading { ROUTE_STATE, ROUTE_IDENTITY, DESTINATION_IDENTITY }
/** Read from an explicit provider state; absent route identity never implies INACTIVE. */
enum class NavigationRouteState { UNKNOWN, INACTIVE, ACTIVE }

data class NavigationSnapshot(
    val revision: Long,
    /** Null alone cannot distinguish a known inactive route from unavailable identity/data. */
    val routeDependency: Long?,
    val hasDestination: Boolean,
    val simulated: Boolean,
    val routeState: NavigationRouteState = NavigationRouteState.UNKNOWN,
) {
    init { require(revision >= 0 && (routeDependency == null || routeDependency == revision)) }

    /** Navigation simulation cannot authorize a forecast for an observed trip. */
    val observedRouteDependency: Long? get() = routeDependency.takeUnless { simulated }
    /** Simulation is not an observed route context for actual location-based guidance. */
    val observedRouteState: NavigationRouteState get() =
        if (simulated) NavigationRouteState.UNKNOWN else routeState
}

sealed interface NavigationEvent {
    data class Availability(val state: NavigationAvailability) : NavigationEvent
    data class Changed(val snapshot: NavigationSnapshot) : NavigationEvent
    data class ReadReceived(val kind: NavigationReading) : NavigationEvent
    /** Fixed enum/code only; SDK errors, destination text and coordinates are not diagnostic payloads. */
    data class Failure(val code: String) : NavigationEvent
}

/** Serial, session-local revisions. GPS movement, distance countdown and ETA changes never change identity. */
class NavigationRevisionTracker(initialRevision: Long = 0) {
    init { require(initialRevision >= 0) }
    private var revision = initialRevision
    private var routing = false
    private var routeId: String? = null
    private var destinationIdentity: String? = null
    private var simulated = false
    private var observedState = NavigationRouteState.UNKNOWN
    /** A path mutation cannot be undone by an in-flight state or identity callback. */
    var awaitingRouteCompletion: Boolean = false
        private set
    private var cancelledUntilStart = false
    val acceptsRouteReadings: Boolean get() = !awaitingRouteCompletion && !cancelledUntilStart
    val snapshot: NavigationSnapshot get() = NavigationSnapshot(revision,
        if (routing && (routeId != null || destinationIdentity != null)) revision else null,
        destinationIdentity != null, simulated, observedState)

    fun routeState(active: Boolean, simulation: Boolean = false): NavigationSnapshot? {
        if (!active) return navigationInactive()
        if (!acceptsRouteReadings) return null
        val before = snapshot
        routing = true
        simulated = simulation
        observedState = NavigationRouteState.ACTIVE
        return snapshot.takeIf { it != before }
    }

    fun routeIdentity(identity: String?): NavigationSnapshot? {
        if (!routing || !acceptsRouteReadings) return null
        val next = identity?.takeIf { it.isNotBlank() }
        if (next == routeId) return null
        routeId = next
        increment()
        return snapshot
    }

    fun destination(identity: String): NavigationSnapshot? {
        if (!routing || !acceptsRouteReadings || identity.isBlank() || identity == destinationIdentity) return null
        destinationIdentity = identity
        increment()
        return snapshot
    }

    /** An explicit completed reroute can change path geometry while keeping the SDK's routeId. */
    fun rerouted(): NavigationSnapshot? {
        if (!routing || !acceptsRouteReadings || snapshot.routeDependency == null) return null
        increment()
        return snapshot
    }

    /** Only an explicit new search lifecycle can recover a cancelled or failed route. */
    fun routeStarted(): NavigationSnapshot? {
        cancelledUntilStart = false
        return pathChangeStarted()
    }

    /** Option, waypoint and destination changes cannot reopen a cancelled search lifecycle. */
    fun pathChangeStarted(): NavigationSnapshot? {
        if (cancelledUntilStart) return null
        awaitingRouteCompletion = true
        return unavailable()
    }

    /** Completion only opens the fence; a fresh active state and identity are still required. */
    fun pathChangeCompleted() {
        if (cancelledUntilStart) return
        awaitingRouteCompletion = false
    }

    fun pathChangeCancelled(): NavigationSnapshot? {
        cancelledUntilStart = true
        awaitingRouteCompletion = false
        return unavailable()
    }

    /** Initial free-drive reads are normal; ending an observed/searching route is terminal. */
    fun navigationInactive(): NavigationSnapshot? {
        val before = snapshot
        if (routing || awaitingRouteCompletion || cancelledUntilStart) {
            cancelledUntilStart = true
            awaitingRouteCompletion = false
        }
        clearRoute()
        simulated = false
        observedState = NavigationRouteState.INACTIVE
        return snapshot.takeIf { it != before }
    }

    /** A generic invalidation never opens either the completion or cancellation fence. */
    fun unavailable(): NavigationSnapshot? {
        val before = snapshot
        clearRoute()
        simulated = false
        observedState = NavigationRouteState.UNKNOWN
        return snapshot.takeIf { it != before }
    }

    private fun clearRoute() {
        if (routeId != null || destinationIdentity != null) increment()
        routing = false; routeId = null; destinationIdentity = null
    }
    private fun increment() { check(revision < Long.MAX_VALUE); revision++ }
}
