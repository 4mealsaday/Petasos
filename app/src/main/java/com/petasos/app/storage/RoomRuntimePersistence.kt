package com.petasos.app.storage

import android.content.Context
import android.database.Cursor
import android.os.SystemClock
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.petasos.app.music.MusicCatalog
import com.petasos.app.music.EmptyMusicCatalog
import com.petasos.app.generation.AnswerGenerationContext
import com.petasos.app.playback.*
import com.petasos.app.runtime.*
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.Callable

/** Worker-thread storage boundary. Plaintext exists only in transient objects and encrypted BLOBs. */
class RoomRuntimePersistence internal constructor(
    internal val database: StorageDatabase,
    private val cipher: PayloadCipher,
    private val wallTime: () -> Long,
    private val elapsedTime: () -> Long,
    private val clockScopeId: String,
    private val musicCatalog: MusicCatalog = EmptyMusicCatalog,
) : RuntimePersistence, AutoCloseable {
    constructor(context: Context, databaseName: String = "petasos.db", musicCatalog: MusicCatalog = EmptyMusicCatalog) : this(
        StorageDatabase.open(context, databaseName), PayloadCipher(AndroidPayloadKeys()),
        System::currentTimeMillis, SystemClock::elapsedRealtime, UUID.randomUUID().toString(), musicCatalog,
    )

    /** Shares this database and cipher without creating keys or owning this connection's lifetime. */
    fun summaryStore(): RoomSummaryStore = RoomSummaryStore(database, cipher, wallTime, ownsDatabase = false)

    fun sessionRecovery(): RoomSessionRecovery = RoomSessionRecovery(database, cipher, wallTime, ownsDatabase = false, openSession = ::openSession)

    fun storyGenerationStore(): RoomStoryGenerationStore = RoomStoryGenerationStore(database, wallTime)

    fun tripLifecycleStore(): RoomTripLifecycleStore = RoomTripLifecycleStore(database, wallTime)

    fun resumeGenerationContext(effect: PlaybackEffect.PlanResume, frozenTurnId: String?, isRuntimeCurrent: () -> Boolean) =
        RoomResumeGenerationContext(database, cipher, wallTime).prepare(effect, frozenTurnId, isRuntimeCurrent)

    override fun prepareMusic(assetId: String): PreparedMusic = musicCatalog.prepare(assetId)
    override fun loadMusic(reference: OutputReference.MusicTrack): RuntimeMusicSource = musicCatalog.resolve(reference.assetId)

    private val content = RoomContentContext(database, cipher, wallTime)

    /** Resolves a currently permitted, complete installed version without starting playback. */
    override fun prepareStory(versionId: String): PreparedStory = content.prepareStory(versionId)

    fun answerContext(request: RuntimeGenerationRequest, isRuntimeCurrent: () -> Boolean): AnswerGenerationContext? =
        content.answerContext(request, isRuntimeCurrent)

    private data class ActiveOutput(val token: OperationToken, val reference: OutputReference)
    private val activeOutputs = mutableMapOf<String, ActiveOutput>()
    private val sql: SupportSQLiteDatabase get() = database.openHelper.writableDatabase

    override fun openSession(tripId: String, sessionId: String) {
        require(tripId.isNotBlank() && sessionId.isNotBlank())
        if (count("SELECT count(*) FROM pending_conversation") == 0L) cipher.provisionNewStore()
        transaction {
            val now = wallTime()
            // A process/session restart never reconstructs completion or silently resumes playback.
            exec("UPDATE playback_attempt SET state='interrupted', interrupted_at=?, interruption_reason='session_recovery' WHERE state IN ('prepared','playing')", now)
            exec("UPDATE conversation_turn SET lifecycle='interrupted', frozen=1, closed_at=? WHERE lifecycle='open'", now)
            exec("UPDATE recording_segment SET status='interrupted', interruption_detected_at=? WHERE status='active'", now)
            exec("UPDATE session SET status='interrupted' WHERE status='active'")
            exec("UPDATE trip SET status='interrupted', interruption_detected_at=? WHERE status='active' AND trip_id<>?", now, tripId)
            val status = scalarString("SELECT status FROM trip WHERE trip_id=?", tripId)
            if (status == null) exec("INSERT INTO trip(trip_id,started_at,status) VALUES (?,?,'active')", tripId, now)
            else {
                requireStorage(status != "closed", "trip_closed")
                exec("UPDATE trip SET status='active', revision=revision+1 WHERE trip_id=?", tripId)
            }
            // IDs are never reused, including an accidentally repeated openSession call.
            exec("INSERT INTO session(session_id,trip_id,started_at,status) VALUES (?,?,?,'active')", sessionId, tripId, now)
        }
        activeOutputs.clear()
    }

    override fun registerTurn(tripId: String, sessionId: String, turnId: String) = transaction {
        requireActiveSession(sessionId)
        requireStorage(scalarString("SELECT trip_id FROM session WHERE session_id=?", sessionId) == tripId, "turn_trip_mismatch")
        val existing = scalarString("SELECT session_id FROM conversation_turn WHERE turn_id=?", turnId)
        if (existing != null) {
            requireStorage(existing == sessionId, "turn_session_mismatch")
        } else exec("INSERT INTO conversation_turn(turn_id,session_id,created_at,lifecycle,summary_state) VALUES (?,?,?,'open','no_text')", turnId, sessionId, wallTime())
    }

    override fun registerOutput(attempt: OperationToken, reference: OutputReference, turnId: String?) {
        requireStorage(activeOutputs.isEmpty(), "output_already_registered")
        transaction {
            requireActiveSession(attempt.sessionId)
            val associatedTurn = when (reference) {
                is OutputReference.AnswerParagraph -> reference.turnId
                is OutputReference.FixedPrompt -> turnId
                is OutputReference.StoryParagraph, is OutputReference.MusicTrack -> null
            }
            if (associatedTurn != null) requireTurn(associatedTurn, attempt.sessionId, requireOpen = reference is OutputReference.AnswerParagraph)
            var paragraph: String? = null
            var transientKey: String? = null
            var asset: String? = null
            val kind = when (reference) {
                is OutputReference.StoryParagraph -> {
                    validateStory(reference.storyId, reference.versionId, reference.paragraphId)
                    paragraph = reference.paragraphId
                    "story"
                }
                is OutputReference.AnswerParagraph -> {
                    requireStorage(reference.payload.id == reference.turnId, "payload_turn_mismatch")
                    val answer = readPayload(reference.turnId).second.responseParagraphs.firstOrNull { it.key == reference.paragraphKey }
                        ?: throw StorageUnavailable("answer_paragraph_missing")
                    content.requireAnswerSources(answer.sourceRevisionIds)
                    transientKey = reference.paragraphKey
                    "answer"
                }
                is OutputReference.MusicTrack -> { musicCatalog.resolve(reference.assetId); asset = reference.assetId; "music" }
                is OutputReference.FixedPrompt -> { asset = reference.assetId; fixedPrompt(asset); "prompt" }
            }
            exec("INSERT INTO playback_attempt(attempt_id,session_id,kind,paragraph_id,turn_id,transient_paragraph_key,asset_id,state,clock_scope_id) VALUES (?,?,?,?,?,?,?,'prepared',?)",
                attempt.id, attempt.sessionId, kind, paragraph, associatedTurn, transientKey, asset, clockScopeId)
        }
        activeOutputs[attempt.id] = ActiveOutput(attempt, reference)
    }

    override fun markOutputStarted(attempt: OperationToken) {
        requireStorage(activeOutputs[attempt.id]?.token == attempt, "unknown_output_attempt")
        transaction {
            val state = scalarString("SELECT state FROM playback_attempt WHERE attempt_id=?", attempt.id)
            if (state == "playing") return@transaction
            requireStorage(state == "prepared", "output_start_after_terminal")
            exec("UPDATE playback_attempt SET state='playing', started_at=?, started_elapsed_ms=? WHERE attempt_id=?", wallTime(), elapsedTime(), attempt.id)
        }
    }

    override fun loadOutput(reference: OutputReference): RuntimeText = transaction { when (reference) {
        is OutputReference.MusicTrack -> throw RuntimeContentUnavailable()
        is OutputReference.FixedPrompt -> RuntimeText(fixedPrompt(reference.assetId))
        is OutputReference.StoryParagraph -> {
            validateStory(reference.storyId, reference.versionId, reference.paragraphId)
            RuntimeText(scalarString("SELECT text FROM paragraph WHERE paragraph_id=?", reference.paragraphId) ?: throw StorageUnavailable("paragraph_missing"))
        }
        is OutputReference.AnswerParagraph -> {
            requireStorage(reference.payload.id == reference.turnId, "payload_turn_mismatch")
            val answer = readPayload(reference.turnId).second.responseParagraphs.firstOrNull { it.key == reference.paragraphKey }
                ?: throw StorageUnavailable("answer_paragraph_missing")
            content.requireAnswerSources(answer.sourceRevisionIds)
            answer.text
        }
    } }

    override fun loadUtterance(payload: PayloadHandle): RuntimeText = transaction { RuntimeText(readPayload(payload.id).second.userFinalText) }

    override fun persistUtterance(commit: OperationToken, turnId: String, text: RuntimeText): PayloadHandle = transaction {
        requireActiveSession(commit.sessionId)
        requireTurn(turnId, commit.sessionId, requireOpen = true)
        requireStorage(text.value.length <= 4_000, "utterance_too_large")
        if (count("SELECT count(*) FROM pending_conversation WHERE turn_id=?", turnId) != 0L) {
            requireStorage(readPayload(turnId).second.userFinalText == text.value, "different_final_for_turn")
            return@transaction PayloadHandle(turnId)
        }
        requireStorage(count("SELECT count(*) FROM conversation_turn WHERE turn_id=? AND utterance_finalized_at IS NULL", turnId) == 1L, "final_payload_missing")
        val version = count("SELECT turn_version FROM conversation_turn WHERE turn_id=?", turnId)
        writePayload(turnId, commit.sessionId, version, ConversationPayload(text.value), isNew = true)
        exec("UPDATE conversation_turn SET utterance_finalized_at=?, summary_state='pending' WHERE turn_id=?", wallTime(), turnId)
        PayloadHandle(turnId)
    }

    override fun persistAnswer(commit: OperationToken, turnId: String, answer: RuntimeAnswer): ValidatedAnswer = transaction {
        requireActiveSession(commit.sessionId)
        requireTurn(turnId, commit.sessionId, requireOpen = true)
        requireStorage(count("SELECT cancel_epoch FROM session WHERE session_id=?", commit.sessionId) == commit.cancelEpoch, "stale_answer_epoch")
        answer.paragraphs.forEach { paragraph ->
            requireStorage(paragraph.text.value.length <= 240, "answer_paragraph_too_large")
            content.requireAnswerSources(paragraph.sourceRevisionIds)
        }
        val (version, existing) = readPayload(turnId)
        requireStorage(existing.responseParagraphs.isEmpty(), "answer_already_persisted")
        writePayload(turnId, commit.sessionId, version + 1, existing.withAnswer(answer), isNew = false)
        exec("UPDATE conversation_turn SET turn_version=? WHERE turn_id=?", version + 1, turnId)
        ValidatedAnswer(PayloadHandle(turnId), answer.paragraphs.map { it.key })
    }

    override fun commitPlayback(effect: PlaybackEffect.CommitPlayback, snapshot: PlaybackSnapshot) {
        val active = activeOutputs[effect.attempt.id]
        requireStorage(active != null && active.token == effect.attempt && active.reference == effect.reference, "unregistered_playback_commit")
        requireStorage(snapshot.sessionId == effect.attempt.sessionId, "playback_session_mismatch")
        transaction {
            val oldState = scalarString("SELECT state FROM playback_attempt WHERE attempt_id=?", effect.attempt.id)
            requireStorage(oldState in setOf("prepared", "playing"), "terminal_playback_immutable")
            val completed = effect.outcome == AttemptOutcome.COMPLETED
            if (completed) requireStorage(oldState == "playing", "completion_without_start")
            if (completed) exec("UPDATE playback_attempt SET state='completed', completed_at=?, ended_elapsed_ms=? WHERE attempt_id=?", wallTime(), elapsedTime(), effect.attempt.id)
            else exec("UPDATE playback_attempt SET state='interrupted', interrupted_at=?, ended_elapsed_ms=?, interruption_reason='coordinator_interrupt' WHERE attempt_id=?", wallTime(), elapsedTime(), effect.attempt.id)
            val answer = effect.reference as? OutputReference.AnswerParagraph
            if (answer != null) {
                requireTurn(answer.turnId, effect.attempt.sessionId, requireOpen = true)
                val (version, payload) = readPayload(answer.turnId)
                writePayload(answer.turnId, effect.attempt.sessionId, version + 1, payload.withPlayback(answer.paragraphKey, completed), isNew = false)
                exec("UPDATE conversation_turn SET turn_version=? WHERE turn_id=?", version + 1, answer.turnId)
            }
            if (effect.reference is OutputReference.MusicTrack) {
                val position = effect.outputPositionMs ?: throw StorageUnavailable("music_position_missing")
                requireStorage(position >= 0, "music_position_invalid")
                exec("UPDATE playback_attempt SET position_ms=? WHERE attempt_id=?", position, effect.attempt.id)
            }
            applyCheckpoint(snapshot, effect.checkpoint, effect.musicCheckpoint)
            if (completed && effect.reference is OutputReference.StoryParagraph) collectStory(effect.reference)
        }
        activeOutputs.remove(effect.attempt.id)
    }

    override fun freezeTurn(effect: PlaybackEffect.FreezeTurn) = transaction {
        val existing = count("SELECT count(*) FROM conversation_turn WHERE turn_id=? AND frozen=1", effect.turnId)
        if (existing == 1L) return@transaction
        requireStorage(count("SELECT count(*) FROM conversation_turn WHERE turn_id=?", effect.turnId) == 1L, "turn_missing")
        val hasPayload = count("SELECT count(*) FROM pending_conversation WHERE turn_id=?", effect.turnId) == 1L
        requireStorage(hasPayload == effect.hasCommittedUtterance, "turn_payload_mismatch")
        if (hasPayload) {
            val payload = readPayload(effect.turnId).second
            requireStorage(payload.playedParagraphKeys == effect.completedAnswerKeys.toSet(), "turn_playback_mismatch")
        }
        exec("UPDATE conversation_turn SET lifecycle=?, frozen=1, closed_at=? WHERE turn_id=?",
            effect.outcome.name.lowercase(), wallTime(), effect.turnId)
    }

    override fun saveCheckpoint(snapshot: PlaybackSnapshot) = transaction { applyCheckpoint(snapshot, snapshot.checkpoint) }

    override fun closeSession(sessionId: String) = transaction {
        requireStorage(activeOutputs.isEmpty(), "session_has_registered_output")
        requireStorage(count("SELECT count(*) FROM recording_segment WHERE session_id=? AND status='active'", sessionId) == 0L, "session_has_active_segment")
        requireStorage(count("SELECT count(*) FROM playback_attempt WHERE session_id=? AND state IN ('prepared','playing')", sessionId) == 0L, "session_has_active_attempt")
        requireStorage(count("SELECT count(*) FROM conversation_turn WHERE session_id=? AND (lifecycle='open' OR frozen<>1)", sessionId) == 0L, "session_has_open_turn")
        val state = scalarString("SELECT status FROM session WHERE session_id=?", sessionId)
        requireStorage(state in setOf("active", "closed"), "session_not_closable")
        if (state == "closed") {
            requireStorage(count("SELECT count(*) FROM session WHERE session_id=? AND closed_at IS NOT NULL", sessionId) == 1L, "session_close_time_missing")
        } else {
            val now = wallTime()
            requireStorage(now >= 0 && count("SELECT started_at FROM session WHERE session_id=?", sessionId) <= now, "session_close_clock_invalid")
            exec("UPDATE session SET status='closed', closed_at=? WHERE session_id=? AND status='active'", now, sessionId)
        }
    }

    private fun applyCheckpoint(snapshot: PlaybackSnapshot, checkpoint: StoryCheckpoint?, music: MusicCheckpoint? = snapshot.musicCheckpoint) {
        requireStorage(scalarString("SELECT trip_id FROM session WHERE session_id=?", snapshot.sessionId) == snapshot.tripId, "checkpoint_session_mismatch")
        requireStorage(count("SELECT state_revision FROM session WHERE session_id=?", snapshot.sessionId) <= snapshot.stateRevision, "stale_state_revision")
        var next: String? = null
        var last: String? = null
        if (checkpoint != null) {
            // Stopping/closing must persist history even when content permission was just revoked.
            checkpoint.story.paragraphIds.forEach { validateStoryRelationship(checkpoint.story.storyId, checkpoint.story.versionId, it) }
            if (checkpoint.hasNext) next = checkpoint.story.paragraphIds[checkpoint.paragraphIndex]
            if (checkpoint.paragraphIndex > 0) last = checkpoint.story.paragraphIds[checkpoint.paragraphIndex - 1]
        }
        val args = arrayOf<Any?>(snapshot.sessionId, snapshot.stateRevision, if (music != null) "music" else if (next == null) "idle" else "story", next, last, music?.track?.assetId, music?.positionMs,
            if (snapshot.playIntent) 1 else 0, JSONArray(snapshot.blockers.map { it.name.lowercase() }.sorted()).toString(), wallTime(), snapshot.tripId)
        if (count("SELECT count(*) FROM playback_checkpoint WHERE trip_id=?", snapshot.tripId) == 0L) {
            exec("INSERT INTO playback_checkpoint(session_id,state_revision,mode,paragraph_id,last_completed_paragraph_id,asset_id,music_position_ms,play_intent,blockers_json,updated_at,trip_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)", *args)
        } else exec("UPDATE playback_checkpoint SET session_id=?, state_revision=?, mode=?, paragraph_id=?, last_completed_paragraph_id=?, asset_id=?, music_position_ms=?, play_intent=?, blockers_json=?, updated_at=? WHERE trip_id=?", *args)
        exec("UPDATE session SET state_revision=?, cancel_epoch=? WHERE session_id=?", snapshot.stateRevision, snapshot.cancelEpoch, snapshot.sessionId)
    }

    private fun collectStory(reference: OutputReference.StoryParagraph) {
        if (count("SELECT count(*) FROM collection_record WHERE story_id=? AND collection_type='story'", reference.storyId) > 0) return
        if (count("SELECT count(*) FROM paragraph WHERE story_version_id=? AND required=1", reference.versionId) == 0L) return
        if (count("SELECT count(*) FROM paragraph p WHERE p.story_version_id=? AND p.required=1 AND NOT EXISTS (SELECT 1 FROM playback_attempt a WHERE a.paragraph_id=p.paragraph_id AND a.state='completed')", reference.versionId) != 0L) return
        val collectionId = UUID.randomUUID().toString()
        val now = wallTime()
        exec("INSERT INTO collection_record(collection_id,collection_type,story_id,story_version_id,rule_version,earned_at,created_at) VALUES (?,'story',?,?,'0.8',?,?)", collectionId, reference.storyId, reference.versionId, now, now)
        query("SELECT p.paragraph_id, min(a.attempt_id) AS attempt_id FROM paragraph p JOIN playback_attempt a ON a.paragraph_id=p.paragraph_id AND a.state='completed' WHERE p.story_version_id=? AND p.required=1 GROUP BY p.paragraph_id", reference.versionId).use { rows ->
            while (rows.moveToNext()) exec("INSERT INTO collection_proof(collection_id,proof_key,attempt_id) VALUES (?,?,?)", collectionId, rows.getString(0), rows.getString(1))
        }
    }

    private fun validateStory(storyId: String, versionId: String, paragraphId: String) {
        content.requireStory(OutputReference.StoryParagraph(storyId, versionId, paragraphId))
    }

    private fun validateStoryRelationship(storyId: String, versionId: String, paragraphId: String) {
        requireStorage(count("SELECT count(*) FROM paragraph p JOIN story_version v ON v.story_version_id=p.story_version_id WHERE p.paragraph_id=? AND v.story_version_id=? AND v.story_id=?", paragraphId, versionId, storyId) == 1L, "story_reference_unavailable")
    }

    private fun requireActiveSession(sessionId: String) {
        requireStorage(scalarString("SELECT status FROM session WHERE session_id=?", sessionId) == "active", "session_not_active")
    }

    private fun requireTurn(turnId: String, sessionId: String, requireOpen: Boolean) {
        val condition = if (requireOpen) " AND lifecycle='open' AND frozen=0" else ""
        requireStorage(count("SELECT count(*) FROM conversation_turn WHERE turn_id=? AND session_id=?$condition", turnId, sessionId) == 1L, "turn_not_available")
    }

    private fun writePayload(turnId: String, sessionId: String, version: Long, payload: ConversationPayload, isNew: Boolean) {
        val plaintext = payload.encode()
        val encrypted = try { cipher.encrypt(PayloadIdentity(sessionId, turnId, version), plaintext) } finally { plaintext.fill(0) }
        val now = wallTime()
        if (isNew) exec("INSERT INTO pending_conversation(turn_id,payload_version,ciphertext,nonce,key_version,payload_hash,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)", turnId, version, encrypted.ciphertext, encrypted.nonce, encrypted.keyVersion, encrypted.payloadHash, now, now)
        else exec("UPDATE pending_conversation SET payload_version=?, ciphertext=?, nonce=?, key_version=?, payload_hash=?, updated_at=? WHERE turn_id=?", version, encrypted.ciphertext, encrypted.nonce, encrypted.keyVersion, encrypted.payloadHash, now, turnId)
    }

    private fun readPayload(turnId: String): Pair<Long, ConversationPayload> {
        try {
            query("SELECT t.session_id,t.turn_version,p.payload_version,p.ciphertext,p.nonce,p.key_version,p.payload_hash FROM conversation_turn t JOIN pending_conversation p ON p.turn_id=t.turn_id WHERE t.turn_id=?", turnId).use { row ->
                requireStorage(row.moveToFirst(), "payload_missing")
                val version = row.getLong(1)
                requireStorage(version == row.getLong(2), "payload_version_mismatch")
                val bytes = cipher.decrypt(PayloadIdentity(row.getString(0), turnId, version), EncryptedPayload(row.getBlob(3), row.getBlob(4), row.getString(5), row.getString(6)))
                return try { version to ConversationPayload.decode(bytes) } finally { bytes.fill(0) }
            }
        } catch (failure: StorageUnavailable) {
            // If called inside a transaction this update rolls back with that transaction; the outer
            // transaction boundary records the block after rollback, preserving the encrypted bytes.
            markPayloadBlocked(turnId, failure.code)
            throw failure
        } catch (_: Exception) {
            markPayloadBlocked(turnId, "payload_decode_failed")
            throw StorageUnavailable("payload_decode_failed")
        }
    }

    private var pendingCryptoBlock: Pair<String, String>? = null
    private fun markPayloadBlocked(turnId: String, code: String) {
        if (database.inTransaction()) pendingCryptoBlock = turnId to code
        else exec("UPDATE conversation_turn SET summary_state='blocked',last_error_code=? WHERE turn_id=? AND utterance_finalized_at IS NOT NULL AND summary_state<>'saved'", code, turnId)
    }

    private fun <T> transaction(block: () -> T): T = try {
        database.runInTransaction(Callable { block() })
    } finally {
        pendingCryptoBlock?.also { (turn, code) ->
            pendingCryptoBlock = null
            markPayloadBlocked(turn, code)
        }
    }

    private fun fixedPrompt(assetId: String): String = when (assetId) {
        "talk-prompt" -> "말씀해 주세요."
        "generation-failed", "generation-unavailable" -> "지금은 답변을 준비할 수 없습니다."
        "command-music-unavailable" -> "재생할 수 있는 음악이 준비되어 있지 않습니다."
        "command-replay-unavailable" -> "다시 재생할 콘텐츠가 없습니다."
        else -> throw RuntimeContentUnavailable()
    }

    private fun exec(statement: String, vararg args: Any?) { sql.execSQL(statement, args) }
    private fun query(statement: String, vararg args: Any?): Cursor = sql.query(SimpleSQLiteQuery(statement, args))
    private fun count(statement: String, vararg args: Any?): Long = query(statement, *args).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    private fun scalarString(statement: String, vararg args: Any?): String? = query(statement, *args).use { if (it.moveToFirst()) it.getString(0) else null }
    private fun requireStorage(condition: Boolean, code: String) { if (!condition) throw StorageUnavailable(code) }
    override fun close() { activeOutputs.clear(); database.close() }
}
