package com.petasos.app.location

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Bounds are longitude first, matching the compiler's EPSG:4326 packed coordinates. */
data class PackedRegionBounds(
    val minLongitude: Double,
    val minLatitude: Double,
    val maxLongitude: Double,
    val maxLatitude: Double,
) {
    init {
        require(supportedPackedCoordinate(minLongitude, minLatitude) &&
            supportedPackedCoordinate(maxLongitude, maxLatitude) &&
            minLongitude < maxLongitude && minLatitude < maxLatitude) { "Invalid packed boundary bounds" }
    }

    internal fun contains(longitude: Double, latitude: Double): Boolean =
        longitude >= minLongitude - PACKED_TOLERANCE && longitude <= maxLongitude + PACKED_TOLERANCE &&
            latitude >= minLatitude - PACKED_TOLERANCE && latitude <= maxLatitude + PACKED_TOLERANCE

    override fun toString(): String = "PackedRegionBounds([redacted])"
}

/** Immutable descriptor obtained from a separately reviewed manifest; it does not grant source rights. */
class PackedRegionBoundaryMetadata(
    val regionRevisionId: String,
    val datasetVersion: String,
    val sha256: String,
    val byteLength: Long,
    val ringCount: Int,
    val pointCount: Int,
    val bounds: PackedRegionBounds,
) {
    init {
        requirePackedIdentity(regionRevisionId)
        requirePackedIdentity(datasetVersion)
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid packed boundary hash" }
        require(ringCount in 1..PackedRegionBoundaryLookup.MAX_RINGS &&
            pointCount in 4..PackedRegionBoundaryLookup.MAX_POINTS &&
            pointCount.toLong() >= ringCount.toLong() * 4 &&
            pointCount.toLong() <= ringCount.toLong() * PackedRegionBoundaryLookup.MAX_RING_POINTS) {
            "Invalid packed boundary counts"
        }
        require(byteLength == 16L + 36L * ringCount + 16L * pointCount &&
            byteLength <= PackedRegionBoundaryLookup.MAX_BYTES) { "Invalid packed boundary length" }
    }

    override fun toString(): String = "PackedRegionBoundaryMetadata([redacted])"
}

/**
 * Streaming reader for reviewed PTRG0001 geometry. Each query opens every candidate region again,
 * checks its entire SHA-256/length, and validates all encoded counts, points, bounds and closures.
 * No coordinate array or per-point object is allocated. This preserves detailed islands without
 * relaxing the in-memory geometry engine's limits or simplifying official boundaries.
 *
 * Polygon topology is verified by the offline compiler and bound to the manifest's digest. Runtime
 * checks do not claim to prove topology. Even-odd containment handles reviewed shells/holes/islands;
 * conservative WGS84 clearance is exactly the same metric as ReviewedPolygonBoundaryLookup.
 *
 * A missing/revoked/corrupt candidate, or any quarantined bounds covering the query, withholds ALL
 * results so another overlapping region cannot silently win. Noncandidate streams are never opened.
 * The caller must supply authority from its source-rights store; neither construction nor lookup
 * chooses location quality, administrative unit policy, or location collection consent.
 */
class PackedRegionBoundaryLookup(
    regions: List<PackedRegionBoundaryMetadata>,
    private val open: (PackedRegionBoundaryMetadata) -> InputStream,
    private val isAuthorized: (PackedRegionBoundaryMetadata) -> Boolean = { true },
    quarantinedBounds: List<PackedRegionBounds> = emptyList(),
) : RegionBoundaryLookup {
    private val regions = Collections.unmodifiableList(ArrayList(regions))
    private val quarantinedBounds = Collections.unmodifiableList(ArrayList(quarantinedBounds))

    init {
        require(regions.size.toLong() + quarantinedBounds.size <= MAX_REGIONS) { "Too many packed boundaries" }
        require(regions.map { it.regionRevisionId }.toSet().size == regions.size) { "Duplicate packed region revision" }
    }

    override fun containing(latitude: Double, longitude: Double): List<RegionContainment> {
        if (!supportedPackedCoordinate(longitude, latitude) ||
            quarantinedBounds.any { it.contains(longitude, latitude) }) return emptyList()
        val candidates = regions.filter { it.bounds.contains(longitude, latitude) }
        val result = ArrayList<RegionContainment>()
        return try {
            for (metadata in candidates) {
                if (!isAuthorized(metadata)) return emptyList()
                val clearance = readRegion(metadata, longitude, latitude)
                if (!isAuthorized(metadata)) return emptyList()
                if (clearance != null) result += RegionContainment(
                    metadata.regionRevisionId, metadata.datasetVersion, "EPSG:4326", clearance,
                )
            }
            // A later candidate can take time to scan; recheck earlier source authority before return.
            if (candidates.any { !isAuthorized(it) }) emptyList() else Collections.unmodifiableList(result)
        } catch (_: Exception) {
            // Streams and authority callbacks can fail. Do not log the coordinate, path or exception.
            emptyList()
        }
    }

    internal val polylineRegionRevisions: Set<String> get() = regions.map { it.regionRevisionId }.toSet()
    internal val polylineGeometryKey: String by lazy {
        fun boundsKey(bounds: PackedRegionBounds) = listOf(bounds.minLongitude, bounds.minLatitude,
            bounds.maxLongitude, bounds.maxLatitude).map { java.lang.Double.toHexString(it) }
        polylineDigest(listOf("packed-polyline-geometry-v1") + regions.sortedBy { it.regionRevisionId }.flatMap {
            listOf("ready", it.regionRevisionId, it.datasetVersion, it.sha256, it.byteLength.toString(),
                it.ringCount.toString(), it.pointCount.toString()) + boundsKey(it.bounds)
        } + quarantinedBounds.map(::boundsKey).sortedBy { it.joinToString(":") }.flatMap { listOf("quarantined") + it })
    }

    /**
     * Internal first-point containment set, only if no boundary touches ANY segment of the path.
     * The returned clearance is deliberately still that first point's clearance; callers must use
     * the separate PolylineRegion contract and must not interpret it as a path clearance.
     */
    internal fun matchesForUnbrokenPolyline(path: List<BoundaryCoordinate>): List<RegionContainment> = try {
        val index = PolylineBoundaryIndex(path)
        if (quarantinedBounds.any(index::overlaps)) emptyList() else {
            val candidates = regions.filter { index.overlaps(it.bounds) }
            val matches = ArrayList<RegionContainment>()
            for (metadata in candidates) {
                checkPacked(isAuthorized(metadata))
                val clearance = readRegion(metadata, index.first.longitude, index.first.latitude, index)
                checkPacked(isAuthorized(metadata))
                if (clearance != null) matches += RegionContainment(metadata.regionRevisionId, metadata.datasetVersion,
                    "EPSG:4326", clearance)
            }
            checkPacked(candidates.all(isAuthorized))
            Collections.unmodifiableList(matches)
        }
    } catch (_: Exception) { emptyList() }

    private fun readRegion(metadata: PackedRegionBoundaryMetadata, longitude: Double, latitude: Double,
                           path: PolylineBoundaryIndex? = null): Double? {
        val digest = MessageDigest.getInstance("SHA-256")
        val counted = ExactLengthInputStream(open(metadata), metadata.byteLength)
        DataInputStream(BufferedInputStream(DigestInputStream(counted, digest), BUFFER_BYTES)).use { input ->
            checkPacked(input.readLong() == MAGIC)
            checkPacked(input.readInt() == metadata.ringCount && input.readInt() == metadata.pointCount)
            var readPoints = 0L
            var inside = false
            var boundary = false
            var clearance = Double.POSITIVE_INFINITY
            var minLongitude = Double.POSITIVE_INFINITY
            var minLatitude = Double.POSITIVE_INFINITY
            var maxLongitude = Double.NEGATIVE_INFINITY
            var maxLatitude = Double.NEGATIVE_INFINITY
            repeat(metadata.ringCount) {
                val ringMinLongitude = input.readDouble()
                val ringMinLatitude = input.readDouble()
                val ringMaxLongitude = input.readDouble()
                val ringMaxLatitude = input.readDouble()
                checkPacked(supportedPackedCoordinate(ringMinLongitude, ringMinLatitude) &&
                    supportedPackedCoordinate(ringMaxLongitude, ringMaxLatitude) &&
                    ringMinLongitude < ringMaxLongitude && ringMinLatitude < ringMaxLatitude)
                val pointCount = input.readInt()
                checkPacked(pointCount in 4..MAX_RING_POINTS)
                readPoints += pointCount
                checkPacked(readPoints <= metadata.pointCount)
                var actualMinLongitude = Double.POSITIVE_INFINITY
                var actualMinLatitude = Double.POSITIVE_INFINITY
                var actualMaxLongitude = Double.NEGATIVE_INFINITY
                var actualMaxLatitude = Double.NEGATIVE_INFINITY
                var firstLongitude = 0.0
                var firstLatitude = 0.0
                var previousLongitude = 0.0
                var previousLatitude = 0.0
                var doubledArea = 0.0
                var compensation = 0.0
                repeat(pointCount) { index ->
                    val nextLongitude = input.readDouble()
                    val nextLatitude = input.readDouble()
                    checkPacked(supportedPackedCoordinate(nextLongitude, nextLatitude))
                    actualMinLongitude = min(actualMinLongitude, nextLongitude)
                    actualMinLatitude = min(actualMinLatitude, nextLatitude)
                    actualMaxLongitude = max(actualMaxLongitude, nextLongitude)
                    actualMaxLatitude = max(actualMaxLatitude, nextLatitude)
                    if (index == 0) {
                        firstLongitude = nextLongitude
                        firstLatitude = nextLatitude
                    } else {
                        // A competing region absent at the first vertex can still cross the path.
                        checkPacked(path?.touches(previousLongitude, previousLatitude, nextLongitude, nextLatitude) != true)
                        if (packedOnSegment(longitude, latitude, previousLongitude, previousLatitude, nextLongitude, nextLatitude)) {
                            boundary = true
                        }
                        if ((previousLatitude > latitude) != (nextLatitude > latitude)) {
                            val crossing = previousLongitude + (latitude - previousLatitude) *
                                (nextLongitude - previousLongitude) / (nextLatitude - previousLatitude)
                            if (longitude < crossing) inside = !inside
                        }
                        clearance = min(clearance, BoundaryMetric.clearance(
                            longitude, latitude, previousLongitude, previousLatitude, nextLongitude, nextLatitude,
                        ))
                        val value = (previousLongitude - firstLongitude) * (nextLatitude - firstLatitude) -
                            (nextLongitude - firstLongitude) * (previousLatitude - firstLatitude)
                        val corrected = value - compensation
                        val sum = doubledArea + corrected
                        compensation = (sum - doubledArea) - corrected
                        doubledArea = sum
                    }
                    previousLongitude = nextLongitude
                    previousLatitude = nextLatitude
                }
                checkPacked(previousLongitude == firstLongitude && previousLatitude == firstLatitude && doubledArea != 0.0)
                checkPacked(actualMinLongitude == ringMinLongitude && actualMinLatitude == ringMinLatitude &&
                    actualMaxLongitude == ringMaxLongitude && actualMaxLatitude == ringMaxLatitude)
                minLongitude = min(minLongitude, actualMinLongitude)
                minLatitude = min(minLatitude, actualMinLatitude)
                maxLongitude = max(maxLongitude, actualMaxLongitude)
                maxLatitude = max(maxLatitude, actualMaxLatitude)
            }
            checkPacked(readPoints == metadata.pointCount.toLong() &&
                minLongitude == metadata.bounds.minLongitude && minLatitude == metadata.bounds.minLatitude &&
                maxLongitude == metadata.bounds.maxLongitude && maxLatitude == metadata.bounds.maxLatitude)
            checkPacked(input.read() == -1 && counted.count == metadata.byteLength)
            checkPacked(MessageDigest.isEqual(digest.digest(), decodePackedHash(metadata.sha256)))
            return when {
                boundary -> 0.0
                inside -> clearance.takeIf { it.isFinite() } ?: throw IOException("Invalid packed boundary")
                else -> null
            }
        }
    }

    override fun toString(): String = "PackedRegionBoundaryLookup([redacted])"

    companion object {
        const val MAX_REGIONS = 512
        const val MAX_RINGS = 10_000
        const val MAX_POINTS = 3_000_000
        const val MAX_RING_POINTS = 500_000
        const val MAX_BYTES = 64L * 1024 * 1024
        internal const val BUFFER_BYTES = 8_192
        private const val MAGIC = 0x5054524730303031L // ASCII PTRG0001
    }
}

private const val PACKED_TOLERANCE = 1e-10

private fun supportedPackedCoordinate(longitude: Double, latitude: Double): Boolean =
    longitude.isFinite() && latitude.isFinite() && longitude in 120.0..135.0 && latitude in 30.0..45.0

private fun requirePackedIdentity(value: String) {
    require(value.length in 1..256 && value == value.trim() && value.none { it.isISOControl() }) { "Invalid packed boundary identity" }
}

private fun checkPacked(condition: Boolean) {
    if (!condition) throw IOException("Invalid packed boundary")
}

private fun packedOnSegment(
    longitude: Double, latitude: Double,
    ax: Double, ay: Double, bx: Double, by: Double,
): Boolean {
    if (longitude < min(ax, bx) - PACKED_TOLERANCE || longitude > max(ax, bx) + PACKED_TOLERANCE ||
        latitude < min(ay, by) - PACKED_TOLERANCE || latitude > max(ay, by) + PACKED_TOLERANCE) return false
    val dx = bx - ax
    val dy = by - ay
    return abs(dx * (latitude - ay) - dy * (longitude - ax)) <= PACKED_TOLERANCE * (abs(dx) + abs(dy))
}

private fun decodePackedHash(value: String): ByteArray = ByteArray(32) { index ->
    value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
}

/** Bounds all source reads, including the one-byte EOF check, without ever buffering a whole region. */
private class ExactLengthInputStream(input: InputStream, private val expectedLength: Long) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int {
        val value = `in`.read()
        if (value >= 0) record(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val request = min(length.toLong(), expectedLength - count + 1).toInt()
        val actual = `in`.read(buffer, offset, request)
        if (actual > 0) record(actual)
        return actual
    }

    private fun record(amount: Int) {
        count += amount
        checkPacked(count <= expectedLength)
    }
}
