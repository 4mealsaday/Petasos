package com.petasos.app.storage

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.generation.StrictJson
import com.petasos.app.records.*
import java.util.concurrent.Callable

/** Worker-thread, read-only history projection. No recovery, keys, recording, or playback side effects. */
class RoomTripResultsStore internal constructor(
    private val database: StorageDatabase?,
    private val ownsDatabase: Boolean = false,
) : AutoCloseable {
    /** Opening results on a fresh installation does not create a database or user-history rows. */
    constructor(context: Context, databaseName: String = "petasos.db") : this(
        if (context.getDatabasePath(databaseName).isFile) StorageDatabase.open(context, databaseName) else null, true,
    )

    private val sql get() = checkNotNull(database).openHelper.readableDatabase

    fun listTrips(limit: Int = 20, offset: Int = 0): List<TripResultHeader> {
        require(limit in 1..100 && offset >= 0)
        if (database == null) return emptyList()
        return transaction {
            val result = mutableListOf<TripResultHeader>()
            query("SELECT trip_id,status,started_at,ended_at,last_observed_at,interruption_detected_at,end_reason,revision FROM trip ORDER BY started_at DESC,trip_id DESC LIMIT ? OFFSET ?", limit, offset).use {
                while (it.moveToNext()) result += header(it)
            }
            result
        }
    }

    /** Each detail list contains at most 50 latest rows; all aggregate counts remain complete. */
    fun readTrip(tripId: String, detailLimit: Int = 50): TripResultsSnapshot? {
        require(tripId.isNotBlank() && detailLimit in 1..50)
        if (database == null) return null
        return transaction { readSnapshot(tripId, detailLimit) }
    }

    fun latestTrip(detailLimit: Int = 50): TripResultsSnapshot? {
        require(detailLimit in 1..50)
        if (database == null) return null
        return transaction {
            val id = query("SELECT trip_id FROM trip ORDER BY started_at DESC,trip_id DESC LIMIT 1").use {
                if (!it.moveToFirst()) return@transaction null
                it.getString(0)
            }
            readSnapshot(id, detailLimit)
        }
    }

    private fun readSnapshot(tripId: String, limit: Int): TripResultsSnapshot? {
        val header = query("SELECT trip_id,status,started_at,ended_at,last_observed_at,interruption_detected_at,end_reason,revision FROM trip WHERE trip_id=?", tripId).use {
            if (!it.moveToFirst()) return null
            header(it)
        }
        val sessions = statuses("SELECT status,count(*) FROM session WHERE trip_id=? GROUP BY status", tripId)
        val segments = statuses("SELECT status,count(*) FROM recording_segment WHERE trip_id=? GROUP BY status", tripId)
        val locationSamples = locations("SELECT p.quality_status,count(*) FROM route_point p JOIN recording_segment g ON g.segment_id=p.segment_id WHERE g.trip_id=? GROUP BY p.quality_status", tripId)
        val attempts = mutableMapOf<Pair<String, String>, Long>()
        query("SELECT a.kind,a.state,count(*) FROM playback_attempt a JOIN session s ON s.session_id=a.session_id WHERE s.trip_id=? GROUP BY a.kind,a.state", tripId).use {
            while (it.moveToNext()) {
                if (it.getString(0) !in PLAYBACK_KINDS || it.getString(1) !in ATTEMPT_STATES) invalid()
                attempts[it.getString(0) to it.getString(1)] = it.getLong(2)
            }
        }
        fun counts(kind: String) = TripAttemptCounts(attempts[kind to "prepared"] ?: 0, attempts[kind to "playing"] ?: 0,
            attempts[kind to "completed"] ?: 0, attempts[kind to "interrupted"] ?: 0, attempts[kind to "failed"] ?: 0)
        val playback = TripPlaybackCounts(counts("story"), counts("answer"), counts("music"), counts("prompt"), counts("transition"),
            count("SELECT count(DISTINCT a.paragraph_id) FROM playback_attempt a JOIN session s ON s.session_id=a.session_id WHERE s.trip_id=? AND a.kind='story' AND a.state='completed'", tripId))
        val summaryStates = mutableMapOf<String, Long>()
        query("SELECT t.summary_state,count(*) FROM conversation_turn t JOIN session s ON s.session_id=t.session_id WHERE s.trip_id=? GROUP BY t.summary_state", tripId).use {
            while (it.moveToNext()) {
                if (it.getString(0) !in SUMMARY_STATES) invalid()
                summaryStates[it.getString(0)] = it.getLong(1)
            }
        }
        val summaries = TripSummaryCounts(summaryStates["no_text"] ?: 0, summaryStates["pending"] ?: 0, summaryStates["running"] ?: 0,
            summaryStates["retry_wait"] ?: 0, summaryStates["saved"] ?: 0, summaryStates["blocked"] ?: 0)
        val savedCount = count("""SELECT count(*) FROM conversation_summary c JOIN conversation_turn t ON t.turn_id=c.turn_id
            JOIN session s ON s.session_id=t.session_id WHERE s.trip_id=? AND t.frozen=1 AND t.summary_state='saved'
            AND t.turn_version=c.turn_version AND t.summary_saved_at IS NOT NULL""", tripId)
        val allSummaryRows = count("SELECT count(*) FROM conversation_summary c JOIN conversation_turn t ON t.turn_id=c.turn_id JOIN session s ON s.session_id=t.session_id WHERE s.trip_id=?", tripId)
        if (savedCount != summaries.saved || allSummaryRows != savedCount) invalid()
        return TripResultsSnapshot(header, sessions, segments, locationSamples, playback, summaries,
            TripResultPage(segmentDetails(tripId, limit), segments.total),
            TripResultPage(savedSummaries(tripId, limit), savedCount),
            TripResultPage(collections(tripId, limit), count("$COLLECTION_PROOFS SELECT count(*) FROM attributed", tripId, tripId)))
    }

    private fun segmentDetails(tripId: String, limit: Int): List<TripSegmentResult> {
        val result = mutableListOf<TripSegmentResult>()
        query("SELECT segment_id,session_id,status,started_at,ended_at,last_observed_at,interruption_detected_at,end_reason FROM recording_segment WHERE trip_id=? ORDER BY started_at DESC,segment_id DESC LIMIT ?", tripId, limit).use {
            while (it.moveToNext()) result += TripSegmentResult(it.getString(0), it.getString(1), status(it.getString(2)), it.getLong(3),
                it.nullLong(4), it.nullLong(5), it.nullLong(6), reason(it.nullString(7)),
                locations("SELECT quality_status,count(*) FROM route_point WHERE segment_id=? GROUP BY quality_status", it.getString(0)))
        }
        return result
    }

    private fun savedSummaries(tripId: String, limit: Int): List<TripSavedSummary> {
        val result = mutableListOf<TripSavedSummary>()
        query("""SELECT c.summary_id,c.turn_id,c.turn_version,c.summary_text,c.provenance_json,c.summary_schema_version,c.model_id,c.prompt_version,c.created_at
            FROM conversation_summary c JOIN conversation_turn t ON t.turn_id=c.turn_id JOIN session s ON s.session_id=t.session_id
            WHERE s.trip_id=? AND t.frozen=1 AND t.summary_state='saved' AND t.turn_version=c.turn_version
            ORDER BY c.created_at DESC,c.summary_id DESC LIMIT ?""", tripId, limit).use {
            while (it.moveToNext()) {
                val body = it.getString(3)
                if (body.isBlank() || body.length > 4_000 || it.getString(5) != "0.8") invalid()
                result += TripSavedSummary(it.getString(0), it.getString(1), it.getLong(2), body, provenance(it.getString(4)),
                    it.getString(5), it.getString(6), it.getString(7), it.getLong(8))
            }
        }
        return result
    }

    private fun provenance(raw: String): TripSummaryProvenance {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val parsed = try { StrictJson.parse(bytes) as? Map<*, *> ?: invalid() } finally { bytes.fill(0) }
        if (parsed.keys != setOf("userClaims", "verifiedFactRevisionIds", "unresolvedQuestions", "completedResponseKeys", "partialUnknownKeys")) invalid()
        fun strings(key: String, maxItems: Int, maxLength: Int): List<String> {
            val values = parsed[key] as? List<*> ?: invalid()
            if (values.size > maxItems) invalid()
            return values.map { value -> (value as? String)?.takeIf { it.isNotBlank() && it.length <= maxLength } ?: invalid() }
        }
        fun identifiers(key: String): List<String> = strings(key, 50, 128).also { values ->
            if (values.size != values.distinct().size || values.any { !it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")) }) invalid()
        }
        return TripSummaryProvenance(strings("userClaims", 20, 4_000), identifiers("verifiedFactRevisionIds"),
            strings("unresolvedQuestions", 20, 256), identifiers("completedResponseKeys"), identifiers("partialUnknownKeys"))
    }

    private fun collections(tripId: String, limit: Int): List<TripCollectionResult> {
        val result = mutableListOf<TripCollectionResult>()
        query("""$COLLECTION_PROOFS SELECT collection_id,collection_type,story_id,story_version_id,region_id,rule_version,
            earned_at,created_at,this_trip,other_trips,total_proofs FROM attributed ORDER BY created_at DESC,collection_id DESC LIMIT ?""", tripId, tripId, limit).use {
            while (it.moveToNext()) result += TripCollectionResult(it.getString(0), when (it.getString(1)) {
                "story" -> TripCollectionKind.STORY; "region" -> TripCollectionKind.REGION; else -> invalid()
            }, it.nullString(2), it.nullString(3), it.nullString(4), it.getString(5), it.getLong(6), it.getLong(7), it.getLong(8), it.getLong(9), it.getLong(10))
        }
        return result
    }

    private fun header(row: Cursor) = TripResultHeader(row.getString(0), status(row.getString(1)), row.getLong(2),
        row.nullLong(3), row.nullLong(4), row.nullLong(5), reason(row.nullString(6)), row.getLong(7))
    private fun status(value: String): TripRecordStatus = when (value) {
        "active" -> TripRecordStatus.ACTIVE; "interrupted" -> TripRecordStatus.INTERRUPTED; "closed" -> TripRecordStatus.CLOSED; else -> invalid()
    }
    private fun statuses(statement: String, argument: String): TripStatusCounts {
        val values = mutableMapOf<String, Long>()
        query(statement, argument).use { while (it.moveToNext()) { status(it.getString(0)); values[it.getString(0)] = it.getLong(1) } }
        return TripStatusCounts(values["active"] ?: 0, values["interrupted"] ?: 0, values["closed"] ?: 0)
    }
    private fun locations(statement: String, argument: String): TripLocationSampleCounts {
        val values = mutableMapOf<String, Long>()
        query(statement, argument).use { while (it.moveToNext()) {
            if (it.getString(0) !in setOf("valid", "stale", "invalid", "unknown")) invalid()
            values[it.getString(0)] = it.getLong(1)
        } }
        return TripLocationSampleCounts(values["valid"] ?: 0, values["stale"] ?: 0, values["invalid"] ?: 0, values["unknown"] ?: 0)
    }
    private fun reason(value: String?): String? = value?.let { if (it in END_REASONS) it else "other" }
    private fun Cursor.nullLong(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun Cursor.nullString(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun query(statement: String, vararg args: Any?): Cursor = sql.query(SimpleSQLiteQuery(statement, args))
    private fun count(statement: String, vararg args: Any?): Long = query(statement, *args).use { if (!it.moveToFirst()) invalid(); it.getLong(0) }
    private fun invalid(): Nothing = throw StorageUnavailable("TRIP_RESULTS_INCONSISTENT")
    private fun <T> transaction(action: () -> T): T = try { checkNotNull(database).runInTransaction(Callable { action() }) }
    catch (failure: StorageUnavailable) { throw failure }
    catch (_: Exception) { throw StorageUnavailable("TRIP_RESULTS_STORAGE_UNAVAILABLE") }
    override fun close() { if (ownsDatabase) database?.close() }

    private companion object {
        val PLAYBACK_KINDS = setOf("story", "answer", "music", "prompt", "transition")
        val ATTEMPT_STATES = setOf("prepared", "playing", "completed", "interrupted", "failed")
        val SUMMARY_STATES = setOf("no_text", "pending", "running", "retry_wait", "saved", "blocked")
        val END_REASONS = setOf("user_end", "service_end", "suspend", "permission_lost", "process_recovery", "explicit_stop",
            "receipt_gap", "permission_denied", "no_location", "location_disconnected", "unsupported")
        // Titles and public narrative bodies are deliberately absent: revoked source rights cannot
        // cause old content text to escape through a history/collection projection.
        val COLLECTION_PROOFS = """WITH proof_trips AS (
            SELECT cp.collection_id,s.trip_id FROM collection_proof cp
            JOIN collection_record c ON c.collection_id=cp.collection_id
            JOIN playback_attempt a ON a.attempt_id=cp.attempt_id JOIN paragraph p ON p.paragraph_id=a.paragraph_id
            JOIN session s ON s.session_id=a.session_id
            WHERE c.collection_type='story' AND cp.point_id IS NULL AND a.kind='story' AND a.state='completed'
              AND p.story_version_id=c.story_version_id AND cp.proof_key=p.paragraph_id
            UNION ALL
            SELECT cp.collection_id,g.trip_id FROM collection_proof cp
            JOIN collection_record c ON c.collection_id=cp.collection_id JOIN route_point p ON p.point_id=cp.point_id
            JOIN recording_segment g ON g.segment_id=p.segment_id JOIN region_revision r ON r.region_revision_id=cp.region_revision_id
            WHERE c.collection_type='region' AND cp.attempt_id IS NULL AND p.quality_status='valid'
              AND r.region_id=c.region_id
        ), attributed AS (
            SELECT c.collection_id,c.collection_type,c.story_id,c.story_version_id,c.region_id,c.rule_version,c.earned_at,c.created_at,
              sum(CASE WHEN p.trip_id=? THEN 1 ELSE 0 END) AS this_trip,
              sum(CASE WHEN p.trip_id<>? THEN 1 ELSE 0 END) AS other_trips,
              (SELECT count(*) FROM collection_proof all_proofs WHERE all_proofs.collection_id=c.collection_id) AS total_proofs
            FROM collection_record c JOIN proof_trips p ON p.collection_id=c.collection_id
            GROUP BY c.collection_id HAVING this_trip>0
        )"""
    }
}
