package com.petasos.app.location

import java.util.Collections
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** GeoJSON order is longitude, latitude, even though the lookup API accepts latitude first. */
data class BoundaryCoordinate(val longitude: Double, val latitude: Double) {
    override fun toString(): String = "BoundaryCoordinate([redacted])"
}

/** A closed exterior and zero or more closed holes. Both winding orders are accepted. */
class RegionPolygon(exterior: List<BoundaryCoordinate>, holes: List<List<BoundaryCoordinate>> = emptyList()) {
    val exterior: List<BoundaryCoordinate>
    val holes: List<List<BoundaryCoordinate>>
    internal val coordinateCount: Int

    init {
        require(holes.size < ReviewedPolygonBoundaryLookup.MAX_RINGS_PER_POLYGON) { "Too many boundary rings" }
        val count = exterior.size.toLong() + holes.sumOf { it.size.toLong() }
        require(count <= ReviewedPolygonBoundaryLookup.MAX_TOTAL_COORDINATES) { "Boundary geometry exceeds limit" }
        this.exterior = copyRing(exterior)
        this.holes = immutableList(holes.map(::copyRing))
        coordinateCount = count.toInt()
    }

    override fun toString(): String = "RegionPolygon([redacted])"
}

/** All polygons share one immutable region revision; disjoint islands are represented individually. */
class ReviewedRegionGeometry(val regionRevisionId: String, polygons: List<RegionPolygon>) {
    val polygons: List<RegionPolygon>
    internal val coordinateCount: Int

    init {
        requireBoundaryIdentity(regionRevisionId)
        require(polygons.size in 1..ReviewedPolygonBoundaryLookup.MAX_POLYGONS_PER_REGION) { "Invalid polygon count" }
        val count = polygons.sumOf { it.coordinateCount.toLong() }
        require(count <= ReviewedPolygonBoundaryLookup.MAX_TOTAL_COORDINATES) { "Boundary geometry exceeds limit" }
        this.polygons = immutableList(polygons)
        coordinateCount = count.toInt()
    }

    override fun toString(): String = "ReviewedRegionGeometry([redacted])"
}

/**
 * Geometry engine for a separately reviewed and versioned WGS84 boundary dataset. Construction does
 * not review a dataset, select a RegionPolicy, or enable product location/region collection.
 *
 * Containment follows RFC 7946 sections 3.1.1/3.1.6: edges are straight in longitude/latitude, not
 * geodesic arcs. Exterior and hole boundaries have zero clearance. Matches from different regions
 * are all returned, so RegionConfirmation can reject overlaps and shared administrative borders.
 *
 * interiorDistanceMeters is a CONSERVATIVE LOWER BOUND on the shortest WGS84 surface distance to
 * those edges, rather than an asserted exact geodesic distance. It can withhold a region near a
 * boundary; it cannot grant additional clearance. See BoundaryMetric for the derivation.
 *
 * The supported envelope is 120..135 E / 30..45 N (Korea); no antimeridian or polar interpretation
 * is attempted. Invalid/unsupported geometry throws without including input values. Invalid query
 * coordinates return no matches. Ring simplicity/topology checks use an x-interval sweep with a
 * fixed comparison budget, avoiding unbounded quadratic work on pathological coastal input.
 *
 * Sources: https://datatracker.ietf.org/doc/html/rfc7946#section-3.1.1
 * WGS84 parameters: https://earth-info.nga.mil/index.php?action=wgs84&dir=wgs84
 */
class ReviewedPolygonBoundaryLookup(
    val datasetVersion: String,
    regions: List<ReviewedRegionGeometry>,
) : RegionBoundaryLookup {
    private val regions: List<CheckedRegion>

    init {
        requireBoundaryIdentity(datasetVersion)
        require(regions.size in 1..MAX_REGIONS) { "Invalid region count" }
        require(regions.map { it.regionRevisionId }.toSet().size == regions.size) { "Duplicate region revision" }
        require(regions.sumOf { it.coordinateCount.toLong() } <= MAX_TOTAL_COORDINATES) { "Boundary geometry exceeds limit" }
        val budget = ValidationBudget()
        this.regions = immutableList(regions.map { checkRegion(it, budget) })
    }

    override fun containing(latitude: Double, longitude: Double): List<RegionContainment> {
        if (!supportedCoordinate(longitude, latitude)) return emptyList()
        val point = BoundaryCoordinate(longitude, latitude)
        val result = ArrayList<RegionContainment>()
        for (region in regions) {
            for (polygon in region.polygons) {
                val relation = polygon.relation(point)
                if (relation == Relation.OUTSIDE) continue
                val distance = if (relation == Relation.BOUNDARY) 0.0 else {
                    polygon.rings.minOf { ring -> ring.edges.minOf { BoundaryMetric.clearance(point, it.start, it.end) } }
                }
                result += RegionContainment(region.revisionId, datasetVersion, "EPSG:4326", distance)
                break
            }
        }
        return immutableList(result)
    }

    override fun toString(): String = "ReviewedPolygonBoundaryLookup([redacted])"

    companion object {
        const val MAX_REGIONS = 512
        const val MAX_POLYGONS_PER_REGION = 2_048
        const val MAX_RINGS_PER_POLYGON = 256
        const val MAX_RING_COORDINATES = 32_769
        const val MAX_TOTAL_COORDINATES = 100_000
        const val MAX_VALIDATION_COMPARISONS = 2_000_000
    }
}

private const val COORDINATE_TOLERANCE = 1e-10
private const val MIN_LONGITUDE = 120.0
private const val MAX_LONGITUDE = 135.0
private const val MIN_LATITUDE = 30.0
private const val MAX_LATITUDE = 45.0

private fun supportedCoordinate(longitude: Double, latitude: Double): Boolean =
    longitude.isFinite() && latitude.isFinite() &&
        longitude in MIN_LONGITUDE..MAX_LONGITUDE && latitude in MIN_LATITUDE..MAX_LATITUDE

private fun requireBoundaryIdentity(value: String) {
    require(value.length in 1..256 && value == value.trim() && value.none { it.isISOControl() }) { "Invalid boundary identity" }
}

private fun <T> immutableList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

private fun copyRing(input: List<BoundaryCoordinate>): List<BoundaryCoordinate> {
    require(input.size in 4..ReviewedPolygonBoundaryLookup.MAX_RING_COORDINATES) { "Invalid boundary ring size" }
    val points = immutableList(input)
    require(points.all { supportedCoordinate(it.longitude, it.latitude) }) { "Unsupported boundary coordinates" }
    require(points.first() == points.last()) { "Boundary ring must be explicitly closed" }
    for (index in 0 until points.lastIndex) {
        val a = points[index]
        val b = points[index + 1]
        require(max(abs(a.longitude - b.longitude), abs(a.latitude - b.latitude)) > COORDINATE_TOLERANCE) {
            "Degenerate boundary edge"
        }
    }
    return points
}

private class ValidationBudget {
    private var remaining = ReviewedPolygonBoundaryLookup.MAX_VALIDATION_COMPARISONS
    fun spend() {
        require(remaining-- > 0) { "Boundary validation complexity exceeds limit" }
    }
}

private enum class Relation { OUTSIDE, INSIDE, BOUNDARY }

private class Bounds(points: List<BoundaryCoordinate>) {
    val minX = points.minOf { it.longitude }
    val maxX = points.maxOf { it.longitude }
    val minY = points.minOf { it.latitude }
    val maxY = points.maxOf { it.latitude }
    fun includes(point: BoundaryCoordinate): Boolean =
        point.longitude >= minX - COORDINATE_TOLERANCE && point.longitude <= maxX + COORDINATE_TOLERANCE &&
            point.latitude >= minY - COORDINATE_TOLERANCE && point.latitude <= maxY + COORDINATE_TOLERANCE
    fun overlaps(other: Bounds): Boolean =
        minX <= other.maxX + COORDINATE_TOLERANCE && maxX >= other.minX - COORDINATE_TOLERANCE &&
            minY <= other.maxY + COORDINATE_TOLERANCE && maxY >= other.minY - COORDINATE_TOLERANCE
}

private class Edge(
    val start: BoundaryCoordinate, val end: BoundaryCoordinate,
    val ringId: Int, val index: Int, val ringEdgeCount: Int,
) {
    val minX = min(start.longitude, end.longitude)
    val maxX = max(start.longitude, end.longitude)
    val minY = min(start.latitude, end.latitude)
    val maxY = max(start.latitude, end.latitude)
    fun isAdjacent(other: Edge): Boolean = ringId == other.ringId &&
        (abs(index - other.index) == 1 || abs(index - other.index) == ringEdgeCount - 1)
}

private class CheckedRing(val points: List<BoundaryCoordinate>, ringId: Int) {
    val bounds = Bounds(points)
    val edges = (0 until points.lastIndex).map { Edge(points[it], points[it + 1], ringId, it, points.lastIndex) }

    init {
        // Translate to a local origin to avoid cancellation of large longitude*latitude products.
        val origin = points.first()
        var doubledArea = 0.0
        var compensation = 0.0
        for (edge in edges) {
            val value = (edge.start.longitude - origin.longitude) * (edge.end.latitude - origin.latitude) -
                (edge.end.longitude - origin.longitude) * (edge.start.latitude - origin.latitude)
            val corrected = value - compensation
            val sum = doubledArea + corrected
            compensation = (sum - doubledArea) - corrected
            doubledArea = sum
        }
        require(abs(doubledArea) > 1e-12) { "Degenerate boundary ring area" }
        for (index in edges.indices) {
            val previous = edges[(index + edges.size - 1) % edges.size]
            val next = edges[index]
            val along = (previous.end.longitude - previous.start.longitude) * (next.end.longitude - next.start.longitude) +
                (previous.end.latitude - previous.start.latitude) * (next.end.latitude - next.start.latitude)
            require(orientation(previous.start, previous.end, next.end) != 0 || along > 0.0) { "Backtracking boundary edge" }
        }
    }

    fun relation(point: BoundaryCoordinate, budget: ValidationBudget? = null): Relation {
        if (!bounds.includes(point)) return Relation.OUTSIDE
        var inside = false
        for (edge in edges) {
            budget?.spend()
            if (onSegment(point, edge.start, edge.end)) return Relation.BOUNDARY
            val a = edge.start
            val b = edge.end
            if ((a.latitude > point.latitude) != (b.latitude > point.latitude)) {
                val crossingX = a.longitude + (point.latitude - a.latitude) *
                    (b.longitude - a.longitude) / (b.latitude - a.latitude)
                if (point.longitude < crossingX) inside = !inside
            }
        }
        return if (inside) Relation.INSIDE else Relation.OUTSIDE
    }
}

private class CheckedPolygon(val exterior: CheckedRing, val holes: List<CheckedRing>) {
    val rings = listOf(exterior) + holes
    fun relation(point: BoundaryCoordinate, budget: ValidationBudget? = null): Relation {
        val exteriorRelation = exterior.relation(point, budget)
        if (exteriorRelation != Relation.INSIDE) return exteriorRelation
        for (hole in holes) {
            when (hole.relation(point, budget)) {
                Relation.INSIDE -> return Relation.OUTSIDE
                Relation.BOUNDARY -> return Relation.BOUNDARY
                Relation.OUTSIDE -> Unit
            }
        }
        return Relation.INSIDE
    }
}

private class CheckedRegion(val revisionId: String, val polygons: List<CheckedPolygon>)

private fun checkRegion(region: ReviewedRegionGeometry, budget: ValidationBudget): CheckedRegion {
    var ringId = 0
    val polygons = region.polygons.map { polygon ->
        CheckedPolygon(CheckedRing(polygon.exterior, ringId++), polygon.holes.map { CheckedRing(it, ringId++) })
    }
    // Different regions may overlap. Within one revision all rings must be simple and may not touch
    // or cross, including different islands. Ambiguous topologies are rejected rather than repaired.
    checkIntersections(polygons.flatMap { it.rings }.flatMap { it.edges }, budget)
    for (polygon in polygons) {
        for ((index, hole) in polygon.holes.withIndex()) {
            require(polygon.exterior.relation(hole.points.first(), budget) == Relation.INSIDE) { "Hole is outside exterior" }
            for (other in polygon.holes.take(index)) {
                budget.spend()
                if (!hole.bounds.overlaps(other.bounds)) continue
                require(hole.relation(other.points.first(), budget) == Relation.OUTSIDE &&
                    other.relation(hole.points.first(), budget) == Relation.OUTSIDE) { "Nested boundary holes" }
            }
        }
    }
    for ((index, polygon) in polygons.withIndex()) {
        for (other in polygons.take(index)) {
            budget.spend()
            if (!polygon.exterior.bounds.overlaps(other.exterior.bounds)) continue
            require(polygon.relation(other.exterior.points.first(), budget) == Relation.OUTSIDE &&
                other.relation(polygon.exterior.points.first(), budget) == Relation.OUTSIDE) { "Overlapping polygons in region" }
        }
    }
    return CheckedRegion(region.regionRevisionId, polygons)
}

private fun checkIntersections(edges: List<Edge>, budget: ValidationBudget) {
    val active = ArrayList<Edge>()
    for (edge in edges.sortedWith(compareBy<Edge> { it.minX }.thenBy { it.minY })) {
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            budget.spend()
            val other = iterator.next()
            if (other.maxX < edge.minX - COORDINATE_TOLERANCE) {
                iterator.remove()
                continue
            }
            if (edge.isAdjacent(other) || other.maxY < edge.minY - COORDINATE_TOLERANCE ||
                other.minY > edge.maxY + COORDINATE_TOLERANCE) continue
            require(!intersects(edge, other)) { "Intersecting boundary rings" }
        }
        active.add(edge)
    }
}

private fun orientation(a: BoundaryCoordinate, b: BoundaryCoordinate, c: BoundaryCoordinate): Int {
    val dx = b.longitude - a.longitude
    val dy = b.latitude - a.latitude
    val determinant = dx * (c.latitude - a.latitude) - dy * (c.longitude - a.longitude)
    val tolerance = COORDINATE_TOLERANCE * (abs(dx) + abs(dy))
    return when {
        determinant > tolerance -> 1
        determinant < -tolerance -> -1
        else -> 0
    }
}

private fun onSegment(point: BoundaryCoordinate, a: BoundaryCoordinate, b: BoundaryCoordinate): Boolean =
    point.longitude >= min(a.longitude, b.longitude) - COORDINATE_TOLERANCE &&
        point.longitude <= max(a.longitude, b.longitude) + COORDINATE_TOLERANCE &&
        point.latitude >= min(a.latitude, b.latitude) - COORDINATE_TOLERANCE &&
        point.latitude <= max(a.latitude, b.latitude) + COORDINATE_TOLERANCE && orientation(a, b, point) == 0

private fun intersects(a: Edge, b: Edge): Boolean {
    val first = orientation(a.start, a.end, b.start)
    val second = orientation(a.start, a.end, b.end)
    val third = orientation(b.start, b.end, a.start)
    val fourth = orientation(b.start, b.end, a.end)
    return (first * second < 0 && third * fourth < 0) ||
        (first == 0 && onSegment(b.start, a.start, a.end)) ||
        (second == 0 && onSegment(b.end, a.start, a.end)) ||
        (third == 0 && onSegment(a.start, b.start, b.end)) ||
        (fourth == 0 && onSegment(a.end, b.start, b.end))
}

internal object BoundaryMetric {
    /*
     * WGS84: a=6378137 m, f=1/298.257223563. Both principal curvature radii are at least
     * a*(1-e²)=6335439.327... m everywhere, so ellipsoidal distance >= Rmin*spherical angle.
     * Spherical angle >= unit-sphere chord, whose squared length for two geodetic coordinates is
     * 4*sin²(dLat/2) + 4*cos(lat1)*cos(lat2)*sin²(dLon/2).
     * In the supported envelope, |dLat|,|dLon| <= 15° and cos(lat1)*cos(lat2) >= cos²(45°).
     * sin(x)/x decreases on [0,7.5°], so the chord is at least the Euclidean length with scales
     * sinc(7.5°) on latitude and cos(45°)*sinc(7.5°) on longitude. Minimizing that linear metric
     * over a GeoJSON segment therefore stays BELOW its minimum ellipsoidal distance, without
     * approximating the segment by a different great-circle arc. A millimetre is withheld for
     * floating point roundoff. This bound is intentionally conservative, especially in longitude.
     */
    private val radiansPerDegree = Math.PI / 180.0
    private val halfSpanRadians = 7.5 * radiansPerDegree
    private val latitudeScale = 6_335_439.0 * sin(halfSpanRadians) / halfSpanRadians * radiansPerDegree
    private val longitudeScale = latitudeScale * cos(45.0 * radiansPerDegree)

    fun clearance(point: BoundaryCoordinate, a: BoundaryCoordinate, b: BoundaryCoordinate): Double =
        clearance(point.longitude, point.latitude, a.longitude, a.latitude, b.longitude, b.latitude)

    /** Scalar form lets packed coastal geometry stay constant-memory while scanning millions of points. */
    fun clearance(
        longitude: Double, latitude: Double,
        startLongitude: Double, startLatitude: Double, endLongitude: Double, endLatitude: Double,
    ): Double {
        val dx = (endLongitude - startLongitude) * longitudeScale
        val dy = (endLatitude - startLatitude) * latitudeScale
        val px = (longitude - startLongitude) * longitudeScale
        val py = (latitude - startLatitude) * latitudeScale
        val squaredLength = dx * dx + dy * dy
        val projection = if (squaredLength == 0.0) 0.0 else ((px * dx + py * dy) / squaredLength).coerceIn(0.0, 1.0)
        return (hypot(px - projection * dx, py - projection * dy) - 0.001).coerceAtLeast(0.0)
    }
}
