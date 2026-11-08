package com.petasos.app.planning.auxiliary

import com.petasos.app.location.BoundaryCoordinate
import com.petasos.app.location.PolylineRegion
import com.petasos.app.location.RegionPolylineLookup
import com.petasos.app.location.polylineDigest
import com.petasos.app.location.polylineSha256
import java.util.Collections

/**
 * NAVER guide.duration covers the complete previous-guide-index to current-guide-index span.
 * https://api.ncloud-docs.com/docs/application-maps-directions5
 */
data class AuxiliaryGuideInterval(
    val startPointIndex: Int,
    val endPointIndex: Int,
    val durationMs: Long,
    val distanceMeters: Long,
) {
    init {
        require(startPointIndex in 0 until AuxiliaryRouteGeometry.MAX_POINTS &&
            endPointIndex in startPointIndex until AuxiliaryRouteGeometry.MAX_POINTS &&
            durationMs in 0..AuxiliaryRouteGeometry.MAX_DURATION_MS &&
            distanceMeters in 0..AuxiliaryRouteGeometry.MAX_DISTANCE_METERS) { "Invalid auxiliary guide interval" }
    }
    override fun toString() = "AuxiliaryGuideInterval([redacted])"
}

/**
 * Immutable transport input. routeKey hashes normalized provider/source/geometry/timing, not a model
 * choice or a navigation-route identity. Incomplete timing remains representable for diagnostics;
 * the projector checks completeness independently. This object has no clock or location authority.
 */
class AuxiliaryRouteGeometry(
    val routeKey: String,
    path: List<BoundaryCoordinate>,
    guides: List<AuxiliaryGuideInterval>,
    val totalDurationMs: Long,
) {
    val path: List<BoundaryCoordinate>
    val guides: List<AuxiliaryGuideInterval>
    init {
        require(polylineSha256(routeKey) && path.size in 2..MAX_POINTS && guides.size <= MAX_GUIDES &&
            totalDurationMs in 0..MAX_DURATION_MS && path.all {
                it.longitude.isFinite() && it.latitude.isFinite() &&
                    it.longitude in 120.0..135.0 && it.latitude in 30.0..45.0
            }) { "Invalid auxiliary route geometry" }
        this.path = Collections.unmodifiableList(ArrayList(path))
        this.guides = Collections.unmodifiableList(ArrayList(guides))
    }
    override fun toString() = "AuxiliaryRouteGeometry([redacted])"
    companion object {
        const val MAX_POINTS = 200_000
        const val MAX_GUIDES = 50_000
        const val MAX_DURATION_MS = 7L * 24 * 60 * 60 * 1_000
        const val MAX_DISTANCE_METERS = 10_000_000L
    }
}

@ConsistentCopyVisibility
data class AuxiliaryRegionWindow internal constructor(
    val visitId: String,
    val region: PolylineRegion,
    val startsAtOffsetMs: Long,
    val endsAtOffsetMs: Long,
    val startGuideIndex: Int,
    val endGuideIndex: Int,
) {
    init {
        require(visitId.startsWith("aux-") && polylineSha256(visitId.removePrefix("aux-")) &&
            startsAtOffsetMs >= 0 && endsAtOffsetMs > startsAtOffsetMs &&
            endsAtOffsetMs <= AuxiliaryRouteGeometry.MAX_DURATION_MS && startGuideIndex >= 0 &&
            endGuideIndex in startGuideIndex until AuxiliaryRouteGeometry.MAX_GUIDES) { "Invalid auxiliary region window" }
    }
    override fun toString() = "AuxiliaryRegionWindow([redacted])"
}

/**
 * Provider-relative predictions only. Offset zero is not an observed departure time. A later adapter
 * must bind a fresh, unambiguous observed position, source authority, clock and expiration before
 * creating a ReviewedRouteForecast. Missing windows are unknown gaps, never a stay in the last region.
 */
class AuxiliaryRegionTimeline internal constructor(
    val routeKey: String,
    val geometryEvidenceKey: String,
    val totalDurationMs: Long,
    windows: List<AuxiliaryRegionWindow>,
    val unknownGuideCount: Int,
    val evaluatedGuideCount: Int,
    val geometryBudgetExhausted: Boolean,
) {
    val windows: List<AuxiliaryRegionWindow> = Collections.unmodifiableList(ArrayList(windows))
    init {
        require(polylineSha256(routeKey) && polylineSha256(geometryEvidenceKey) &&
            totalDurationMs in 1..AuxiliaryRouteGeometry.MAX_DURATION_MS &&
            unknownGuideCount in 0..AuxiliaryRouteGeometry.MAX_GUIDES &&
            evaluatedGuideCount in 0..AuxiliaryRouteGeometry.MAX_GUIDES &&
            this.windows.size <= AuxiliaryRouteGeometry.MAX_GUIDES &&
            this.windows.map { it.visitId }.distinct().size == this.windows.size &&
            this.windows.all { it.endsAtOffsetMs <= totalDurationMs } &&
            this.windows.zipWithNext().all { (a, b) -> a.endsAtOffsetMs <= b.startsAtOffsetMs && a.endGuideIndex < b.startGuideIndex } &&
            this.windows.map { it.region.datasetVersion to it.region.hierarchyKey }.distinct().size <= 1) { "Invalid auxiliary region timeline" }
    }
    override fun toString() = "AuxiliaryRegionTimeline([redacted])"
}

/**
 * No boundary-time interpolation: only entire guide polylines within one canonical region yield a
 * time window. Adjacent proved spans of that same exact region may merge; unknown spans always break
 * a visit, even if the same region occurs again. Complete timing can validly produce no windows.
 */
class AuxiliaryRegionTimelineProjector(
    private val geometry: RegionPolylineLookup,
    private val maximumGeometryQueries: Int = DEFAULT_MAXIMUM_GEOMETRY_QUERIES,
) {
    init { require(maximumGeometryQueries in 1..1_024) { "Invalid auxiliary projection budget" } }

    fun project(route: AuxiliaryRouteGeometry): AuxiliaryRegionTimeline? = try {
        val evidence = geometry.evidenceKey
        if (!polylineSha256(evidence) || !auxiliaryTimingUsable(route)) null else projectUsable(route, evidence)
    } catch (_: Exception) { null }

    private fun projectUsable(route: AuxiliaryRouteGeometry, evidence: String): AuxiliaryRegionTimeline? {
        val windows = mutableListOf<AuxiliaryRegionWindow>()
        var offset = 0L
        var unknown = 0
        var mayMerge = false
        var hierarchyKey: String? = null
        var datasetVersion: String? = null
        var geometryQueries = 0
        var budgetExhausted = false
        for ((index, guide) in route.guides.withIndex()) {
            // Zero-time same-index origin/duplicate guides contribute neither travel nor a gap.
            if (guide.startPointIndex == guide.endPointIndex) continue
            val end = Math.addExact(offset, guide.durationMs)
            // Wire limits need not authorize 50,000 repeated scans of detailed coastal geometry.
            // Exhaustion leaves the remaining time intervals unknown; it never extends a known region.
            val region = if (geometryQueries < maximumGeometryQueries) {
                geometryQueries++
                geometry.containing(route.path.subList(guide.startPointIndex, guide.endPointIndex + 1))
            } else { budgetExhausted = true; null }
            if (geometry.evidenceKey != evidence) return null
            if (region == null) {
                unknown++
                mayMerge = false
            } else {
                if ((hierarchyKey != null && hierarchyKey != region.hierarchyKey) ||
                    (datasetVersion != null && datasetVersion != region.datasetVersion)) return null
                hierarchyKey = region.hierarchyKey
                datasetVersion = region.datasetVersion
                val previous = windows.lastOrNull()
                val startGuide = if (mayMerge && previous != null && previous.region == region &&
                    previous.endsAtOffsetMs == offset) previous.startGuideIndex else index
                val startOffset = if (startGuide != index) windows.removeAt(windows.lastIndex).startsAtOffsetMs else offset
                windows += AuxiliaryRegionWindow(visitKey(route.routeKey, evidence, region, startGuide, index), region,
                    startOffset, end, startGuide, index)
                mayMerge = true
            }
            offset = end
        }
        return if (geometry.evidenceKey != evidence) null else
            AuxiliaryRegionTimeline(route.routeKey, evidence, route.totalDurationMs, windows, unknown,
                geometryQueries, budgetExhausted)
    }

    private fun visitKey(route: String, geometry: String, region: PolylineRegion, start: Int, end: Int): String =
        "aux-" + polylineDigest(listOf(route, geometry, region.regionId, region.regionRevisionId,
            region.datasetVersion, region.hierarchyKey, start.toString(), end.toString()))

    companion object {
        /** Implementation work limit, not a provider accuracy or a reviewed visit-policy threshold. */
        const val DEFAULT_MAXIMUM_GEOMETRY_QUERIES = 64
    }
}

/** Shared by the relative projector and observed-position anchor; no provider flag substitutes for it. */
internal fun auxiliaryTimingUsable(route: AuxiliaryRouteGeometry): Boolean {
        if (route.guides.isEmpty() || route.totalDurationMs <= 0) return false
        var previousIndex = 0
        var total = 0L
        for (guide in route.guides) {
            if (guide.startPointIndex != previousIndex || guide.endPointIndex > route.path.lastIndex) return false
            val sameIndex = guide.startPointIndex == guide.endPointIndex
            if (sameIndex) {
                if (guide.durationMs != 0L || guide.distanceMeters != 0L) return false
            } else {
                if (guide.durationMs <= 0 || (guide.startPointIndex until guide.endPointIndex).none {
                        route.path[it] != route.path[it + 1]
                    }) return false
            }
            total = Math.addExact(total, guide.durationMs)
            if (total > route.totalDurationMs) return false
            previousIndex = guide.endPointIndex
        }
        return previousIndex == route.path.lastIndex && total == route.totalDurationMs
}
