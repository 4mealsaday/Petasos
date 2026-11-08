package com.petasos.app.location

import com.petasos.app.capability.location.LocationEvent
import com.petasos.app.capability.location.LocationOperation
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSource

/**
 * Main-thread owned composition: Pleos supplies a current access signal, a separate source supplies
 * measurements. Pleos coordinates, confidence and measurement timestamps are never interpreted or
 * forwarded. The caller supplies the access-signal freshness policy and a main-thread scheduler.
 */
class PleosAuthorizedLocationSource(
    private val accessSourceFactory: () -> LocationSource,
    private val measurementSourceFactory: (() -> Boolean) -> LocationSource,
    private val externalAccessAllowed: () -> Boolean,
    private val elapsedMs: () -> Long,
    private val schedule: (delayMs: Long, task: () -> Unit) -> (() -> Unit),
    private val guardFreshnessMs: Long,
) : LocationSource {
    private var active: Session? = null

    init { require(guardFreshnessMs > 0) }

    override fun start(onEvent: (LocationEvent) -> Unit) {
        check(active == null) { "LOCATION_SESSION_ALREADY_STARTED" }
        val session = Session(onEvent)
        active = session
        if (!externalAllowed(session)) return
        val source = try { OwnedSource(accessSourceFactory()) }
        catch (_: Exception) { fail(session, LocationOperation.CREATE, "AccessSourceUnavailable"); return }
        if (active !== session) { source.close(); return }
        session.access = source
        if (!externalAllowed(session)) return
        try { source.value.start { accessEvent(session, source, it) } }
        catch (_: Exception) {
            if (active === session && session.access === source) {
                session.access = null
                source.close()
                fail(session, LocationOperation.REGISTER_UPDATES, "AccessSubscriptionFailed")
            }
        }
    }

    override fun close() {
        val session = active ?: return
        active = null // Fence callbacks before timer/provider cleanup, including reentrant callbacks.
        revoke(session)
        val access = session.access
        session.access = null
        access?.close()
    }

    private fun accessEvent(session: Session, source: OwnedSource, event: LocationEvent) {
        if (active !== session || session.revoking || session.access !== source || source.closed ||
            !externalAllowed(session)) return
        when (event) {
            is LocationEvent.SampleReceived -> {
                if (event.kind != LocationReadingKind.UPDATE) return
                val now = clock(session) ?: return
                // This is local callback receipt time, not the undocumented Pleos measurement time.
                val receipt = event.sample.receivedAtElapsedRealtimeMillis
                if (receipt < 0 || receipt > now) {
                    doubtful(session, LocationEvent.NoLocation(LocationReadingKind.UPDATE))
                    return
                }
                if (receipt <= session.revokedAt || receipt < session.lastReceipt) return
                if (now - receipt >= guardFreshnessMs) {
                    doubtful(session, LocationEvent.NoLocation(LocationReadingKind.UPDATE))
                    return
                }
                session.lastReceipt = receipt
                val previous = session.lease
                val lease = Lease(receipt)
                session.lease = lease
                cancel(previous?.timer)
                if (active !== session || session.lease !== lease || !externalAllowed(session)) return
                if (!arm(session, lease, guardFreshnessMs - (now - receipt))) return
                if (session.measurement == null) startMeasurement(session)
            }
            is LocationEvent.NoLocation -> if (event.kind == LocationReadingKind.LAST_KNOWN)
                emit(session, event) else doubtful(session, event)
            is LocationEvent.Failure -> if (event.operation == LocationOperation.LAST_KNOWN)
                emit(session, event) else doubtful(session, event)
            LocationEvent.PermissionDenied -> doubtful(session, event)
            is LocationEvent.Availability -> if (event.supported) emit(session, event) else doubtful(session, event)
            LocationEvent.InitializationRequested, LocationEvent.SubscriptionRequested -> emit(session, event)
        }
    }

    private fun startMeasurement(session: Session) {
        if (!authorized(session)) return
        val measurement = Measurement()
        session.measurement = measurement
        val source = try { OwnedSource(measurementSourceFactory { authorized(session, measurement) }) }
        catch (_: Exception) { fail(session, LocationOperation.CREATE, "MeasurementSourceUnavailable"); return }
        if (active !== session || session.measurement !== measurement) { source.close(); return }
        measurement.source = source
        if (!authorized(session, measurement)) return
        try {
            source.value.start { event ->
                if (source.closed || !authorized(session, measurement)) return@start
                when (event) {
                    // No cache is normal at cold start; keep waiting for a current measurement.
                    is LocationEvent.NoLocation -> if (event.kind == LocationReadingKind.LAST_KNOWN)
                        emit(session, event) else doubtful(session, event)
                    is LocationEvent.Failure -> if (event.operation == LocationOperation.LAST_KNOWN)
                        emit(session, event) else doubtful(session, event)
                    LocationEvent.PermissionDenied -> doubtful(session, event)
                    is LocationEvent.Availability -> if (event.supported) emit(session, event) else doubtful(session, event)
                    else -> emit(session, event)
                }
            }
        } catch (_: Exception) {
            if (active === session && session.measurement === measurement)
                fail(session, LocationOperation.REGISTER_UPDATES, "MeasurementSubscriptionFailed")
        }
    }

    /** Passed to the measurement adapter as well as checked before forwarding each of its events. */
    private fun authorized(session: Session, measurement: Measurement? = null): Boolean {
        if (active !== session || session.revoking || measurement != null && session.measurement !== measurement) return false
        if (!externalAllowed(session)) return false
        val lease = session.lease ?: return false
        val now = clock(session) ?: return false
        if (now < lease.receipt || now - lease.receipt >= guardFreshnessMs) {
            doubtful(session, LocationEvent.NoLocation(LocationReadingKind.UPDATE))
            return false
        }
        return active === session && session.lease === lease &&
            (measurement == null || session.measurement === measurement)
    }

    private fun externalAllowed(session: Session): Boolean {
        if (active !== session || session.externallyBlocked) return false
        val allowed = try { externalAccessAllowed() } catch (_: Exception) { false }
        if (allowed) return active === session
        // Consent/visibility/power loss must not leave even the access-signal subscription running.
        session.externallyBlocked = true
        val access = session.access
        session.access = null
        revoke(session)
        access?.close()
        emit(session, LocationEvent.PermissionDenied)
        return false
    }

    private fun clock(session: Session): Long? {
        val now = try { elapsedMs() } catch (_: Exception) { -1L }
        if (now < 0 || now < session.lastClock) {
            fail(session, LocationOperation.UPDATES, "InvalidMonotonicClock")
            return null
        }
        session.lastClock = now
        return now
    }

    private fun arm(session: Session, lease: Lease, delayMs: Long): Boolean {
        val timer = Timer()
        lease.timer = timer
        var scheduling = true
        var synchronous = false
        val cancellation = try {
            schedule(delayMs) {
                if (scheduling) { synchronous = true; return@schedule }
                if (active !== session || session.lease !== lease || lease.timer !== timer) return@schedule
                lease.timer = null
                if (!authorized(session)) return@schedule
                val now = clock(session) ?: return@schedule
                arm(session, lease, guardFreshnessMs - (now - lease.receipt))
            }
        } catch (_: Exception) {
            scheduling = false
            if (active === session && session.lease === lease)
                fail(session, LocationOperation.REGISTER_UPDATES, "AccessWatchdogUnavailable")
            return false
        }
        scheduling = false
        timer.cancel = cancellation
        if (synchronous || active !== session || session.lease !== lease || lease.timer !== timer) {
            cancel(timer)
            if (synchronous && active === session && session.lease === lease)
                fail(session, LocationOperation.REGISTER_UPDATES, "AccessWatchdogInvalid")
            return false
        }
        return true
    }

    private fun doubtful(session: Session, event: LocationEvent) {
        if (active !== session) return
        revoke(session)
        emit(session, event)
    }

    private fun fail(session: Session, operation: LocationOperation, type: String) =
        doubtful(session, LocationEvent.Failure(operation, type))

    private fun revoke(session: Session) {
        if (session.revoking) return
        session.revoking = true
        val lease = session.lease
        val measurement = session.measurement
        session.lease = null
        session.measurement = null
        val now = try { elapsedMs() } catch (_: Exception) { -1L }
        session.revokedAt = maxOf(session.revokedAt, session.lastClock, now)
        cancel(lease?.timer)
        measurement?.source?.close()
        session.revoking = false
    }

    private fun emit(session: Session, event: LocationEvent) {
        if (active === session) session.onEvent(event)
    }

    private fun cancel(timer: Timer?) {
        val cancellation = timer?.cancel ?: return
        timer.cancel = null
        try { cancellation() } catch (_: Exception) { /* Identity was already revoked. */ }
    }

    private class Session(val onEvent: (LocationEvent) -> Unit) {
        var access: OwnedSource? = null
        var measurement: Measurement? = null
        var lease: Lease? = null
        var lastReceipt = -1L
        var lastClock = -1L
        var revokedAt = -1L
        var externallyBlocked = false
        var revoking = false
    }
    private class Lease(val receipt: Long) { var timer: Timer? = null }
    private class Timer { var cancel: (() -> Unit)? = null }
    private class Measurement { var source: OwnedSource? = null }
    private class OwnedSource(val value: LocationSource) {
        var closed = false
            private set
        fun close() {
            if (closed) return
            closed = true
            try { value.close() } catch (_: Exception) { /* Late cleanup callbacks stay fenced. */ }
        }
    }
}
