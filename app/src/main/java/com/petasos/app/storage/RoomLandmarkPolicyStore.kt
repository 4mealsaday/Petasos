package com.petasos.app.storage

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.generation.StrictJson
import com.petasos.app.location.LandmarkDirectionRule
import com.petasos.app.location.LandmarkPassedRule
import com.petasos.app.location.LandmarkPlaceMetadata
import com.petasos.app.location.LandmarkPolicy
import com.petasos.app.location.LandmarkPolicyReview
import com.petasos.app.location.ReviewedLandmarkPolicyCodec

/** Shared storage authority for installation and candidate reads; callers own the transaction.
 * No JSON/coordinates leave this worker reader and no validity result starts playback.
 */
internal class RoomLandmarkPolicyStore(private val database: StorageDatabase, private val wallTime: () -> Long) {
    private val sql get() = database.openHelper.writableDatabase

    /** Includes retained rejected associations when detecting ambiguous story/place assignments. */
    fun references(): List<LandmarkPolicyReference> {
        val result = mutableListOf<LandmarkPolicyReference>()
        query("SELECT * FROM place WHERE landmark_policy_json IS NOT NULL ORDER BY place_id").use { rows ->
            while (rows.moveToNext()) {
                val place = values(rows)
                decode(place)?.let(result::add)
            }
        }
        return result.toList()
    }

    fun reviewedPolicies(): List<ReviewedLandmarkRecord> {
        val refs = references()
        val counts = refs.groupingBy { it.storyId }.eachCount()
        return refs.mapNotNull { ref -> if (counts[ref.storyId] == 1) current(ref) else null }
    }

    fun reviewedPolicy(placeId: String): ReviewedLandmarkRecord? = reviewedPolicies().singleOrNull { it.place.placeId == placeId }

    /** Validate a prospective review before writing; source/fact review must precede policy review. */
    fun prospective(review: LandmarkPolicyReview): ReviewedLandmarkRecord? {
        val row = row("SELECT * FROM place WHERE place_id=?", review.placeId) ?: return null
        return current(reference(review, "verified", row))
    }

    private fun decode(place: Map<String, Any?>): LandmarkPolicyReference? {
        return try {
            val raw = place["landmark_policy_json"] as? String ?: return null
            val json = StrictJson.parse(raw.toByteArray(Charsets.UTF_8)) as? Map<*, *> ?: return null
            if (json.keys == ReviewedLandmarkPolicyCodec.legacyKeys) legacy(place, json)
            else {
                // A marked or malformed new envelope never falls back to legacy interpretation.
                val stored = ReviewedLandmarkPolicyCodec.decodeStored(raw.toByteArray(Charsets.UTF_8), wallTime())
                if (stored.review.placeId != place["place_id"]) null else reference(stored.review, stored.status, place)
            }
        } catch (_: Exception) { null }
    }

    private fun reference(review: LandmarkPolicyReview, status: String, place: Map<String, Any?>) =
        LandmarkPolicyReference(review.storyId, review.associationFactRevisionId, review.policy,
            review.verifiedAt, status, review.placeMetadataSha256, place)

    private fun legacy(place: Map<String, Any?>, json: Map<*, *>): LandmarkPolicyReference? {
        if (json["reviewStatus"] !in setOf("verified", "rejected")) return null
        val reviewed = json["verifiedAt"] as? Long ?: return null
        val basis = json["reviewBasis"] as? String ?: return null
        val storyId = json["storyId"] as? String ?: return null
        val factId = json["associationFactRevisionId"] as? String ?: return null
        val radius = json["approachRadiusMeters"] as? Long ?: return null
        val cooldown = json["cooldownMs"] as? Long ?: return null
        if (reviewed < 0 || reviewed > wallTime() || basis.isBlank() || basis.length > 4000 ||
            !ID.matches(storyId) || !ID.matches(factId) ||
            json["passedRule"] != "increasing_distance_after_approach" || json["directionRule"] != "decreasing_distance") return null
        val policy = LandmarkPolicy(radius.toDouble(), LandmarkPassedRule.INCREASING_DISTANCE_AFTER_APPROACH,
            LandmarkDirectionRule.DECREASING_DISTANCE, cooldown,
            json["policyVersion"] as? String ?: return null, json["qualityAdapterVersion"] as? String ?: return null)
        return LandmarkPolicyReference(storyId, factId, policy, reviewed, json["reviewStatus"] as String, null, place)
    }

    private fun current(ref: LandmarkPolicyReference): ReviewedLandmarkRecord? {
        if (ref.status != "verified" || ref.verifiedAt < 0 || ref.verifiedAt > wallTime()) return null
        val place = metadata(ref.place) ?: return null
        if (ref.placeMetadataSha256 != null && ref.placeMetadataSha256 != place.fingerprint()) return null
        val story = row("SELECT story_id,place_id FROM story WHERE story_id=?", ref.storyId) ?: return null
        // Legacy policy relies on the original non-null FK. Only the pinned envelope authorizes NULL.
        val existingPlace = story["place_id"]
        if (existingPlace != place.placeId && (existingPlace != null || ref.placeMetadataSha256 == null)) return null
        val source = source(place.coordinateSourceRevisionId, ref.verifiedAt) ?: return null
        val association = fact(ref.associationFactRevisionId, ref.verifiedAt, allowTradition = false) ?: return null
        return ReviewedLandmarkRecord(ref.storyId, place, ref.policy, ref.place, source, association)
    }

    /** The independent association survives a generated version omitting that fact. */
    fun fact(id: String, verifiedBy: Long, allowTradition: Boolean): LandmarkFactEvidence? {
        val fact = row("SELECT * FROM fact_revision WHERE fact_revision_id=?", id) ?: return null
        val reviewed = fact["verified_at"] as? Long ?: return null
        if (reviewed < 0 || reviewed > verifiedBy || reviewed > wallTime() ||
            (fact["version"] as? Long ?: 0) < 1 || (fact["statement"] as? String).isNullOrBlank() ||
            (fact["kind"] != "verified_fact" && !(allowTradition && fact["kind"] == "tradition"))) return null
        val sources = linkedMapOf<String, Map<String, Any?>>()
        query("SELECT source_revision_id FROM fact_source WHERE fact_revision_id=? ORDER BY source_revision_id", id).use { rows ->
            while (rows.moveToNext()) {
                val sourceId = rows.getString(0)
                if (sources.size >= 64) return null
                sources[sourceId] = source(sourceId, verifiedBy) ?: return null
            }
        }
        if (sources.isEmpty()) return null
        return LandmarkFactEvidence(fact + ("sourceRevisionIds" to sources.keys.toList()), sources.toMap())
    }

    private fun source(id: String, verifiedBy: Long): Map<String, Any?>? {
        val source = row("SELECT * FROM source_evidence WHERE source_revision_id=?", id) ?: return null
        val reviewed = source["verified_at"] as? Long ?: return null
        if (source["review_status"] != "verified" || reviewed < 0 || reviewed > verifiedBy || reviewed > wallTime()) return null
        val uses = try { StrictJson.parse((source["allowed_uses_json"] as? String ?: return null).toByteArray(Charsets.UTF_8)) as? Map<*, *> }
            catch (_: Exception) { null } ?: return null
        return source.takeIf { uses["allowDisplay"] == true && uses["allowDistribution"] == true }
    }

    fun metadata(place: Map<String, Any?>): LandmarkPlaceMetadata? = try {
        fun coordinate(key: String): Double = (place[key] as? String)?.toDoubleOrNull() ?: throw IllegalArgumentException()
        LandmarkPlaceMetadata(place["place_id"] as String, place["region_id"] as? String,
            place["name"] as String, place["aliases_json"] as String, coordinate("latitude"), coordinate("longitude"),
            place["coordinate_source_revision_id"] as String).also { it.fingerprint() }
    } catch (_: Exception) { null }

    private fun row(statement: String, vararg args: Any?): Map<String, Any?>? = query(statement, *args).use {
        if (it.moveToFirst()) values(it) else null
    }
    private fun values(row: Cursor): Map<String, Any?> = row.columnNames.associateWith { name ->
        val index = row.getColumnIndexOrThrow(name)
        when (row.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> row.getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> row.getDouble(index).toString()
            else -> row.getString(index)
        }
    }
    private fun query(statement: String, vararg args: Any?): Cursor = sql.query(SimpleSQLiteQuery(statement, args))
    private companion object { val ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}") }
}

internal class LandmarkPolicyReference(
    val storyId: String, val associationFactRevisionId: String, val policy: LandmarkPolicy,
    val verifiedAt: Long, val status: String, val placeMetadataSha256: String?, val place: Map<String, Any?>,
) { override fun toString() = "LandmarkPolicyReference([redacted])" }

internal class ReviewedLandmarkRecord(
    val storyId: String, val place: LandmarkPlaceMetadata, val policy: LandmarkPolicy,
    val placeRow: Map<String, Any?>, val coordinateSource: Map<String, Any?>, val association: LandmarkFactEvidence,
) { override fun toString() = "ReviewedLandmarkRecord([redacted])" }

internal class LandmarkFactEvidence(val metadata: Map<String, Any?>, val sources: Map<String, Map<String, Any?>>) {
    override fun toString() = "LandmarkFactEvidence([redacted])"
}
