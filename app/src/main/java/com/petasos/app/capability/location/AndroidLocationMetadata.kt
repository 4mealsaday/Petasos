package com.petasos.app.capability.location

/** Raw documented Android Location fields copied from the same fix; never attached to Pleos data. */
data class AndroidLocationMetadata(
    val provider: String?,
    val elapsedRealtimeNanos: Long,
    val receivedAtElapsedRealtimeNanos: Long,
    /** Null means hasAccuracy() was false. Values retain their original units and invalid numbers. */
    val horizontalAccuracyMeters: Double?,
    val isMock: Boolean,
    val elapsedRealtimeUncertaintyNanos: Double? = null,
) {
    override fun toString(): String = "AndroidLocationMetadata([redacted])"
}
