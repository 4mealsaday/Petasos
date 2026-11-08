package com.petasos.app.storage

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.content.ContentException
import com.petasos.app.content.ContentRegionBinding
import com.petasos.app.generation.StrictJson

/** Textual story-region association, independent of coordinates and visited-region policy.
 * Callers own the surrounding read/write transaction. No region policy or consent is granted here.
 */
internal class RoomRegionalContentBindings(private val database: StorageDatabase, private val wallTime: () -> Long) {
    private val sql get() = database.openHelper.writableDatabase

    fun install(binding: ContentRegionBinding) {
        val expected = values(binding)
        val previous = row("SELECT * FROM story_region_binding WHERE story_id=?", binding.storyId)
        if (previous != null && previous != expected) throw ContentException("CONTENT_IMMUTABLE_CONFLICT")
        if (previous == null) sql.execSQL(
            "INSERT INTO story_region_binding (${expected.keys.joinToString()}) VALUES (${expected.keys.joinToString { "?" }})",
            expected.values.toTypedArray())
        if (currentKey(binding.storyId, binding.regionId) == null) throw ContentException("CONTENT_REGION_UNAVAILABLE")
    }

    /** Checked even when a later generated version no longer narrates the association fact. */
    fun currentKey(storyId: String, regionId: String, forGeneration: Boolean = false): String? = try {
        val binding = row("SELECT * FROM story_region_binding WHERE story_id=?", storyId)
        if (binding == null || binding["region_id"] != regionId ||
            (binding["verified_at"] as? Long)?.let { it >= 0 && it <= wallTime() } != true ||
            (binding["review_basis"] as? String).isNullOrBlank()) null
        else checkedKey(binding, storyId, regionId, forGeneration)
    } catch (_: Exception) { null }

    private fun checkedKey(binding: Map<String, Any?>, storyId: String, regionId: String, forGeneration: Boolean): String? {
        val story = row("SELECT region_id FROM story WHERE story_id=?", storyId) ?: return null
        if (story["region_id"] != regionId) return null
        val region = row("SELECT * FROM region_revision WHERE region_revision_id=?", binding["region_revision_id"]) ?: return null
        for ((stored, reviewed) in listOf("region_id" to "region_id", "code" to "region_code",
            "dataset_version" to "dataset_version", "name" to "region_name", "source_revision_id" to "region_source_revision_id"))
            if (region[stored] != binding[reviewed]) return null
        // A geometry revision is an identity reference. Its visit policy remains separately gated.
        val geometryPolicy = json(region["policy_json"]) ?: return null
        if (geometryPolicy["geometryStatus"]?.let { it != "ready" } == true) return null
        val geometrySource = source(binding["region_source_revision_id"], false) ?: return null
        val fact = row("SELECT * FROM fact_revision WHERE fact_revision_id=?", binding["association_fact_revision_id"]) ?: return null
        val factTime = fact["verified_at"] as? Long ?: return null
        if (fact["kind"] != "verified_fact" || factTime < 0 || factTime > (binding["verified_at"] as Long) ||
            (fact["statement"] as? String).isNullOrBlank()) return null
        val sources = linkedMapOf<String, Map<String, Any?>>()
        query("SELECT source_revision_id FROM fact_source WHERE fact_revision_id=? ORDER BY source_revision_id",
            binding["association_fact_revision_id"]).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                sources[id] = source(id, forGeneration) ?: return null
            }
        }
        if (sources.isEmpty()) return null
        return ContentBundleCodec.sha256(StrictJson.encode(mapOf("binding" to binding,
            "region" to region, "geometrySource" to geometrySource, "associationFact" to fact, "sources" to sources)))
    }

    private fun source(id: Any?, generation: Boolean): Map<String, Any?>? {
        val source = row("SELECT * FROM source_evidence WHERE source_revision_id=?", id) ?: return null
        val uses = json(source["allowed_uses_json"]) ?: return null
        val verified = source["verified_at"] as? Long ?: return null
        if (source["review_status"] != "verified" || verified < 0 || verified > wallTime() ||
            uses["allowDisplay"] != true || uses["allowDistribution"] != true ||
            generation && uses["allowGeneration"] != true) return null
        return source
    }

    private fun values(b: ContentRegionBinding): Map<String, Any?> = linkedMapOf(
        "story_id" to b.storyId, "region_id" to b.regionId, "region_revision_id" to b.regionRevisionId,
        "region_code" to b.regionCode, "dataset_version" to b.datasetVersion, "region_name" to b.regionName,
        "region_source_revision_id" to b.regionSourceRevisionId, "association_fact_revision_id" to b.associationFactRevisionId,
        "verified_at" to b.verifiedAt, "review_basis" to b.reviewBasis)
    private fun json(value: Any?): Map<*, *>? = (value as? String)?.let { StrictJson.parse(it.toByteArray()) as? Map<*, *> }
    private fun query(statement: String, vararg args: Any?): Cursor = sql.query(SimpleSQLiteQuery(statement, args))
    private fun row(statement: String, vararg args: Any?): Map<String, Any?>? = query(statement, *args).use { row ->
        if (!row.moveToFirst()) null else row.columnNames.associateWith { name ->
            val index = row.getColumnIndexOrThrow(name)
            when (row.getType(index)) { Cursor.FIELD_TYPE_NULL -> null; Cursor.FIELD_TYPE_INTEGER -> row.getLong(index); else -> row.getString(index) }
        }
    }
}
