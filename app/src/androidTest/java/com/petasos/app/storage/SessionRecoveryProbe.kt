package com.petasos.app.storage

import android.app.Instrumentation
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.playback.*
import com.petasos.app.runtime.*
import com.petasos.app.summary.SummaryClaim
import java.util.UUID

/** Real TX-04/Room/Keystore; synthetic data, no audio, location request, HTTP or model. */
object SessionRecoveryProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "session-recovery-${UUID.randomUUID()}.db"
        var now = 100L
        try {
            SyntheticStorageFixture.create(context, name, wallTime = { now }).use { store ->
                val story = SyntheticStorageFixture.install(store)
                val sql = store.database.openHelper.writableDatabase
                fun exec(statement: String, vararg args: Any?) = sql.execSQL(statement, args)
                fun number(statement: String): Long = sql.query(statement).use { check(it.moveToFirst()); it.getLong(0) }
                fun text(statement: String): String? = sql.query(statement).use { check(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0) }
                fun ciphertext(): ByteArray = sql.query("SELECT ciphertext FROM pending_conversation WHERE turn_id='turn'").use { check(it.moveToFirst()); it.getBlob(0) }
                fun reject(action: () -> Unit) {
                    try { action(); error("RECOVERY_REJECTION_MISSING") } catch (_: StorageUnavailable) { }
                }
                store.openSession("trip", "old-session")
                exec("INSERT INTO recording_segment(segment_id,trip_id,session_id,started_at,last_observed_at,status) VALUES ('old-segment','trip','old-session',100,120,'active')")
                exec("UPDATE trip SET last_observed_at=120")
                val snapshot = PlaybackSnapshot("trip", "old-session", PlaybackMode.STORY, 1, 0, true, false,
                    emptySet(), AdapterPhase.READY, AdapterPhase.READY, false, null, StoryCheckpoint(story, 1), 0, false)
                val first = OperationToken("old-session", 0, "completed-story")
                val firstRef = OutputReference.StoryParagraph(story.storyId, story.versionId, "test-p1")
                store.registerOutput(first, firstRef, null)
                store.markOutputStarted(first)
                store.commitPlayback(PlaybackEffect.CommitPlayback(first, firstRef, AttemptOutcome.COMPLETED, StoryCheckpoint(story, 1)), snapshot)
                store.registerTurn("trip", "old-session", "turn")
                val handle = store.persistUtterance(OperationToken("old-session", 0, "utterance"), "turn", RuntimeText("합성 복구 시험 발화입니다."))
                store.persistAnswer(OperationToken("old-session", 0, "answer"), "turn", RuntimeAnswer(listOf(
                    RuntimeParagraph("answer-key", RuntimeText("합성 복구 시험 응답입니다."), listOf("test-source-r1")),
                )))
                val active = OperationToken("old-session", 0, "unfinished-answer")
                val reference = OutputReference.AnswerParagraph("turn", handle, "answer-key")
                store.registerOutput(active, reference, "turn")
                store.markOutputStarted(active)
                exec("INSERT INTO generation_request(request_id,session_id,turn_id,task,input_hash,schema_version,cancel_epoch,turn_version,created_at,status,prompt_version) VALUES ('old-request','old-session','turn','answer','synthetic','0.8',0,2,100,'running','test')")
                val originalCiphertext = ciphertext()
                now = 1_000
                store.sessionRecovery().use { recovery ->
                    check(recovery.currentTripCandidate() == null)
                    exec("CREATE TRIGGER recovery_fail BEFORE UPDATE ON trip BEGIN SELECT RAISE(ABORT,'injected'); END")
                    reject { recovery.recoverOnAppStart() }
                    check(number("SELECT count(*) FROM session WHERE status='active'") == 1L)
                    check(number("SELECT count(*) FROM playback_attempt WHERE state='playing'") == 1L)
                    check(number("SELECT turn_version FROM conversation_turn WHERE turn_id='turn'") == 2L)
                    check(originalCiphertext.contentEquals(ciphertext()))
                    exec("DROP TRIGGER recovery_fail")
                    mark("recovery_tx04_failure_rolls_back_payload_and_lifecycle")

                    val candidate = checkNotNull(recovery.recoverOnAppStart())
                    check(candidate.tripId == "trip" && candidate.previousSessionId == "old-session")
                    check(candidate.checkpoint == RecoveryCheckpoint.Story("test-story", "test-story-v1", "test-p2"))
                    check(candidate.previousPlayIntent)
                    check(number("SELECT count(*) FROM session") == 1L)
                    check(number("SELECT count(*) FROM session WHERE status='active'") == 0L)
                    check(number("SELECT count(*) FROM recording_segment WHERE status='active'") == 0L)
                    check(number("SELECT count(*) FROM playback_attempt WHERE state='completed'") == 1L)
                    check(number("SELECT count(*) FROM playback_attempt WHERE state='interrupted'") == 1L)
                    check(number("SELECT count(*) FROM collection_record") == 0L)
                    check(text("SELECT closed_at FROM session WHERE session_id='old-session'") == null)
                    check(text("SELECT ended_at FROM recording_segment WHERE segment_id='old-segment'") == null)
                    check(number("SELECT last_observed_at FROM recording_segment WHERE segment_id='old-segment'") == 120L)
                    check(number("SELECT interruption_detected_at FROM recording_segment WHERE segment_id='old-segment'") == 1_000L)
                    check(number("SELECT cancel_epoch FROM session WHERE session_id='old-session'") == 1L)
                    check(text("SELECT status FROM generation_request WHERE request_id='old-request'") == "cancelled")
                    check(number("SELECT frozen FROM conversation_turn WHERE turn_id='turn'") == 1L)
                    check(number("SELECT turn_version FROM conversation_turn WHERE turn_id='turn'") == 3L)
                    check(!originalCiphertext.contentEquals(ciphertext()))
                    val claimed = store.summaryStore().claim("turn") as SummaryClaim.Claimed
                    check(claimed.work.input.completedResponses.isEmpty())
                    check(claimed.work.input.partialUnknownKeys == listOf("answer-key"))
                    mark("recovery_freezes_stale_work_preserves_unknown_end_and_partial_response_without_autostart")

                    val recoveredCiphertext = ciphertext()
                    now = 2_000
                    check(recovery.recoverOnAppStart() == candidate)
                    check(ciphertext().contentEquals(recoveredCiphertext))
                    check(number("SELECT interruption_detected_at FROM recording_segment WHERE segment_id='old-segment'") == 1_000L)
                    reject { store.persistAnswer(OperationToken("old-session", 0, "late"), "turn", RuntimeAnswer(listOf(
                        RuntimeParagraph("late", RuntimeText("늦은 합성 응답입니다."), listOf("test-source-r1")),
                    ))) }
                    mark("recovery_repeated_scan_idempotent_late_session_answer_rejected")

                    exec("UPDATE source_evidence SET review_status='draft' WHERE source_revision_id='test-source-r1'")
                    check(recovery.currentTripCandidate() == candidate)
                    try { store.prepareStory("test-story-v1"); error("REVOKED_RECOVERY_CONTENT_ACCEPTED") }
                    catch (_: RuntimeContentUnavailable) { }
                    exec("UPDATE source_evidence SET review_status='verified' WHERE source_revision_id='test-source-r1'")
                    exec("UPDATE playback_checkpoint SET state_revision=2 WHERE trip_id='trip'")
                    reject { recovery.openResumedSession(candidate, "new-session", "new-segment") }
                    check(number("SELECT count(*) FROM session") == 1L)
                    val fresh = checkNotNull(recovery.currentTripCandidate())
                    exec("CREATE TRIGGER segment_fail BEFORE INSERT ON recording_segment BEGIN SELECT RAISE(ABORT,'injected'); END")
                    reject { recovery.openResumedSession(fresh, "new-session", "new-segment") }
                    check(number("SELECT count(*) FROM session") == 1L)
                    check(text("SELECT status FROM trip WHERE trip_id='trip'") == "interrupted")
                    exec("DROP TRIGGER segment_fail")
                    recovery.openResumedSession(fresh, "new-session", "new-segment")
                    check(number("SELECT count(*) FROM trip") == 1L)
                    check(number("SELECT count(*) FROM session") == 2L)
                    check(number("SELECT count(*) FROM session WHERE status='active'") == 1L)
                    check(number("SELECT count(*) FROM recording_segment WHERE status='active'") == 1L)
                    check(recovery.currentTripCandidate() == null)
                    reject { recovery.openResumedSession(fresh, "another-session", "another-segment") }
                    mark("recovery_explicit_resume_revalidates_candidate_and_atomically_creates_new_generation_and_opted_segment")

                    val next = checkNotNull(recovery.recoverOnAppStart())
                    recovery.openResumedSession(next, "listening-only-session")
                    check(number("SELECT count(*) FROM recording_segment WHERE status='active'") == 0L)
                    exec("UPDATE playback_checkpoint SET mode='music',paragraph_id=NULL,asset_id='test-music',music_position_ms=42000 WHERE trip_id='trip'")
                    val music = checkNotNull(recovery.recoverOnAppStart())
                    check(music.checkpoint == RecoveryCheckpoint.Music("test-music", 42_000))
                    mark("recovery_recording_requires_opt_in_and_music_position_remains_reference_only")
                }
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }
        keyLoss(instrumentation, mark)
    }

    private fun keyLoss(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "session-recovery-key-${UUID.randomUUID()}.db"
        try {
            SyntheticStorageFixture.create(context, name).use { store ->
                SyntheticStorageFixture.install(store)
                store.openSession("trip", "session")
                store.registerTurn("trip", "session", "turn")
                val payload = store.persistUtterance(OperationToken("session", 0, "final"), "turn", RuntimeText("합성 키 소실 복구 발화입니다."))
                store.persistAnswer(OperationToken("session", 0, "answer"), "turn", RuntimeAnswer(listOf(
                    RuntimeParagraph("key", RuntimeText("합성 키 소실 복구 응답입니다."), listOf("test-source-r1")),
                )))
                store.registerOutput(OperationToken("session", 0, "prepared"), OutputReference.AnswerParagraph("turn", payload, "key"), "turn")
                val sql = store.database.openHelper.writableDatabase
                fun bytes() = sql.query("SELECT ciphertext FROM pending_conversation WHERE turn_id='turn'").use { check(it.moveToFirst()); it.getBlob(0) }
                val before = bytes()
                SyntheticStorageFixture.deleteEncryptionKey(name)
                check(store.sessionRecovery().recoverOnAppStart() != null)
                check(before.contentEquals(bytes()))
                sql.query(SimpleSQLiteQuery("SELECT frozen,summary_state,last_error_code,turn_version FROM conversation_turn WHERE turn_id='turn'")).use {
                    check(it.moveToFirst() && it.getLong(0) == 1L && it.getString(1) == "blocked" &&
                        it.getString(2) == "RECOVERY_PAYLOAD_UNAVAILABLE" && it.getLong(3) == 2L)
                }
                mark("recovery_key_loss_preserves_ciphertext_and_blocks_summary_without_new_keys")
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }
    }
}
