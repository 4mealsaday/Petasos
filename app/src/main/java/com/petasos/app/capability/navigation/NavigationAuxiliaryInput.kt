package com.petasos.app.capability.navigation

import com.petasos.app.location.BoundaryCoordinate
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/** Separate transient port: these coordinates never belong to NavigationSnapshot or Room. */
interface NavigationAuxiliaryInputSource {
    /** Main-thread subscription; the current value (including null) is delivered immediately. */
    fun setAuxiliaryInputSink(sink: ((NavigationAuxiliaryInput?) -> Unit)?)
    fun auxiliaryInput(): NavigationAuxiliaryInput?
}

/** Destination/waypoint input for an AUXILIARY query, never proof of the active road geometry. */
class NavigationAuxiliaryInput internal constructor(
    val routeRevision: Long,
    val inputKey: String,
    val destination: BoundaryCoordinate,
    waypoints: List<BoundaryCoordinate>,
    private val current: () -> Boolean,
) {
    val waypoints: List<BoundaryCoordinate> = Collections.unmodifiableList(ArrayList(waypoints))
    /** May be called by a network worker. A revoked instance never becomes current again. */
    fun isCurrent(): Boolean = try { current() } catch (_: Exception) { false }
    override fun toString() = "NavigationAuxiliaryInput([redacted])"
}

/** Opaque read identity captured at SDK callback ingress, before posting to the main thread. */
class NavigationAuxiliaryRead internal constructor() {
    override fun toString() = "NavigationAuxiliaryRead([redacted])"
}

/**
 * Serial controller with thread-safe lease checks. At most one destination/waypoint pair is in
 * flight. Invalidated reads must drain before another pair starts; missing callbacks stay null.
 * SDK responses have no request ID: this fences known in-flight and queued callbacks, without
 * claiming that it can identify an unsolicited duplicate delivered in a later provider lifecycle.
 */
class NavigationAuxiliaryInputController(
    initialSnapshot: NavigationSnapshot,
    private val allowed: () -> Boolean,
    private val onChanged: (NavigationAuxiliaryInput?) -> Unit,
) {
    private var snapshot = initialSnapshot
    private var pending: Pending? = null
    @Volatile private var ingress: NavigationAuxiliaryRead? = null
    @Volatile private var closed = false
    private val current = AtomicReference<NavigationAuxiliaryInput?>()

    fun input(): NavigationAuxiliaryInput? = current.get()?.takeIf { it.isCurrent() }
    fun callbackRead(): NavigationAuxiliaryRead? = ingress

    fun observe(next: NavigationSnapshot) {
        if (closed) return
        if (snapshot != next) invalidate()
        snapshot = next
    }

    fun beginRead(): NavigationAuxiliaryRead? {
        if (closed || pending != null || !eligible(snapshot) || !permitted()) return null
        clearCurrent()
        if (closed || pending != null || !eligible(snapshot) || !permitted()) return null
        val token = NavigationAuxiliaryRead()
        pending = Pending(token)
        ingress = token
        return token
    }

    /** Used before updating the ordinary destination fingerprint from a requested response. */
    fun accepts(token: NavigationAuxiliaryRead?): Boolean =
        token != null && !closed && pending?.let { it.token === token && it.valid && !it.destinationReceived } == true && permitted()

    /** Returns true only when an invalidated old pair drained and a fresh query can be requested. */
    fun destination(token: NavigationAuxiliaryRead?, point: BoundaryCoordinate?, afterIdentity: NavigationSnapshot): Boolean {
        val read = pending?.takeIf { it.token === token && !it.destinationReceived } ?: return false
        read.destinationReceived = true
        if (read.valid) {
            val revisionMatches = afterIdentity.revision == snapshot.revision ||
                snapshot.revision < Long.MAX_VALUE && afterIdentity.revision == snapshot.revision + 1
            if (point == null || !valid(point) || !eligible(afterIdentity) || !afterIdentity.hasDestination ||
                afterIdentity.observedRouteDependency == null || !revisionMatches || !permitted()) reject(read)
            else { snapshot = afterIdentity; read.destination = point }
        }
        return complete(read)
    }

    fun waypoints(token: NavigationAuxiliaryRead?, points: List<BoundaryCoordinate>?): Boolean {
        val read = pending?.takeIf { it.token === token && !it.waypointsReceived } ?: return false
        read.waypointsReceived = true
        if (read.valid) {
            if (points == null || points.size > 2 || points.any { !valid(it) } || !permitted()) reject(read)
            else read.waypoints = ArrayList(points) // Preserve provider list order; never infer/sort by ETA or distance.
        }
        return complete(read)
    }

    fun invalidate() {
        clearCurrent()
        pending?.let { reject(it); it.restartAfterDrain = true }
    }

    fun close() {
        closed = true
        clearCurrent()
        pending = null
        ingress = null
    }

    private fun complete(read: Pending): Boolean {
        if (!read.destinationReceived || !read.waypointsReceived) return false
        pending = null
        ingress = null
        val destination = read.destination
        val waypoints = read.waypoints
        if (!closed && read.valid && destination != null && waypoints != null && eligible(snapshot) && permitted()) {
            lateinit var input: NavigationAuxiliaryInput
            input = NavigationAuxiliaryInput(snapshot.revision, key(snapshot.revision, destination, waypoints), destination, waypoints) {
                if (closed || current.get() !== input) false else if (permitted()) true else {
                    current.compareAndSet(input, null)
                    false
                }
            }
            current.set(input)
            onChanged(input)
        }
        return read.restartAfterDrain && !closed
    }

    private fun clearCurrent() { if (current.getAndSet(null) != null) onChanged(null) }
    private fun reject(read: Pending) { read.valid = false; read.destination = null; read.waypoints = null }
    private fun permitted(): Boolean = try { allowed() } catch (_: Exception) { false }
    private fun eligible(value: NavigationSnapshot) = value.observedRouteState == NavigationRouteState.ACTIVE && !value.simulated
    private fun valid(point: BoundaryCoordinate) = point.longitude.isFinite() && point.latitude.isFinite() &&
        point.longitude in 120.0..135.0 && point.latitude in 30.0..45.0

    private fun key(revision: Long, destination: BoundaryCoordinate, waypoints: List<BoundaryCoordinate>): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeUTF("petasos-pleos-auxiliary-input-v1")
            data.writeLong(revision)
            data.writeDouble(destination.longitude); data.writeDouble(destination.latitude)
            data.writeInt(waypoints.size)
            waypoints.forEach { data.writeDouble(it.longitude); data.writeDouble(it.latitude) }
        }
        val bytes = output.toByteArray()
        return try { MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
        finally { bytes.fill(0) }
    }

    private class Pending(val token: NavigationAuxiliaryRead) {
        var valid = true
        var restartAfterDrain = false
        var destinationReceived = false
        var waypointsReceived = false
        var destination: BoundaryCoordinate? = null
        var waypoints: List<BoundaryCoordinate>? = null
    }
}
