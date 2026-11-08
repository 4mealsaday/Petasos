package com.petasos.app.storage

import android.database.Cursor
import com.petasos.app.content.ContentException
import com.petasos.app.generation.StrictJson
import com.petasos.app.playback.PreparedStory

/** Common ordinary/landmark eligibility; callers own the surrounding read transaction.
 * Selection reason, place policy and location authority are deliberately outside this reader.
 */
internal class RoomAutomaticStoryEligibility(database: StorageDatabase, wallTime: () -> Long) {
    private val installer = RoomContentInstaller(database, wallTime, ownsDatabase = false)

    fun read(row: Cursor): AutomaticStoryRecord? {
        val tags = tags(row.getString(row.getColumnIndexOrThrow("interest_tags_json"))) ?: return null
        val versionId = row.getString(row.getColumnIndexOrThrow("story_version_id"))
        val regionIndex = row.getColumnIndexOrThrow("region_id")
        val placeIndex = row.getColumnIndexOrThrow("place_id")
        val metadata = linkedMapOf<String, Any?>()
        for (index in 0 until row.columnCount) metadata[row.getColumnName(index)] = when (row.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> row.getLong(index)
            else -> row.getString(index)
        }
        return AutomaticStoryRecord(row.getString(row.getColumnIndexOrThrow("story_id")), versionId, tags,
            if (row.isNull(regionIndex)) null else row.getString(regionIndex),
            if (row.isNull(placeIndex)) null else row.getString(placeIndex), metadata.toMap())
    }

    /** Call after reason-specific metadata filtering, preserving the ordinary store's read order. */
    fun prepare(record: AutomaticStoryRecord): PreparedStory? = try { installer.publishedStory(record.versionId) }
    catch (failure: ContentException) {
        if (failure.code == "CONTENT_UNAVAILABLE") null else throw failure
    }

    private fun tags(json: String): List<String>? {
        return try {
            val values = StrictJson.parse(json.toByteArray(Charsets.UTF_8)) as? List<*> ?: return null
            if (values.size > 20) return null
            val tags = values.map { it as? String ?: return null }
            tags.takeIf { it.distinct().size == it.size && it.all { tag -> tag.isNotBlank() && tag.length <= 100 } }
        } catch (_: Exception) { null }
    }

    companion object {
        /** Apply session exclusions and reason/policy checks before any bounded result limit. */
        val SELECT = """SELECT s.story_id,s.topic_id,s.title,s.core_question,s.interest_tags_json,s.place_id,s.region_id,
            v.story_version_id,v.version,v.content_hash,v.created_at,v.verified_at,v.valid_until,v.estimated_duration_ms,v.status
            FROM story_version v JOIN story s ON s.story_id=v.story_id
            WHERE v.status='published'
              AND v.version=(SELECT MAX(newer.version) FROM story_version newer WHERE newer.story_id=v.story_id AND newer.status='published')
              AND NOT EXISTS (SELECT 1 FROM playback_attempt a JOIN paragraph hp ON hp.paragraph_id=a.paragraph_id
                JOIN story_version hv ON hv.story_version_id=hp.story_version_id
                WHERE a.kind='story' AND a.state='completed' AND hv.story_id=s.story_id)
              AND NOT EXISTS (SELECT 1 FROM paragraph cp JOIN paragraph_fact cpf ON cpf.paragraph_id=cp.paragraph_id
                JOIN fact_revision cf ON cf.fact_revision_id=cpf.fact_revision_id
                JOIN fact_revision hf ON hf.fact_id=cf.fact_id JOIN paragraph_fact hpf ON hpf.fact_revision_id=hf.fact_revision_id
                JOIN playback_attempt ha ON ha.paragraph_id=hpf.paragraph_id
                WHERE cp.story_version_id=v.story_version_id AND ha.kind='story' AND ha.state='completed')"""
    }
}

internal class AutomaticStoryRecord(
    val storyId: String,
    val versionId: String,
    val interestTags: List<String>,
    val regionId: String?,
    val placeId: String?,
    val metadata: Map<String, Any?>,
) { override fun toString() = "AutomaticStoryRecord([redacted])" }
