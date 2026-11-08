package com.petasos.app.location

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Bounded index of route segments; reviewed coastal boundary coordinates remain streamed. */
internal class PolylineBoundaryIndex(path: List<BoundaryCoordinate>, private val maximumComparisons: Int = MAX_COMPARISONS) {
    private data class Segment(val a: BoundaryCoordinate, val b: BoundaryCoordinate) {
        val minX = min(a.longitude, b.longitude)
        val maxX = max(a.longitude, b.longitude)
        val minY = min(a.latitude, b.latitude)
        val maxY = max(a.latitude, b.latitude)
    }
    private val segments: List<Segment>
    private val prefixMaximumX: DoubleArray
    private var comparisons = 0
    val first: BoundaryCoordinate
    private val minX: Double
    private val maxX: Double
    private val minY: Double
    private val maxY: Double

    init {
        require(path.size in 2..MAX_POINTS && maximumComparisons > 0 && path.all {
            it.longitude.isFinite() && it.latitude.isFinite() && it.longitude in 120.0..135.0 && it.latitude in 30.0..45.0
        }) { "Invalid polyline" }
        first = path.first()
        segments = path.zipWithNext { a, b -> Segment(a, b) }.sortedBy { it.minX }
        prefixMaximumX = DoubleArray(segments.size)
        var upper = Double.NEGATIVE_INFINITY
        segments.forEachIndexed { index, segment -> upper = max(upper, segment.maxX); prefixMaximumX[index] = upper }
        minX = segments.minOf { it.minX }; maxX = segments.maxOf { it.maxX }
        minY = segments.minOf { it.minY }; maxY = segments.maxOf { it.maxY }
    }

    fun overlaps(bounds: PackedRegionBounds): Boolean = minX <= bounds.maxLongitude + TOLERANCE &&
        maxX >= bounds.minLongitude - TOLERANCE && minY <= bounds.maxLatitude + TOLERANCE &&
        maxY >= bounds.minLatitude - TOLERANCE

    /** True for crossing, endpoint touch, collinear overlap, or roundoff-uncertain contact. */
    fun touches(ax: Double, ay: Double, bx: Double, by: Double): Boolean {
        val edgeMinX = min(ax, bx); val edgeMaxX = max(ax, bx)
        val edgeMinY = min(ay, by); val edgeMaxY = max(ay, by)
        if (edgeMinX > maxX + TOLERANCE || edgeMaxX < minX - TOLERANCE ||
            edgeMinY > maxY + TOLERANCE || edgeMaxY < minY - TOLERANCE) return false
        var low = 0
        var high = segments.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (segments[middle].minX <= edgeMaxX + TOLERANCE) low = middle + 1 else high = middle
        }
        var index = low - 1
        while (index >= 0 && prefixMaximumX[index] >= edgeMinX - TOLERANCE) {
            check(comparisons++ < maximumComparisons) { "Polyline comparison budget exceeded" }
            val segment = segments[index--]
            if (segment.maxX < edgeMinX - TOLERANCE || segment.maxY < edgeMinY - TOLERANCE ||
                segment.minY > edgeMaxY + TOLERANCE) continue
            if (intersects(segment.a.longitude, segment.a.latitude, segment.b.longitude, segment.b.latitude,
                    ax, ay, bx, by)) return true
        }
        return false
    }

    companion object {
        const val MAX_POINTS = 200_000
        const val MAX_COMPARISONS = 2_000_000
        private const val TOLERANCE = 1e-10

        private fun orientation(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Int {
            val dx = bx - ax; val dy = by - ay
            val cross = dx * (cy - ay) - dy * (cx - ax)
            val tolerance = TOLERANCE * (abs(dx) + abs(dy))
            return when { cross > tolerance -> 1; cross < -tolerance -> -1; else -> 0 }
        }

        private fun onSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Boolean =
            px >= min(ax, bx) - TOLERANCE && px <= max(ax, bx) + TOLERANCE &&
                py >= min(ay, by) - TOLERANCE && py <= max(ay, by) + TOLERANCE &&
                orientation(ax, ay, bx, by, px, py) == 0

        private fun intersects(ax: Double, ay: Double, bx: Double, by: Double,
                               cx: Double, cy: Double, dx: Double, dy: Double): Boolean {
            val first = orientation(ax, ay, bx, by, cx, cy)
            val second = orientation(ax, ay, bx, by, dx, dy)
            val third = orientation(cx, cy, dx, dy, ax, ay)
            val fourth = orientation(cx, cy, dx, dy, bx, by)
            return (first * second < 0 && third * fourth < 0) ||
                (first == 0 && onSegment(cx, cy, ax, ay, bx, by)) ||
                (second == 0 && onSegment(dx, dy, ax, ay, bx, by)) ||
                (third == 0 && onSegment(ax, ay, cx, cy, dx, dy)) ||
                (fourth == 0 && onSegment(bx, by, cx, cy, dx, dy))
        }
    }
}
