package com.petasos.app.runtime

import org.junit.Assert.*
import org.junit.Test

class CaptionVisibilityAuthorityTest {
    @Test fun anActiveTripDoesNotInventAnOverlayDisplay() {
        val authority = CaptionVisibilityAuthority()
        assertFalse(authority.hasVisibleSurface(true))
        assertFalse(authority.mayReadOverlay(Any(), true))
        assertFalse(authority.mayReadActivity())
    }

    @Test fun anOverlayNeedsAnActiveCompanionAndDoesNotAuthorizeTheActivity() {
        val authority = CaptionVisibilityAuthority()
        val owner = Any()
        assertFalse(authority.setOverlayVisible(owner, true, false))
        assertFalse(authority.hasVisibleSurface(false))
        assertTrue(authority.setOverlayVisible(owner, true, true))
        assertTrue(authority.hasVisibleSurface(true))
        assertTrue(authority.mayReadOverlay(owner, true))
        assertFalse(authority.mayReadActivity())
    }

    @Test fun activityHideKeepsAnActuallyVisibleAuthorizedOverlay() {
        val authority = CaptionVisibilityAuthority()
        val owner = Any()
        authority.setActivityVisible(true)
        authority.setOverlayVisible(owner, true, true)
        authority.setActivityVisible(false)
        assertTrue(authority.hasVisibleSurface(true))
        assertTrue(authority.mayReadOverlay(owner, true))
        assertFalse(authority.mayReadActivity())
        assertTrue(authority.setOverlayVisible(owner, false, true))
        assertFalse(authority.hasVisibleSurface(true))
    }

    @Test fun aLateHideFromAnOldOverlayCannotHideOrReadItsReplacement() {
        data class Owner(val value: String)
        val authority = CaptionVisibilityAuthority()
        val old = Owner("same")
        val current = Owner("same")
        authority.setOverlayVisible(old, true, true)
        authority.setOverlayVisible(current, true, true)
        assertFalse(authority.setOverlayVisible(old, false, true))
        assertFalse(authority.mayReadOverlay(old, true))
        assertTrue(authority.mayReadOverlay(current, true))
    }

    @Test fun companionRevocationDiscardsTheOverlayWithoutImplicitRestoration() {
        val authority = CaptionVisibilityAuthority()
        val owner = Any()
        authority.setOverlayVisible(owner, true, true)
        assertFalse(authority.mayReadOverlay(owner, false))
        assertFalse(authority.mayReadOverlay(owner, true))
        assertFalse(authority.hasVisibleSurface(true))
    }

    @Test fun activityCaptionCanExistWithoutGivingAnOverlayAnyRightToReadIt() {
        val authority = CaptionVisibilityAuthority()
        authority.setActivityVisible(true)
        assertTrue(authority.hasVisibleSurface(false))
        assertTrue(authority.mayReadActivity())
        assertFalse(authority.mayReadOverlay(Any(), false))
    }

    @Test fun offClearsBothSurfacesAndRequiresFreshDisplayRequests() {
        val authority = CaptionVisibilityAuthority()
        val owner = Any()
        authority.setActivityVisible(true)
        authority.setOverlayVisible(owner, true, true)
        authority.clear()
        assertFalse(authority.mayReadActivity())
        assertFalse(authority.mayReadOverlay(owner, true))
        assertFalse(authority.hasVisibleSurface(true))
    }
}
