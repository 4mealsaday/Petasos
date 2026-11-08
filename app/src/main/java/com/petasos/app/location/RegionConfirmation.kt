package com.petasos.app.location

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
    fun confirmed(
        regionRevisionId: String, policy: RegionPolicy, points: List<RegionPoint>, boundary: RegionBoundaryLookup,
    ): Boolean {
        if (points.size != policy.consecutiveSamples) return false
        val first = points.first()
        val last = points.last()
        if (last.location.receivedAtElapsedMs < first.location.receivedAtElapsedMs ||
            last.location.receivedAtElapsedMs - first.location.receivedAtElapsedMs < policy.minConfirmationDurationMs) return false
        return points.withIndex().all { (index, point) ->
            val location = point.location
            val previous = points.getOrNull(index - 1)?.location
            val accuracy = location.accuracyMeters
            if (point.sequence != first.sequence + index || location.quality != LocationQuality.VALID ||
                location.adapterVersion != policy.qualityAdapterVersion || location.clockScopeId != first.location.clockScopeId ||
                location.latitude == null || location.longitude == null || accuracy == null || !accuracy.isFinite() || accuracy < 0 ||
                location.observedAtEpochMs == null || location.observedAtEpochMs < 0 ||
                location.observedAtEpochMs > location.receivedAtEpochMs ||
                (previous != null && (location.receivedAtElapsedMs <= previous.receivedAtElapsedMs ||
                    previous.observedAtEpochMs == null || location.observedAtEpochMs <= previous.observedAtEpochMs))) return@all false
            val regions = try { boundary.containing(location.latitude, location.longitude) } catch (_: Exception) { emptyList() }
            // A reviewed hierarchy wrapper may resolve a parent/child chain; raw ambiguity still fails.
            val region = regions.singleOrNull() ?: return@all false
            (try { boundary.isSelectionCurrent(regionRevisionId) } catch (_: Exception) { false }) &&
                region.regionRevisionId == regionRevisionId && region.datasetVersion == policy.datasetVersion &&
                region.coordinateReferenceSystem == policy.coordinateReferenceSystem && region.interiorDistanceMeters.isFinite() &&
                // A shared edge or roundoff-uncertain point is never a proven interior, even for zero thresholds.
                region.interiorDistanceMeters > 0 &&
                region.interiorDistanceMeters >= policy.boundaryMarginMeters + accuracy
        }
    }
}
