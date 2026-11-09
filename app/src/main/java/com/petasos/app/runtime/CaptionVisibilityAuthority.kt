package com.petasos.app.runtime

/** Two independent display surfaces, never microphone authority or persisted visibility. */
class CaptionVisibilityAuthority {
    private var activityVisible = false
    private var overlayOwner: Any? = null

    @Synchronized fun setActivityVisible(visible: Boolean) { activityVisible = visible }

    @Synchronized
    fun setOverlayVisible(owner: Any, visible: Boolean, companionActive: Boolean): Boolean {
        if (!companionActive) { overlayOwner = null; return false }
        if (visible) { overlayOwner = owner; return true }
        if (overlayOwner !== owner) return false
        overlayOwner = null
        return true
    }

    @Synchronized fun mayReadActivity(): Boolean = activityVisible

    @Synchronized
    fun mayReadOverlay(owner: Any, companionActive: Boolean): Boolean {
        if (!companionActive) overlayOwner = null
        return overlayOwner === owner
    }

    @Synchronized
    fun hasVisibleSurface(companionActive: Boolean): Boolean {
        if (!companionActive) overlayOwner = null
        return activityVisible || overlayOwner != null
    }

    @Synchronized fun revokeOverlay() { overlayOwner = null }

    @Synchronized fun clear() { activityVisible = false; overlayOwner = null }
}
