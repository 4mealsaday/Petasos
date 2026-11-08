package com.petasos.app.storage

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.generation.StrictJson
import com.petasos.app.location.RegionPolicy
import com.petasos.app.location.ReviewedRegionPolicyCodec
import java.util.concurrent.Callable

internal data class ReviewedRegionPolicy(
    val regionId: String, val regionRevisionId: String, val policy: RegionPolicy, val eligibilityKey: String,
) { override fun toString() = "ReviewedRegionPolicy([redacted])" }

/** Shared current-policy authority. Callers still require a matching verified geometry and location. */
internal class RoomRegionPolicyStore(
    private val database: StorageDatabase,
    private val wallTime: () -> Long = System::currentTimeMillis,
) {
    private val sql get() = database.openHelper.readableDatabase

    fun reviewedPolicy(regionRevisionId: String): ReviewedRegionPolicy? = try {
        database.runInTransaction(Callable { read(regionRevisionId) })
    } catch (_: Exception) { null }

    private fun read(id: String): ReviewedRegionPolicy? {
        val now = wallTime()
        if (now < 0) return null
        val revision = row("SELECT * FROM region_revision WHERE region_revision_id=?", id) ?: return null
        val regionId = revision["region_id"] as? String ?: return null
        val region = row("SELECT * FROM region WHERE region_id=?", regionId) ?: return null
        val source = row("SELECT * FROM source_evidence WHERE source_revision_id=?", revision["source_revision_id"]) ?: return null
        val verified = revision["verified_at"] as? Long ?: return null
        val sourceVerified = source["verified_at"] as? Long ?: return null
        if (source["review_status"] != "verified" || sourceVerified < 0 || verified < sourceVerified || verified > now) return null
        val uses = json(source["allowed_uses_json"]) ?: return null
        if (uses["allowDisplay"] != true || uses["allowDistribution"] != true) return null
        val raw = revision["policy_json"] as? String ?: return null
        val policy = json(raw) ?: return null
        val value = if (policy.keys == LEGACY_KEYS) {
            // Existing non-packaged DATA-11 policies remain valid. Additional envelope markers never downgrade here.
            val samples = policy["consecutiveSamples"] as? Long ?: return null
            if (samples !in 2..100) return null
            RegionPolicy(policy["datasetVersion"] as? String ?: return null, samples.toInt(),
                policy["minConfirmationDurationMs"] as? Long ?: return null,
                (policy["boundaryMarginMeters"] as? Long)?.toDouble() ?: return null,
                policy["qualityAdapterVersion"] as? String ?: return null,
                policy["coordinateReferenceSystem"] as? String ?: return null)
        } else {
            val reviewed = ReviewedRegionPolicyCodec.decodeStored(raw.toByteArray(), id, now)
            if (reviewed.verifiedAt != verified) return null
            reviewed.policy
        }
        if (value.datasetVersion != revision["dataset_version"]) return null
        val key = ContentBundleCodec.sha256(StrictJson.encode(mapOf("region" to region, "revision" to revision, "source" to source)))
        return ReviewedRegionPolicy(regionId, id, value, key)
    }

    private fun json(value: Any?): Map<*, *>? = (value as? String)?.let { StrictJson.parse(it.toByteArray()) as? Map<*, *> }
    private fun row(statement: String, vararg args: Any?): Map<String, Any?>? = sql.query(SimpleSQLiteQuery(statement, args)).use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.columnNames.associateWith { name ->
            val index = cursor.getColumnIndexOrThrow(name)
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                else -> cursor.getString(index)
            }
        }
    }

    private companion object {
        val LEGACY_KEYS = setOf("datasetVersion", "consecutiveSamples", "minConfirmationDurationMs",
            "boundaryMarginMeters", "qualityAdapterVersion", "coordinateReferenceSystem")
    }
}
