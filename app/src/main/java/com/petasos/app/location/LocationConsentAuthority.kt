package com.petasos.app.location

/**
 * An explicit withdrawal takes effect before its queued preference write. Reading an older stored
 * preference cannot undo it: only successful persistence of the latest explicit ON request can.
 * This latch supplements persisted consent and never grants location permission by itself.
 */
class LocationConsentAuthority {
    private var latestRequest = 0L
    private var latestRequestedEnabled = false
    private var withdrawn = false

    @Synchronized
    fun request(enabled: Boolean): Long {
        check(latestRequest < Long.MAX_VALUE) { "LOCATION_CONSENT_REQUEST_EXHAUSTED" }
        latestRequest++
        latestRequestedEnabled = enabled
        if (!enabled) withdrawn = true
        return latestRequest
    }

    /** Invoke only after an explicit ON preference write succeeds, never after a database read. */
    @Synchronized
    fun confirmEnabled(request: Long): Boolean {
        if (!latestRequestedEnabled || request != latestRequest) return false
        withdrawn = false
        return true
    }

    @Synchronized
    fun isWithdrawn(): Boolean = withdrawn
}
