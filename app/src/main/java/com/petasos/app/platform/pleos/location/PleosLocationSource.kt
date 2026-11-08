package com.petasos.app.platform.pleos.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.petasos.app.capability.location.LocationEvent
import com.petasos.app.capability.location.LocationOperation
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.capability.location.LocationSource

/**
 * UI-independent Pleos FusedLocation 1.1.3 adapter for foreground capability verification.
 * It neither requests permission through UI nor stores locations or interprets confidence.
 */
class PleosLocationSource(context: Context) : LocationSource {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeSession: Session? = null

    @SuppressLint("MissingPermission") // The SDK's dangerous permission is checked below.
    override fun start(onEvent: (LocationEvent) -> Unit) {
        requireMainThread()
        check(activeSession == null) { "Close the current location session before starting another." }

        val sdk = try {
            FusedLocation(appContext)
        } catch (exception: Exception) {
            onEvent(failure(LocationOperation.CREATE, exception))
            return
        }
        val session = Session(sdk, onEvent)
        activeSession = session

        val supported = try {
            sdk.isSupported()
        } catch (exception: Exception) {
            failAndClose(session, LocationOperation.CHECK_AVAILABILITY, exception)
            return
        }
        emit(session, LocationEvent.Availability(supported))
        if (!supported || activeSession !== session) return

        val permissionGranted = try {
            appContext.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        } catch (exception: Exception) {
            failAndClose(session, LocationOperation.CHECK_PERMISSION, exception)
            return
        }
        if (!permissionGranted) {
            emit(session, LocationEvent.PermissionDenied)
            return
        }

        emit(session, LocationEvent.InitializationRequested)
        if (activeSession !== session) return
        session.initializationAttempted = true
        try {
            sdk.initialize()
        } catch (exception: Exception) {
            failAndClose(session, LocationOperation.INITIALIZE, exception)
            return
        }

        // Mark an attempt before calling the SDK so partial registration is cleaned up on failure.
        session.registrationAttempted = true
        try {
            sdk.registerFusedLocationCallback(session.updatesListener)
        } catch (exception: Exception) {
            failAndClose(session, LocationOperation.REGISTER_UPDATES, exception)
            return
        }
        emit(session, LocationEvent.SubscriptionRequested)
        if (activeSession !== session) return

        try {
            sdk.getLastKnownLocation(session.lastKnownListener)
        } catch (exception: Exception) {
            // A failed cached read does not discard an already registered live subscription.
            emit(session, failure(LocationOperation.LAST_KNOWN, exception))
        }
    }

    override fun close() {
        requireMainThread()
        val session = activeSession ?: return
        // Invalidate first: callbacks queued before or during teardown cannot reach the consumer.
        activeSession = null
        val failures = releaseSession(session)
        failures.forEach(session.onEvent)
    }

    private fun emit(session: Session, event: LocationEvent) {
        if (activeSession === session) session.onEvent(event)
    }

    private fun enqueue(session: Session, event: LocationEvent) {
        // Always post, including callbacks invoked synchronously by the SDK, to avoid reentrancy.
        mainHandler.post { emit(session, event) }
    }

    private fun failAndClose(
        session: Session,
        operation: LocationOperation,
        exception: Exception,
    ) {
        if (activeSession !== session) return
        activeSession = null
        val failures = listOf(failure(operation, exception)) + releaseSession(session)
        failures.forEach(session.onEvent)
    }

    @SuppressLint("MissingPermission") // Teardown is attempted even if permission was revoked.
    private fun releaseSession(session: Session): List<LocationEvent.Failure> {
        val failures = mutableListOf<LocationEvent.Failure>()
        if (session.registrationAttempted) {
            try {
                session.sdk.unregisterFusedLocationCallback(session.updatesListener)
            } catch (exception: Exception) {
                failures += failure(LocationOperation.UNREGISTER_UPDATES, exception)
            } finally {
                session.registrationAttempted = false
            }
        }
        if (session.initializationAttempted) {
            try {
                session.sdk.release()
            } catch (exception: Exception) {
                failures += failure(LocationOperation.RELEASE, exception)
            } finally {
                session.initializationAttempted = false
            }
        }
        return failures
    }

    private fun listener(session: Session, kind: LocationReadingKind): FusedLocationListener =
        object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                val event = if (location == null) {
                    LocationEvent.NoLocation(kind)
                } else {
                    // Capture receipt times on the callback thread, before waiting for the UI thread.
                    val epochMillis = System.currentTimeMillis()
                    val elapsedMillis = SystemClock.elapsedRealtime()
                    LocationEvent.SampleReceived(
                        kind = kind,
                        sample = LocationSample(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            altitude = location.altitude,
                            heading = location.heading,
                            rawConfidence = location.confidence,
                            rawTimestampMillis = location.timestampMillis,
                            receivedAtEpochMillis = epochMillis,
                            receivedAtElapsedRealtimeMillis = elapsedMillis,
                        ),
                    )
                }
                enqueue(session, event)
            }

            override fun onError(exception: Exception) {
                val operation = when (kind) {
                    LocationReadingKind.LAST_KNOWN -> LocationOperation.LAST_KNOWN
                    LocationReadingKind.UPDATE -> LocationOperation.UPDATES
                }
                enqueue(session, failure(operation, exception))
            }
        }

    private fun failure(operation: LocationOperation, exception: Exception) =
        LocationEvent.Failure(operation, exception.javaClass.simpleName)

    private fun requireMainThread() {
        check(Looper.myLooper() === Looper.getMainLooper()) {
            "LocationSource start and close must run on the main thread."
        }
    }

    private inner class Session(
        val sdk: FusedLocation,
        val onEvent: (LocationEvent) -> Unit,
    ) {
        var initializationAttempted = false
        var registrationAttempted = false
        val updatesListener = listener(this, LocationReadingKind.UPDATE)
        val lastKnownListener = listener(this, LocationReadingKind.LAST_KNOWN)
    }

    companion object {
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
    }
}
