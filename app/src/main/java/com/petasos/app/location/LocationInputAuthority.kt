package com.petasos.app.location

import java.util.concurrent.atomic.AtomicReference

/**
 * One instance belongs to one location-input session. Ingress changes authority before worker
 * callbacks or pending database batches run; a storage commit alone cannot restore input authority.
 */
class LocationInputAuthority {
    /** Identity, rather than a millisecond timestamp, distinguishes even simultaneous callbacks. */
    class ReceiptPermit internal constructor(internal val receivedElapsedMs: Long) {
        override fun toString(): String = "ReceiptPermit([redacted])"
    }

    private val current = AtomicReference<ReceiptPermit?>()

    /**
     * Called at callback ingress. Keep the returned token with this exact sample/point ID through
     * batching and retries; never substitute the worker's latest token for an older stored point.
     */
    fun record(receivedElapsedMs: Long, admissible: Boolean): ReceiptPermit? {
        val next = if (admissible && receivedElapsedMs >= 0) ReceiptPermit(receivedElapsedMs) else null
        current.set(next)
        return next
    }

    /** Doubtful samples, disconnects, permission changes and session teardown revoke pending work. */
    fun invalidate() { current.set(null) }

    /** True while the latest callback was admissible (no doubtful sample, disconnect or teardown since). */
    fun hasAdmissibleInput(): Boolean = current.get() != null

    /** An earlier batch must still belong to the exact latest admissible callback at use time. */
    fun canUse(permit: ReceiptPermit?, lastReceivedElapsedMs: Long): Boolean {
        val accepted = current.get() ?: return false
        return accepted === permit && accepted.receivedElapsedMs == lastReceivedElapsedMs &&
            current.get() === accepted
    }
}
