package com.petasos.app.storage

import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.generation.*
import com.petasos.app.planning.ApiResumeContext
import com.petasos.app.playback.*
import com.petasos.app.runtime.RuntimeText
import com.petasos.app.summary.SummaryClaim
import com.petasos.app.summary.ValidatedSummaryResult
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Room and Keystore, synthetic frozen turns only. No microphone, TTS, or external HTTP. */
object ResumeContextProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        verifyInputAndNoWrites(context)
        mark("resume_context_uses_frozen_user_and_completed_answers_without_new_plaintext_storage")
        verifyRightsAndSnapshots(context)
        mark("resume_context_rechecks_permissions_source_fact_links_and_candidate_hashes")
        verifyFrozenPayloadAndSession(context)
        mark("resume_context_rejects_uncommitted_open_mismatched_corrupt_and_stale_turns")
        verifySummaryRace(context)
        mark("resume_context_summary_claim_preserves_authority_but_tx03_deletion_invalidates_it")
        verifyBoundsAndHostGuard(context)
        mark("resume_context_bounds_valid_json_and_requires_runtime_music_authority")
        verifyClientBoundaries(context)
        mark("resume_context_revocation_before_http_and_during_response_is_rejected")
    }

    private fun verifyInputAndNoWrites(context: Context) = Fixture(context).use { f ->
        val ciphertext = f.payloadBytes()
        try {
            f.exec("UPDATE source_evidence SET review_status='rejected',allowed_uses_json=? WHERE source_revision_id='unplayed-source'",
                StrictJson.stringify(SyntheticStorageFixture.allowedUses(false, false, false)))
            val prepared = f.prepare()
            val decoded = StrictJson.parse(prepared.contextSummary.toByteArray(Charsets.UTF_8)).jsonObject()
            check(decoded.text("userFinalText") == "SYNTHETIC_SUMMARY_USER_${f.turnId}")
            val completed = (decoded["completedResponses"] as List<*>).map { it.jsonObject() }
            check(completed.size == 1 && completed.single().text("paragraphKey") == f.keys.completed)
            check(completed.single().text("text") == "COMPLETED_BODY_ALLOWED")
            check(!prepared.contextSummary.contains("INTERRUPTED_BODY_MUST_NOT_LEAVE"))
            check(!prepared.contextSummary.contains("UNPLAYED_BODY_MUST_NOT_LEAVE"))
            check(!prepared.contextSummary.contains("unplayed-source"))
            check(prepared.routeRevision == null && prepared.isCurrent())
            check(prepared.contextSummary.length <= 2_000)
            check(!prepared.toString().contains("SYNTHETIC_SUMMARY_USER"))
            check(f.count("SELECT count(*) FROM generation_request") == 0L)
            check(f.count("SELECT count(*) FROM conversation_summary") == 0L)
            check(f.count("SELECT count(*) FROM pending_conversation") == 1L)
            val after = f.payloadBytes()
            try { check(ciphertext.contentEquals(after)) } finally { after.fill(0) }
        } finally { ciphertext.fill(0) }
    }

    private fun verifyRightsAndSnapshots(context: Context) = Fixture(context).use { f ->
        for (denial in listOf("generation", "display", "distribution", "review")) {
            val prepared = f.prepare()
            f.deny(denial)
            check(!prepared.isCurrent())
            check(f.builder.prepare(f.effect, f.turnId) { true } == null)
            f.restoreRights()
        }
        val changes = listOf(
            Triple("UPDATE source_evidence SET attribution='changed' WHERE source_revision_id='test-source-r1'",
                "UPDATE source_evidence SET attribution=? WHERE source_revision_id='test-source-r1'", "Test APK only"),
            Triple("UPDATE source_evidence SET content_hash='changed' WHERE source_revision_id='test-source-r1'",
                "UPDATE source_evidence SET content_hash=? WHERE source_revision_id='test-source-r1'", f.text("SELECT content_hash FROM source_evidence WHERE source_revision_id='test-source-r1'")),
            Triple("UPDATE fact_revision SET statement='changed' WHERE fact_revision_id='test-fact-r1'",
                "UPDATE fact_revision SET statement=? WHERE fact_revision_id='test-fact-r1'", "Synthetic test fixture 1 only"),
        )
        changes.forEach { (mutation, restore, value) ->
            val prepared = f.prepare()
            f.exec(mutation)
            check(!prepared.isCurrent())
            f.exec(restore, value)
        }
        val beforeLink = f.prepare()
        f.exec("INSERT INTO fact_source(fact_revision_id,source_revision_id) VALUES ('test-fact-r1','test-source-r2')")
        check(!beforeLink.isCurrent())
        f.exec("DELETE FROM fact_source WHERE fact_revision_id='test-fact-r1' AND source_revision_id='test-source-r2'")

        val beforeParagraph = f.prepare()
        val text = f.text("SELECT text FROM paragraph WHERE paragraph_id='test-p1'")
        f.exec("UPDATE paragraph SET text='changed public paragraph' WHERE paragraph_id='test-p1'")
        check(!beforeParagraph.isCurrent())
        check(f.builder.prepare(f.effect, f.turnId) { true } == null)
        f.exec("UPDATE paragraph SET text=? WHERE paragraph_id='test-p1'", text)
        val invented = f.effect.copy(candidates = listOf(StoryCheckpoint(f.story.copy(storyId = "unoffered-story"), 0)))
        check(f.builder.prepare(invented, f.turnId) { true } == null)
    }

    private fun verifyFrozenPayloadAndSession(context: Context) = Fixture(context).use { f ->
        check(f.builder.prepare(f.effect, null) { true } == null)
        check(f.builder.prepare(f.effect.copy(afterInteraction = false), f.turnId) { true } == null)
        f.store.registerTurn("summary-trip", "summary-session", "no-final")
        f.store.freezeTurn(PlaybackEffect.FreezeTurn("no-final", TurnOutcome.CANCELLED, false, emptyList()))
        check(f.builder.prepare(f.effect, "no-final") { true } == null)
        f.store.registerTurn("summary-trip", "summary-session", "still-open")
        f.store.persistUtterance(OperationToken("summary-session", 0, "open-final"), "still-open", RuntimeText("synthetic unfinished turn"))
        check(f.builder.prepare(f.effect, "still-open") { true } == null)

        var prepared = f.prepare()
        f.exec("UPDATE playback_attempt SET transient_paragraph_key='unmatched-key' WHERE transient_paragraph_key=? AND state='completed'", f.keys.completed)
        check(!prepared.isCurrent())
        check(f.builder.prepare(f.effect, f.turnId) { true } == null)
        f.exec("UPDATE playback_attempt SET transient_paragraph_key=? WHERE transient_paragraph_key='unmatched-key'", f.keys.completed)

        prepared = f.prepare()
        val hash = f.text("SELECT payload_hash FROM pending_conversation WHERE turn_id='resume-context-turn'")
        f.exec("UPDATE pending_conversation SET payload_hash=? WHERE turn_id=?", "0".repeat(64), f.turnId)
        check(!prepared.isCurrent())
        check(f.builder.prepare(f.effect, f.turnId) { true } == null)
        f.exec("UPDATE pending_conversation SET payload_hash=? WHERE turn_id=?", hash, f.turnId)

        prepared = f.prepare()
        f.exec("UPDATE pending_conversation SET payload_version=payload_version+1 WHERE turn_id=?", f.turnId)
        check(!prepared.isCurrent())
        f.exec("UPDATE pending_conversation SET payload_version=payload_version-1 WHERE turn_id=?", f.turnId)
        prepared = f.prepare()
        f.exec("UPDATE session SET cancel_epoch=1 WHERE session_id='summary-session'")
        check(!prepared.isCurrent())
        f.exec("UPDATE session SET cancel_epoch=0 WHERE session_id='summary-session'")
        // The earlier open-turn rejection is complete; session close requires every turn frozen.
        f.store.freezeTurn(PlaybackEffect.FreezeTurn("still-open", TurnOutcome.CANCELLED, true, emptyList()))
        prepared = f.prepare()
        f.store.closeSession("summary-session")
        check(!prepared.isCurrent())
        check(f.builder.prepare(f.effect, f.turnId) { true } == null)
    }

    private fun verifySummaryRace(context: Context) = Fixture(context).use { f ->
        val prepared = f.prepare()
        f.store.summaryStore().use { summary ->
            val claim = summary.claim(f.turnId)
            check(claim is SummaryClaim.Claimed && prepared.isCurrent())
            val work = claim.work
            check(summary.commit(work, ValidatedSummaryResult("SYNTHETIC_SAVED_SUMMARY_MUST_NOT_BE_REUSED", emptyList(),
                listOf("test-fact-r1"), emptyList(), listOf(f.keys.completed), listOf(f.keys.interrupted),
                "synthetic-model", "synthetic-provider", "synthetic-prompt")))
            check(!prepared.isCurrent())
            check(f.builder.prepare(f.effect, f.turnId) { true } == null)
            check(f.count("SELECT count(*) FROM pending_conversation") == 0L)
            check(f.count("SELECT count(*) FROM conversation_summary") == 1L)
        }
    }

    private fun verifyBoundsAndHostGuard(context: Context) {
        Fixture(context, publicText = "SYNTHETIC_PUBLIC_LONG_".repeat(160)).use { f ->
            val prepared = f.prepare()
            val parsed = StrictJson.parse(prepared.contextSummary.toByteArray(Charsets.UTF_8)).jsonObject()
            check(prepared.contextSummary.length <= 2_000)
            check(parsed["omittedPublicCandidateCount"] == 1L)
            check((parsed["publicCandidates"] as List<*>).isEmpty())
            check((parsed["completedResponses"] as List<*>).size == 1)
            f.store.registerTurn("summary-trip", "summary-session", "long-turn")
            f.store.persistUtterance(OperationToken("summary-session", 0, "long-final"), "long-turn", RuntimeText("x".repeat(4_000)))
            f.store.freezeTurn(PlaybackEffect.FreezeTurn("long-turn", TurnOutcome.CLOSED, true, emptyList()))
            check(f.builder.prepare(f.effect, "long-turn") { true } == null)
            var hostPermits = true
            val musicEffect = f.effect.copy(candidates = emptyList(), musicCandidates = listOf(MusicCheckpoint(PreparedMusic("synthetic-music", 60_000), 1_234)))
            val music = f.builder.prepare(musicEffect, f.turnId) { hostPermits }
            check(music != null && music.isCurrent())
            hostPermits = false
            check(!music.isCurrent())
            check(f.builder.prepare(musicEffect, f.turnId) { hostPermits } == null)
        }
        Fixture(context).use { f ->
            val prepared = f.prepare()
            SyntheticStorageFixture.deleteEncryptionKey(f.name)
            check(!prepared.isCurrent())
            check(f.builder.prepare(f.effect, f.turnId) { true } == null)
            check(f.count("SELECT count(*) FROM pending_conversation") == 1L)
            check(f.count("SELECT count(*) FROM conversation_summary") == 0L)
        }
    }

    private fun verifyClientBoundaries(context: Context) {
        Fixture(context).use { f ->
            val prepared = f.prepare()
            val transport = SyntheticResumeTransport()
            client(transport) { f.deny("generation") }.use { client ->
                val pending = CompletableFuture<GenerationOutcome<ValidatedResumePlan>>()
                client.submitResume(f.effect.request, wireContext(prepared)) { pending.complete(it) }
                val outcome = pending.get(5, TimeUnit.SECONDS)
                check(outcome is GenerationOutcome.Failure && outcome.failure.kind == GenerationFailureKind.STALE)
                check(transport.calls.get() == 0)
            }
        }
        Fixture(context).use { f ->
            val prepared = f.prepare()
            val transport = SyntheticResumeTransport(delayed = true)
            try {
                client(transport).use { client ->
                    val pending = CompletableFuture<GenerationOutcome<ValidatedResumePlan>>()
                    client.submitResume(f.effect.request, wireContext(prepared)) { pending.complete(it) }
                    check(transport.started.await(2, TimeUnit.SECONDS))
                    f.deny("distribution")
                    transport.release.countDown()
                    val outcome = pending.get(5, TimeUnit.SECONDS)
                    check(outcome is GenerationOutcome.Failure && outcome.failure.kind == GenerationFailureKind.STALE)
                    check(transport.calls.get() == 1)
                    check(f.count("SELECT count(*) FROM pending_conversation") == 1L)
                }
            } finally { transport.release.countDown() }
        }
    }

    private class Fixture(private val context: Context, publicText: String = "합성 저장소 시험 문단 1입니다.") : AutoCloseable {
        val name = "resume-context-probe-${UUID.randomUUID()}.db"
        private val database = StorageDatabase.open(context, name)
        private val cipher = PayloadCipher(AndroidPayloadKeys("com.petasos.app.tests.$name"))
        val store = RoomRuntimePersistence(database, cipher, System::currentTimeMillis, SystemClock::elapsedRealtime, UUID.randomUUID().toString())
        val builder = RoomResumeGenerationContext(database, cipher, System::currentTimeMillis)
        val turnId = "resume-context-turn"
        val story: PreparedStory
        val keys: SummaryStorageFixture.Keys
        val effect: PlaybackEffect.PlanResume
        init {
            story = SyntheticStorageFixture.install(store, publicText)
            store.openSession("summary-trip", "summary-session")
            keys = SummaryStorageFixture.prepareFrozenTurn(store, turnId)
            effect = PlaybackEffect.PlanResume(OperationToken("summary-session", 0, "resume-context-request"), listOf(StoryCheckpoint(story, 0)), afterInteraction = true)
        }
        fun prepare(): ApiResumeContext = checkNotNull(builder.prepare(effect, turnId) { true }) { "resume_context_unavailable" }
        fun deny(kind: String) {
            if (kind == "review") exec("UPDATE source_evidence SET review_status='rejected' WHERE source_revision_id='test-source-r1'")
            else exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'",
                StrictJson.stringify(SyntheticStorageFixture.allowedUses(kind != "generation", kind != "display", kind != "distribution")))
        }
        fun restoreRights() = exec("UPDATE source_evidence SET review_status='verified',allowed_uses_json=? WHERE source_revision_id='test-source-r1'",
            StrictJson.stringify(SyntheticStorageFixture.allowedUses()))
        fun exec(statement: String, vararg args: Any?) = database.openHelper.writableDatabase.execSQL(statement, args)
        fun count(statement: String): Long = database.openHelper.writableDatabase.query(statement).use { check(it.moveToFirst()); it.getLong(0) }
        fun text(statement: String): String = database.openHelper.writableDatabase.query(statement).use { check(it.moveToFirst()); it.getString(0) }
        fun payloadBytes(): ByteArray = database.openHelper.writableDatabase.query(SimpleSQLiteQuery(
            "SELECT ciphertext FROM pending_conversation WHERE turn_id=?", arrayOf(turnId),
        )).use { check(it.moveToFirst()); it.getBlob(0) }
        override fun close() { store.close(); SyntheticStorageFixture.cleanup(context, name) }
    }

    private class SyntheticResumeTransport(delayed: Boolean = false) : GenerationTransport {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(if (delayed) 1 else 0)
        override fun execute(configuration: DeviceGenerationConfiguration, path: String, body: ByteArray?, exchange: GenerationExchange): GenerationHttpResponse {
            check(path == "/v1/generations" && body != null)
            calls.incrementAndGet()
            val raw = body.toString(Charsets.UTF_8)
            check(!raw.contains("INTERRUPTED_BODY_MUST_NOT_LEAVE") && !raw.contains("UNPLAYED_BODY_MUST_NOT_LEAVE"))
            val request = StrictJson.parse(body).jsonObject()
            started.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            val response = request.filterKeys { it !in setOf("payload", "inputHash") } + mapOf(
                "status" to "ok", "result" to mapOf("action" to "resume", "targetContentId" to "test-story-v1",
                    "paragraphId" to "test-p1", "transitionText" to null, "reason" to "synthetic selection"),
                "metadata" to mapOf("modelId" to "synthetic-model", "provider" to "synthetic-provider", "promptVersion" to "synthetic-prompt",
                    "inputTokens" to null, "outputTokens" to null))
            return GenerationHttpResponse(200, StrictJson.encode(response), null)
        }
    }
    private fun wireContext(context: ApiResumeContext) = ResumeGenerationContext(context.contextSummary,
        listOf(ResumeGenerationCandidate("test-story-v1", "test-p1", "story")), context.routeRevision, context.isCurrent)
    private fun client(transport: GenerationTransport, beforeConfiguration: () -> Unit = {}) = GenerationClient(
        GenerationConfigurationSource { beforeConfiguration(); DeviceGenerationConfiguration("https://example.invalid", "synthetic-test-token-0000000000000000") },
        GenerationInputHasher { ContentBundleCodec.sha256(it) }, transport)
}
