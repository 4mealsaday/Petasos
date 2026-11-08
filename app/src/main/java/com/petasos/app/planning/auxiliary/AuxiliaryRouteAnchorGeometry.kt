package com.petasos.app.planning.auxiliary

import com.petasos.app.location.BoundaryCoordinate
import kotlin.math.*

internal data class AuxiliaryRoutePhase(val lowerProgress: Double, val upperProgress: Double,
                                        val startsAtOffsetMs: Long, val endsAtOffsetMs: Long) {
    override fun toString() = "AuxiliaryRoutePhase([redacted])"
}

internal sealed interface AuxiliaryRouteMatch {
    data class Found(val phase: AuxiliaryRoutePhase) : AuxiliaryRouteMatch
    data object Ambiguous : AuxiliaryRouteMatch
    data object OffPath : AuxiliaryRouteMatch
    data object Backtracked : AuxiliaryRouteMatch
}

/**
 * Finds one connected possible portion of the ENTIRE path. It never chooses the nearest branch of
 * parallel roads/loops. Lower distance bounds include every possibly matching segment; at least one
 * actual point on that path must also pass an upper distance bound. Both use WGS84 curvature bounds
 * in the same Korean envelope as BoundaryMetric. The accuracy radius itself is Android's statistical
 * estimate, not a promise that the real car is in that circle.
 */
internal class AuxiliaryRouteAnchorGeometry(private val route: AuxiliaryRouteGeometry) {
    private val offsets = LongArray(route.guides.size + 1).also { sums ->
        route.guides.forEachIndexed { index, guide -> sums[index + 1] = Math.addExact(sums[index], guide.durationMs) }
    }

    fun match(point: BoundaryCoordinate, accuracyMeters: Double, progressFloor: Double): AuxiliaryRouteMatch {
        var lower: Double? = null
        var upper = 0.0
        var near = false
        for (index in 0 until route.path.lastIndex) {
            val a = route.path[index]; val b = route.path[index + 1]
            val ax = (a.longitude - point.longitude) * LOWER_LONGITUDE_SCALE
            val ay = (a.latitude - point.latitude) * LOWER_LATITUDE_SCALE
            val bx = (b.longitude - point.longitude) * LOWER_LONGITUDE_SCALE
            val by = (b.latitude - point.latitude) * LOWER_LATITUDE_SCALE
            val dx = bx - ax; val dy = by - ay
            val squared = dx * dx + dy * dy
            val radius = accuracyMeters + NUMERIC_METERS
            val center = if (squared == 0.0) 0.0 else -(ax * dx + ay * dy) / squared
            val closest = center.coerceIn(0.0, 1.0)
            if (hypot(ax + closest * dx, ay + closest * dy) > radius) continue
            val halfWidth = if (squared == 0.0) 1.0 else {
                val perpendicularSquared = (ax + center * dx).pow(2) + (ay + center * dy).pow(2)
                sqrt(max(0.0, radius * radius - perpendicularSquared) / squared)
            }
            val start = if (squared == 0.0) 0.0 else max(0.0, center - halfWidth)
            val end = if (squared == 0.0) 1.0 else min(1.0, center + halfWidth)
            if (start > end) continue
            val from = index + start; val to = index + end
            if (lower == null) lower = from else if (from > upper + PROGRESS_ROUNDOFF) return AuxiliaryRouteMatch.Ambiguous
            upper = max(upper, to)
            // Evaluate a real point of the encoded linear path, rather than treating the lower
            // bound as proof of proximity. A nonoptimal chosen point can only withhold a match.
            if (upperDistance(point, BoundaryCoordinate(a.longitude + closest * (b.longitude - a.longitude),
                    a.latitude + closest * (b.latitude - a.latitude))) <= accuracyMeters + NUMERIC_METERS) near = true
        }
        val minimum = lower ?: return AuxiliaryRouteMatch.OffPath
        if (!near) return AuxiliaryRouteMatch.OffPath
        if (upper + PROGRESS_ROUNDOFF < progressFloor) return AuxiliaryRouteMatch.Backtracked
        val progress = max(minimum, progressFloor)
        var phaseStart: Long? = null
        var phaseEnd = 0L
        for ((index, guide) in route.guides.withIndex()) {
            if (guide.startPointIndex == guide.endPointIndex) continue
            if (guide.endPointIndex.toDouble() + PROGRESS_ROUNDOFF < progress ||
                guide.startPointIndex.toDouble() - PROGRESS_ROUNDOFF > upper) continue
            if (phaseStart == null) phaseStart = offsets[index]
            phaseEnd = offsets[index + 1]
        }
        return phaseStart?.let { AuxiliaryRouteMatch.Found(AuxiliaryRoutePhase(progress, upper, it, phaseEnd)) }
            ?: AuxiliaryRouteMatch.OffPath
    }

    private fun upperDistance(a: BoundaryCoordinate, b: BoundaryCoordinate): Double {
        val latitude = (b.latitude - a.latitude) * RADIANS
        val longitude = (b.longitude - a.longitude) * RADIANS
        val square = sin(latitude / 2).pow(2) + cos(a.latitude * RADIANS) * cos(b.latitude * RADIANS) * sin(longitude / 2).pow(2)
        // 6,400 km exceeds both WGS84 principal curvature radii everywhere.
        return 6_400_000.0 * 2 * asin(sqrt(square.coerceIn(0.0, 1.0)))
    }

    companion object {
        private const val RADIANS = Math.PI / 180.0
        private const val NUMERIC_METERS = 0.001
        private const val PROGRESS_ROUNDOFF = 1e-9
        private val halfSpan = 7.5 * RADIANS
        private val LOWER_LATITUDE_SCALE = 6_335_439.0 * sin(halfSpan) / halfSpan * RADIANS
        private val LOWER_LONGITUDE_SCALE = LOWER_LATITUDE_SCALE * cos(45.0 * RADIANS)
    }
}
