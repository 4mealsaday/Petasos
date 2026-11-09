package com.petasos.app.location

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

/**
 * Demo rule: the current location is confirmed once the last N (=[RegionPolicy.consecutiveSamples])
 * stored points each land inside the same region with accuracy under [MAX_ACCURACY_METERS].
 */
object RegionConfirmation {
    /** Bounds both the SQL read and the durable proof; thresholds never cause an unbounded scan. */
    const val MAX_PROOF_POINTS = 100
    const val MAX_ACCURACY_METERS = 100.0

    fun confirmed(
        regionRevisionId: String, policy: RegionPolicy, points: List<RegionPoint>, boundary: RegionBoundaryLookup,
    ): Boolean = proof(regionRevisionId, policy, points, boundary) != null

    /** Chronological, unfiltered rows from ONE segment. Returns the last N points once all qualify. */
    fun proof(
        regionRevisionId: String, policy: RegionPolicy, points: List<RegionPoint>, boundary: RegionBoundaryLookup,
    ): List<RegionPoint>? {
        if (points.size !in policy.consecutiveSamples..MAX_PROOF_POINTS) return null
        val recent = points.subList(points.size - policy.consecutiveSamples, points.size)
        for (point in recent) {
            val location = point.location
            val accuracy = location.accuracyMeters
            if (location.quality != LocationQuality.VALID || location.latitude == null || location.longitude == null ||
                accuracy == null || !accuracy.isFinite() || accuracy < 0 || accuracy >= MAX_ACCURACY_METERS) return null
            val regions = try { boundary.containing(location.latitude, location.longitude) } catch (_: Exception) { emptyList() }
            // A reviewed hierarchy wrapper may resolve a parent/child chain; raw ambiguity still fails.
            val region = regions.singleOrNull() ?: return null
            if (region.regionRevisionId != regionRevisionId) return null
        }
        return Collections.unmodifiableList(ArrayList(recent))
    }
}
