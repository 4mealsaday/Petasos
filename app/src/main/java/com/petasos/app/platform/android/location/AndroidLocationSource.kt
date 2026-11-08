package com.petasos.app.platform.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.petasos.app.capability.location.AndroidLocationMetadata
import com.petasos.app.capability.location.LocationEvent
import com.petasos.app.capability.location.LocationOperation
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.capability.location.LocationSource

data class AndroidLocationProviderCapability(val name: String, val enabled: Boolean)
data class AndroidLocationCapabilities(
    val locationEnabled: Boolean,
    val finePermissionGranted: Boolean,
    val coarsePermissionGranted: Boolean,
    val providers: List<AndroidLocationProviderCapability>,
) {
    fun preferredProvider(): String? = if (!locationEnabled) null else listOf("fused", "gps", "network")
        .firstOrNull { name -> providers.any { it.name == name && it.enabled } }
}

/**
 * Explicit-provider, foreground standard Android adapter. It never requests permission, changes
 * settings, chooses another provider after denial, or interprets the Pleos SDK's location fields.
 * The external guard must include current consent, foreground lifecycle and the Pleos access gate.
 */
class AndroidLocationSource(
    context: Context,
    private val provider: String,
    private val externalAccessAllowed: () -> Boolean = { false },
    private val minUpdateIntervalMs: Long = 1_000,
) : LocationSource {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var activeSession: Session? = null

    init { require(provider.isNotBlank() && minUpdateIntervalMs >= 0) }

    @SuppressLint("MissingPermission") // Both Android and the external access guard are checked before every read/subscription.
    override fun start(onEvent: (LocationEvent) -> Unit) {
        requireMainThread()
        check(activeSession == null) { "Close the current location session before starting another." }
        val manager = try { appContext.getSystemService(LocationManager::class.java) }
            catch (failure: Exception) { onEvent(diagnostic(LocationOperation.CREATE, failure)); return }
        if (manager == null) { onEvent(LocationEvent.Availability(false)); return }
        val session = Session(manager, onEvent)
        activeSession = session
        val supported = try { provider in manager.allProviders }
            catch (failure: Exception) { failAndClose(session, LocationOperation.CHECK_AVAILABILITY, failure); return }
        emit(session, LocationEvent.Availability(supported))
        if (activeSession !== session) return
        if (!supported) { closeSession(session, emptyList()); return }
        if (!checkAccess(session)) return

        try {
            val filter = IntentFilter().apply {
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            }
            session.receiverRegistered = true
            if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(session.receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") appContext.registerReceiver(session.receiver, filter)
        } catch (failure: Exception) {
            failAndClose(session, LocationOperation.REGISTER_UPDATES, failure)
            return
        }
        if (!checkAccess(session)) return
        session.registrationAttempted = true
        try {
            manager.requestLocationUpdates(provider, minUpdateIntervalMs, 0f, session.listener, Looper.getMainLooper())
        } catch (failure: Exception) {
            failAndClose(session, LocationOperation.REGISTER_UPDATES, failure)
            return
        }
        emit(session, LocationEvent.SubscriptionRequested)
        if (!checkAccess(session)) return
        handler.postDelayed(session.accessWatchdog, ACCESS_RECHECK_MS)
        try {
            val cached = manager.getLastKnownLocation(provider)
            if (cached == null) enqueue(session, LocationEvent.NoLocation(LocationReadingKind.LAST_KNOWN))
            else enqueueLocation(session, LocationReadingKind.LAST_KNOWN, cached)
        } catch (failure: SecurityException) {
            failAndClose(session, LocationOperation.LAST_KNOWN, failure)
        } catch (failure: Exception) {
            // A non-permission cached-read failure does not cancel already registered live updates.
            enqueue(session, diagnostic(LocationOperation.LAST_KNOWN, failure))
        }
    }

    override fun close() {
        requireMainThread()
        activeSession?.let { closeSession(it, emptyList()) }
    }

    /** Invalidation precedes teardown and diagnostics; queued callbacks cannot reopen this session. */
    @SuppressLint("MissingPermission") // Cleanup is attempted after permission/guard revocation too.
    private fun closeSession(session: Session, events: List<LocationEvent>) {
        if (activeSession !== session) return
        activeSession = null
        handler.removeCallbacks(session.accessWatchdog)
        val cleanup = mutableListOf<LocationEvent>()
        if (session.registrationAttempted) {
            try { session.manager.removeUpdates(session.listener) }
            catch (failure: Exception) { cleanup += diagnostic(LocationOperation.UNREGISTER_UPDATES, failure) }
            session.registrationAttempted = false
        }
        if (session.receiverRegistered) {
            try { appContext.unregisterReceiver(session.receiver) }
            catch (failure: Exception) { cleanup += diagnostic(LocationOperation.RELEASE, failure) }
            session.receiverRegistered = false
        }
        (events + cleanup).forEach(session.onEvent)
    }

    private fun checkAccess(session: Session): Boolean {
        if (activeSession !== session) return false
        val permitted = try { externalAccessAllowed() && LocationAccess.hasAndroidLocationPermission(appContext) }
            catch (_: Exception) { false }
        if (!permitted) { closeSession(session, listOf(LocationEvent.PermissionDenied)); return false }
        val enabled = try { session.manager.isLocationEnabled && session.manager.isProviderEnabled(provider) }
            catch (failure: Exception) { failAndClose(session, LocationOperation.CHECK_AVAILABILITY, failure); return false }
        if (!enabled) { closeSession(session, listOf(LocationEvent.NoLocation(LocationReadingKind.UPDATE))); return false }
        return activeSession === session
    }

    private fun emit(session: Session, event: LocationEvent) {
        if (activeSession === session) session.onEvent(event)
    }

    private fun enqueue(session: Session, event: LocationEvent) {
        handler.post { if (checkAccess(session)) emit(session, event) }
    }

    private fun enqueueLocation(session: Session, kind: LocationReadingKind, location: Location) {
        val epoch = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()
        val event = try { LocationEvent.SampleReceived(kind, sample(location, epoch, elapsedNanos)) }
            catch (failure: Exception) {
                handler.post { failAndClose(session, LocationOperation.UPDATES, failure) }
                return
            }
        enqueue(session, event)
    }

    private fun failAndClose(session: Session, operation: LocationOperation, exception: Exception) {
        val event = if (exception is SecurityException) LocationEvent.PermissionDenied else diagnostic(operation, exception)
        closeSession(session, listOf(event))
    }

    private fun diagnostic(operation: LocationOperation, exception: Exception) = LocationEvent.Failure(operation, exception.javaClass.simpleName)

    private fun requireMainThread() = check(Looper.myLooper() === Looper.getMainLooper()) {
        "LocationSource start and close must run on the main thread."
    }

    private inner class Session(val manager: LocationManager, val onEvent: (LocationEvent) -> Unit) {
        var registrationAttempted = false
        var receiverRegistered = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { checkAccess(this@Session) }
        }
        val accessWatchdog = object : Runnable {
            override fun run() {
                if (checkAccess(this@Session)) handler.postDelayed(this, ACCESS_RECHECK_MS)
            }
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (activeSession === this@Session) enqueueLocation(this@Session, LocationReadingKind.UPDATE, location)
            }
            override fun onProviderDisabled(provider: String) {
                handler.post { closeSession(this@Session, listOf(LocationEvent.NoLocation(LocationReadingKind.UPDATE))) }
            }
            override fun onProviderEnabled(provider: String) { handler.post { checkAccess(this@Session) } }
            @Deprecated("Required on Android versions below API 30; no status inference is performed.")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { handler.post { checkAccess(this@Session) } }
        }
    }

    companion object {
        private const val ACCESS_RECHECK_MS = 1_000L

        /** Provider names/states/permission bits only: no last-known, current-location or subscription call. */
        fun inspect(context: Context): AndroidLocationCapabilities {
            val app = context.applicationContext
            val manager = app.getSystemService(LocationManager::class.java)
            return AndroidLocationCapabilities(
                locationEnabled = manager?.isLocationEnabled == true,
                finePermissionGranted = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                coarsePermissionGranted = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                providers = manager?.allProviders.orEmpty().distinct().sorted().map { AndroidLocationProviderCapability(it, manager?.isProviderEnabled(it) == true) },
            )
        }

        fun preferredProvider(context: Context): String? = inspect(context).preferredProvider()

        /** Synthetic device probes can exercise the exact copy path without subscribing or reading real coordinates. */
        internal fun sample(location: Location, receivedAtEpochMs: Long, receivedAtElapsedNanos: Long): LocationSample {
            val metadata = AndroidLocationMetadata(location.provider, location.elapsedRealtimeNanos, receivedAtElapsedNanos,
                if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                if (Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider,
                if (Build.VERSION.SDK_INT >= 29 && location.hasElapsedRealtimeUncertaintyNanos()) location.elapsedRealtimeUncertaintyNanos else null)
            return LocationSample(location.latitude, location.longitude,
                if (location.hasAltitude()) location.altitude else Double.NaN,
                if (location.hasBearing()) location.bearing else Float.NaN,
                Float.NaN, location.time, receivedAtEpochMs, receivedAtElapsedNanos / 1_000_000L, metadata)
        }
    }
}
