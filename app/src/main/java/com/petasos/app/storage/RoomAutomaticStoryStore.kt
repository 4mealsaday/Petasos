package com.petasos.app.storage

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.generation.StrictJson
import com.petasos.app.planning.AutomaticStoryCandidate
import com.petasos.app.planning.CandidateReason
import java.util.concurrent.Callable

/** Read-only RUN-07 eligibility. An offered handle never replaces the same checks before output. */
class RoomAutomaticStoryStore internal constructor(
    private val database: StorageDatabase,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val ownsDatabase: Boolean = false,
) : AutoCloseable {
    constructor(context: Context, databaseName: String = "petasos.db") : this(
        StorageDatabase.open(context, databaseName), System::currentTimeMillis, true,
    )

    private val sql get() = database.openHelper.writableDatabase
    private val eligibility = RoomAutomaticStoryEligibility(database, wallTime)
    private val preferences = RoomVehiclePreferenceStore(database, wallTime, ownsDatabase = false)
    private val regionBindings = RoomRegionalContentBindings(database, wallTime)

    /** Region first, then exact explicit-interest tags; no keyword inference or implicit preference writes. */
    fun candidates(currentRegionId: String?, excludedStoryIds: Set<String> = emptySet()): List<AutomaticStoryCandidate> = read(emptyList()) {
        val region = currentRegionId?.takeIf { it.isNotBlank() }
        val excluded = excludedStoryIds.toSet()
        val interest = readInterest()
        val result = mutableListOf<AutomaticStoryCandidate>()
        // Apply reviewed regional eligibility before priority and the result cap. Raw region_id
        // alone could otherwise put 64 unreviewed interest rows ahead of a valid regional story.
        val reasons = if (region == null) listOf(CandidateReason.INTEREST)
            else listOf(CandidateReason.CURRENT_REGION, CandidateReason.INTEREST)
        for (reason in reasons) {
            val statement = "${RoomAutomaticStoryEligibility.SELECT}" + (if (reason == CandidateReason.CURRENT_REGION) " AND s.region_id=?" else "") + " ORDER BY s.story_id"
            val arguments = if (reason == CandidateReason.CURRENT_REGION) arrayOf<Any?>(region) else emptyArray()
            query(statement, *arguments).use { rows ->
                while (result.size < MAX_CANDIDATES && rows.moveToNext()) {
                    if (rows.getString(rows.getColumnIndexOrThrow("story_id")) in excluded) continue
                    readCandidate(rows, region, interest)?.takeIf { it.reason == reason }?.let(result::add)
                }
            }
        }
        result.toList()
    }

    /** Re-read metadata, preferences, current rights and global completed-story/stable-fact history. */
    fun isCurrent(candidate: AutomaticStoryCandidate, currentRegionId: String?): Boolean = read(false) {
        val region = currentRegionId?.takeIf { it.isNotBlank() }
        query("${RoomAutomaticStoryEligibility.SELECT} AND v.story_version_id=?", candidate.story.versionId).use { rows ->
            if (!rows.moveToFirst()) return@use false
            val current = readCandidate(rows, region, readInterest()) ?: return@use false
            current.story == candidate.story && current.reason == candidate.reason &&
                current.regionId == candidate.regionId && current.eligibilityKey == candidate.eligibilityKey
        }
    }

    private class Interest(val version: Long?, val keywords: List<String>) {
        override fun toString() = "AutomaticInterest([redacted])"
    }

    private fun readInterest(): Interest? = try {
        val settings = preferences.readSettings()
        Interest(settings.version, settings.keywords)
    } catch (_: StorageUnavailable) {
        // Invalid optional interests cannot authorize an interest candidate or erase a valid region.
        null
    }

    private fun readCandidate(row: Cursor, currentRegionId: String?, interest: Interest?): AutomaticStoryCandidate? {
        val record = eligibility.read(row) ?: return null
        val tags = record.interestTags
        val storyRegion = record.regionId
        val regionBindingKey = if (currentRegionId != null && storyRegion == currentRegionId)
            regionBindings.currentKey(record.storyId, storyRegion) else null
        val reason = when {
            regionBindingKey != null -> CandidateReason.CURRENT_REGION
            interest != null && tags.any { it in interest.keywords } -> CandidateReason.INTEREST
            else -> return null
        }
        val prepared = eligibility.prepare(record) ?: return null
        // Public metadata and explicit preference state only; no pending payload, summary or route points.
        val key = ContentBundleCodec.sha256(StrictJson.encode(linkedMapOf(
            "metadata" to record.metadata, "reason" to reason.name,
            "regionBinding" to if (reason == CandidateReason.CURRENT_REGION) regionBindingKey else null,
            "preference" to if (reason == CandidateReason.INTEREST) mapOf(
                "version" to interest!!.version, "keywords" to interest.keywords,
            ) else null,
        )))
        return AutomaticStoryCandidate(prepared, reason,
            if (reason == CandidateReason.CURRENT_REGION) storyRegion else null, key)
    }

    private fun query(statement: String, vararg arguments: Any?): Cursor = sql.query(SimpleSQLiteQuery(statement, arguments))
    private fun <T> read(fallback: T, operation: () -> T): T = try {
        database.runInTransaction(Callable { operation() })
    } catch (_: Exception) { fallback }

    override fun close() { if (ownsDatabase) database.close() }

    private companion object {
        const val MAX_CANDIDATES = 64
    }
}
