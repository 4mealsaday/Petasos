package com.petasos.app.platform.pleos.navigation

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.petasos.app.capability.navigation.*
import java.security.MessageDigest

/** NaviHelper 3.0.5 read-only adapter. No route/search/map command is exposed or called. */
class PleosNavigationSource(context: Context, private val initialRevision: Long = 0) : NavigationSource {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var active: Session? = null
    private var latestRevision = initialRevision

    override fun start(onEvent: (NavigationEvent) -> Unit) {
        requireMainThread()
        check(active == null)
        if (!permissionGranted()) { onEvent(NavigationEvent.Availability(NavigationAvailability.PERMISSION_DENIED)); return }
        val sdk = try { NaviHelper(appContext) } catch (_: Exception) {
            onEvent(NavigationEvent.Availability(NavigationAvailability.UNAVAILABLE)); return
        }
        val session = Session(sdk, onEvent)
        active = session
        emit(session, NavigationEvent.Availability(NavigationAvailability.CONNECTING))
        session.initializationAttempted = true
        try {
            sdk.initialize(onConnected = { post(session) {
                if (!permissionGranted()) fail(session, NavigationAvailability.PERMISSION_DENIED, "NAVI_PERMISSION_DENIED")
                else try {
                    session.listenerRegistered = true
                    sdk.addListener(session.listener)
                    session.connected = true
                    emit(session, NavigationEvent.Availability(NavigationAvailability.READY))
                    emit(session, NavigationEvent.Changed(session.tracker.snapshot))
                    requestState(session)
                } catch (_: Exception) { fail(session, NavigationAvailability.UNAVAILABLE, "NAVI_CONNECTION_FAILED") }
            } }, onFailed = { post(session) { fail(session, NavigationAvailability.UNAVAILABLE, "NAVI_CONNECTION_FAILED") } })
        } catch (_: Exception) { fail(session, NavigationAvailability.UNAVAILABLE, "NAVI_INITIALIZATION_FAILED") }
    }

    override fun refresh() {
        requireMainThread()
        active?.takeIf { it.connected }?.let { requestState(it) }
    }

    override fun close() {
        requireMainThread()
        val session = active ?: return
        active = null // Fence queued callbacks before listener removal or SDK release.
        session.tracker.unavailable()?.let { latestRevision = it.revision; session.onEvent(NavigationEvent.Changed(it)) }
        release(session)
        session.onEvent(NavigationEvent.Availability(NavigationAvailability.CLOSED))
    }

    @SuppressLint("MissingPermission")
    private fun requestState(session: Session) {
        if (!permissionGranted()) { fail(session, NavigationAvailability.PERMISSION_DENIED, "NAVI_PERMISSION_DENIED"); return }
        try { session.sdk.getRouteStateInfo() }
        catch (_: Exception) { fail(session, NavigationAvailability.UNAVAILABLE, "NAVI_READ_FAILED") }
    }

    @SuppressLint("MissingPermission")
    private fun requestIdentity(session: Session) {
        if (!permissionGranted()) { fail(session, NavigationAvailability.PERMISSION_DENIED, "NAVI_PERMISSION_DENIED"); return }
        try { session.sdk.getCurrentLocationInfo(); session.sdk.getDestinationInfo() }
        catch (_: Exception) { fail(session, NavigationAvailability.UNAVAILABLE, "NAVI_READ_FAILED") }
    }

    private inner class Session(val sdk: NaviHelper, val onEvent: (NavigationEvent) -> Unit) {
        val tracker = NavigationRevisionTracker(latestRevision)
        var initializationAttempted = false
        var listenerRegistered = false
        var connected = false
        val listener = object : NaviHelperEventListener {
            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) { post(this@Session) {
                emit(this@Session, NavigationEvent.ReadReceived(NavigationReading.ROUTE_STATE))
                if (!tracker.acceptsRouteReadings) return@post
                val routeActive = when (routeStateInfo.naviMode) {
                    NaviMode.ROUTE_GUIDING -> { changed(tracker.routeState(true)); true }
                    NaviMode.SIMULATION_GUIDING -> { changed(tracker.routeState(true, simulation = true)); true }
                    // NOT_WORKING/SAFE_GUIDING enum pages do not supply a reviewed active-route
                    // absence contract. Only explicit NavigationStatus INACTIVE/FREE_DRIVE does.
                    else -> { changed(tracker.unavailable()); false }
                }
                if (routeActive && tracker.acceptsRouteReadings) requestIdentity(this@Session)
            } }
            override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) { post(this@Session) {
                emit(this@Session, NavigationEvent.ReadReceived(NavigationReading.ROUTE_IDENTITY))
                if (!tracker.acceptsRouteReadings) return@post
                // Deliberately ignore current coordinates, heading, address and route index.
                changed(tracker.routeIdentity(currentLocationInfo.routeId))
            } }
            override fun onDestinationInfo(destinationInfo: DestinationInfo) { post(this@Session) {
                emit(this@Session, NavigationEvent.ReadReceived(NavigationReading.DESTINATION_IDENTITY))
                if (!tracker.acceptsRouteReadings) return@post
                if (!destinationInfo.latitude.isFinite() || destinationInfo.latitude !in -90.0..90.0 ||
                    !destinationInfo.longitude.isFinite() || destinationInfo.longitude !in -180.0..180.0) {
                    changed(tracker.pathChangeCancelled()); emit(this@Session, NavigationEvent.Failure("NAVI_DESTINATION_INVALID"))
                } else changed(tracker.destination(destinationIdentity(destinationInfo)))
            } }
            // Public callbacks have no request correlation or ordering guarantee. A mutation waits
            // for a completion event; if the provider omits it, route dependency stays unavailable.
            // Neither completion nor identity refresh proves GPS quality or road geometry.
            override fun onDestinationChanged() { post(this@Session) { changed(tracker.pathChangeStarted()) } }
            override fun onRouteStarted(routeStartInfo: RouteStartInfo) { post(this@Session) {
                changed(tracker.routeStarted())
            } }
            override fun onRouteOptionChanged(routeOption: RouteOption) { post(this@Session) {
                changed(tracker.pathChangeStarted())
            } }
            override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) { post(this@Session) {
                // onWayPointArrived is intentionally not handled: passage progress alone is not a path mutation.
                changed(tracker.pathChangeStarted())
            } }
            override fun onRoutingCompleted() { post(this@Session) {
                tracker.pathChangeCompleted()
                if (tracker.acceptsRouteReadings) requestState(this@Session)
            } }
            override fun onReRouteEnded() { post(this@Session) {
                // A provider can finish a reroute without first sending a search-start callback.
                // Invalidate the old identity even then; this is not a new lifecycle and cannot
                // reopen a cancelled/failed route when the completion callback arrives late.
                changed(tracker.pathChangeStarted())
                tracker.pathChangeCompleted()
                if (tracker.acceptsRouteReadings) requestState(this@Session)
            } }
            override fun onRouteCancelled() { post(this@Session) { changed(tracker.pathChangeCancelled()) } }
            override fun onNavigationStatus(status: NavigationStatus) { post(this@Session) {
                when (status) {
                    NavigationStatus.INACTIVE, NavigationStatus.FREE_DRIVE -> changed(tracker.navigationInactive())
                    NavigationStatus.ROUTE_DRIVE -> {
                        // Revoke a previous known-inactive context while the mode/identity read is
                        // pending. Repeated active-status notifications do not change path identity.
                        if (tracker.snapshot.routeState == NavigationRouteState.INACTIVE) changed(tracker.unavailable())
                        requestState(this@Session)
                    }
                    NavigationStatus.INITIALIZING -> changed(tracker.unavailable())
                }
            } }
            override fun onError(errorCode: NaviErrorCode) { post(this@Session) {
                changed(tracker.pathChangeCancelled())
                emit(this@Session, NavigationEvent.Failure("NAVI_REQUEST_FAILED"))
            } }
        }
        fun changed(snapshot: NavigationSnapshot?) { snapshot?.let { emit(this, NavigationEvent.Changed(it)) } }
    }

    private fun destinationIdentity(destination: DestinationInfo): String {
        // Countdown fields and display text are excluded. Only a memory-local digest reaches the tracker.
        val parts = listOf(destination.latitude.toString(), destination.longitude.toString(), destination.poiProvider,
            destination.poiId, destination.poiSubId ?: "")
        val encoded = parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return try { MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) } }
        finally { encoded.fill(0) }
    }

    private fun fail(session: Session, state: NavigationAvailability, code: String) {
        if (active !== session) return
        active = null
        session.tracker.unavailable()?.let { latestRevision = it.revision; session.onEvent(NavigationEvent.Changed(it)) }
        release(session)
        session.onEvent(NavigationEvent.Failure(code)); session.onEvent(NavigationEvent.Availability(state))
    }

    private fun release(session: Session) {
        if (session.listenerRegistered) try { session.sdk.removeListener(session.listener) } catch (_: Exception) { session.onEvent(NavigationEvent.Failure("NAVI_RELEASE_FAILED")) }
        if (session.initializationAttempted) try { session.sdk.release() } catch (_: Exception) { session.onEvent(NavigationEvent.Failure("NAVI_RELEASE_FAILED")) }
        session.connected = false
    }

    private fun emit(session: Session, event: NavigationEvent) {
        if (active === session) {
            if (event is NavigationEvent.Changed) latestRevision = event.snapshot.revision
            session.onEvent(event)
        }
    }
    private fun post(session: Session, action: () -> Unit) { main.post {
        if (active === session) {
            if (!permissionGranted()) fail(session, NavigationAvailability.PERMISSION_DENIED, "NAVI_PERMISSION_DENIED") else action()
        }
    } }
    private fun permissionGranted(): Boolean = appContext.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    private fun requireMainThread() { check(Looper.myLooper() === Looper.getMainLooper()) }

    companion object { const val PERMISSION = "pleos.car.permission.NAVI_ROUTE" }
}
