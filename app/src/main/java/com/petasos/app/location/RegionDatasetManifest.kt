package com.petasos.app.location

import com.petasos.app.generation.StrictJson
import java.net.URI
import java.security.MessageDigest
import java.util.Collections

/** Reviewed, app-packaged geometry metadata. Ready geometry is not a reviewed visit policy. */
internal class RegionDatasetManifest(
    val fingerprint: String,
    val datasetVersion: String,
    val source: RegionDatasetSource,
    val transform: RegionDatasetTransform,
    regions: List<RegionDatasetEntry>,
) {
    val regions: List<RegionDatasetEntry> = Collections.unmodifiableList(ArrayList(regions))
    override fun toString() = "RegionDatasetManifest([redacted])"
}

internal data class RegionDatasetSource(
    val sourceRevisionId: String, val sourceId: String, val provider: String, val sourceUrl: String,
    val title: String, val fetchedAt: Long, val sourceUpdatedRaw: String, val verifiedAt: Long,
    val contentHash: String, val licenseUrl: String, val attribution: String, val reviewBasis: String,
) { override fun toString() = "RegionDatasetSource([redacted])" }

internal data class RegionDatasetTransform(
    val libraryVersion: String, val operation: String, val expectedAccuracyMeters: Double,
    val expectedAccuracyDecimal: String,
) { override fun toString() = "RegionDatasetTransform([redacted])" }

internal data class RegionDatasetEntry(
    val regionId: String, val regionRevisionId: String, val code: String, val name: String,
    val geometryAsset: String, val geometrySha256: String, val geometryBytes: Long,
    val ringCount: Int, val pointCount: Int, val bounds: PackedRegionBounds,
    val status: String, val reasonCode: String?,
) { override fun toString() = "RegionDatasetEntry([redacted])" }

internal class RegionDatasetException(val code: String) : IllegalArgumentException(code)

/** Small strict manifest plus separately streamed binaries; no general JSON size/number relaxation. */
internal object RegionDatasetManifestCodec {
    const val MAX_MANIFEST_BYTES = StrictJson.MAX_BYTES
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
    private val digest = Regex("[0-9a-f]{64}")
    private val decimal = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")

    fun decode(bytes: ByteArray, expectedSha256: String): RegionDatasetManifest = try {
        need(bytes.size in 1..MAX_MANIFEST_BYTES && digest.matches(expectedSha256))
        val fingerprint = sha256(bytes)
        need(fingerprint == expectedSha256)
        val root = obj(StrictJson.parse(bytes), "formatVersion", "datasetVersion", "source", "regions",
            "coordinateReferenceSystem", "sourceCoordinateReferenceSystem", "transform", "policyStatus")
        need(number(root, "formatVersion") == 1L && root["coordinateReferenceSystem"] == "EPSG:4326" &&
            root["sourceCoordinateReferenceSystem"] == "EPSG:5179" && root["policyStatus"] == "pending")
        val version = id(root, "datasetVersion")
        val source = obj(root["source"], "sourceRevisionId", "sourceId", "provider", "sourceUrl", "title", "fetchedAt",
            "sourceUpdatedRaw", "verifiedAt", "contentHash", "licenseUrl", "attribution", "reviewBasis")
        val sourceValue = RegionDatasetSource(id(source, "sourceRevisionId"), id(source, "sourceId"),
            string(source, "provider", 128), https(source, "sourceUrl"), string(source, "title", 500),
            number(source, "fetchedAt").also { need(it >= 0) }, string(source, "sourceUpdatedRaw", 1000),
            number(source, "verifiedAt").also { need(it >= 0) }, hash(source, "contentHash"),
            https(source, "licenseUrl").also { need(it == "https://creativecommons.org/licenses/by/2.0/kr/") },
            string(source, "attribution", 4000), string(source, "reviewBasis", 8000))
        val transform = obj(root["transform"], "libraryVersion", "operation", "expectedAccuracyMeters")
        val accuracy = decimalString(transform["expectedAccuracyMeters"], 0.0, 100.0)
        val transformValue = RegionDatasetTransform(string(transform, "libraryVersion", 128),
            string(transform, "operation", 2000), accuracy.toDouble(), accuracy)
        val rows = root["regions"] as? List<*> ?: invalid()
        need(rows.size in 1..512)
        val entries = rows.map { raw ->
            val row = obj(raw, "regionId", "regionRevisionId", "code", "name", "geometryAsset", "geometrySha256",
                "geometryBytes", "ringCount", "pointCount", "bounds", "status", "reasonCode")
            val bounds = row["bounds"] as? List<*> ?: invalid()
            need(bounds.size == 4)
            val minX = decimalString(bounds[0], 120.0, 135.0).toDouble()
            val minY = decimalString(bounds[1], 30.0, 45.0).toDouble()
            val maxX = decimalString(bounds[2], 120.0, 135.0).toDouble()
            val maxY = decimalString(bounds[3], 30.0, 45.0).toDouble()
            need(minX < maxX && minY < maxY)
            val status = string(row, "status", 16)
            val reason = row["reasonCode"] as? String
            need((status == "ready" && row["reasonCode"] == null) ||
                (status == "quarantined" && reason in setOf("INVALID_TOPOLOGY", "UNSUPPORTED_GEOMETRY", "MISSING_GEOMETRY")))
            val rings = number(row, "ringCount").also { need(it in 1..PackedRegionBoundaryLookup.MAX_RINGS) }.toInt()
            val points = number(row, "pointCount").also { need(it in 4..PackedRegionBoundaryLookup.MAX_POINTS) }.toInt()
            val size = number(row, "geometryBytes").also { need(it in 1..PackedRegionBoundaryLookup.MAX_BYTES) }
            need(size == 16L + 36L * rings + 16L * points && points.toLong() >= 4L * rings)
            need(points.toLong() <= rings.toLong() * PackedRegionBoundaryLookup.MAX_RING_POINTS)
            RegionDatasetEntry(id(row, "regionId"), id(row, "regionRevisionId"),
                string(row, "code", 5).also { need(Regex("[0-9]{5}").matches(it)) }, string(row, "name", 500),
                string(row, "geometryAsset", 240).also {
                    need(Regex("regions/[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*").matches(it) &&
                        it.split('/').none { segment -> segment == "." || segment == ".." })
                }, hash(row, "geometrySha256"), size, rings, points,
                PackedRegionBounds(minX, minY, maxX, maxY), status, reason)
        }
        need(entries.sumOf { it.geometryBytes } <= 536_870_912L)
        need(entries.map { it.regionId }.distinct().size == entries.size &&
            entries.map { it.regionRevisionId }.distinct().size == entries.size &&
            entries.map { it.code }.distinct().size == entries.size &&
            entries.map { it.geometryAsset }.distinct().size == entries.size)
        RegionDatasetManifest(fingerprint, version, sourceValue, transformValue, entries)
    } catch (failure: RegionDatasetException) { throw failure }
      catch (_: Exception) { invalid() }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun obj(value: Any?, vararg keys: String): Map<String, Any?> {
        need(value is Map<*, *> && value.keys == keys.toSet())
        @Suppress("UNCHECKED_CAST") return value as Map<String, Any?>
    }
    private fun string(row: Map<String, Any?>, key: String, max: Int): String = (row[key] as? String ?: invalid()).also {
        need(it.isNotBlank() && it.length <= max && it.none { c -> c.isISOControl() && c !in "\n\r\t" })
    }
    private fun number(row: Map<String, Any?>, key: String): Long = row[key] as? Long ?: invalid()
    private fun id(row: Map<String, Any?>, key: String) = string(row, key, 128).also { need(identifier.matches(it)) }
    private fun hash(row: Map<String, Any?>, key: String) = string(row, key, 64).also { need(digest.matches(it)) }
    private fun decimalString(value: Any?, minimum: Double, maximum: Double): String = (value as? String ?: invalid()).also {
        need(it.length <= 64 && decimal.matches(it))
        val number = it.toDoubleOrNull() ?: invalid()
        need(number.isFinite() && number in minimum..maximum)
    }
    private fun https(row: Map<String, Any?>, key: String): String = string(row, key, 2048).also {
        val uri = URI(it)
        need(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null &&
            !Regex("(?i)(servicekey|api[_-]?key|access[_-]?token|authorization)=").containsMatchIn(it))
    }
    private fun need(value: Boolean) { if (!value) invalid() }
    private fun invalid(): Nothing = throw RegionDatasetException("INVALID_REGION_DATASET")
}
