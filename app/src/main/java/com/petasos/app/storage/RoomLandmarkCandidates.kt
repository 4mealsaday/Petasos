package com.petasos.app.storage

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.generation.StrictJson
import com.petasos.app.planning.LandmarkStoryCandidate
import java.util.concurrent.Callable

/** Reviewed landmark inventory only. This store neither observes location nor approves an approach.
 * All calls run on the worker; the caller still needs current location/route/output authority.
 */
class RoomLandmarkCandidates internal constructor(
    private val database: StorageDatabase,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val ownsDatabase: Boolean = false,
) : AutoCloseable {
    constructor(context: Context, databaseName: String = "petasos.db") : this(
        StorageDatabase.open(context, databaseName), System::currentTimeMillis, true)

    private val sql get() = database.openHelper.writableDatabase
    private val eligibility = RoomAutomaticStoryEligibility(database, wallTime)
    private val policies = RoomLandmarkPolicyStore(database, wallTime)

    /** No alphabetical cap before approach/distance selection; only explicitly reviewed major places enter. */
    fun candidates(excludedStoryIds: Set<String> = emptySet()): List<LandmarkStoryCandidate> = read(emptyList()) {
        val excluded = excludedStoryIds.toSet()
        val result = mutableListOf<LandmarkStoryCandidate>()
        policies.reviewedPolicies().forEach { reviewed ->
            if (reviewed.storyId !in excluded) {
                query("${RoomAutomaticStoryEligibility.SELECT} AND s.story_id=?", reviewed.storyId).use { rows ->
                    if (rows.moveToFirst()) readCandidate(rows, reviewed)?.let(result::add)
                }
            }
        }
        result.toList()
    }

    /** Current history, paragraph hash, place/review/source metadata and rights are all read again. */
    fun isCurrent(candidate: LandmarkStoryCandidate, excludedStoryIds: Set<String> = emptySet()): Boolean = read(false) {
        if (candidate.story.storyId in excludedStoryIds) return@read false
        val reviewed = policies.reviewedPolicy(candidate.placeId) ?: return@read false
        if (reviewed.storyId != candidate.story.storyId) return@read false
        query("${RoomAutomaticStoryEligibility.SELECT} AND v.story_version_id=?", candidate.story.versionId).use { rows ->
            if (!rows.moveToFirst()) return@use false
            val current = readCandidate(rows, reviewed) ?: return@use false
            current.story == candidate.story && current.placeId == candidate.placeId &&
                current.latitude == candidate.latitude && current.longitude == candidate.longitude &&
                current.coordinateSourceRevisionId == candidate.coordinateSourceRevisionId &&
                current.policy == candidate.policy && current.eligibilityKey == candidate.eligibilityKey
        }
    }

    private fun readCandidate(cursor: Cursor, reviewed: ReviewedLandmarkRecord): LandmarkStoryCandidate? {
        val story = eligibility.read(cursor) ?: return null
        if (story.storyId != reviewed.storyId || (story.placeId != null && story.placeId != reviewed.place.placeId)) return null
        val prepared = eligibility.prepare(story) ?: return null
        val evidence = evidence(story.versionId) ?: return null
        val key = try { ContentBundleCodec.sha256(StrictJson.encode(mapOf(
            "story" to story.metadata, "place" to reviewed.placeRow, "coordinateSource" to reviewed.coordinateSource,
            "associationFact" to reviewed.association.metadata, "associationSources" to reviewed.association.sources,
            "evidence" to evidence,
        ))) } catch (_: Exception) { return null }
        return LandmarkStoryCandidate(prepared, reviewed.place.placeId, reviewed.place.latitude, reviewed.place.longitude,
            reviewed.place.coordinateSourceRevisionId, reviewed.policy, key)
    }

    /** A previously issued landmark is invalidated even when its independent evidence changes. */
    private fun evidence(versionId: String): Map<String, Any?>? {
        val facts = linkedMapOf<String, Map<String, Any?>>()
        val sources = linkedMapOf<String, Map<String, Any?>>()
        query("SELECT DISTINCT pf.fact_revision_id FROM paragraph p JOIN paragraph_fact pf ON pf.paragraph_id=p.paragraph_id WHERE p.story_version_id=? ORDER BY pf.fact_revision_id", versionId).use { cursor ->
            while (cursor.moveToNext()) {
                if (facts.size >= 256) return null
                val id = cursor.getString(0)
                val fact = policies.fact(id, wallTime(), allowTradition = true) ?: return null
                facts[id] = fact.metadata
                sources.putAll(fact.sources)
                if (sources.size > 64) return null
            }
        }
        return if (facts.isEmpty() || sources.isEmpty()) null else mapOf("facts" to facts, "sources" to sources)
    }

    private fun query(statement: String, vararg args: Any?): Cursor = sql.query(SimpleSQLiteQuery(statement, args))
    private fun <T> read(fallback: T, action: () -> T): T = try { database.runInTransaction(Callable { action() }) }
        catch (_: Exception) { fallback }
    override fun close() { if (ownsDatabase) database.close() }

}
