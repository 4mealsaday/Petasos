package com.petasos.app.storage

import android.app.Instrumentation
import android.database.sqlite.SQLiteConstraintException
import com.petasos.app.playback.*
import com.petasos.app.runtime.RuntimeText
import java.util.UUID

/** Real Room/Keystore lifecycle transactions using synthetic records; no SDK audio, GPS or network. */
object TripLifecycleProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        liveEnd(instrumentation, mark)
        recoveredEnd(instrumentation, mark)
    }

    private fun liveEnd(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "trip-lifecycle-${UUID.randomUUID()}.db"
        var now = 100L
        try {
            SyntheticStorageFixture.create(context, name, wallTime = { now }).use { store ->
                val story = SyntheticStorageFixture.install(store)
                val sql = store.database.openHelper.writableDatabase
                fun exec(statement: String, vararg args: Any?) = sql.execSQL(statement, args)
                fun number(statement: String): Long = sql.query(statement).use { check(it.moveToFirst()); it.getLong(0) }
                fun text(statement: String): String? = sql.query(statement).use { check(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0) }
                val lifecycle = store.tripLifecycleStore()

                // Retain a previous resumable trip to exercise latest-trip selection after user end.
                store.openSession("older-trip", "older-session")
                store.closeSession("older-session")
                now = 200
                store.openSession("trip", "session")
                val snapshot = PlaybackSnapshot("trip", "session", PlaybackMode.PAUSED, 1, 0, true, false,
                    emptySet(), AdapterPhase.READY, AdapterPhase.READY, false, null, StoryCheckpoint(story, 0), 0, false)
                store.saveCheckpoint(snapshot)
                reject { lifecycle.endTrip("trip", "session") }
                check(text("SELECT status FROM trip WHERE trip_id='trip'") == "active")
                check(text("SELECT ended_at FROM trip WHERE trip_id='trip'") == null)
                mark("trip_media_pause_does_not_end_trip_and_active_session_rejects_end")

                exec("INSERT INTO recording_segment(segment_id,trip_id,session_id,started_at,status) VALUES ('segment','trip','session',200,'active')")
                reject { store.closeSession("session") }
                check(text("SELECT status FROM session WHERE session_id='session'") == "active")
                exec("UPDATE recording_segment SET status='closed',ended_at=210,end_reason='explicit_stop' WHERE segment_id='segment'")
                now = 220
                store.registerTurn("trip", "session", "turn")
                store.persistUtterance(OperationToken("session", 0, "utterance"), "turn", RuntimeText("합성 이동 종료 검증입니다."))
                reject { store.closeSession("session") }
                store.freezeTurn(PlaybackEffect.FreezeTurn("turn", TurnOutcome.CANCELLED, true, emptyList()))
                val attempt = OperationToken("session", 0, "attempt")
                val reference = OutputReference.StoryParagraph(story.storyId, story.versionId, story.paragraphIds.first())
                store.registerOutput(attempt, reference, null)
                store.markOutputStarted(attempt)
                reject { store.closeSession("session") }
                store.commitPlayback(PlaybackEffect.CommitPlayback(attempt, reference, AttemptOutcome.INTERRUPTED, snapshot.checkpoint), snapshot)
                check(number("SELECT count(*) FROM playback_attempt WHERE state='completed'") == 0L)
                check(number("SELECT count(*) FROM collection_record") == 0L)
                mark("trip_session_close_requires_segment_turn_and_attempt_cleanup_without_completion")

                now = 300
                exec("CREATE TRIGGER close_session_failure BEFORE UPDATE OF status ON session WHEN NEW.status='closed' BEGIN SELECT RAISE(ABORT,'injected'); END")
                try { store.closeSession("session"); error("TRIP_SESSION_WRITE_FAILURE_MISSING") }
                catch (_: SQLiteConstraintException) { /* Expected injected SQL abort, not a state-policy rejection. */ }
                check(text("SELECT status FROM session WHERE session_id='session'") == "active")
                check(text("SELECT closed_at FROM session WHERE session_id='session'") == null)
                exec("DROP TRIGGER close_session_failure")
                store.closeSession("session")
                now = 350
                store.closeSession("session")
                check(number("SELECT closed_at FROM session WHERE session_id='session'") == 300L)
                check(text("SELECT status FROM trip WHERE trip_id='trip'") == "active")
                check(text("SELECT ended_at FROM trip WHERE trip_id='trip'") == null)
                mark("trip_session_close_failure_retries_and_success_preserves_resumable_trip")

                exec("INSERT INTO generation_request(request_id,session_id,task,input_hash,schema_version,cancel_epoch,created_at,status,prompt_version) VALUES ('resume-request','session','resume','synthetic','0.8',0,220,'running','test')")
                exec("INSERT INTO generation_request(request_id,session_id,turn_id,task,input_hash,schema_version,cancel_epoch,turn_version,created_at,status,prompt_version) VALUES ('summary-request','session','turn','summary','synthetic','0.8',0,2,220,'running','test')")
                val revision = number("SELECT revision FROM trip WHERE trip_id='trip'")
                exec("CREATE TRIGGER close_trip_failure BEFORE UPDATE OF status ON trip WHEN NEW.status='closed' BEGIN SELECT RAISE(ABORT,'injected'); END")
                reject { lifecycle.endTrip("trip", "session", revision) }
                check(text("SELECT status FROM trip WHERE trip_id='trip'") == "active")
                check(text("SELECT status FROM generation_request WHERE request_id='resume-request'") == "running")
                check(number("SELECT play_intent FROM playback_checkpoint WHERE trip_id='trip'") == 1L)
                exec("DROP TRIGGER close_trip_failure")
                val ended = lifecycle.endTrip("trip", "session", revision)
                check(ended.tripId == "trip" && ended.endedAt == 350L && ended.revision == revision + 1)
                check(text("SELECT status FROM trip WHERE trip_id='trip'") == "closed")
                check(text("SELECT end_reason FROM trip WHERE trip_id='trip'") == "user_end")
                check(text("SELECT status FROM generation_request WHERE request_id='resume-request'") == "cancelled")
                check(text("SELECT status FROM generation_request WHERE request_id='summary-request'") == "running")
                check(number("SELECT play_intent FROM playback_checkpoint WHERE trip_id='trip'") == 0L)
                check(text("SELECT paragraph_id FROM playback_checkpoint WHERE trip_id='trip'") == story.paragraphIds.first())
                mark("trip_user_end_is_atomic_preserves_summary_and_history_and_clears_play_intent")

                now = 500
                val repeated = lifecycle.endTrip("trip", "session", revision)
                check(repeated.endedAt == ended.endedAt && repeated.revision == ended.revision)
                check(text("SELECT status FROM trip WHERE trip_id='older-trip'") == "interrupted")
                store.sessionRecovery().use { check(it.currentTripCandidate() == null) }
                reject { store.openSession("trip", "forbidden-session") }
                store.openSession("new-trip", "new-session")
                check(number("SELECT count(*) FROM trip") == 3L)
                check(text("SELECT trip_id FROM session WHERE status='active'") == "new-trip")
                check(number("SELECT count(*) FROM session WHERE session_id='forbidden-session'") == 0L)
                check(text("SELECT status FROM trip WHERE trip_id='trip'") == "closed")
                mark("trip_repeat_end_idempotent_latest_closed_hides_older_trip_and_next_session_uses_new_trip")
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }
    }

    private fun recoveredEnd(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "trip-recovered-end-${UUID.randomUUID()}.db"
        var now = 100L
        try {
            SyntheticStorageFixture.create(context, name, wallTime = { now }).use { store ->
                val sql = store.database.openHelper.writableDatabase
                fun number(statement: String): Long = sql.query(statement).use { check(it.moveToFirst()); it.getLong(0) }
                store.openSession("trip", "interrupted-session")
                sql.execSQL("INSERT INTO recording_segment(segment_id,trip_id,session_id,started_at,last_observed_at,status) VALUES ('segment','trip','interrupted-session',100,120,'active')")
                now = 1_000
                store.sessionRecovery().use { recovery ->
                    val candidate = checkNotNull(recovery.recoverOnAppStart())
                    val lifecycle = store.tripLifecycleStore()
                    reject { lifecycle.endTrip(candidate.tripId, "incorrect-session", candidate.tripRevision) }
                    sql.execSQL("UPDATE trip SET revision=revision+1 WHERE trip_id='trip'")
                    reject { lifecycle.endTrip(candidate.tripId, candidate.previousSessionId, candidate.tripRevision) }
                    val current = checkNotNull(recovery.currentTripCandidate())
                    now = 2_000
                    lifecycle.endTrip(current.tripId, current.previousSessionId, current.tripRevision)
                    check(number("SELECT count(*) FROM session") == 1L)
                    check(number("SELECT count(*) FROM session WHERE status='interrupted' AND closed_at IS NULL") == 1L)
                    check(number("SELECT count(*) FROM recording_segment WHERE status='interrupted' AND ended_at IS NULL AND last_observed_at=120 AND interruption_detected_at=1000") == 1L)
                    check(number("SELECT ended_at FROM trip WHERE trip_id='trip'") == 2_000L)
                    check(recovery.currentTripCandidate() == null)
                    mark("trip_recovered_end_checks_session_revision_preserves_unknown_history_and_creates_no_session")

                    now = 3_000
                    store.openSession("resumed-trip", "previous-session")
                    store.closeSession("previous-session")
                    val previous = checkNotNull(recovery.currentTripCandidate())
                    recovery.openResumedSession(previous, "latest-session")
                    store.closeSession("latest-session")
                    reject { lifecycle.endTrip(previous.tripId, previous.previousSessionId) }
                    check(number("SELECT count(*) FROM trip WHERE trip_id='resumed-trip' AND status='active'") == 1L)
                    val latest = checkNotNull(recovery.currentTripCandidate())
                    lifecycle.endTrip(latest.tripId, latest.previousSessionId, latest.tripRevision)
                    check(recovery.currentTripCandidate() == null)
                    mark("trip_old_session_selection_cannot_close_a_later_resumed_session")
                }
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }
    }

    private fun reject(action: () -> Unit) {
        try { action(); error("TRIP_LIFECYCLE_REJECTION_MISSING") } catch (_: StorageUnavailable) { }
    }
}
