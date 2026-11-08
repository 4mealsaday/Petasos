package com.petasos.app.location

import org.junit.Assert.*
import org.junit.Test

class ConfirmedRegionAuthorityTest {
    private val confirmation = ConfirmedRegion("region", "revision", "a".repeat(64), "point")

    @Test fun cheapPermitDoesNotReadStorageAndExactConfirmationReachesTheWorkerCheck() {
        val authority = ConfirmedRegionAuthority(confirmation)
        var reads = 0
        assertTrue(authority.isCurrent())
        assertTrue(authority.recheck { reads++; assertSame(confirmation, it); true })
        repeat(3) { assertTrue(authority.isCurrent()) }
        assertEquals(1, reads)
    }

    @Test fun observedRevocationCannotReviveWhenPolicyOrSourceMetadataIsRestored() {
        val authority = ConfirmedRegionAuthority(confirmation)
        assertFalse(authority.recheck { false })
        assertFalse(authority.isCurrent())
        var queried = false
        assertFalse(authority.recheck { queried = true; true })
        assertFalse(queried)
        // Only a later independently confirmed sample can own a new permit.
        val next = ConfirmedRegionAuthority(confirmation.copy(lastPointId = "next-point"))
        assertTrue(next.recheck { true })
        assertFalse(authority.isCurrent())
    }

    @Test fun lookupFailureWithdrawsThePermitAndDoesNotExposeTheException() {
        val authority = ConfirmedRegionAuthority(confirmation)
        assertFalse(authority.recheck { error("private-region-detail") })
        assertFalse(authority.isCurrent())
        assertFalse(authority.toString().contains("region-detail"))
    }

    @Test fun revocationDuringEvidenceReadCannotBeOverwrittenByItsSuccessfulResult() {
        val authority = ConfirmedRegionAuthority(confirmation)
        assertFalse(authority.recheck { authority.invalidate(); true })
        assertFalse(authority.isCurrent())
    }
}
