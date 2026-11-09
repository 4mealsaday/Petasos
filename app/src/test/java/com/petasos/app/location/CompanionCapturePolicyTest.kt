package com.petasos.app.location

import org.junit.Assert.*
import org.junit.Test

class CompanionCapturePolicyTest {
    @Test fun savedConsentAndPermissionsDoNotStartRecordingWithoutAnExplicitLiveOwner() {
        assertFalse(CompanionCapturePolicy.mayRecord(false, true, false, true))
    }

    @Test fun homeOffStopsCaptureEvenIfTheActivityRemainsVisibleAndConsentRemainsEnabled() {
        val authority = CompanionSessionAuthority()
        val ticket = checkNotNull(authority.request(true, true, true, true))
        val service = Any()
        assertTrue(authority.acknowledge(ticket, service, true, true, true, true, true))
        assertTrue(CompanionCapturePolicy.mayRecord(authority.isActive(), true, false, true))
        authority.revoke()
        assertFalse(CompanionCapturePolicy.mayRecord(authority.isActive(), true, false, true))
        // Activity start, process recovery and another stored preference read grant no lease.
        assertFalse(CompanionCapturePolicy.mayRecord(authority.isActive(), true, false, true))
        assertFalse(CompanionCapturePolicy.mayRecord(CompanionSessionAuthority().isActive(), true, false, true))
    }

    @Test fun consentAndPermissionChangesRemainIndependentOfTheForegroundOwner() {
        assertFalse(CompanionCapturePolicy.mayRecord(true, false, false, true))
        assertFalse(CompanionCapturePolicy.mayRecord(true, true, true, true))
        assertFalse(CompanionCapturePolicy.mayRecord(true, true, false, false))
        assertTrue(CompanionCapturePolicy.mayRecord(true, true, false, true))
    }

    @Test fun pendingHomeOnCannotCaptureBeforeForegroundAcknowledgement() {
        val authority = CompanionSessionAuthority()
        val ticket = checkNotNull(authority.request(true, true, true, true))
        assertTrue(authority.isPending(ticket))
        assertFalse(CompanionCapturePolicy.mayRecord(authority.isActive(), true, false, true))
    }
}
