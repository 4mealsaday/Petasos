package com.petasos.app.storage

import android.app.Instrumentation
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.generation.StrictJson
import com.petasos.app.records.*
import com.petasos.app.summary.SummaryClaim
import com.petasos.app.summary.ValidatedSummaryResult
import java.util.UUID

/** Disposable Room/Keystore only. No actual location, microphone, output, HTTP, or model call. */
object TripResultsProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "trip-results-${UUID.randomUUID()}.db"
        var now = 100L
        try {
            RoomTripResultsStore(context, name).use { empty ->
                check(empty.listTrips().isEmpty() && empty.latestTrip() == null && empty.readTrip("absent") == null)
                check(!context.getDatabasePath(name).exists())
            }
            mark("trip_results_empty_read_does_not_create_database_or_history")
            SyntheticStorageFixture.create(context, name, wallTime = { now }).use { store ->
                SyntheticStorageFixture.install(store)
                val sql = store.database.openHelper.writableDatabase
                fun exec(statement: String, vararg args: Any?) = sql.execSQL(statement, args)
                fun number(statement: String, vararg args: Any?): Long = sql.query(SimpleSQLiteQuery(statement, args)).use {
                    check(it.moveToFirst()); it.getLong(0)
                }
                fun attempt(id: String, session: String, kind: String, state: String, paragraph: String? = null) {
                    exec("""INSERT INTO playback_attempt(attempt_id,session_id,kind,paragraph_id,asset_id,state,
                        started_at,completed_at,interrupted_at,clock_scope_id) VALUES (?,?,?,?,?,?,?,?,?,'synthetic-clock')""",
                        id, session, kind, paragraph, if (kind == "story") null else "synthetic-asset", state,
                        if (state == "prepared") null else now, if (state == "completed") now else null,
                        if (state == "interrupted") now else null)
                }
                fun point(id: String, sequence: Long, quality: String) {
                    exec("""INSERT INTO route_point(point_id,segment_id,sequence,provider,source_time_unit,received_at,
                        received_elapsed_ms,clock_scope_id,latitude,longitude,raw_coordinates_json,quality_status,route_revision)
                        VALUES (?,'segment-b',?,'synthetic-test','unknown',?,?,'synthetic-clock',37,127,'{}',?,0)""",
                        id, sequence, now, now, quality)
                }
                RoomTripResultsStore(store.database).use { results ->
                    check(results.listTrips().isEmpty())
                    store.openSession("trip-a", "session-a")
                    exec("INSERT INTO recording_segment(segment_id,trip_id,session_id,started_at,last_observed_at,status) VALUES ('segment-a','trip-a','session-a',100,120,'active')")
                    attempt("a-p1", "session-a", "story", "completed", "test-p1")
                    attempt("a-p1-repeat", "session-a", "story", "completed", "test-p1")
                    attempt("a-p2-interrupted", "session-a", "story", "interrupted", "test-p2")
                    attempt("a-p2-failed", "session-a", "story", "failed", "test-p2")
                    attempt("a-music", "session-a", "music", "completed")
                    attempt("a-prompt", "session-a", "prompt", "interrupted")
                    attempt("a-transition", "session-a", "transition", "failed")
                    repeat(2) { index ->
                        val turn = "saved-$index"
                        val keys = SummaryStorageFixture.prepareFrozenTurn(store, turn, "trip-a", "session-a", index * 2L)
                        store.summaryStore().use { summaries ->
                            val work = (summaries.claim(turn) as SummaryClaim.Claimed).work
                            check(summaries.commit(work, ValidatedSummaryResult("SYNTHETIC_SAVED_SUMMARY_$index",
                                listOf("SYNTHETIC_USER_CLAIM"), listOf("test-fact-r1"), listOf("SYNTHETIC_OPEN_QUESTION"),
                                listOf(keys.completed), listOf(keys.interrupted), "synthetic-model", "synthetic-provider", "synthetic-prompt")))
                        }
                        now++
                    }
                    SummaryStorageFixture.prepareFrozenTurn(store, "pending", "trip-a", "session-a", 4)
                    listOf("no_text", "running", "retry_wait", "blocked").forEach { state ->
                        exec("""INSERT INTO conversation_turn(turn_id,session_id,created_at,utterance_finalized_at,closed_at,
                            lifecycle,frozen,summary_state) VALUES (?,'session-a',100,101,102,'closed',1,?)""", "state-$state", state)
                    }
                    now = 200
                    exec("UPDATE recording_segment SET status='closed',ended_at=200,end_reason='user_end' WHERE segment_id='segment-a'")
                    exec("UPDATE session SET status='closed',closed_at=200 WHERE session_id='session-a'")
                    exec("UPDATE trip SET status='closed',ended_at=200,last_observed_at=150,end_reason='user_end' WHERE trip_id='trip-a'")
                    exec("INSERT INTO session(session_id,trip_id,started_at,closed_at,status) VALUES ('session-a-second','trip-a',160,180,'closed')")
                    exec("INSERT INTO recording_segment(segment_id,trip_id,session_id,started_at,ended_at,status,end_reason) VALUES ('segment-a-second','trip-a','session-a-second',160,180,'closed','user_end')")

                    now = 300
                    store.openSession("trip-b", "session-b")
                    exec("INSERT INTO recording_segment(segment_id,trip_id,session_id,started_at,last_observed_at,status) VALUES ('segment-b','trip-b','session-b',300,350,'active')")
                    exec("UPDATE trip SET last_observed_at=350 WHERE trip_id='trip-b'")
                    attempt("b-p2", "session-b", "story", "completed", "test-p2")
                    exec("INSERT INTO collection_record(collection_id,collection_type,story_id,story_version_id,rule_version,earned_at,created_at) VALUES ('shared-card','story','test-story','test-story-v1','synthetic-rule',300,301)")
                    exec("INSERT INTO collection_proof(collection_id,proof_key,attempt_id) VALUES ('shared-card','test-p1','a-p1')")
                    exec("INSERT INTO collection_proof(collection_id,proof_key,attempt_id) VALUES ('shared-card','test-p2','b-p2')")
                    listOf("valid", "stale", "invalid", "unknown").forEachIndexed { index, quality -> point("point-$quality", index.toLong(), quality) }
                    exec("INSERT INTO region(region_id,name) VALUES ('synthetic-region','SYNTHETIC REGION')")
                    exec("""INSERT INTO region_revision(region_revision_id,region_id,code,dataset_version,name,source_revision_id,policy_json,verified_at)
                        VALUES ('region-r1','synthetic-region','synthetic','synthetic','SYNTHETIC REGION','test-source-r1','{}',1)""")
                    exec("INSERT INTO collection_record(collection_id,collection_type,region_id,rule_version,earned_at,created_at) VALUES ('region-card','region','synthetic-region','synthetic-rule',300,302)")
                    exec("INSERT INTO collection_proof(collection_id,proof_key,point_id,region_revision_id) VALUES ('region-card','synthetic-region','point-valid','region-r1')")
                    now = 400
                    store.sessionRecovery().use { it.recoverOnAppStart() }
                    val recovered = checkNotNull(results.readTrip("trip-b"))
                    check(recovered.header.status == TripRecordStatus.INTERRUPTED && recovered.header.endedAt == null)
                    check(recovered.header.lastObservedAt == 350L && recovered.header.interruptionDetectedAt == 400L)
                    check(recovered.segmentDetails.items.single().let { it.status == TripRecordStatus.INTERRUPTED && it.endedAt == null && it.interruptionDetectedAt == 400L })
                    mark("trip_results_recovery_preserves_unknown_actual_end_and_detection_time")

                    now = 500
                    store.openSession("trip-c", "session-c")
                    exec("INSERT INTO recording_segment(segment_id,trip_id,session_id,started_at,status) VALUES ('segment-c','trip-c','session-c',500,'active')")
                    attempt("c-replay", "session-c", "story", "completed", "test-p1")
                    attempt("c-prepared", "session-c", "story", "prepared", "test-p2")
                    attempt("c-playing", "session-c", "story", "playing", "test-p2")
                    val active = checkNotNull(results.latestTrip())
                    check(active.header.tripId == "trip-c" && active.header.status == TripRecordStatus.ACTIVE && active.header.endedAt == null)
                    check(active.sessions.active == 1L && active.segments.active == 1L)
                    check(active.playback.story.prepared == 1L && active.playback.story.playing == 1L && active.playback.story.completed == 1L)
                    check(active.collectionsWithProofInTrip.totalCount == 0L)
                    check(results.listTrips().map { it.tripId } == listOf("trip-c", "trip-b", "trip-a"))
                    check(results.listTrips(limit = 1, offset = 1).single().tripId == "trip-b")
                    check(results.readTrip("missing") == null)
                    mark("trip_results_active_closed_interrupted_and_trip_pagination_are_distinct")

                    val closed = checkNotNull(results.readTrip("trip-a", detailLimit = 1))
                    check(closed.header.status == TripRecordStatus.CLOSED && closed.header.endedAt == 200L)
                    check(closed.sessions.closed == 2L && closed.segments.closed == 2L)
                    check(closed.segmentDetails.items.size == 1 && closed.segmentDetails.totalCount == 2L && closed.segmentDetails.hasMore)
                    check(closed.playback.story.completed == 2L && closed.playback.distinctCompletedStoryParagraphs == 1L)
                    check(closed.playback.story.interrupted == 1L && closed.playback.story.failed == 1L)
                    check(closed.playback.answer.completed == 3L && closed.playback.answer.interrupted == 3L)
                    check(closed.playback.music.completed == 1L && closed.playback.prompt.interrupted == 1L && closed.playback.transition.failed == 1L)
                    check(closed.summaries == TripSummaryCounts(1, 1, 1, 1, 2, 1))
                    check(closed.savedSummaries.items.size == 1 && closed.savedSummaries.totalCount == 2L && closed.savedSummaries.hasMore)
                    check(closed.savedSummaries.items.single().summaryText == "SYNTHETIC_SAVED_SUMMARY_1")
                    check(closed.savedSummaries.items.single().provenance.userClaims == listOf("SYNTHETIC_USER_CLAIM"))
                    val location = checkNotNull(results.readTrip("trip-b", detailLimit = 1))
                    check(location.locationSamples == TripLocationSampleCounts(1, 1, 1, 1))
                    check(location.segmentDetails.items.single().locationSamples.total == 4L)
                    check(location.collectionsWithProofInTrip.totalCount == 2L && location.collectionsWithProofInTrip.hasMore)
                    mark("trip_results_full_counts_survive_bounded_details_and_do_not_infer_distance_or_completion")

                    val sharedA = closed.collectionsWithProofInTrip.items.single()
                    val cardsB = checkNotNull(results.readTrip("trip-b")).collectionsWithProofInTrip.items
                    val sharedB = cardsB.single { it.kind == TripCollectionKind.STORY }
                    check(sharedA.collectionId == sharedB.collectionId && sharedA.proofsInThisTrip == 1L && sharedA.proofsInOtherTrips == 1L)
                    check(sharedB.totalProofs == 2L && sharedB.sharedAcrossTrips && !sharedB.allProofsFromThisTrip)
                    check(cardsB.single { it.kind == TripCollectionKind.REGION }.allProofsFromThisTrip)
                    check(closed.collectionsWithProofInTrip.totalCount == 1L)
                    mark("trip_results_collection_attribution_uses_proofs_not_timestamps_or_replayed_paragraphs")

                    // A saved summary remains readable without the pending payload key. Results must
                    // neither decrypt nor repair that payload, and must not expose revoked public text.
                    exec("UPDATE source_evidence SET allowed_uses_json=?", StrictJson.stringify(SyntheticStorageFixture.allowedUses(false, false, false)))
                    exec("UPDATE region_revision SET verified_at=NULL WHERE region_revision_id='region-r1'")
                    exec("UPDATE pending_conversation SET ciphertext=X'00' WHERE turn_id='pending'")
                    SyntheticStorageFixture.deleteEncryptionKey(name)
                    val changesBefore = number("SELECT total_changes()")
                    val payloadRowsBefore = number("SELECT count(*) FROM pending_conversation")
                    val afterKeyLoss = checkNotNull(results.readTrip("trip-a"))
                    val afterRevocation = checkNotNull(results.readTrip("trip-b"))
                    results.listTrips(); results.latestTrip()
                    check(afterKeyLoss.savedSummaries.items.size == 2 && afterKeyLoss.summaries.pending == 1L)
                    check(afterRevocation.collectionsWithProofInTrip.totalCount == 2L)
                    check(number("SELECT total_changes()") == changesBefore)
                    check(number("SELECT count(*) FROM pending_conversation") == payloadRowsBefore)
                    check(number("SELECT length(ciphertext) FROM pending_conversation WHERE turn_id='pending'") == 1L)
                    check(number("SELECT count(*) FROM session WHERE status='active'") == 1L)
                    check(number("SELECT count(*) FROM recording_segment WHERE status='active'") == 1L)
                    val redacted = listOf(afterKeyLoss, afterKeyLoss.header, afterKeyLoss.sessions, afterKeyLoss.locationSamples,
                        afterKeyLoss.playback, afterKeyLoss.playback.story, afterKeyLoss.summaries, afterKeyLoss.segmentDetails,
                        afterKeyLoss.segmentDetails.items.first(), afterKeyLoss.savedSummaries.items.first(),
                        afterKeyLoss.savedSummaries.items.first().provenance, sharedA)
                    check(redacted.all { it.toString().endsWith("([redacted])") })
                    mark("trip_results_read_only_key_independent_saved_summary_and_revoked_card_ids_are_redacted")

                    var limitRejected = false
                    try { results.readTrip("trip-a", 51) } catch (_: IllegalArgumentException) { limitRejected = true }
                    check(limitRejected)
                    exec("UPDATE conversation_summary SET provenance_json='{}' WHERE turn_id='saved-0'")
                    var invalidRejected = false
                    try { results.readTrip("trip-a") } catch (failure: StorageUnavailable) {
                        invalidRejected = failure.code == "TRIP_RESULTS_INCONSISTENT" && !failure.toString().contains("SYNTHETIC")
                    }
                    check(invalidRejected)
                    mark("trip_results_detail_limit_and_invalid_saved_provenance_fail_closed")
                }
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }
        RecordsAtlasProjectionProbe.run(instrumentation, mark)
    }
}
