package com.petasos.app.location

import com.petasos.app.capability.location.LocationEvent
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.generation.StrictJson

enum class LocationQuality { VALID, STALE, INVALID, UNKNOWN }
enum class LocationRecordingStatus { STOPPED, RECEIVING, NO_LOCATION, PERMISSION_DENIED, DISCONNECTED }

/** Only an adapter whose timestamp and accuracy meanings have been verified may supply these values. */
data class VerifiedLocationMeaning(
    val adapterVersion: String,
    val sourceTimeUnit: String,
    val observedAtEpochMs: Long,
    val accuracyMeters: Double,
    val maximumAccuracyMeters: Double,
    /** Known same-boot measurement age. Android freshness must not use a wall-clock age instead. */
    val measurementAgeAtReceiptMs: Long? = null,
) {
    init {
        require(adapterVersion.isNotBlank())
        require(sourceTimeUnit in setOf("epoch_ms", "epoch_s", "elapsed_ms", "other"))
        require(observedAtEpochMs >= 0 && accuracyMeters.isFinite() && accuracyMeters >= 0)
        require(maximumAccuracyMeters.isFinite() && maximumAccuracyMeters > 0)
        require(measurementAgeAtReceiptMs == null || measurementAgeAtReceiptMs >= 0)
    }
}

fun interface LocationMeaningAdapter {
    fun interpret(sample: LocationSample): VerifiedLocationMeaning?

    companion object {
        /** Pleos confidence/time origin are not yet verified. Receipt time never substitutes for measurement time. */
        val UNKNOWN = LocationMeaningAdapter { null }
    }
}

data class RecordedLocation(
    val pointId: String,
    val provider: String,
    val providerEpoch: String?,
    val sourceEventId: String?,
    val rawTimestamp: String,
    val sourceTimeUnit: String,
    val observedAtEpochMs: Long?,
    val receivedAtEpochMs: Long,
    val receivedAtElapsedMs: Long,
    val clockScopeId: String,
    val latitude: Double?,
    val longitude: Double?,
    val rawCoordinatesJson: String,
    val accuracyMeters: Double?,
    val providerConfidenceJson: String,
    val quality: LocationQuality,
    val adapterVersion: String?,
    val routeRevision: Long = 0,
    /** Restored only from verified monotonic evidence for adapters that define it. */
    val measurementAgeAtReceiptMs: Long? = null,
) {
    override fun toString(): String = "RecordedLocation([redacted])"
}

/** TIME-03/LOC-01: preserve doubtful originals while preventing their use in automatic features. */
class LocationNormalizer(
    private val meaning: LocationMeaningAdapter = LocationMeaningAdapter.UNKNOWN,
    private val freshnessMs: Long = LocationFreshness.DEFAULT_FRESHNESS_MS,
) {
    init { require(freshnessMs > 0) }

    fun normalize(
        sample: LocationSample, kind: LocationReadingKind, pointId: String,
        provider: String, clockScopeId: String, providerEpoch: String? = null, sourceEventId: String? = null,
        routeRevision: Long = 0,
    ): RecordedLocation {
        require(pointId.isNotBlank() && provider.isNotBlank() && clockScopeId.isNotBlank())
        require(routeRevision >= 0)
        require((providerEpoch == null) == (sourceEventId == null))
        require(providerEpoch == null || (providerEpoch.isNotBlank() && sourceEventId!!.isNotBlank()))
        require(sample.receivedAtElapsedRealtimeMillis >= 0 && sample.receivedAtEpochMillis >= 0)
        val mapped = try { meaning.interpret(sample) } catch (_: Exception) { null }
        val coordinatesValid = sample.latitude.isFinite() && sample.latitude in -90.0..90.0 &&
            sample.longitude.isFinite() && sample.longitude in -180.0..180.0
        val ageAtReceipt = when {
            mapped == null -> null
            mapped.adapterVersion == AndroidLocationMeaningAdapter.VERSION -> mapped.measurementAgeAtReceiptMs
            mapped.observedAtEpochMs <= sample.receivedAtEpochMillis -> sample.receivedAtEpochMillis - mapped.observedAtEpochMs
            else -> null
        }
        val quality = when {
            !coordinatesValid -> LocationQuality.INVALID
            mapped == null -> LocationQuality.UNKNOWN
            mapped.observedAtEpochMs > sample.receivedAtEpochMillis -> LocationQuality.INVALID
            ageAtReceipt == null -> LocationQuality.UNKNOWN
            ageAtReceipt > freshnessMs -> LocationQuality.STALE
            mapped.accuracyMeters > mapped.maximumAccuracyMeters -> LocationQuality.INVALID
            else -> LocationQuality.VALID
        }
        val android = sample.androidMetadata
        val recordedProvider = if (meaning is AndroidLocationMeaningAdapter && android != null)
            AndroidLocationMeaningAdapter.provider(android) ?: provider else provider
        val confidence = linkedMapOf<String, Any?>("rawConfidence" to sample.rawConfidence.toString(), "adapterVersion" to mapped?.adapterVersion)
        if (android != null) {
            confidence["androidLocation"] = mapOf(
                "provider" to android.provider,
                "elapsedRealtimeNanos" to android.elapsedRealtimeNanos,
                "receivedAtElapsedRealtimeNanos" to android.receivedAtElapsedRealtimeNanos,
                "horizontalAccuracyMeters" to android.horizontalAccuracyMeters?.toString(),
                "isMock" to android.isMock,
                "elapsedRealtimeUncertaintyNanos" to android.elapsedRealtimeUncertaintyNanos?.toString(),
            )
            confidence["measurementAgeAtReceiptMs"] = mapped?.measurementAgeAtReceiptMs
        }
        // String representations retain NaN/Infinity and unverified SDK values without invalid JSON numbers.
        return RecordedLocation(pointId, recordedProvider, providerEpoch, sourceEventId, sample.rawTimestampMillis.toString(),
            mapped?.sourceTimeUnit ?: "unknown", mapped?.observedAtEpochMs, sample.receivedAtEpochMillis,
            sample.receivedAtElapsedRealtimeMillis, clockScopeId,
            if (coordinatesValid) sample.latitude else null, if (coordinatesValid) sample.longitude else null,
            StrictJson.stringify(mapOf("latitude" to sample.latitude.toString(), "longitude" to sample.longitude.toString(),
                "altitude" to sample.altitude.toString(), "heading" to sample.heading.toString(), "readingKind" to kind.name)),
            mapped?.accuracyMeters, StrictJson.stringify(confidence), quality, mapped?.adapterVersion, routeRevision,
            mapped?.measurementAgeAtReceiptMs)
    }
}

data class LocationCommit(
    val inserted: Int,
    val duplicateEvents: Int,
    val acquiredRegionIds: Set<String>,
    val confirmedRegionId: String? = null,
    /** A region ID alone does not authorize automatic regional planning. */
    val confirmedRegion: ConfirmedRegion? = null,
) {
    init { require(confirmedRegion == null || confirmedRegion.regionId == confirmedRegionId) }
}

interface LocationRecordingStore {
    /** Runs only after an explicit movement start/resume and an already active Coordinator session. */
    fun openSegment(tripId: String, sessionId: String, segmentId: String)
    fun append(segmentId: String, points: List<RecordedLocation>): LocationCommit
    /** Unknown interruption time keeps ended_at null. Explicit stop records a real end time. */
    fun finishSegment(segmentId: String, reason: String, interrupted: Boolean)
}

/** Worker-thread owned. Caller schedules tick() at the next flush deadline and serializes all calls. */
class LocationRecorder(
    private val store: LocationRecordingStore,
    private val normalizer: LocationNormalizer,
    private val ids: () -> String,
    private val clockScopeId: String,
    private val maxReceiptGapMs: Long,
    private val provider: String = "pleos-fused-location-1.1.3",
    private val routeRevision: () -> Long = { 0 },
) {
    init { require(maxReceiptGapMs > 0 && clockScopeId.isNotBlank()) }
    private var owner: Pair<String, String>? = null
    private var segmentId: String? = null
    private val pending = mutableListOf<RecordedLocation>()
    private var lastReceipt: Long? = null
    var status = LocationRecordingStatus.STOPPED
        private set
    val pendingCount: Int get() = pending.size
    val nextFlushElapsedMs: Long? get() = pending.firstOrNull()?.receivedAtElapsedMs?.let { it + BATCH_MILLIS }

    fun begin(tripId: String, sessionId: String) {
        check(owner == null)
        require(tripId.isNotBlank() && sessionId.isNotBlank())
        owner = tripId to sessionId
        status = LocationRecordingStatus.NO_LOCATION
    }

    /** Media pause deliberately has no counterpart here: location continues while movement remains active. */
    fun receive(event: LocationEvent) {
        if (owner == null) return
        when (event) {
            is LocationEvent.SampleReceived -> {
                if (pending.size >= MAX_BATCH_POINTS) flush()
                val elapsed = event.sample.receivedAtElapsedRealtimeMillis
                val previous = lastReceipt
                if (previous != null && (elapsed < previous || elapsed - previous > maxReceiptGapMs)) gap("receipt_gap")
                ensureSegment()
                pending += normalizer.normalize(event.sample, event.kind, ids(), provider, clockScopeId, routeRevision = routeRevision())
                lastReceipt = elapsed
                status = LocationRecordingStatus.RECEIVING
                if (pending.size >= MAX_BATCH_POINTS || elapsed >= (nextFlushElapsedMs ?: Long.MAX_VALUE)) flush()
            }
            LocationEvent.PermissionDenied -> { gap("permission_denied"); status = LocationRecordingStatus.PERMISSION_DENIED }
            is LocationEvent.NoLocation -> if (event.kind == LocationReadingKind.UPDATE || lastReceipt == null) {
                gap("no_location"); status = LocationRecordingStatus.NO_LOCATION
            }
            is LocationEvent.Failure -> if (event.operation != com.petasos.app.capability.location.LocationOperation.LAST_KNOWN) {
                gap("location_disconnected"); status = LocationRecordingStatus.DISCONNECTED
            }
            is LocationEvent.Availability -> if (!event.supported) { gap("unsupported"); status = LocationRecordingStatus.DISCONNECTED }
            else -> Unit
        }
    }

    fun tick(elapsedMs: Long): LocationCommit? {
        val previous = lastReceipt
        if (previous != null && (elapsedMs < previous || elapsedMs - previous > maxReceiptGapMs)) {
            gap("receipt_gap"); status = LocationRecordingStatus.DISCONNECTED
            return null
        }
        return if (elapsedMs >= (nextFlushElapsedMs ?: Long.MAX_VALUE)) flush() else null
    }

    fun flush(): LocationCommit? {
        if (pending.isEmpty()) return null
        val committed = store.append(checkNotNull(segmentId), pending.toList())
        pending.clear() // On transaction failure retain the exact point IDs for an idempotent retry.
        return committed
    }

    fun stop(suspended: Boolean = false) {
        flush()
        segmentId?.let { store.finishSegment(it, if (suspended) "suspend" else "explicit_stop", interrupted = false) }
        segmentId = null; lastReceipt = null; owner = null; status = LocationRecordingStatus.STOPPED
    }

    private fun gap(reason: String) {
        flush()
        segmentId?.let { store.finishSegment(it, reason, interrupted = true) }
        segmentId = null; lastReceipt = null
    }

    private fun ensureSegment() {
        if (segmentId != null) return
        val (trip, session) = checkNotNull(owner)
        val id = ids()
        store.openSegment(trip, session, id)
        segmentId = id
    }

    companion object { const val BATCH_MILLIS = 1_000L; const val MAX_BATCH_POINTS = 20 }
}
