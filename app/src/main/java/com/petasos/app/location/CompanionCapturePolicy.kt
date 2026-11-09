package com.petasos.app.location

/** Visibility and stored opt-in are not an ON command. Only an acknowledged live owner permits capture. */
object CompanionCapturePolicy {
    fun mayRecord(
        leaseActive: Boolean,
        consentEnabled: Boolean,
        consentWithdrawn: Boolean,
        permissionsGranted: Boolean,
    ): Boolean = leaseActive && consentEnabled && !consentWithdrawn && permissionsGranted
}
