package com.petasos.app.location

import com.petasos.app.generation.StrictJson
import java.net.URI

/** Relation evidence is independent of the separately reviewed live-location confirmation policy. */
internal object ReviewedRegionHierarchyCodec {
    fun decode(bytes: ByteArray, expectedSha256: String, manifest: RegionDatasetManifest): ReviewedRegionHierarchy = try {
        require(bytes.size in 1..65_536 && Regex("[0-9a-f]{64}").matches(expectedSha256))
        require(RegionDatasetManifestCodec.sha256(bytes) == expectedSha256)
        val root = obj(StrictJson.parse(bytes), "formatVersion", "datasetVersion", "manifestSha256", "reviewedOn", "evidence", "relations")
        require(root["formatVersion"] == 1L && root["datasetVersion"] == manifest.datasetVersion && root["manifestSha256"] == manifest.fingerprint)
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(str(root, "reviewedOn")))
        val evidence = root["evidence"] as? List<*> ?: error("invalid")
        require(evidence.size in 1..16)
        val evidenceIds = evidence.map { raw ->
            val row = obj(raw, "id", "url", "basis", "access")
            val uri = URI(str(row, "url"))
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
            require(str(row, "access") in setOf("direct", "official_search_excerpt"))
            str(row, "basis")
            str(row, "id")
        }
        require(evidenceIds.distinct().size == evidenceIds.size)
        val entries = manifest.regions.associateBy { it.regionRevisionId }
        fun node(raw: Any?): RegionHierarchyNode {
            val row = obj(raw, "regionId", "regionRevisionId", "code", "name")
            val entry = entries[str(row, "regionRevisionId")] ?: error("invalid")
            require(entry.status == "ready" && entry.regionId == str(row, "regionId") && entry.code == str(row, "code") && entry.name == str(row, "name"))
            return RegionHierarchyNode(entry.regionId, entry.regionRevisionId)
        }
        val relations = root["relations"] as? List<*> ?: error("invalid")
        require(relations.size in 1..32)
        val parsed = relations.map { raw ->
            val row = obj(raw, "child", "parent", "relation", "evidenceIds")
            require(row["relation"] == "non_autonomous_district_of_city")
            val refs = row["evidenceIds"] as? List<*> ?: error("invalid")
            require(refs.isNotEmpty() && refs.distinct().size == refs.size && refs.all { it is String && it in evidenceIds })
            RegionHierarchyRelation(node(row["child"]), node(row["parent"]))
        }
        ReviewedRegionHierarchy(expectedSha256, manifest.datasetVersion, parsed)
    } catch (_: Exception) { throw RegionDatasetException("INVALID_REGION_HIERARCHY") }

    private fun obj(raw: Any?, vararg keys: String): Map<*, *> = (raw as? Map<*, *> ?: error("invalid")).also {
        require(it.keys == keys.toSet())
    }
    private fun str(row: Map<*, *>, key: String): String = (row[key] as? String ?: error("invalid")).also {
        require(it.isNotBlank() && it.length <= 4000 && it.none(Char::isISOControl))
    }
}
