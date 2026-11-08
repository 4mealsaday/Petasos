package com.petasos.app.storage

import android.app.Instrumentation
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.playback.*
import com.petasos.app.runtime.RuntimeText
import com.petasos.app.summary.*
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real SQLite/Keystore transactions. All utterances, responses and summaries are synthetic. */
object SummaryStorageProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "summary-storage-probe-${UUID.randomUUID()}.db"
        var now = 1_800_000_000_000L
        var store = SyntheticStorageFixture.create(context, name) { now }
        var summaries = store.summaryStore()
        var revision = 0L
        fun db() = store.database.openHelper.writableDatabase
        fun count(statement: String, vararg args: Any?): Long = db().query(SimpleSQLiteQuery(statement, args)).use { check(it.moveToFirst()); it.getLong(0) }
        fun prepare(turn: String): SummaryStorageFixture.Keys {
            val keys = SummaryStorageFixture.prepareFrozenTurn(store, turn, revisionBase = revision)
            revision += 2
            return keys
        }
        fun claim(turn: String): SummaryWork = (summaries.claim(turn) as? SummaryClaim.Claimed)?.work ?: error("summary_not_claimed")
        fun expectRejected(code: String, action: () -> Unit) {
            var rejected = false
            try { action() } catch (_: Exception) { rejected = true }
            check(rejected) { code }
        }
        try {
            SyntheticStorageFixture.install(store)
            store.openSession("summary-trip", "summary-session")
            store.registerTurn("summary-trip", "summary-session", "not-frozen")
            store.persistUtterance(OperationToken("summary-session", 0, "not-frozen-final"), "not-frozen", RuntimeText("SYNTHETIC_UNFROZEN_USER"))
            check(summaries.claim("not-frozen") == SummaryClaim.NoOp)
            check(count("SELECT count(*) FROM pending_conversation WHERE turn_id='not-frozen'") == 1L)
            mark("summary_unfrozen_turn_not_claimed")

            val keys = prepare("filtered")
            val first = claim("filtered")
            check(first.input.userText == "SYNTHETIC_SUMMARY_USER_filtered")
            check(first.input.completedResponses.map { it.key } == listOf(keys.completed))
            check(first.input.completedResponses.single().text == "COMPLETED_BODY_ALLOWED")
            check(first.input.partialUnknownKeys == listOf(keys.interrupted))
            check(first.approvedFactSources == mapOf("test-fact-r1" to setOf("test-source-r1")))
            check(!first.input.completedResponses.any { it.key == keys.unplayed || it.text.contains("MUST_NOT_LEAVE") })
            check(summaries.claim("filtered") == SummaryClaim.NoOp)
            mark("summary_input_excludes_unplayed_interrupted_bodies_and_evidence")

            val failed = summaries.fail(first, SummaryFailure(SummaryFailureKind.RETRYABLE, "UPSTREAM_UNAVAILABLE")) as SummaryRetryDecision.RetryAt
            check(failed.schedule.notBeforeEpochMs == now + 30_000)
            check(summaries.claim("filtered") is SummaryClaim.Wait)
            now = failed.schedule.notBeforeEpochMs
            val second = claim("filtered")
            check(second.requestId == first.requestId && second.inputHash == first.inputHash)
            check(second.attemptNumber == first.attemptNumber + 1)
            check(!summaries.commit(first, validResult(first)))
            check(count("SELECT count(*) FROM pending_conversation WHERE turn_id='filtered'") == 1L)
            mark("summary_retry_reuses_id_hmac_and_rejects_previous_attempt")

            val staleVersion = SummaryWork(second.requestId, second.sessionId, second.turnId, second.turnVersion - 1,
                second.cancelEpoch, second.inputHash, second.attemptNumber, second.input, second.approvedFactSources)
            check(!summaries.commit(staleVersion, validResult(second)))
            expectRejected("unplayed_evidence_accepted") { summaries.commit(second, validResult(second, facts = listOf("unplayed-fact"))) }
            expectRejected("unplayed_paragraph_accepted") { summaries.commit(second, validResult(second, completed = listOf(keys.completed, keys.unplayed))) }
            check(count("SELECT count(*) FROM pending_conversation WHERE turn_id='filtered'") == 1L)
            mark("summary_stale_version_and_unheard_provenance_rejected")

            db().execSQL("CREATE TRIGGER summary_fail_after_insert BEFORE UPDATE OF summary_state ON conversation_turn WHEN NEW.summary_state='saved' BEGIN SELECT RAISE(ABORT, 'injected'); END")
            expectRejected("summary_insert_not_rolled_back") { summaries.commit(second, validResult(second)) }
            check(count("SELECT count(*) FROM conversation_summary WHERE turn_id='filtered'") == 0L)
            check(count("SELECT count(*) FROM conversation_turn WHERE turn_id='filtered' AND summary_state='running'") == 1L)
            check(count("SELECT count(*) FROM pending_conversation WHERE turn_id='filtered'") == 1L)
            db().execSQL("DROP TRIGGER summary_fail_after_insert")
            db().execSQL("CREATE TRIGGER summary_fail_before_delete BEFORE DELETE ON pending_conversation BEGIN SELECT RAISE(ABORT, 'injected'); END")
            expectRejected("summary_delete_not_rolled_back") { summaries.commit(second, validResult(second)) }
            check(count("SELECT count(*) FROM conversation_summary WHERE turn_id='filtered'") == 0L)
            check(count("SELECT count(*) FROM conversation_turn WHERE turn_id='filtered' AND summary_state='running' AND summary_saved_at IS NULL") == 1L)
            check(count("SELECT count(*) FROM pending_conversation WHERE turn_id='filtered'") == 1L)
            db().execSQL("DROP TRIGGER summary_fail_before_delete")
            check(summaries.commit(second, validResult(second)))
            check(!summaries.commit(second, validResult(second)))
            check(summaries.claim("filtered") == SummaryClaim.NoOp)
            check(count("SELECT count(*) FROM conversation_summary WHERE turn_id='filtered'") == 1L)
            check(count("SELECT count(*) FROM conversation_turn WHERE turn_id='filtered' AND summary_state='saved' AND summary_saved_at IS NOT NULL") == 1L)
            check(count("SELECT count(*) FROM pending_conversation WHERE turn_id='filtered'") == 0L)
            check(count("SELECT count(*) FROM playback_attempt WHERE turn_id='filtered'") == 2L)
            mark("summary_tx03_insert_saved_delete_roll_back_or_commit_once")

            prepare("blocked")
            val blocked = claim("blocked")
            check(summaries.fail(blocked, SummaryFailure(SummaryFailureKind.AUTHORIZATION, "UNAUTHORIZED")) is SummaryRetryDecision.Blocked)
            check(summaries.recoverOnAppStart().none { it.turnId == "blocked" })
            check(summaries.claim("blocked") is SummaryClaim.Blocked)
            check(summaries.unblockAfterConfigurationChange().any { it.turnId == "blocked" })
            val reconfigured = claim("blocked")
            check(reconfigured.requestId == blocked.requestId && reconfigured.inputHash == blocked.inputHash)
            check(summaries.commit(reconfigured, validResult(reconfigured)))
            mark("summary_auth_block_survives_restart_until_configuration_change")

            prepare("bounded")
            var firstRequest: SummaryWork? = null
            repeat(3) { index ->
                val work = claim("bounded")
                if (firstRequest == null) firstRequest = work
                check(work.requestId == firstRequest!!.requestId && work.inputHash == firstRequest!!.inputHash)
                val failure = summaries.fail(work, SummaryFailure(SummaryFailureKind.RETRYABLE, "UPSTREAM_UNAVAILABLE"))
                val schedule = when (failure) {
                    is SummaryRetryDecision.RetryAt -> { check(index < 2); failure.schedule }
                    is SummaryRetryDecision.Exhausted -> { check(index == 2); failure.schedule }
                    else -> error("unexpected_retry_decision")
                }
                check(schedule.notBeforeEpochMs - now == listOf(30_000L, 120_000L, 600_000L)[index])
                now = schedule.notBeforeEpochMs
            }
            check(summaries.claim("bounded") == SummaryClaim.NoOp)
            check(count("SELECT retry_batch_count FROM conversation_turn WHERE turn_id='bounded'") == 3L)
            check(count("SELECT count(*) FROM pending_conversation WHERE turn_id='bounded'") == 1L)
            check(summaries.recoverOnAppStart().any { it.turnId == "bounded" })
            val newBatch = claim("bounded")
            check(newBatch.requestId == firstRequest!!.requestId && newBatch.inputHash == firstRequest!!.inputHash)
            check(newBatch.attemptNumber == 4)
            check(count("SELECT retry_batch_count FROM conversation_turn WHERE turn_id='bounded'") == 1L)
            check(summaries.commit(newBatch, validResult(newBatch)))
            mark("summary_three_attempt_batch_exhaustion_and_startup_reset")

            prepare("interrupted-worker")
            val interruptedWorker = claim("interrupted-worker")
            check(summaries.recoverInterruptedWork("interrupted-worker") is SummaryRetryDecision.RetryAt)
            check(!summaries.commit(interruptedWorker, validResult(interruptedWorker)))
            val resumeWorkerAt = (summaries.claim("interrupted-worker") as SummaryClaim.Wait).schedule.notBeforeEpochMs
            now = resumeWorkerAt
            val resumedWorker = claim("interrupted-worker")
            check(resumedWorker.requestId == interruptedWorker.requestId && resumedWorker.inputHash == interruptedWorker.inputHash)
            check(summaries.commit(resumedWorker, validResult(resumedWorker)))
            mark("summary_stopped_worker_recovery_preserves_batch_and_fences_old_result")

            prepare("restart")
            val beforeRestart = claim("restart")
            summaries.close()
            store.close()
            store = SyntheticStorageFixture.create(context, name) { now }
            summaries = store.summaryStore()
            val recovered = summaries.recoverOnAppStart().single { it.turnId == "restart" }
            check(recovered.notBeforeEpochMs == now + 30_000)
            check(!summaries.commit(beforeRestart, validResult(beforeRestart)))
            now = recovered.notBeforeEpochMs
            val afterRestart = claim("restart")
            check(afterRestart.requestId == beforeRestart.requestId && afterRestart.inputHash == beforeRestart.inputHash)
            check(summaries.commit(afterRestart, validResult(afterRestart)))
            mark("summary_process_restart_recovers_running_claim_without_payload_loss")

            prepare("concurrent")
            val executor = Executors.newFixedThreadPool(2)
            val concurrent = try {
                executor.invokeAll(listOf(Callable { summaries.claim("concurrent") }, Callable { summaries.claim("concurrent") }))
                    .map { it.get(5, TimeUnit.SECONDS) }
            } finally { executor.shutdownNow() }
            check(concurrent.count { it is SummaryClaim.Claimed } == 1)
            check(concurrent.count { it == SummaryClaim.NoOp } == 1)
            val winner = (concurrent.single { it is SummaryClaim.Claimed } as SummaryClaim.Claimed).work
            check(summaries.commit(winner, validResult(winner)))
            mark("summary_concurrent_claims_allow_one_active_attempt")

            prepare("key-loss")
            prepare("key-loss-during-result")
            val lostDuringResult = claim("key-loss-during-result")
            val beforeLoss = db().query("SELECT ciphertext FROM pending_conversation WHERE turn_id='key-loss'").use { check(it.moveToFirst()); it.getBlob(0) }
            SyntheticStorageFixture.deleteEncryptionKey(name)
            check(!summaries.commit(lostDuringResult, validResult(lostDuringResult)))
            check(count("SELECT count(*) FROM generation_request WHERE request_id=? AND status='failed'", lostDuringResult.requestId) == 1L)
            check(count("SELECT count(*) FROM pending_conversation WHERE turn_id='key-loss-during-result'") == 1L)
            check(summaries.claim("key-loss") is SummaryClaim.Blocked)
            check(summaries.recoverOnAppStart().none { it.turnId == "key-loss" })
            check(summaries.unblockAfterConfigurationChange().none { it.turnId == "key-loss" })
            db().query("SELECT ciphertext FROM pending_conversation WHERE turn_id='key-loss'").use { check(it.moveToFirst()); check(beforeLoss.contentEquals(it.getBlob(0))) }
            check(count("SELECT count(*) FROM conversation_summary WHERE turn_id='key-loss'") == 0L)
            mark("summary_missing_keys_block_without_deleting_ciphertext")
        } finally {
            summaries.close()
            store.close()
            SyntheticStorageFixture.cleanup(context, name)
        }
    }

    private fun validResult(work: SummaryWork, facts: List<String> = listOf("test-fact-r1"), completed: List<String> = work.input.completedResponses.map { it.key }) =
        ValidatedSummaryResult("합성 요약 결과입니다.", listOf("사용자가 합성 시험 발화를 제공함"), facts, emptyList(),
            completed, work.input.partialUnknownKeys, "synthetic-model", "synthetic-provider", "synthetic-prompt", 1, 1)
}
