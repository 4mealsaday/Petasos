package com.petasos.app.presentation

import com.petasos.app.location.LocationInputAuthority
import com.petasos.app.presentation.records.MapCoordinate
import org.junit.Assert.*
import org.junit.Test

class DisplayPositionTest {
    private val point = MapCoordinate(37.0, 127.0)

    @Test fun workerToUiDeliveryRechecksTheDeadlineInsteadOfExtendingFreshness() {
        val lease = DisplayPosition(point, expiresAtElapsedMs = 10_000) { true }
        assertSame(point, lease.currentCoordinate(9_999))
        assertNull(lease.currentCoordinate(10_000))
        assertNull(lease.currentCoordinate(15_000)) // Future resolved after its original deadline.
        assertEquals(10_000L, lease.expiresAtElapsedMs)
    }

    @Test fun expiryShortCircuitsTheAuthorityCallbackAndNeverReusesTheLastVisiblePoint() {
        var guards = 0
        val lease = DisplayPosition(point, 10_000) { guards++; true }
        assertSame(point, lease.currentCoordinate(9_000))
        assertEquals(1, guards)
        assertNull(lease.currentCoordinate(10_000))
        assertNull(lease.currentCoordinate(20_000))
        assertEquals(1, guards)
    }

    @Test fun permissionOrConsentRevocationBetweenPreparationAndDeliveryWithholdsThePosition() {
        var authorized = true
        val lease = DisplayPosition(point, 10_000) { authorized }
        assertSame(point, lease.currentCoordinate(1_000))
        authorized = false
        assertNull(lease.currentCoordinate(1_001))
        assertNull(lease.currentCoordinate(2_000))
    }

    @Test fun exactReceiptAuthorityIsNotReplacedByANewerFixEvenAtTheSameReceiptMillisecond() {
        val authority = LocationInputAuthority()
        val original = authority.record(1_000, admissible = true)
        val lease = DisplayPosition(point, 10_000) { authority.canUse(original, 1_000) }
        assertSame(point, lease.currentCoordinate(2_000))
        authority.record(1_000, admissible = true)
        assertNull(lease.currentCoordinate(2_001))
        authority.invalidate()
        assertNull(lease.currentCoordinate(2_002))
    }

    @Test fun failedGuardReturnsNoPositionAndDoesNotExposeTheErrorOrCoordinate() {
        val lease = DisplayPosition(point, 10_000) { error("synthetic private location error") }
        assertNull(lease.currentCoordinate(1_000))
        assertEquals("DisplayPosition([redacted])", lease.toString())
        assertEquals("MapCoordinate([redacted])", point.toString())
        assertFalse(lease.toString().contains("37.0"))
        assertFalse(lease.toString().contains("127.0"))
    }
}
