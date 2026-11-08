package com.petasos.app.storage

import android.app.Instrumentation
import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.generation.*
import com.petasos.app.summary.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual Room/Keystore summary rights gates; only a synthetic in-process transport is supplied. */
object SummaryRightsProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        verifyClaimAndExplicitRecovery(context, mark)
        verifyUnplayedAndFullProvenance(context, mark)
        verifyBeforeTransport(context, mark)
        verifyLateResponseAndCommit(context, mark)
        verifyMetadataAndFactLinks(context, mark)
        verifyPayloadLossAndStorageFailure(context, mark)
    }

    private fun verifyClaimAndExplicitRecovery(context: Context, mark: (String) -> Unit) {
        listOf("generation", "display", "distribution", "review").forEach { denial ->
            Fixture(context).use { fixture ->
                fixture.deny(denial)
                val transport = SyntheticTransport()
                client(transport).use { client ->
                    check(SummaryExecution(fixture.summary, GenerationSummaryProvider(client), 5000).run(fixture.turnId) == SummaryExecutionResult.Finished)
                    check(transport.calls.get() == 0)
                    check(fixture.count("SELECT count(*) FROM generation_request") == 0L)
                    check(fixture.count("SELECT count(*) FROM conversation_turn WHERE attempt_count=0 AND retry_batch_count=0") == 1L)
                    fixture.assertBlockedAndPreserved()
                    check(fixture.summary.unblockAfterConfigurationChange().isEmpty())
                    check(fixture.summary.unblockAfterEvidenceChange().isEmpty())
                    fixture.restoreRights()
                    check(fixture.summary.claim(fixture.turnId) is SummaryClaim.Blocked)
                    if (denial == "generation") {
                        check(fixture.summary.unblockAfterEvidenceChange().map { it.turnId } == listOf(fixture.turnId))
                        check(SummaryExecution(fixture.summary, GenerationSummaryProvider(client), 5000).run(fixture.turnId) == SummaryExecutionResult.Finished)
                        check(transport.calls.get() == 1)
                        fixture.assertSaved()
                    }
                }
            }
        }
        mark("summary_rights_claim_blocks_each_permission_and_review_without_request_or_attempt")
        mark("summary_rights_restore_requires_explicit_evidence_unblock_before_normal_commit")
    }

    private fun verifyUnplayedAndFullProvenance(context: Context, mark: (String) -> Unit) {
        Fixture(context).use { fixture ->
            fixture.exec("UPDATE source_evidence SET allowed_uses_json=?,review_status='rejected' WHERE source_revision_id='unplayed-source'",
                StrictJson.stringify(SyntheticStorageFixture.allowedUses(false, false, false)))
            val work = fixture.claim()
            check(work.input.completedResponses.map { it.key } == listOf(fixture.keys.completed))
            check(work.input.partialUnknownKeys == listOf(fixture.keys.interrupted))
            check(work.approvedFactSources == mapOf("test-fact-r1" to setOf("test-source-r1")))
            check(work.checkCurrent() == SummaryWorkStatus.CURRENT)
            val transport = SyntheticTransport()
            client(transport).use { client ->
                val outcome = awaitSummary(GenerationSummaryProvider(client), work)
                check(outcome is SummaryGenerationOutcome.Success)
                check(fixture.summary.commit(work, outcome.result))
                check(transport.calls.get() == 1)
                fixture.assertSaved()
            }
        }
        Fixture(context).use { fixture ->
            // A fact supported by two sources cannot be approved from just the completed answer's one source.
            fixture.exec("INSERT INTO fact_source(fact_revision_id,source_revision_id) VALUES ('test-fact-r1','test-source-r2')")
            val work = fixture.claim()
            check("test-fact-r1" !in work.approvedFactSources)
            try { fixture.summary.commit(work, validResult(work)); error("partial_fact_provenance_accepted") }
            catch (_: SummaryResultRejected) { }
            fixture.assertPayloadPreserved()
        }
        mark("summary_rights_excludes_partial_unplayed_bodies_and_requires_complete_fact_provenance")
    }

    private fun verifyBeforeTransport(context: Context, mark: (String) -> Unit) {
        // Claim -> initial client gate, and initial gate -> HTTP executor gate are separate race windows.
        listOf(false, true).forEach { atConfigurationLoad ->
            Fixture(context).use { fixture ->
                val work = fixture.claim()
                val transport = SyntheticTransport()
                if (!atConfigurationLoad) fixture.deny("generation")
                client(transport, beforeConfiguration = {
                    if (atConfigurationLoad) fixture.deny("generation")
                }).use { client ->
                    val outcome = awaitSummary(GenerationSummaryProvider(client), work)
                    check(outcome is SummaryGenerationOutcome.Failure)
                    check(outcome.failure.kind == SummaryFailureKind.EVIDENCE_BLOCKED)
                    check(transport.calls.get() == 0)
                    check(fixture.summary.fail(work, outcome.failure) is SummaryRetryDecision.Blocked)
                    fixture.assertBlockedAndPreserved()
                    check(fixture.count("SELECT attempt_count FROM generation_request") == 1L)
                }
            }
        }
        mark("summary_rights_rechecked_after_claim_and_immediately_before_transport")
    }

    private fun verifyLateResponseAndCommit(context: Context, mark: (String) -> Unit) {
        Fixture(context).use { fixture ->
            val work = fixture.claim()
            val transport = SyntheticTransport(delayed = true)
            try {
                client(transport).use { client ->
                    val pending = CompletableFuture<SummaryGenerationOutcome>()
                    GenerationSummaryProvider(client).generate(work) { pending.complete(it) }
                    check(transport.started.await(5, TimeUnit.SECONDS))
                    fixture.deny("display")
                    transport.release.countDown()
                    val outcome = pending.get(5, TimeUnit.SECONDS)
                    check(outcome is SummaryGenerationOutcome.Failure && outcome.failure.kind == SummaryFailureKind.EVIDENCE_BLOCKED)
                    check(fixture.summary.fail(work, outcome.failure) is SummaryRetryDecision.Blocked)
                    check(transport.calls.get() == 1)
                    fixture.assertBlockedAndPreserved()
                }
            } finally { transport.release.countDown() }
        }
        Fixture(context).use { fixture ->
            val work = fixture.claim()
            val transport = SyntheticTransport()
            client(transport).use { client ->
                val outcome = awaitSummary(GenerationSummaryProvider(client), work)
                check(outcome is SummaryGenerationOutcome.Success)
                fixture.deny("distribution")
                check(!fixture.summary.commit(work, outcome.result))
                fixture.assertBlockedAndPreserved()
            }
        }
        mark("summary_rights_late_response_and_tx03_revocation_preserve_ciphertext_without_summary")
    }

    private fun verifyMetadataAndFactLinks(context: Context, mark: (String) -> Unit) {
        listOf("metadata", "fact", "link").forEach { mutation ->
            Fixture(context).use { fixture ->
                val work = fixture.claim()
                when (mutation) {
                    "metadata" -> fixture.exec("UPDATE source_evidence SET attribution='Changed review metadata' WHERE source_revision_id='test-source-r1'")
                    "fact" -> fixture.exec("UPDATE fact_revision SET statement='Changed fact revision' WHERE fact_revision_id='test-fact-r1'")
                    "link" -> fixture.exec("INSERT INTO fact_source(fact_revision_id,source_revision_id) VALUES ('test-fact-r1','test-source-r2')")
                }
                check(work.checkCurrent() == SummaryWorkStatus.EVIDENCE_BLOCKED)
                check(!fixture.summary.commit(work, validResult(work)))
                fixture.assertBlockedAndPreserved()
            }
        }
        mark("summary_rights_claim_snapshot_rejects_source_metadata_fact_and_full_link_changes")
    }

    private fun verifyPayloadLossAndStorageFailure(context: Context, mark: (String) -> Unit) {
        Fixture(context).use { fixture ->
            val work = fixture.claim()
            fixture.loseEncryptionKey()
            check(work.checkCurrent() == SummaryWorkStatus.PAYLOAD_UNAVAILABLE)
            val transport = SyntheticTransport()
            client(transport).use { client ->
                val outcome = awaitSummary(GenerationSummaryProvider(client), work)
                check(outcome is SummaryGenerationOutcome.Failure)
                check(outcome.failure.kind == SummaryFailureKind.PAYLOAD_UNAVAILABLE && outcome.failure.code == "SUMMARY_PAYLOAD_UNAVAILABLE")
                check(transport.calls.get() == 0)
                check(fixture.summary.fail(work, outcome.failure) is SummaryRetryDecision.Blocked)
                fixture.assertPayloadPreserved()
                check(fixture.count("SELECT count(*) FROM conversation_turn WHERE summary_state='blocked' AND last_error_code='SUMMARY_PAYLOAD_UNAVAILABLE' AND next_retry_at IS NULL") == 1L)
                check(fixture.summary.unblockAfterConfigurationChange().isEmpty())
                check(fixture.summary.unblockAfterEvidenceChange().isEmpty())
                fixture.assertPayloadPreserved()
            }
        }
        mark("summary_rights_crypto_payload_loss_blocks_without_transport_and_preserves_ciphertext")
        Fixture(context).use { fixture ->
            val work = fixture.claim()
            fixture.loseEncryptionKey()
            // Exercise TX-03 directly: an inner guard failure must not roll back the outer blocked-state write.
            check(!fixture.summary.commit(work, validResult(work)))
            fixture.assertPayloadPreserved()
            check(fixture.count("SELECT count(*) FROM conversation_turn WHERE summary_state='blocked' AND last_error_code='SUMMARY_PAYLOAD_UNAVAILABLE' AND next_retry_at IS NULL") == 1L)
            check(fixture.count("SELECT count(*) FROM generation_request WHERE status='failed' AND error_code='SUMMARY_PAYLOAD_UNAVAILABLE'") == 1L)
        }
        mark("summary_rights_tx03_payload_loss_commits_blocked_state_without_deleting_ciphertext")
        Fixture(context).use { fixture ->
            val work = fixture.claim()
            // A closed SQLite store is a storage failure, not evidence denial or a lost decryption key.
            // The guard is all that can be inspected safely after closing this disposable connection.
            fixture.store.close()
            check(work.checkCurrent() == SummaryWorkStatus.STORAGE_UNAVAILABLE)
        }
        mark("summary_rights_closed_database_remains_storage_unavailable_distinct_from_payload_loss")
    }

    private class Fixture(private val context: Context) : AutoCloseable {
        private val name = "summary-rights-probe-${UUID.randomUUID()}.db"
        val turnId = "summary-rights-turn"
        val store = SyntheticStorageFixture.create(context, name)
        val keys: SummaryStorageFixture.Keys
        val summary: RoomSummaryStore
        private val ciphertext: ByteArray
        init {
            SyntheticStorageFixture.install(store)
            store.openSession("summary-trip", "summary-session")
            keys = SummaryStorageFixture.prepareFrozenTurn(store, turnId)
            summary = store.summaryStore()
            ciphertext = payload()
        }
        fun claim(): SummaryWork {
            val claim = summary.claim(turnId)
            check(claim is SummaryClaim.Claimed) { "summary_rights_claim_failed" }
            return claim.work
        }
        fun deny(kind: String) {
            if (kind == "review") exec("UPDATE source_evidence SET review_status='rejected' WHERE source_revision_id='test-source-r1'")
            else exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'",
                StrictJson.stringify(SyntheticStorageFixture.allowedUses(kind != "generation", kind != "display", kind != "distribution")))
        }
        fun restoreRights() = exec("UPDATE source_evidence SET review_status='verified',allowed_uses_json=? WHERE source_revision_id='test-source-r1'",
            StrictJson.stringify(SyntheticStorageFixture.allowedUses()))
        fun assertPayloadPreserved() {
            check(count("SELECT count(*) FROM pending_conversation") == 1L)
            check(count("SELECT count(*) FROM conversation_summary") == 0L)
            check(payload().contentEquals(ciphertext))
        }
        fun assertBlockedAndPreserved() {
            assertPayloadPreserved()
            check(count("SELECT count(*) FROM conversation_turn WHERE summary_state='blocked' AND last_error_code='SUMMARY_EVIDENCE_BLOCKED'") == 1L)
        }
        fun assertSaved() {
            check(count("SELECT count(*) FROM pending_conversation") == 0L)
            check(count("SELECT count(*) FROM conversation_summary") == 1L)
            check(count("SELECT count(*) FROM conversation_turn WHERE frozen=1 AND summary_state='saved'") == 1L)
            check(count("SELECT count(*) FROM generation_request WHERE status='succeeded'") == 1L)
        }
        fun loseEncryptionKey() { SyntheticStorageFixture.deleteEncryptionKey(name) }
        fun exec(statement: String, vararg args: Any?) { store.database.openHelper.writableDatabase.execSQL(statement, args) }
        fun count(statement: String): Long = store.database.openHelper.writableDatabase.query(statement).use { check(it.moveToFirst()); it.getLong(0) }
        private fun payload(): ByteArray = store.database.openHelper.writableDatabase.query(
            SimpleSQLiteQuery("SELECT ciphertext FROM pending_conversation WHERE turn_id=?", arrayOf(turnId)),
        ).use { check(it.moveToFirst()); it.getBlob(0) }
        override fun close() {
            summary.close()
            store.close()
            ciphertext.fill(0)
            SyntheticStorageFixture.cleanup(context, name)
        }
    }

    private class SyntheticTransport(delayed: Boolean = false) : GenerationTransport {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(if (delayed) 1 else 0)
        override fun execute(configuration: DeviceGenerationConfiguration, path: String, body: ByteArray?, exchange: GenerationExchange): GenerationHttpResponse {
            check(path == "/v1/generations" && body != null)
            calls.incrementAndGet()
            val raw = body.toString(Charsets.UTF_8)
            check(!raw.contains("INTERRUPTED_BODY_MUST_NOT_LEAVE") && !raw.contains("UNPLAYED_BODY_MUST_NOT_LEAVE") && !raw.contains("unplayed-source"))
            val request = StrictJson.parse(body).jsonObject()
            val payload = request["payload"].jsonObject()
            val complete = (payload["completedResponses"] as List<*>).map { it.jsonObject().text("paragraphKey") }
            started.countDown()
            check(release.await(8, TimeUnit.SECONDS))
            val response = request.filterKeys { it !in setOf("payload", "inputHash") } + mapOf(
                "status" to "ok", "result" to mapOf("summaryText" to "합성 요약 권한 검증 결과입니다.",
                    "userClaims" to emptyList<String>(), "verifiedFactRevisionIds" to listOf("test-fact-r1"),
                    "unresolvedQuestions" to emptyList<String>(), "completedResponseKeys" to complete,
                    "partialUnknownKeys" to payload["partialUnknownKeys"]),
                "metadata" to mapOf("modelId" to "synthetic-test", "provider" to "synthetic-test", "promptVersion" to "synthetic-test",
                    "inputTokens" to null, "outputTokens" to null))
            return GenerationHttpResponse(200, StrictJson.encode(response), null)
        }
    }

    private fun client(transport: GenerationTransport, beforeConfiguration: () -> Unit = {}) = GenerationClient(
        GenerationConfigurationSource { beforeConfiguration(); DeviceGenerationConfiguration("https://example.invalid", "synthetic-test-token-0000000000000000") },
        GenerationInputHasher { ContentBundleCodec.sha256(it) }, transport)
    private fun awaitSummary(generator: SummaryGenerator, work: SummaryWork): SummaryGenerationOutcome {
        val result = CompletableFuture<SummaryGenerationOutcome>()
        generator.generate(work) { result.complete(it) }
        return result.get(5, TimeUnit.SECONDS)
    }
    private fun validResult(work: SummaryWork) = ValidatedSummaryResult("합성 요약 권한 검증 결과입니다.", emptyList(), listOf("test-fact-r1"),
        emptyList(), work.input.completedResponses.map { it.key }, work.input.partialUnknownKeys, "synthetic-test", "synthetic-test", "synthetic-test")
}
