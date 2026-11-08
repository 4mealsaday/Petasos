package com.petasos.app.location

import com.petasos.app.capability.location.AndroidLocationMetadata
import com.petasos.app.capability.location.LocationEvent
import com.petasos.app.capability.location.LocationOperation
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.capability.location.LocationSource
import org.junit.Assert.*
import org.junit.Test

class PleosAuthorizedLocationSourceTest {
    @Test fun onlyCurrentAccessUpdateStartsMeasurementAndNeverForwardsAccessCoordinates() {
        val h = Harness()
        h.start()
        h.access.emit(LocationEvent.Availability(true))
        h.access.emit(LocationEvent.InitializationRequested)
        h.access.emit(LocationEvent.SubscriptionRequested)
        h.access.emit(LocationEvent.SampleReceived(LocationReadingKind.LAST_KNOWN, sample(h.now)))
        assertTrue(h.measurements.isEmpty())
        h.update(sample(h.now).copy(latitude = Double.NaN, rawTimestampMillis = Long.MIN_VALUE))
        assertEquals(1, h.measurements.size)
        assertTrue(h.guards.single()())
        assertTrue(h.events.none { it is LocationEvent.SampleReceived })
        val measured = sample(h.now).copy(androidMetadata = AndroidLocationMetadata("gps", 1, 2, 3.0, false))
        val event = LocationEvent.SampleReceived(LocationReadingKind.UPDATE, measured)
        h.measurement.emit(event)
        assertSame(event, h.events.last())
        assertSame(measured, (h.events.last() as LocationEvent.SampleReceived).sample)
    }

    @Test fun missingCachedMeasurementDoesNotPreventFirstLiveFix() {
        val h = Harness()
        h.measurementFactory = { guard ->
            h.guards += guard
            FakeSource().also { source ->
                h.measurements += source
                source.onStart = { source.emit(LocationEvent.NoLocation(LocationReadingKind.LAST_KNOWN)) }
            }
        }
        h.start(); h.update()
        assertEquals(0, h.measurement.closes)
        assertTrue(h.guards.single()())
        val event = LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample(h.now))
        h.measurement.emit(event)
        assertSame(event, h.events.last())
    }

    @Test fun synchronousCachedFailureKeepsLiveMeasurementButSubsequentPermissionLossRevokesIt() {
        val h = Harness()
        val cachedFailure = LocationEvent.Failure(LocationOperation.LAST_KNOWN, "CachedReadUnavailable")
        h.measurementFactory = { guard ->
            h.guards += guard
            FakeSource().also { source ->
                h.measurements += source
                source.onStart = { source.emit(cachedFailure) }
            }
        }
        h.start(); h.update()
        assertEquals(listOf(cachedFailure), h.events)
        assertTrue(h.guards.single()())
        assertEquals(0, h.measurement.closes)
        val liveFix = LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample(h.now))
        h.measurement.emit(liveFix)
        assertSame(liveFix, h.events.last())
        h.measurement.emit(LocationEvent.PermissionDenied)
        assertSame(LocationEvent.PermissionDenied, h.events.last())
        assertFalse(h.guards.single()())
        assertEquals(1, h.measurement.closes)
        val count = h.events.size
        h.measurement.emit(liveFix)
        assertEquals(count, h.events.size)
    }

    @Test fun LateCachedMissesFromEitherSourceKeepCurrentAccessLease() {
        val h = Harness()
        h.start(); h.update()
        val uncertain = listOf(
            LocationEvent.NoLocation(LocationReadingKind.LAST_KNOWN),
            LocationEvent.Failure(LocationOperation.LAST_KNOWN, "CachedReadUnavailable"),
        )
        uncertain.forEach { event ->
            h.access.emit(event)
            assertSame(event, h.events.last())
            h.measurement.emit(event)
            assertSame(event, h.events.last())
            assertTrue(h.guards.single()())
            assertEquals(0, h.measurement.closes)
        }
        assertEquals(1, h.measurements.size)
    }

    @Test fun cachedFailuresAloneCannotAuthorizeMeasurement() {
        val h = Harness()
        h.start()
        h.access.emit(LocationEvent.NoLocation(LocationReadingKind.LAST_KNOWN))
        h.access.emit(LocationEvent.Failure(LocationOperation.LAST_KNOWN, "CachedReadUnavailable"))
        assertTrue(h.measurements.isEmpty())
        h.update()
        assertEquals(1, h.measurements.size)
    }

    @Test fun accessHeartbeatRefreshesWatchdogWithoutRestartingMeasurement() {
        val h = Harness()
        h.start(); h.update()
        val oldTimer = h.timers.single()
        h.now += 5_000; h.update()
        assertTrue(oldTimer.cancelled)
        assertEquals(1, h.measurements.size)
        h.now = 11_000
        oldTimer.fire()
        assertTrue(h.guards.single()())
        assertEquals(0, h.measurement.closes)
        h.now = 16_000
        h.timers.last().fire()
        assertEquals(1, h.measurement.closes)
        assertFalse(h.guards.single()())
        assertEquals(LocationEvent.NoLocation(LocationReadingKind.UPDATE), h.events.last())
    }

    @Test fun queuedStaleAccessReceiptCannotAcquireFreshAuthorization() {
        val h = Harness()
        h.start()
        h.now = 11_000
        h.update(sample(1_000))
        assertTrue(h.measurements.isEmpty())
        assertEquals(LocationEvent.NoLocation(LocationReadingKind.UPDATE), h.events.last())
        h.update() // Same-millisecond signal after revocation does not prove ordering.
        assertTrue(h.measurements.isEmpty())
        h.now++
        h.update()
        assertEquals(1, h.measurements.size)
    }

    @Test fun expiryFencesOldMeasurementsAndRequiresNewAccessReceipt() {
        val h = Harness()
        h.start(); h.update()
        val first = h.measurement
        val firstGuard = h.guards.single()
        h.now += 10_000
        h.timers.last().fire()
        val count = h.events.size
        first.emit(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample(h.now)))
        assertEquals(count, h.events.size)
        assertFalse(firstGuard())
        h.update()
        assertEquals(1, h.measurements.size)
        h.now++; h.update()
        assertEquals(2, h.measurements.size)
        assertFalse(firstGuard())
        assertTrue(h.guards.last()())
        first.emit(LocationEvent.PermissionDenied)
        assertTrue(h.guards.last()())
        assertEquals(0, h.measurement.closes)
    }

    @Test fun eachCurrentAccessFailureRevokesBeforeReportingAndFreshUpdateCanRecover() {
        listOf(
            LocationEvent.NoLocation(LocationReadingKind.UPDATE),
            LocationEvent.Failure(LocationOperation.UPDATES, "ProviderUnavailable"),
            LocationEvent.PermissionDenied,
            LocationEvent.Availability(false),
        ).forEach { event ->
            val h = Harness()
            h.start(); h.update()
            val first = h.measurement
            h.onEvent = { assertFalse(h.guards.first()()); assertEquals(1, first.closes) }
            h.access.emit(event)
            assertSame(event, h.events.last())
            assertEquals(0, h.access.closes)
            h.onEvent = null
            h.now++; h.update()
            assertEquals(2, h.measurements.size)
        }
    }

    @Test fun currentMeasurementFailuresAlsoRevokeAndFenceTheirOldCallbacks() {
        listOf(
            LocationEvent.NoLocation(LocationReadingKind.UPDATE),
            LocationEvent.Failure(LocationOperation.UPDATES, "ProviderUnavailable"),
            LocationEvent.PermissionDenied,
            LocationEvent.Availability(false),
        ).forEach { event ->
            val h = Harness()
            h.start(); h.update()
            val first = h.measurement
            first.emit(event)
            assertSame(event, h.events.last())
            assertEquals(1, first.closes)
            val count = h.events.size
            first.emit(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample(h.now)))
            assertEquals(count, h.events.size)
        }
    }

    @Test fun externalDenialBeforeStartCreatesNeitherSubscription() {
        val h = Harness()
        h.allowed = false
        h.start()
        assertTrue(h.accesses.isEmpty())
        assertTrue(h.measurements.isEmpty())
        assertEquals(listOf(LocationEvent.PermissionDenied), h.events)
        h.allowed = true
        assertThrows(IllegalStateException::class.java) { h.start() }
        h.source.close(); h.start(); h.update()
        assertEquals(1, h.measurements.size)
    }

    @Test fun watchdogDetectsExternalLossWithoutProviderEventsAndCannotRegrant() {
        val h = Harness()
        h.start(); h.update()
        val access = h.access
        val measurement = h.measurement
        h.allowed = false
        h.now += 10_000
        h.timers.last().fire()
        assertEquals(1, access.closes)
        assertEquals(1, measurement.closes)
        assertSame(LocationEvent.PermissionDenied, h.events.last())
        h.allowed = true; h.now++
        access.emit(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample(h.now)))
        assertEquals(1, h.measurements.size)
        assertFalse(h.guards.single()())
    }

    @Test fun measurementGuardChecksExternalAccessAndInvalidatesBeforeReentrantCleanup() {
        val h = Harness()
        h.start(); h.update()
        val access = h.access
        h.measurement.onClose = {
            h.allowed = true; h.now++
            access.emit(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample(h.now)))
        }
        h.allowed = false
        assertFalse(h.guards.single()())
        assertEquals(1, h.measurements.size)
        assertEquals(1, access.closes)
        assertSame(LocationEvent.PermissionDenied, h.events.last())
    }

    @Test fun externalPredicateFailureNeverExposesExceptionText() {
        val h = Harness()
        h.externalFailure = true
        h.start()
        assertEquals(listOf(LocationEvent.PermissionDenied), h.events)
        assertTrue(h.accesses.isEmpty())
    }

    @Test fun negativeOrBackwardClockRevokesBeforeAnyMeasurementForwarding() {
        listOf(-1L, 999L).forEach { invalid ->
            val h = Harness()
            h.start(); h.update()
            h.now = invalid
            h.measurement.emit(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample(invalid)))
            assertEquals(1, h.measurement.closes)
            assertEquals(LocationEvent.Failure(LocationOperation.UPDATES, "InvalidMonotonicClock"), h.events.last())
            assertTrue(h.events.none { it is LocationEvent.SampleReceived })
        }
    }

    @Test fun invalidAccessReceiptRevokesButAnOlderValidReceiptCannotReplaceNewLease() {
        listOf(-1L, 1_001L).forEach { invalid ->
            val h = Harness()
            h.start(); h.update()
            h.update(sample(invalid))
            assertFalse(h.guards.single()())
            assertEquals(1, h.measurement.closes)
        }
        val h = Harness()
        h.start(); h.update()
        h.now += 1_000; h.update()
        val latestTimer = h.timers.last()
        h.update(sample(1_500))
        assertSame(latestTimer, h.timers.last())
        assertFalse(latestTimer.cancelled)
        assertTrue(h.guards.single()())
    }

    @Test fun closeAndRestartFenceAllOldProviderAndTimerCallbacks() {
        val h = Harness()
        h.start(); h.update()
        val oldAccess = h.access
        val oldMeasurement = h.measurement
        val oldTimer = h.timers.single()
        oldAccess.onClose = { oldAccess.emit(LocationEvent.PermissionDenied) }
        oldMeasurement.onClose = { oldMeasurement.emit(LocationEvent.PermissionDenied) }
        h.source.close(); h.source.close()
        assertEquals(1, oldAccess.closes)
        assertEquals(1, oldMeasurement.closes)
        assertTrue(h.events.isEmpty())
        h.now++; h.start(); h.update()
        oldAccess.emit(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample(h.now)))
        oldMeasurement.emit(LocationEvent.PermissionDenied)
        oldTimer.fire()
        assertEquals(2, h.measurements.size)
        assertTrue(h.guards.last()())
        assertTrue(h.events.isEmpty())
    }

    @Test fun currentRevocationCannotReopenDuringProviderCleanup() {
        val h = Harness()
        h.start(); h.update()
        h.measurement.onClose = { h.now++; h.update() }
        h.access.emit(LocationEvent.NoLocation(LocationReadingKind.UPDATE))
        assertEquals(1, h.measurements.size)
        assertFalse(h.guards.single()())
        h.now++; h.update()
        assertEquals(2, h.measurements.size)
    }

    @Test fun synchronousMeasurementCallbackMayCloseCompositeWithoutFurtherDelivery() {
        val h = Harness()
        h.measurementFactory = { guard ->
            h.guards += guard
            FakeSource().also { source ->
                h.measurements += source
                source.onStart = {
                    source.emit(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, sample(h.now)))
                    source.emit(LocationEvent.SubscriptionRequested)
                }
            }
        }
        h.onEvent = { h.source.close() }
        h.start(); h.update()
        assertEquals(1, h.events.size)
        assertEquals(1, h.access.closes)
        assertEquals(1, h.measurement.closes)
        assertFalse(h.guards.single()())
    }

    @Test fun earlyTimerDoesNotExtendTheReceiptDeadline() {
        val h = Harness()
        h.start(); h.update()
        h.now += 2_000
        h.timers.last().fire()
        assertEquals(8_000L, h.timers.last().delayMs)
        assertEquals(0, h.measurement.closes)
        h.now = 11_000
        h.timers.last().fire()
        assertEquals(1, h.measurement.closes)
    }

    @Test fun synchronousOrFailedSchedulerCannotStartUnwatchedMeasurement() {
        listOf(false, true).forEach { throws ->
            val h = Harness()
            h.scheduleFailure = throws
            h.synchronousSchedule = !throws
            h.start(); h.update()
            assertTrue(h.measurements.isEmpty())
            assertEquals(LocationEvent.Failure(LocationOperation.REGISTER_UPDATES,
                if (throws) "AccessWatchdogUnavailable" else "AccessWatchdogInvalid"), h.events.last())
            if (!throws) assertTrue(h.timers.single().cancelled)
        }
    }

    @Test fun sourceCreationAndSubscriptionFailuresExposeOnlyFixedCodes() {
        val accessCreate = Harness()
        accessCreate.accessFactory = { error("PRIVATE_PAYLOAD") }
        accessCreate.start()
        assertEquals(LocationEvent.Failure(LocationOperation.CREATE, "AccessSourceUnavailable"), accessCreate.events.last())

        val measurementCreate = Harness()
        measurementCreate.measurementFactory = { error("PRIVATE_PAYLOAD") }
        measurementCreate.start(); measurementCreate.update()
        assertEquals(LocationEvent.Failure(LocationOperation.CREATE, "MeasurementSourceUnavailable"), measurementCreate.events.last())
        assertTrue(measurementCreate.timers.single().cancelled)

        val accessStart = Harness()
        accessStart.accessFactory = { FakeSource().also { it.startFailure = true; accessStart.accesses += it } }
        accessStart.start()
        assertEquals(LocationEvent.Failure(LocationOperation.REGISTER_UPDATES, "AccessSubscriptionFailed"), accessStart.events.last())
        assertEquals(1, accessStart.access.closes)

        val measurementStart = Harness()
        measurementStart.measurementFactory = { guard ->
            measurementStart.guards += guard
            FakeSource().also { it.startFailure = true; measurementStart.measurements += it }
        }
        measurementStart.start(); measurementStart.update()
        assertEquals(LocationEvent.Failure(LocationOperation.REGISTER_UPDATES, "MeasurementSubscriptionFailed"), measurementStart.events.last())
        assertEquals(1, measurementStart.measurement.closes)
    }

    @Test fun closeExceptionsRemainFencedAndCannotLeakMessages() {
        val h = Harness()
        h.start(); h.update()
        h.access.closeFailure = true
        h.measurement.closeFailure = true
        h.source.close()
        h.access.emit(LocationEvent.PermissionDenied)
        h.measurement.emit(LocationEvent.PermissionDenied)
        assertTrue(h.events.isEmpty())
    }

    @Test fun freshnessPolicyMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { Harness(freshnessMs = 0) }
    }

    private class Harness(freshnessMs: Long = 10_000) {
        var now = 1_000L
        var allowed = true
        var externalFailure = false
        var scheduleFailure = false
        var synchronousSchedule = false
        val accesses = mutableListOf<FakeSource>()
        val measurements = mutableListOf<FakeSource>()
        val guards = mutableListOf<() -> Boolean>()
        val timers = mutableListOf<FakeTimer>()
        val events = mutableListOf<LocationEvent>()
        var onEvent: ((LocationEvent) -> Unit)? = null
        var accessFactory: () -> LocationSource = { FakeSource().also { accesses += it } }
        var measurementFactory: (() -> Boolean) -> LocationSource = { guard ->
            guards += guard
            FakeSource().also { measurements += it }
        }
        val source = PleosAuthorizedLocationSource(
            accessSourceFactory = { accessFactory() },
            measurementSourceFactory = { measurementFactory(it) },
            externalAccessAllowed = { if (externalFailure) error("PRIVATE_PAYLOAD") else allowed },
            elapsedMs = { now },
            schedule = { delay, task ->
                if (scheduleFailure) error("PRIVATE_PAYLOAD")
                val timer = FakeTimer(delay, task)
                timers += timer
                if (synchronousSchedule) task()
                val cancel: () -> Unit = { timer.cancelled = true }
                cancel
            },
            guardFreshnessMs = freshnessMs,
        )
        val access get() = accesses.last()
        val measurement get() = measurements.last()
        fun start() = source.start { events += it; onEvent?.invoke(it) }
        fun update(value: LocationSample = sample(now)) =
            access.emit(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, value))
    }

    private class FakeSource : LocationSource {
        private lateinit var callback: (LocationEvent) -> Unit
        var closes = 0
        var startFailure = false
        var closeFailure = false
        var onStart: (() -> Unit)? = null
        var onClose: (() -> Unit)? = null
        override fun start(onEvent: (LocationEvent) -> Unit) {
            callback = onEvent
            onStart?.invoke()
            if (startFailure) error("PRIVATE_PAYLOAD")
        }
        override fun close() {
            closes++
            onClose?.invoke()
            if (closeFailure) error("PRIVATE_PAYLOAD")
        }
        fun emit(event: LocationEvent) = callback(event)
    }

    private class FakeTimer(val delayMs: Long, val task: () -> Unit) {
        var cancelled = false
        fun fire() = task() // Deliberately permits an already queued, cancelled callback.
    }

    companion object {
        private fun sample(receipt: Long) = LocationSample(37.0, 127.0, 0.0, 0f, 0f, 0, 0, receipt)
    }
}
