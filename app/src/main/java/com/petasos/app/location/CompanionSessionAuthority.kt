package com.petasos.app.location

/**
 * A process-local user intent to keep the current trip's location session running beside navigation.
 * Persisted recording consent, media Play, recovery and service creation never create this lease.
 * Android permissions and the current trip must still be checked independently at every use.
 */
class CompanionSessionAuthority {
    class Ticket internal constructor(internal val generation: Long) {
        override fun toString(): String = "CompanionTicket([redacted])"
    }

    private var generation = 0L
    private var ticket: Ticket? = null
    private var owner: Any? = null

    /** Only call for an explicit visible UI action, after reading current consent and permissions. */
    @Synchronized
    fun request(
        activityVisible: Boolean,
        consentEnabled: Boolean,
        permissionsGranted: Boolean,
        sessionReady: Boolean,
    ): Ticket? {
        if (!activityVisible || !consentEnabled || !permissionsGranted || !sessionReady) return null
        check(generation < Long.MAX_VALUE) { "COMPANION_REQUEST_EXHAUSTED" }
        generation++
        return Ticket(generation).also { ticket = it; owner = null }
    }

    /**
     * The actual foreground-service instance acknowledges successful promotion, while the start
     * action is still visible and its gates remain valid. An unsuccessful ACK cancels that request;
     * later callbacks cannot reuse it after permissions, visibility or a service start has failed.
     */
    @Synchronized
    fun acknowledge(
        requested: Ticket,
        serviceOwner: Any,
        activityVisible: Boolean,
        consentEnabled: Boolean,
        permissionsGranted: Boolean,
        sessionReady: Boolean,
        foregroundStarted: Boolean,
    ): Boolean {
        if (!matches(requested) || owner != null) return false
        if (!activityVisible || !consentEnabled || !permissionsGranted || !sessionReady || !foregroundStarted) {
            clear()
            return false
        }
        owner = serviceOwner
        return true
    }

    @Synchronized
    fun isPending(requested: Ticket): Boolean = matches(requested) && owner == null

    @Synchronized
    fun isActive(): Boolean = ticket != null && owner != null

    @Synchronized
    fun owns(requested: Ticket, serviceOwner: Any): Boolean = matches(requested) && owner === serviceOwner

    /** Failure/timeout belongs to this exact start request, never to its replacement. */
    @Synchronized
    fun cancel(requested: Ticket): Boolean {
        if (!matches(requested)) return false
        return clear()
    }

    /** Hiding the initiating Activity cancels an unacknowledged start, but retains an active lease. */
    @Synchronized
    fun cancelPending(): Boolean {
        if (owner != null) return false
        return clear()
    }

    /** Service destruction must not revoke a newer owner, even if the old callbacks arrive late. */
    @Synchronized
    fun release(requested: Ticket, serviceOwner: Any): Boolean {
        if (!matches(requested) || owner !== serviceOwner) return false
        return clear()
    }

    /** Consent withdrawal, permission loss and trip end take effect before queued cleanup work. */
    @Synchronized
    fun revoke(): Boolean = clear()

    private fun matches(requested: Ticket): Boolean = ticket === requested && requested.generation == generation

    private fun clear(): Boolean {
        val hadRequest = ticket != null
        ticket = null
        owner = null
        return hadRequest
    }
}
