package com.petasos.app.platform.automotive

/** Platform values, including every active flag. Unknown is never interpreted as parked. */
data class VehicleUxPolicy(
    val available: Boolean,
    val requiresDistractionOptimization: Boolean,
    val activeRestrictions: Int,
    val maxRestrictedStringLength: Int,
    val maxCumulativeContentItems: Int,
    val maxContentDepth: Int,
) {
    /** The current preview is not a distraction-optimized experience. */
    val permitsParkedPreview: Boolean
        get() = available && !requiresDistractionOptimization && activeRestrictions == 0

    fun permitsFeature(restrictionFlag: Int): Boolean =
        available && restrictionFlag > 0 && (activeRestrictions and restrictionFlag) == 0

    companion object {
        fun unavailable() = VehicleUxPolicy(false, true, -1, 0, 0, 0)
    }
}

/** Component policy is distinct from system suspend and from a user's playback intention. */
data class VehiclePowerPolicy(
    val available: Boolean,
    val audioEnabled: Boolean,
    val microphoneEnabled: Boolean,
) {
    val audioBlocked: Boolean get() = !available || !audioEnabled
    val microphoneBlocked: Boolean get() = !available || !microphoneEnabled

    companion object {
        fun unavailable() = VehiclePowerPolicy(false, false, false)
    }
}
