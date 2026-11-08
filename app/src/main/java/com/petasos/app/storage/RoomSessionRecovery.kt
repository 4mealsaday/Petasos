package com.petasos.app.storage

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.Callable

/** Saved references only. Playback must prepare the current authorized content again. */
sealed interface RecoveryCheckpoint {
    data object Idle : RecoveryCheckpoint
    data class Story(val storyId: String, val versionId: String, val paragraphId: String) : RecoveryCheckpoint
    data class Music(val assetId: String, val positionMs: Long) : RecoveryCheckpoint
}

@ConsistentCopyVisibility
data class RecoveryCandidate internal constructor(
    val tripId: String,
    val previousSessionId: String,
    val tripRevision: Long,
    val previousCancelEpoch: Long,
    val previousStateRevision: Long,
    val checkpointSessionId: String?,
    val checkpointRevision: Long?,
    val checkpoint: RecoveryCheckpoint,
    /** Historical preference only, never permission to start playback on app launch. */
    val previousPlayIntent: Boolean,
    /** Durable cadence only. Restoring this never selects content or grants play intent. */
    val requireMusicBeforeAutomaticStory: Boolean = false,
) {
    override fun toString(): String = "RecoveryCandidate([redacted])"
}

/**
 * Worker-thread TX-04 recovery. Startup only interrupts stale state; it creates no session,
 * recorder, location subscription or output. Run once per process before any runtime opens.
 */
class RoomSessionRecovery internal constructor(
    private val database: StorageDatabase,
    private val cipher: PayloadCipher,
    private val wallTime: () -> Long,
    private val ownsDatabase: Boolean,
    private val openSession: ((String, String) -> Unit)? = null,
) : AutoCloseable {
    constructor(context: Context, databaseName: String = "petasos.db") : this(
        StorageDatabase.open(context, databaseName), PayloadCipher(AndroidPayloadKeys()),
        System::currentTimeMillis, true,
    )

    private val sql: SupportSQLiteDatabase get() = database.openHelper.writableDatabase

    fun recoverOnAppStart(): RecoveryCandidate? = transaction {
        recoverStaleState()
        readCandidate()
    }

    /** Read-only and unavailable while an active runtime exists. */
    fun currentTripCandidate(): RecoveryCandidate? = transaction { readCandidate() }

    /**
     * Invoke only from the explicit user's resume path, as RuntimePersistence.openSession's
     * implementation. The accessor on RoomRuntimePersistence shares its database and cipher.
     * A segment is created only when the caller supplies an ID after explicit recording opt-in.
     */
    fun openResumedSession(candidate: RecoveryCandidate, newSessionId: String, newSegmentId: String? = null) = transaction {
        requireSafe(newSessionId.isNotBlank() && newSessionId != candidate.previousSessionId)
        requireSafe(newSegmentId == null || newSegmentId.isNotBlank())
        val opener = openSession ?: throw StorageUnavailable("RECOVERY_SESSION_FACTORY_REQUIRED")
        requireSafe(readCandidate() == candidate)
        requireSafe(number("SELECT count(*) FROM session WHERE session_id=?", newSessionId) == 0L)
        if (newSegmentId != null) requireSafe(number("SELECT count(*) FROM recording_segment WHERE segment_id=?", newSegmentId) == 0L)
        recoverStaleState()
        opener(candidate.tripId, newSessionId)
        requireSafe(number("SELECT count(*) FROM session WHERE session_id=? AND trip_id=? AND status='active'", newSessionId, candidate.tripId) == 1L)
        if (newSegmentId != null) exec(
            "INSERT INTO recording_segment(segment_id,trip_id,session_id,started_at,status) VALUES (?,?,?,?,'active')",
            newSegmentId, candidate.tripId, newSessionId, wallTime(),
        )
    }

    private fun recoverStaleState() {
        val now = wallTime()
        // Read these before changing terminal states. Include previously interrupted recovery
        // attempts so a later key restoration can repair metadata without inventing completion.
        val interruptedAnswers = linkedMapOf<String, MutableSet<String>>()
        query("""SELECT a.turn_id,a.transient_paragraph_key FROM playback_attempt a
            JOIN pending_conversation p ON p.turn_id=a.turn_id JOIN conversation_turn t ON t.turn_id=a.turn_id
            WHERE a.kind='answer' AND t.summary_state<>'saved' AND
              (a.state IN ('prepared','playing') OR (a.state='interrupted' AND a.interruption_reason='session_recovery'))""").use {
            while (it.moveToNext()) {
                requireSafe(!it.isNull(0) && !it.isNull(1))
                interruptedAnswers.getOrPut(it.getString(0)) { linkedSetOf() }.add(it.getString(1))
            }
        }
        interruptedAnswers.forEach { (turnId, keys) -> recoverAnswerPayload(turnId, keys, now) }
        exec("UPDATE playback_attempt SET state='interrupted',interrupted_at=?,interruption_reason='session_recovery' WHERE state IN ('prepared','playing')", now)
        exec("UPDATE conversation_turn SET lifecycle='interrupted',frozen=1,closed_at=? WHERE lifecycle='open'", now)
        // No completion/ended timestamp is fabricated for an unobserved process death.
        exec("UPDATE recording_segment SET status='interrupted',interruption_detected_at=?,end_reason='process_recovery' WHERE status='active'", now)
        exec("UPDATE session SET status='interrupted',cancel_epoch=cancel_epoch+1 WHERE status='active'")
        exec("UPDATE trip SET status='interrupted',interruption_detected_at=?,revision=revision+1 WHERE status='active'", now)
        exec("UPDATE generation_request SET status='cancelled',finished_at=?,error_code='SESSION_RECOVERED' WHERE task<>'summary' AND status IN ('pending','running')", now)
        // The summary recovery gate handles its own running requests and retry cadence afterward.
    }

    private fun recoverAnswerPayload(turnId: String, interruptedKeys: Set<String>, now: Long) {
        data class Row(val session: String, val version: Long, val encrypted: EncryptedPayload)
        val row = query("""SELECT t.session_id,t.turn_version,p.payload_version,p.ciphertext,p.nonce,p.key_version,p.payload_hash
            FROM conversation_turn t JOIN pending_conversation p ON p.turn_id=t.turn_id WHERE t.turn_id=?""", turnId).use {
            requireSafe(it.moveToFirst())
            if (it.getLong(1) != it.getLong(2)) {
                blockPayload(turnId)
                return
            }
            Row(it.getString(0), it.getLong(1), EncryptedPayload(it.getBlob(3), it.getBlob(4), it.getString(5), it.getString(6)))
        }
        val repaired = try {
            val bytes = cipher.decrypt(PayloadIdentity(row.session, turnId, row.version), row.encrypted)
            var payload = try { ConversationPayload.decode(bytes) } finally { bytes.fill(0) }
            val newKeys = interruptedKeys - payload.interruptedParagraphKeys - payload.playedParagraphKeys
            if (newKeys.isEmpty()) return
            newKeys.forEach { payload = payload.withPlayback(it, completed = false) }
            require(row.version < Long.MAX_VALUE)
            val plain = payload.encode()
            try { cipher.encrypt(PayloadIdentity(row.session, turnId, row.version + 1), plain) }
            finally { plain.fill(0) }
        } catch (_: Exception) {
            // Preserve existing encrypted bytes and version on key loss, failed decrypt or invalid
            // payload. SQL write failures below instead roll back the complete recovery transaction.
            blockPayload(turnId)
            return
        }
        exec("UPDATE pending_conversation SET payload_version=?,ciphertext=?,nonce=?,key_version=?,payload_hash=?,updated_at=? WHERE turn_id=?",
            row.version + 1, repaired.ciphertext, repaired.nonce, repaired.keyVersion, repaired.payloadHash, now, turnId)
        exec("UPDATE conversation_turn SET turn_version=? WHERE turn_id=?", row.version + 1, turnId)
    }

    private fun blockPayload(turnId: String) {
        exec("UPDATE conversation_turn SET summary_state='blocked',next_retry_at=NULL,last_error_code='RECOVERY_PAYLOAD_UNAVAILABLE' WHERE turn_id=? AND summary_state<>'saved'", turnId)
    }

    private fun readCandidate(): RecoveryCandidate? {
        if (number("SELECT count(*) FROM session WHERE status='active'") > 0L) return null
        // A user-ended latest trip is a boundary. Starting again must not revive an older trip.
        return query("""SELECT t.trip_id,t.revision,s.session_id,s.cancel_epoch,s.state_revision,
            c.session_id,c.state_revision,c.mode,c.paragraph_id,c.asset_id,c.music_position_ms,c.play_intent,
            v.story_id,v.story_version_id
            FROM trip t JOIN session s ON s.trip_id=t.trip_id
            LEFT JOIN playback_checkpoint c ON c.trip_id=t.trip_id
            LEFT JOIN paragraph p ON p.paragraph_id=c.paragraph_id
            LEFT JOIN story_version v ON v.story_version_id=p.story_version_id
            WHERE t.rowid=(SELECT max(rowid) FROM trip) AND t.status<>'closed'
              AND s.rowid=(SELECT max(s2.rowid) FROM session s2 WHERE s2.trip_id=t.trip_id)
            ORDER BY t.rowid DESC LIMIT 1""").use {
            if (!it.moveToFirst()) return@use null
            val checkpoint = when (if (it.isNull(7)) "idle" else it.getString(7)) {
                "idle" -> RecoveryCheckpoint.Idle
                "story" -> {
                    requireSafe(!it.isNull(8) && !it.isNull(12) && !it.isNull(13))
                    RecoveryCheckpoint.Story(it.getString(12), it.getString(13), it.getString(8))
                }
                "music" -> {
                    requireSafe(!it.isNull(9) && !it.isNull(10) && it.getLong(10) >= 0)
                    RecoveryCheckpoint.Music(it.getString(9), it.getLong(10))
                }
                else -> throw StorageUnavailable("RECOVERY_CHECKPOINT_UNAVAILABLE")
            }
            RecoveryCandidate(it.getString(0), it.getString(2), it.getLong(1), it.getLong(3), it.getLong(4),
                if (it.isNull(5)) null else it.getString(5), if (it.isNull(6)) null else it.getLong(6),
                checkpoint, !it.isNull(11) && it.getLong(11) == 1L,
                checkpoint is RecoveryCheckpoint.Music || requiresMusic(it.getString(0)))
        }
    }

    /**
     * Registration is append-only and serialized with the preceding output's terminal commit.
     * This local insertion order survives wall-clock changes and spans sessions of the same trip.
     * Only cadence-changing events participate: final-story completion sets the gap, every music
     * attempt retains it, and a durably started/completed music attempt alone satisfies it.
     * A checkpoint's last paragraph is not completion evidence and may remain after completed music.
     */
    private fun requiresMusic(tripId: String): Boolean = query("""SELECT a.kind,a.state,
        a.started_at,a.completed_at,a.started_elapsed_ms,a.ended_elapsed_ms,a.position_ms
        FROM playback_attempt a JOIN session s ON s.session_id=a.session_id
        LEFT JOIN paragraph p ON p.paragraph_id=a.paragraph_id
        WHERE s.trip_id=? AND (a.kind='music' OR
          (a.kind='story' AND a.state='completed' AND NOT EXISTS
            (SELECT 1 FROM paragraph later WHERE later.story_version_id=p.story_version_id AND later.ordinal>p.ordinal)))
        ORDER BY a.rowid DESC LIMIT 1""", tripId).use {
        if (!it.moveToFirst()) return@use false
        if (it.getString(0) != "music" || it.getString(1) != "completed") return@use true
        // Older/incomplete rows cannot turn a missing start, stop, or position into natural completion.
        if ((2..6).any(it::isNull)) return@use true
        it.getLong(2) < 0 || it.getLong(3) < 0 || it.getLong(4) < 0 ||
            it.getLong(5) < it.getLong(4) || it.getLong(6) < 0
    }

    private fun requireSafe(value: Boolean) { if (!value) throw StorageUnavailable("RECOVERY_STATE_UNAVAILABLE") }
    private fun query(statement: String, vararg args: Any?): Cursor = sql.query(SimpleSQLiteQuery(statement, args))
    private fun number(statement: String, vararg args: Any?): Long = query(statement, *args).use { if (it.moveToFirst()) it.getLong(0) else 0 }
    private fun exec(statement: String, vararg args: Any?) = sql.execSQL(statement, args)
    private fun <T> transaction(action: () -> T): T = try { database.runInTransaction(Callable { action() }) }
        catch (failure: StorageUnavailable) { throw failure }
        catch (_: Exception) { throw StorageUnavailable("RECOVERY_STORAGE_UNAVAILABLE") }
    override fun close() { if (ownsDatabase) database.close() }
}
