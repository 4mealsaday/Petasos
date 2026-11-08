package com.petasos.app.capability.location

/** A foreground location probe. Implementations require start and close on the main thread. */
interface LocationSource : AutoCloseable {
    /**
     * Starts one session and delivers events on the main thread. Call [close] before another start.
     * Availability and requested operations do not imply that a location was received.
     */
    fun start(onEvent: (LocationEvent) -> Unit)

    /** Stops updates and releases resources. A cleanup failure may be reported before this returns. */
    override fun close()
}

/** Values copied from one SDK callback; this object does not persist or assess the location. */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val heading: Float,
    /** SDK value without assuming an accuracy unit, scale, or probability. */
    val rawConfidence: Float,
    /** SDK timestamp without assuming its time origin. */
    val rawTimestampMillis: Long,
    /** Local wall-clock time at callback receipt, independent of the SDK timestamp. */
    val receivedAtEpochMillis: Long,
    /** Local monotonic callback-receipt time for measuring receipt intervals. */
    val receivedAtElapsedRealtimeMillis: Long,
    /** Present only for a standard Android Location callback; Pleos semantics stay independent. */
    val androidMetadata: AndroidLocationMetadata? = null,
) {
    // Prevent accidental coordinate or timestamp disclosure through generic diagnostic formatting.
    override fun toString(): String = "LocationSample(values=<redacted>)"
}

enum class LocationReadingKind {
    LAST_KNOWN,
    UPDATE,
}

enum class LocationOperation {
    CREATE,
    CHECK_AVAILABILITY,
    CHECK_PERMISSION,
    INITIALIZE,
    REGISTER_UPDATES,
    LAST_KNOWN,
    UPDATES,
    UNREGISTER_UPDATES,
    RELEASE,
}

sealed interface LocationEvent {
    /** Reports service/provider availability, not receipt, consent, or usable position quality. */
    data class Availability(val supported: Boolean) : LocationEvent

    data object PermissionDenied : LocationEvent

    /** Emitted immediately before initialize(); this is not a connection confirmation. */
    data object InitializationRequested : LocationEvent

    /** Registration returned normally; an actual location callback may still be unavailable. */
    data object SubscriptionRequested : LocationEvent

    data class NoLocation(val kind: LocationReadingKind) : LocationEvent

    data class SampleReceived(
        val kind: LocationReadingKind,
        val sample: LocationSample,
    ) : LocationEvent

    /** Contains the exception class name only, never its message or original exception. */
    data class Failure(
        val operation: LocationOperation,
        val errorType: String,
    ) : LocationEvent
}
