package com.petasos.app.location

import java.security.MessageDigest
import java.util.Collections

/** Entire connected polyline, not sampled vertices or an assertion of a received location. */
interface RegionPolylineLookup {
    /** Binds the reviewed geometry, hierarchy and stable-ID mapping. It grants no live authority. */
    val evidenceKey: String
    fun containing(path: List<BoundaryCoordinate>): PolylineRegion?
}

data class PolylineRegion(
    val regionId: String,
    val regionRevisionId: String,
    val datasetVersion: String,
    val hierarchyKey: String,
) {
    init {
        require(listOf(regionId, regionRevisionId, datasetVersion).all(::polylineIdentity)) {
            "Invalid polyline region identity"
        }
        require(polylineHierarchyKey(hierarchyKey)) { "Invalid polyline hierarchy key" }
    }
    override fun toString() = "PolylineRegion([redacted])"
}

/**
 * Reuses the packed reader's complete hash/topology-envelope validation. Every boundary from every
 * intersecting region is checked against the whole path, including regions absent at the endpoints.
 * An unbroken path has a constant containment set, so its first point may select the exact reviewed
 * hierarchy. The point's clearance is never presented as the clearance of the path.
 *
 * The map is supplied from the same verified manifest as [geometryKey]. Source rights are checked by
 * [raw] on every query. A successful result is still only static route geometry, never a location
 * permit, RegionPolicy approval or collection evidence.
 */
class PackedRegionPolylineLookup(
    private val raw: PackedRegionBoundaryLookup,
    regionIdsByRevision: Map<String, String>,
    private val hierarchy: ReviewedRegionHierarchy,
    geometryKey: String,
) : RegionPolylineLookup {
    private val regionIds = Collections.unmodifiableMap(HashMap(regionIdsByRevision))
    override val evidenceKey: String

    init {
        require(polylineSha256(geometryKey) && polylineHierarchyKey(hierarchy.fingerprint)) {
            "Invalid polyline geometry evidence"
        }
        require(regionIds.keys == raw.polylineRegionRevisions && regionIds.values.distinct().size == regionIds.size &&
            regionIds.all { (revision, region) -> polylineIdentity(revision) && polylineIdentity(region) &&
                (hierarchy.reviewedRevision(region)?.let { it == revision } ?: true) &&
                hierarchy.revisionChain(revision).all { it in regionIds } &&
                hierarchy.scope(region).eligibleStoryRegionIds == hierarchy.revisionChain(revision).map { regionIds[it] }
            }) { "Invalid polyline region mapping" }
        evidenceKey = polylineDigest(listOf(geometryKey, raw.polylineGeometryKey, hierarchy.fingerprint) +
            regionIds.entries.sortedBy { it.key }.flatMap { listOf(it.key, it.value) })
    }

    override fun containing(path: List<BoundaryCoordinate>): PolylineRegion? = try {
        val selected = hierarchy.select(raw.matchesForUnbrokenPolyline(path))
        selected?.takeIf { it.interiorDistanceMeters > 0 }?.let {
            PolylineRegion(regionIds.getValue(it.regionRevisionId), it.regionRevisionId, it.datasetVersion,
                hierarchy.fingerprint)
        }
    } catch (_: Exception) { null }

    override fun toString() = "PackedRegionPolylineLookup([redacted])"
}

internal fun polylineIdentity(value: String): Boolean = value.length in 1..256 && value == value.trim() &&
    value.none { it.isISOControl() }

internal fun polylineSha256(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

internal fun polylineHierarchyKey(value: String): Boolean = value == ReviewedRegionHierarchy.NONE.fingerprint || polylineSha256(value)

/** Length framing prevents different tuples of otherwise valid identifiers sharing an encoding. */
internal fun polylineDigest(parts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(), bytes.size.toByte()))
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
