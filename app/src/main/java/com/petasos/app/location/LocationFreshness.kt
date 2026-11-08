package com.petasos.app.location

/** Measurement age is consumed at receipt; receiving a cached point never renews that budget. */
object LocationFreshness {
    const val DEFAULT_FRESHNESS_MS = 10_000L

    /** Inclusive monotonic deadline. Original measurement/receipt timestamps remain unchanged. */
    fun deadlineElapsedMs(point: RecordedLocation, freshnessMs: Long = DEFAULT_FRESHNESS_MS): Long? {
        require(freshnessMs > 0)
        val observed = point.observedAtEpochMs ?: return null
        val accuracy = point.accuracyMeters ?: return null
        val latitude = point.latitude ?: return null
        val longitude = point.longitude ?: return null
        if (point.quality != LocationQuality.VALID || point.provider.isBlank() || point.clockScopeId.isBlank() ||
            point.adapterVersion.isNullOrBlank() || point.sourceTimeUnit !in setOf("epoch_ms", "epoch_s", "elapsed_ms", "other") ||
            observed < 0 || point.receivedAtEpochMs < observed || point.receivedAtElapsedMs < 0 ||
            !accuracy.isFinite() || accuracy < 0 || !latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0) return null
        // Android's raw epoch is preserved, but only the documented same-boot age grants freshness.
        // Missing/corrupt restored Android age never silently falls back to wall-clock subtraction.
        val ageAtReceipt = if (point.adapterVersion == AndroidLocationMeaningAdapter.VERSION)
            point.measurementAgeAtReceiptMs ?: return null else point.receivedAtEpochMs - observed
        if (ageAtReceipt < 0) return null
        if (ageAtReceipt > freshnessMs) return null
        val remaining = freshnessMs - ageAtReceipt
        if (point.receivedAtElapsedMs > Long.MAX_VALUE - remaining) return null
        return point.receivedAtElapsedMs + remaining
    }

    /** Both the measurement deadline and the consumer's receipt-age cap must still hold. */
    fun isFresh(receivedElapsedMs: Long?, freshUntilElapsedMs: Long?, now: Long, maximumAgeMs: Long): Boolean {
        require(maximumAgeMs > 0)
        val received = receivedElapsedMs ?: return false
        val deadline = freshUntilElapsedMs ?: return false
        return received >= 0 && deadline >= received && now >= received && now <= deadline &&
            now - received <= maximumAgeMs
    }
}
