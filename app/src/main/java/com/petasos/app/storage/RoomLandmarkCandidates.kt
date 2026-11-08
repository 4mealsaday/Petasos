package com.petasos.app.storage

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.generation.StrictJson
import com.petasos.app.location.LandmarkDirectionRule
import com.petasos.app.location.LandmarkPassedRule
import com.petasos.app.location.LandmarkPolicy
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

    /** No alphabetical cap before approach/distance selection; only explicitly reviewed major places enter. */
    fun candidates(excludedStoryIds: Set<String> = emptySet()): List<LandmarkStoryCandidate> = read(emptyList()) {
        val excluded = excludedStoryIds.toSet()
        val result = mutableListOf<LandmarkStoryCandidate>()
        query("${RoomAutomaticStoryEligibility.SELECT} AND EXISTS (SELECT 1 FROM place p WHERE p.place_id=s.place_id AND p.landmark_policy_json IS NOT NULL) ORDER BY s.story_id").use { rows ->
            while (rows.moveToNext()) {
                if (rows.getString(rows.getColumnIndexOrThrow("story_id")) in excluded) continue
                readCandidate(rows)?.let(result::add)
            }
        }
        result.toList()
    }

    /** Current history, paragraph hash, place/review/source metadata and rights are all read again. */
    fun isCurrent(candidate: LandmarkStoryCandidate, excludedStoryIds: Set<String> = emptySet()): Boolean = read(false) {
        if (candidate.story.storyId in excludedStoryIds) return@read false
        query("${RoomAutomaticStoryEligibility.SELECT} AND v.story_version_id=?", candidate.story.versionId).use { rows ->
            if (!rows.moveToFirst()) return@use false
            val current = readCandidate(rows) ?: return@use false
            current.story == candidate.story && current.placeId == candidate.placeId &&
                current.latitude == candidate.latitude && current.longitude == candidate.longitude &&
                current.coordinateSourceRevisionId == candidate.coordinateSourceRevisionId &&
                current.policy == candidate.policy && current.eligibilityKey == candidate.eligibilityKey
        }
    }

    private fun readCandidate(cursor: Cursor): LandmarkStoryCandidate? {
        val story = eligibility.read(cursor) ?: return null
        val placeId = story.placeId ?: return null
        val place = row("SELECT * FROM place WHERE place_id=?", placeId) ?: return null
        val latitude = coordinate(place["latitude"], -90.0, 90.0) ?: return null
        val longitude = coordinate(place["longitude"], -180.0, 180.0) ?: return null
        val coordinateSourceId = place["coordinate_source_revision_id"] as? String ?: return null
        val coordinateSource = source(coordinateSourceId) ?: return null
        val policyJson = place["landmark_policy_json"] as? String ?: return null
        val policy = policy(policyJson, coordinateSource["verified_at"] as? Long ?: return null) ?: return null
        if (policy.storyId != story.storyId) return null
        // This link remains authoritative even when a later generated version omits its fact.
        val association = fact(policy.associationFactRevisionId, policy.verifiedAt, allowTradition = false) ?: return null
        val prepared = eligibility.prepare(story) ?: return null
        val evidence = evidence(story.versionId) ?: return null
        // Coordinates are SQLite decimal strings in this fingerprint, not lossy JSON number coercions.
        val key = try { ContentBundleCodec.sha256(StrictJson.encode(mapOf(
            "story" to story.metadata, "place" to place, "coordinateSource" to coordinateSource,
            "associationFact" to association.metadata, "associationSources" to association.sources, "evidence" to evidence,
        ))) } catch (_: Exception) { return null }
        return LandmarkStoryCandidate(prepared, placeId, latitude, longitude, coordinateSourceId, policy.value, key)
    }

    private fun policy(raw: String, sourceVerifiedAt: Long): ReviewedPolicy? {
        return try {
            val policy = StrictJson.parse(raw.toByteArray(Charsets.UTF_8)) as? Map<*, *> ?: return null
            if (policy.keys != POLICY_KEYS || policy["reviewStatus"] != "verified") return null
            val verifiedAt = policy["verifiedAt"] as? Long ?: return null
            if (verifiedAt < sourceVerifiedAt || verifiedAt > wallTime()) return null
            val basis = policy["reviewBasis"] as? String ?: return null
            if (basis.isBlank() || basis.length > 4000) return null
            val radius = policy["approachRadiusMeters"] as? Long ?: return null
            val cooldown = policy["cooldownMs"] as? Long ?: return null
            val storyId = policy["storyId"] as? String ?: return null
            val associationFactId = policy["associationFactRevisionId"] as? String ?: return null
            if (!ID.matches(storyId) || !ID.matches(associationFactId)) return null
            if (policy["passedRule"] != "increasing_distance_after_approach" ||
                policy["directionRule"] != "decreasing_distance") return null
            val value = LandmarkPolicy(radius.toDouble(), LandmarkPassedRule.INCREASING_DISTANCE_AFTER_APPROACH,
                LandmarkDirectionRule.DECREASING_DISTANCE, cooldown,
                policy["policyVersion"] as? String ?: return null,
                policy["qualityAdapterVersion"] as? String ?: return null)
            ReviewedPolicy(value, storyId, associationFactId, verifiedAt)
        } catch (_: Exception) { null }
    }

    /** A previously issued landmark is invalidated even when its independent evidence changes. */
    private fun evidence(versionId: String): Map<String, Any?>? {
        val facts = linkedMapOf<String, Map<String, Any?>>()
        val sources = linkedMapOf<String, Map<String, Any?>>()
        query("SELECT DISTINCT pf.fact_revision_id FROM paragraph p JOIN paragraph_fact pf ON pf.paragraph_id=p.paragraph_id WHERE p.story_version_id=? ORDER BY pf.fact_revision_id", versionId).use { cursor ->
            while (cursor.moveToNext()) {
                if (facts.size >= 256) return null
                val id = cursor.getString(0)
                val fact = fact(id, wallTime(), allowTradition = true) ?: return null
                facts[id] = fact.metadata
                sources.putAll(fact.sources)
                if (sources.size > 64) return null
            }
        }
        return if (facts.isEmpty() || sources.isEmpty()) null else mapOf("facts" to facts, "sources" to sources)
    }

    private fun fact(id: String, verifiedBy: Long, allowTradition: Boolean): ReviewedFact? {
        val fact = row("SELECT * FROM fact_revision WHERE fact_revision_id=?", id) ?: return null
        val verifiedAt = fact["verified_at"] as? Long ?: return null
        if (verifiedAt < 0 || verifiedAt > verifiedBy || verifiedAt > wallTime() ||
            (fact["version"] as? Long ?: 0) < 1 || (fact["statement"] as? String).isNullOrBlank() ||
            (fact["kind"] != "verified_fact" && !(allowTradition && fact["kind"] == "tradition"))) return null
        val sources = linkedMapOf<String, Map<String, Any?>>()
        query("SELECT source_revision_id FROM fact_source WHERE fact_revision_id=? ORDER BY source_revision_id", id).use { links ->
            while (links.moveToNext()) {
                val sourceId = links.getString(0)
                if (sources.size >= 64) return null
                sources[sourceId] = source(sourceId) ?: return null
            }
        }
        if (sources.isEmpty()) return null
        return ReviewedFact(fact + ("sourceRevisionIds" to sources.keys.toList()), sources.toMap())
    }

    private fun source(id: String): Map<String, Any?>? {
        val source = row("SELECT * FROM source_evidence WHERE source_revision_id=?", id) ?: return null
        val verifiedAt = source["verified_at"] as? Long ?: return null
        if (source["review_status"] != "verified" || verifiedAt < 0 || verifiedAt > wallTime()) return null
        val uses = try { StrictJson.parse((source["allowed_uses_json"] as? String ?: return null).toByteArray(Charsets.UTF_8)) as? Map<*, *> }
            catch (_: Exception) { null } ?: return null
        return source.takeIf { uses["allowDisplay"] == true && uses["allowDistribution"] == true }
    }

    private fun coordinate(value: Any?, minimum: Double, maximum: Double): Double? =
        (when (value) { is String -> value.toDoubleOrNull(); is Long -> value.toDouble(); else -> null })
            ?.takeIf { it.isFinite() && it in minimum..maximum }
    private fun row(statement: String, vararg args: Any?): Map<String, Any?>? = query(statement, *args).use {
        if (it.moveToFirst()) values(it) else null
    }
    private fun values(row: Cursor): Map<String, Any?> = row.columnNames.associateWith { name ->
        val index = row.getColumnIndexOrThrow(name)
        when (row.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> row.getLong(index)
            else -> row.getString(index)
        }
    }
    private fun query(statement: String, vararg args: Any?): Cursor = sql.query(SimpleSQLiteQuery(statement, args))
    private fun <T> read(fallback: T, action: () -> T): T = try { database.runInTransaction(Callable { action() }) }
        catch (_: Exception) { fallback }
    override fun close() { if (ownsDatabase) database.close() }

    private class ReviewedPolicy(val value: LandmarkPolicy, val storyId: String,
        val associationFactRevisionId: String, val verifiedAt: Long) {
        override fun toString() = "ReviewedPolicy([redacted])"
    }
    private class ReviewedFact(val metadata: Map<String, Any?>, val sources: Map<String, Map<String, Any?>>) {
        override fun toString() = "ReviewedFact([redacted])"
    }
    private companion object {
        val ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
        val POLICY_KEYS = setOf("approachRadiusMeters", "passedRule", "directionRule", "cooldownMs", "policyVersion",
            "qualityAdapterVersion", "reviewStatus", "verifiedAt", "reviewBasis", "storyId", "associationFactRevisionId")
    }
}
