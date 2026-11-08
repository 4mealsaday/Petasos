package com.petasos.app.location

import com.petasos.app.generation.StrictJson
import java.util.Collections

/** Configured only from a reviewed dataset; none of these thresholds has a production default. */
data class RegionPolicy(
    val datasetVersion: String,
    val consecutiveSamples: Int,
    val minConfirmationDurationMs: Long,
    val boundaryMarginMeters: Double,
    val qualityAdapterVersion: String,
    val coordinateReferenceSystem: String,
) {
    init {
        require(datasetVersion.isNotBlank() && qualityAdapterVersion.isNotBlank())
        require(consecutiveSamples in 2..100 && minConfirmationDurationMs >= 0)
        require(boundaryMarginMeters.isFinite() && boundaryMarginMeters >= 0)
        require(coordinateReferenceSystem == "EPSG:4326")
    }
}

/** Real containment and a conservative lower bound on distance to the reviewed boundary. */
data class RegionContainment(
    val regionRevisionId: String,
    val datasetVersion: String,
    val coordinateReferenceSystem: String,
    val interiorDistanceMeters: Double,
)

fun interface RegionBoundaryLookup {
    fun containing(latitude: Double, longitude: Double): List<RegionContainment>
    /** Optional reviewed selection identity; raw geometry callers retain exact-only semantics. */
    val selectionKey: String? get() = null
    fun isSelectionCurrent(regionRevisionId: String): Boolean = true

    companion object {
        /** Unknown boundaries never become an approximate administrative region or an earned card. */
        val NONE = RegionBoundaryLookup { _, _ -> emptyList() }
    }
}

data class RegionPoint(val sequence: Long, val location: RecordedLocation) {
    override fun toString(): String = "RegionPoint([redacted])"
}

/** Confirmation consumes consecutive stored rows, including invalid rows, never filtered valid samples. */
object RegionConfirmation {
    /** Bounds both the SQL read and the durable proof; thresholds never cause an unbounded scan. */
    const val MAX_PROOF_POINTS = 100

    fun confirmed(
        regionRevisionId: String, policy: RegionPolicy, points: List<RegionPoint>, boundary: RegionBoundaryLookup,
    ): Boolean = proof(regionRevisionId, policy, points, boundary) != null

    /** Chronological, unfiltered rows from ONE segment. Return the shortest valid suffix. */
    fun proof(
        regionRevisionId: String, policy: RegionPolicy, points: List<RegionPoint>, boundary: RegionBoundaryLookup,
    ): List<RegionPoint>? {
        if (points.size !in policy.consecutiveSamples..MAX_PROOF_POINTS) return null
        val last = points.last()
        val selectionKey = try { boundary.selectionKey } catch (_: Exception) { return null }
        val ids = mutableSetOf<String>()
        var newer: RegionPoint? = null
        var newerMeasurement: Long? = null
        for (index in points.lastIndex downTo 0) {
            val point = points[index]
            val location = point.location
            val accuracy = location.accuracyMeters
            if (point.sequence < 0 || location.pointId.isBlank() || !ids.add(location.pointId) ||
                location.quality != LocationQuality.VALID || location.receivedAtElapsedMs < 0 ||
                location.adapterVersion != policy.qualityAdapterVersion || location.clockScopeId != last.location.clockScopeId ||
                location.latitude == null || location.longitude == null || accuracy == null || !accuracy.isFinite() || accuracy < 0 ||
                location.observedAtEpochMs == null || location.observedAtEpochMs < 0 ||
                location.observedAtEpochMs > location.receivedAtEpochMs) return null
            val measurement = measurementOrder(location) ?: return null
            if (newer != null && (newer.sequence == 0L || point.sequence != newer.sequence - 1 ||
                    location.receivedAtElapsedMs >= newer.location.receivedAtElapsedMs ||
                    location.observedAtEpochMs >= checkNotNull(newer.location.observedAtEpochMs) ||
                    measurement >= checkNotNull(newerMeasurement))) return null
            val regions = try { boundary.containing(location.latitude, location.longitude) } catch (_: Exception) { emptyList() }
            // A reviewed hierarchy wrapper may resolve a parent/child chain; raw ambiguity still fails.
            val region = regions.singleOrNull() ?: return null
            if (!selectionCurrent(boundary, selectionKey, regionRevisionId) ||
                region.regionRevisionId != regionRevisionId || region.datasetVersion != policy.datasetVersion ||
                region.coordinateReferenceSystem != policy.coordinateReferenceSystem || !region.interiorDistanceMeters.isFinite() ||
                // A shared edge or roundoff-uncertain point is never a proven interior, even for zero thresholds.
                region.interiorDistanceMeters <= 0 ||
                region.interiorDistanceMeters < policy.boundaryMarginMeters + accuracy) return null
            if (points.size - index >= policy.consecutiveSamples &&
                last.location.receivedAtElapsedMs - location.receivedAtElapsedMs >= policy.minConfirmationDurationMs) {
                if (!selectionCurrent(boundary, selectionKey, regionRevisionId)) return null
                return Collections.unmodifiableList(ArrayList(points.subList(index, points.size)))
            }
            newer = point
            newerMeasurement = measurement
        }
        return null
    }

    private fun selectionCurrent(boundary: RegionBoundaryLookup, key: String?, revision: String): Boolean = try {
        boundary.selectionKey == key && boundary.isSelectionCurrent(revision)
    } catch (_: Exception) { false }

    /** Historical rows keep receipt-time quality; only verified same-boot Android measurements count. */
    private fun measurementOrder(point: RecordedLocation): Long? {
        if (point.adapterVersion != AndroidLocationMeaningAdapter.VERSION) return point.observedAtEpochMs
        return try {
            val envelope = StrictJson.parse(point.providerConfidenceJson.toByteArray()) as? Map<*, *> ?: return null
            val age = AndroidLocationMeaningAdapter.restoredAgeMs(envelope, point.receivedAtElapsedMs) ?: return null
            if (age != point.measurementAgeAtReceiptMs) return null
            val metadata = envelope["androidLocation"] as? Map<*, *> ?: return null
            metadata["elapsedRealtimeNanos"] as? Long
        } catch (_: Exception) { null }
    }
}
