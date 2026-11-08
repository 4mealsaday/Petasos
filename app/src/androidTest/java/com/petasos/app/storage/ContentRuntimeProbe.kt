package com.petasos.app.storage

import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentBundle
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.content.ContentFact
import com.petasos.app.content.ContentParagraph
import com.petasos.app.content.ContentVersion
import com.petasos.app.generation.*
import com.petasos.app.platform.android.runtime.AndroidRuntimeClock
import com.petasos.app.platform.android.runtime.AndroidRuntimeQueue
import com.petasos.app.playback.*
import com.petasos.app.runtime.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual Room/Keystore + installed content + runtime/client boundaries. No microphone, audio or HTTP. */
object ContentRuntimeProbe {
    private const val FINAL_TEXT = "실제 사용자 발화가 아닌 콘텐츠 연결 검사용 합성 입력입니다."
    private const val ANSWER_TEXT = "이 응답은 콘텐츠 연결 검증용 합성 문장입니다."

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val incoming = File(context.cacheDir, "content-bundle.json")
        try {
            verifyStoredContextAndRights(context, mark)
            verifyBeforeSpeechGuards(context, mark)
            verifyMalformedHandleControlRace(context, mark)
            verifyRegisteredOutputDenial(context, mark)
            verifyAnswerCommitDenial(context, mark)
            runRuntime(context, SyntheticStorageFixture.bundle(), mark, stopDuringGeneration = false, realBundle = false)
            runRuntime(context, SyntheticStorageFixture.bundle(), mark, stopDuringGeneration = true, realBundle = false)
            if (incoming.isFile) {
                runRuntime(context, readBundle(incoming), mark, stopDuringGeneration = false, realBundle = true)
            } else mark("content_runtime_no_external_bundle_supplied_synthetic_checks_only")
        } finally { incoming.delete() }
    }

    private fun verifyStoredContextAndRights(context: Context, mark: (String) -> Unit) {
        val name = "content-context-probe-${UUID.randomUUID()}.db"
        try {
            SyntheticStorageFixture.create(context, name).use { store ->
                val story = SyntheticStorageFixture.install(store)
                store.openSession("context-trip", "old-session")
                exec(store, "INSERT INTO playback_attempt(attempt_id,session_id,kind,paragraph_id,state,started_at,completed_at,clock_scope_id) VALUES ('old-complete','old-session','story','test-p2','completed',1,2,'test-clock')")
                store.openSession("context-trip", "current-session")
                // A terminal interrupted row and a completed row in another session must not become heard evidence.
                exec(store, "INSERT INTO playback_attempt(attempt_id,session_id,kind,paragraph_id,state,started_at,interrupted_at,clock_scope_id) VALUES ('current-incomplete','current-session','story','test-p2','interrupted',1,2,'test-clock')")
                val attempt = OperationToken("current-session", 0, "current-complete")
                val reference = OutputReference.StoryParagraph(story.storyId, story.versionId, "test-p1")
                store.registerOutput(attempt, reference, null)
                store.markOutputStarted(attempt)
                val checkpoint = StoryCheckpoint(story, 1)
                store.commitPlayback(PlaybackEffect.CommitPlayback(attempt, reference, AttemptOutcome.COMPLETED, checkpoint),
                    PlaybackSnapshot("context-trip", "current-session", PlaybackMode.STORY, 1, 0, true, false,
                        emptySet(), AdapterPhase.READY, AdapterPhase.READY, false, null, checkpoint, 0, false))
                store.registerTurn("context-trip", "current-session", "context-turn")
                store.persistUtterance(OperationToken("current-session", 0, "context-final"), "context-turn", RuntimeText(FINAL_TEXT))
                fun request(id: String = UUID.randomUUID().toString(), session: String = "current-session", turn: String = "context-turn") =
                    RuntimeGenerationRequest(OperationToken(session, 0, id), turn, 1, RuntimeText(FINAL_TEXT))
                val current = AtomicBoolean(true)
                val initial = checkNotNull(store.answerContext(request()) { current.get() })
                check(initial.heardParagraphIds == listOf("test-p1"))
                check(initial.sourceRevisionIds == listOf("test-source-r1"))
                check(initial.approvedFactSources == mapOf("test-fact-r1" to setOf("test-source-r1")))
                check(initial.contextSummary.isNotBlank() && initial.contextSummary.length <= 4000)
                check(initial.isCurrent())
                check(store.answerContext(request(session = "old-session")) { true } == null)
                check(store.answerContext(request(turn = "unknown-turn")) { true } == null)
                current.set(false)
                check(!initial.isCurrent())
                current.set(true)
                mark("content_context_only_current_session_completed_paragraphs_and_linked_facts")

                val transport = SyntheticTransport("test-fact-r1", listOf("test-source-r1"), "verified_fact")
                client(transport).use { client ->
                    val generation = ApiRuntimeGeneration(client) { store.answerContext(it) { true } }
                    rights(store, "test-source-r1", generation = false)
                    check(store.prepareStory(story.versionId).paragraphIds == story.paragraphIds)
                    val forbidden = awaitGeneration(generation, request())
                    check(forbidden is RuntimeGenerationResult.Failure && !forbidden.retryable)
                    check(transport.calls.get() == 0)
                    rights(store, "test-source-r1")
                }
                mark("content_generation_forbidden_still_displayable_without_transport_call")

                val delayed = SyntheticTransport("test-fact-r1", listOf("test-source-r1"), "verified_fact", delayed = true)
                try {
                    client(delayed).use { client ->
                        val generation = ApiRuntimeGeneration(client) { store.answerContext(it) { true } }
                        val result = CompletableFuture<RuntimeGenerationResult>()
                        generation.generate(request()) { result.complete(it) }
                        check(delayed.started.await(5, TimeUnit.SECONDS)) { "synthetic_transport_not_started" }
                        rights(store, "test-source-r1", generation = false)
                        delayed.release.countDown()
                        check(result.get(5, TimeUnit.SECONDS) is RuntimeGenerationResult.Failure)
                        check(count(store, "SELECT turn_version FROM conversation_turn WHERE turn_id='context-turn'") == 1L)
                    }
                } finally { delayed.release.countDown(); rights(store, "test-source-r1") }
                mark("content_late_generation_response_rejected_after_source_rights_revocation")

                val answer = RuntimeAnswer(listOf(RuntimeParagraph("stored-answer", RuntimeText(ANSWER_TEXT), listOf("test-source-r1"))))
                val beforeAnswer = checkNotNull(store.answerContext(request()) { true })
                rights(store, "test-source-r1", display = false)
                rejected("answer_source_unavailable") {
                    store.persistAnswer(OperationToken("current-session", 0, "forbidden-answer"), "context-turn", answer)
                }
                check(count(store, "SELECT turn_version FROM conversation_turn WHERE turn_id='context-turn'") == 1L)
                rights(store, "test-source-r1")
                val validated = store.persistAnswer(OperationToken("current-session", 0, "stored-answer"), "context-turn", answer)
                check(!beforeAnswer.isCurrent()) // Persisting the answer advanced the durable turn version.
                val answerRef = OutputReference.AnswerParagraph("context-turn", validated.payload, "stored-answer")
                check(store.loadOutput(answerRef).value == ANSWER_TEXT)
                rights(store, "test-source-r1", distribution = false)
                rejected("answer_source_unavailable") { store.loadOutput(answerRef) }
                rejected("answer_source_unavailable") {
                    store.registerOutput(OperationToken("current-session", 0, "forbidden-output"), answerRef, "context-turn")
                }
                check(count(store, "SELECT count(*) FROM playback_attempt WHERE attempt_id='forbidden-output'") == 0L)
                rights(store, "test-source-r1")
                mark("content_answer_commit_and_output_recheck_rights_and_turn_version")

                store.registerTurn("context-trip", "current-session", "session-turn")
                store.persistUtterance(OperationToken("current-session", 0, "session-final"), "session-turn", RuntimeText(FINAL_TEXT))
                val oldContext = checkNotNull(store.answerContext(request(turn = "session-turn")) { true })
                store.openSession("context-trip", "replacement-session")
                check(!oldContext.isCurrent())
                check(store.answerContext(request(turn = "session-turn")) { true } == null)
                check(count(store, "SELECT count(*) FROM pending_conversation") == 2L)
                mark("content_generation_context_invalidated_by_session_replacement_without_payload_loss")
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }
    }

    private fun verifyBeforeSpeechGuards(context: Context, mark: (String) -> Unit) {
        listOf(false, true).forEach { tamper ->
            Harness(context, SyntheticStorageFixture.bundle()).use { harness ->
                val prepared = onWorker(harness.queue) { harness.store.prepareStory(harness.versionId) }
                onWorker(harness.queue) {
                    if (tamper) exec(harness.store, "UPDATE paragraph SET text='Unreviewed mutation' WHERE paragraph_id='test-p1'")
                    else rights(harness.store, "test-source-r1", display = false)
                }
                // A previously prepared handle is not authority to bypass current stored rights.
                if (tamper) harness.runtime.playInstalledStory(harness.versionId)
                else harness.runtime.dispatch(PlaybackEvent.PlayStory(prepared))
                check(harness.issues.poll(5, TimeUnit.SECONDS) == RuntimeIssue.CONTENT_UNAVAILABLE)
                onWorker(harness.queue) {
                    check(harness.speech.outputCalls.get() == 0)
                    check(count(harness.store, "SELECT count(*) FROM playback_attempt") == 0L)
                    check(count(harness.store, "SELECT count(*) FROM collection_record") == 0L)
                    if (tamper) exec(harness.store, "UPDATE paragraph SET text='합성 저장소 시험 문단 1입니다.' WHERE paragraph_id='test-p1'")
                    else rights(harness.store, "test-source-r1")
                }
                recoverAndClose(harness)
            }
        }
        mark("content_preparation_and_unregistered_output_denial_create_no_attempt_and_same_runtime_recovers")
    }

    private fun verifyRegisteredOutputDenial(context: Context, mark: (String) -> Unit) {
        var injected = false
        Harness(context, SyntheticStorageFixture.bundle(), persistence = { store ->
            object : RuntimePersistence by store {
                override fun registerOutput(attempt: OperationToken, reference: OutputReference, turnId: String?) {
                    store.registerOutput(attempt, reference, turnId)
                    if (!injected && reference is OutputReference.StoryParagraph) {
                        injected = true
                        rights(store, "test-source-r1", display = false)
                    }
                }
            }
        }).use { harness ->
            harness.runtime.playInstalledStory(harness.versionId)
            check(harness.issues.poll(5, TimeUnit.SECONDS) == RuntimeIssue.CONTENT_UNAVAILABLE)
            harness.awaitState { it.mode == PlaybackMode.PAUSED && it.pendingPlaybackCommits == 0 && it.outputPhase == AdapterPhase.READY }
            onWorker(harness.queue) {
                check(injected && harness.speech.outputCalls.get() == 0)
                check(count(harness.store, "SELECT count(*) FROM playback_attempt") == 1L)
                check(count(harness.store, "SELECT count(*) FROM playback_attempt WHERE state='interrupted' AND started_at IS NULL AND interrupted_at IS NOT NULL") == 1L)
                check(count(harness.store, "SELECT count(*) FROM playback_attempt WHERE state='completed'") == 0L)
                check(count(harness.store, "SELECT count(*) FROM collection_record") == 0L)
                rights(harness.store, "test-source-r1")
            }
            recoverAndClose(harness)
        }
        mark("content_registered_output_denial_commits_real_interruption_without_speech_and_same_runtime_recovers")
    }

    private fun verifyMalformedHandleControlRace(context: Context, mark: (String) -> Unit) {
        listOf(false, true).forEach { closeFromIssue ->
            val injected = AtomicBoolean()
            Harness(context, SyntheticStorageFixture.bundle(), issueCallback = { runtime, issue ->
                if (issue == RuntimeIssue.CONTENT_UNAVAILABLE && injected.compareAndSet(false, true)) {
                    // Enqueue the control from the rejection callback itself, before the runtime
                    // can enqueue OutputNotRegistered. A normal later Stop does not reproduce this race.
                    if (closeFromIssue) runtime.close() else runtime.dispatch(PlaybackEvent.Stop)
                }
            }).use { harness ->
                val malformed = PreparedStory("test-story", "test-story-v1", listOf("missing-story-paragraph"))
                harness.runtime.dispatch(PlaybackEvent.PlayStory(malformed))
                if (closeFromIssue) harness.awaitState(setOf(RuntimeIssue.CONTENT_UNAVAILABLE)) { it.closed }
                else {
                    harness.awaitState(setOf(RuntimeIssue.CONTENT_UNAVAILABLE)) {
                        it.mode == PlaybackMode.PAUSED && !it.playIntent && it.pendingPlaybackCommits == 0 && it.outputPhase == AdapterPhase.READY
                    }
                    harness.runtime.close()
                    harness.awaitState(setOf(RuntimeIssue.CONTENT_UNAVAILABLE)) { it.closed }
                }
                onWorker(harness.queue) {
                    check(injected.get())
                    check(harness.speech.outputCalls.get() == 0)
                    check(count(harness.store, "SELECT count(*) FROM playback_attempt") == 0L)
                    check(count(harness.store, "SELECT count(*) FROM collection_record") == 0L)
                    check(count(harness.store, "SELECT count(*) FROM session WHERE session_id=? AND status='closed'", harness.sessionId) == 1L)
                    check(count(harness.store, "SELECT count(*) FROM playback_checkpoint WHERE paragraph_id IS NOT NULL OR last_completed_paragraph_id IS NOT NULL") == 0L)
                    check(harness.issues.none { it != RuntimeIssue.CONTENT_UNAVAILABLE })
                }
            }
        }
        mark("content_invalid_handle_issue_callback_stop_and_close_do_not_poison_storage_or_checkpoint")
    }

    private fun verifyAnswerCommitDenial(context: Context, mark: (String) -> Unit) {
        var injected = false
        Harness(context, SyntheticStorageFixture.bundle(), persistence = { store ->
            object : RuntimePersistence by store {
                override fun persistAnswer(commit: OperationToken, turnId: String, answer: RuntimeAnswer): ValidatedAnswer {
                    if (!injected) {
                        injected = true
                        rights(store, "test-source-r1", display = false)
                    }
                    return store.persistAnswer(commit, turnId, answer)
                }
            }
        }).use { harness ->
            harness.runtime.playInstalledStory(harness.versionId)
            check(harness.speech.firstStarted.await(5, TimeUnit.SECONDS))
            harness.runtime.dispatch(PlaybackEvent.Talk)
            harness.speech.completeFirst()
            check(harness.issues.poll(5, TimeUnit.SECONDS) == RuntimeIssue.CONTENT_UNAVAILABLE)
            // The fixed failure prompt may run, then the old story is independently denied while its rights remain revoked.
            harness.awaitState(setOf(RuntimeIssue.CONTENT_UNAVAILABLE)) {
                it.turnId == null && it.pendingPlaybackCommits == 0 && it.outputPhase == AdapterPhase.READY &&
                    it.mode in setOf(PlaybackMode.PAUSED, PlaybackMode.IDLE)
            }
            onWorker(harness.queue) {
                check(injected)
                check(count(harness.store, "SELECT count(*) FROM pending_conversation WHERE payload_version=1 AND length(ciphertext)>0") == 1L)
                check(count(harness.store, "SELECT count(*) FROM conversation_turn WHERE frozen=1 AND lifecycle<>'open' AND turn_version=1") == 1L)
                check(count(harness.store, "SELECT count(*) FROM playback_attempt WHERE kind='answer'") == 0L)
                check(count(harness.store, "SELECT count(*) FROM playback_attempt WHERE state IN ('prepared','playing')") == 0L)
                rights(harness.store, "test-source-r1")
            }
            recoverAndClose(harness)
        }
        mark("content_answer_commit_denial_preserves_final_payload_freezes_turn_and_same_runtime_recovers")
    }

    /** Restoring a right alone never starts speech; an explicit installed-story request is required. */
    private fun recoverAndClose(harness: Harness) {
        val outputs = harness.speech.outputCalls.get()
        val prior = onWorker(harness.queue) { count(harness.store, "SELECT count(*) FROM playback_attempt WHERE kind='story' AND state='completed'") }
        onWorker(harness.queue) { check(harness.speech.outputCalls.get() == outputs) }
        harness.runtime.playInstalledStory(harness.versionId)
        if (outputs == 0) {
            check(harness.speech.firstStarted.await(5, TimeUnit.SECONDS))
            harness.speech.completeFirst()
        }
        harness.awaitState { it.mode == PlaybackMode.IDLE && it.checkpoint?.paragraphIndex == harness.paragraphCount && it.pendingPlaybackCommits == 0 }
        onWorker(harness.queue) {
            check(count(harness.store, "SELECT count(*) FROM playback_attempt WHERE kind='story' AND state='completed'") == prior + harness.paragraphCount)
            check(count(harness.store, "SELECT count(*) FROM playback_attempt WHERE state IN ('prepared','playing')") == 0L)
        }
        harness.runtime.close()
        harness.awaitState { it.closed }
        onWorker(harness.queue) { check(count(harness.store, "SELECT count(*) FROM session WHERE session_id=? AND status='closed'", harness.sessionId) == 1L) }
    }

    private fun runRuntime(context: Context, bytes: ByteArray, mark: (String) -> Unit, stopDuringGeneration: Boolean, realBundle: Boolean) {
        Harness(context, bytes, delayed = stopDuringGeneration).use { harness ->
            harness.runtime.playInstalledStory(harness.versionId)
            check(harness.speech.firstStarted.await(5, TimeUnit.SECONDS)) { "content_first_output_not_started" }
            // Queue Talk while the first installed paragraph is playing; only its committed completion is evidence.
            harness.runtime.dispatch(PlaybackEvent.Talk)
            harness.speech.completeFirst()
            if (stopDuringGeneration) {
                check(harness.transport.started.await(5, TimeUnit.SECONDS)) { "content_generation_not_started" }
                val request = checkNotNull(harness.lastRequest.get())
                check(harness.runtime.isGenerationCurrent(request))
                harness.runtime.dispatch(PlaybackEvent.Stop)
                check(!harness.runtime.isGenerationCurrent(request)) // Ingress fence, before queue processing.
                harness.transport.release.countDown()
                check(harness.transport.returned.await(5, TimeUnit.SECONDS))
                harness.awaitState { it.mode == PlaybackMode.PAUSED && !it.interactionIntent && it.pendingPlaybackCommits == 0 }
                onWorker(harness.queue) {
                    check(count(harness.store, "SELECT count(*) FROM playback_attempt WHERE kind='answer'") == 0L)
                    check(count(harness.store, "SELECT count(*) FROM conversation_turn WHERE turn_version>1") == 0L)
                }
                mark("content_runtime_stop_invalidates_generation_before_queue_and_late_answer_not_committed")
            } else {
                harness.awaitState { it.mode == PlaybackMode.IDLE && it.checkpoint?.paragraphIndex == harness.paragraphCount && it.pendingPlaybackCommits == 0 }
                onWorker(harness.queue) {
                    val answerContext = checkNotNull(harness.lastContext.get())
                    check(answerContext.heardParagraphIds == listOf(harness.firstParagraphId))
                    check(answerContext.sourceRevisionIds.toSet() == harness.firstSources)
                    check(harness.transport.calls.get() == 1)
                    check(count(harness.store, "SELECT count(*) FROM playback_attempt WHERE session_id=? AND kind='story' AND state='completed'", harness.sessionId) == harness.paragraphCount.toLong())
                    check(count(harness.store, "SELECT count(*) FROM playback_attempt WHERE kind='answer' AND state='completed'") == 1L)
                    check(count(harness.store, "SELECT count(*) FROM pending_conversation") == 1L)
                    check(count(harness.store, "SELECT count(*) FROM conversation_turn WHERE lifecycle='closed' AND frozen=1 AND turn_version=3") == 1L)
                    check(count(harness.store, "SELECT count(*) FROM pending_conversation WHERE length(ciphertext)>0 AND length(nonce)=12") == 1L)
                }
                mark(if (realBundle) "content_runtime_real_reviewed_bundle_completed_context_generated_and_encrypted_answer_committed"
                    else "content_runtime_installed_synthetic_bundle_completed_context_generated_and_encrypted_answer_committed")
            }
            harness.runtime.close()
            harness.awaitState { it.closed }
            onWorker(harness.queue) {
                check(count(harness.store, "SELECT count(*) FROM session WHERE session_id=? AND status='closed'", harness.sessionId) == 1L)
            }
        }
    }

    private class Harness(context: Context, bytes: ByteArray, delayed: Boolean = false,
        persistence: (RoomRuntimePersistence) -> RuntimePersistence = { it },
        issueCallback: (PlaybackRuntime, RuntimeIssue) -> Unit = { _, _ -> },
    ) : AutoCloseable {
        private val context = context
        private val name = "content-runtime-probe-${UUID.randomUUID()}.db"
        val queue = AndroidRuntimeQueue()
        val issues = LinkedBlockingQueue<RuntimeIssue>()
        private val transitions = LinkedBlockingQueue<PlaybackTransition>()
        val speech = ScriptedSpeech()
        val lastRequest = AtomicReference<RuntimeGenerationRequest?>()
        val lastContext = AtomicReference<AnswerGenerationContext?>()
        private val bundle: ContentBundle = ContentBundleCodec.decode(bytes, System.currentTimeMillis())
        private val version: ContentVersion = bundle.versions.first()
        private val first: ContentParagraph = version.paragraphs.first()
        private val fact: ContentFact = bundle.facts.first { it.factRevisionId == first.factRevisionIds.first() }
        val versionId = version.storyVersionId
        val paragraphCount = version.paragraphs.size
        val firstParagraphId = first.paragraphId
        val firstSources = first.factRevisionIds.flatMap { id -> bundle.facts.single { it.factRevisionId == id }.sourceRevisionIds }.toSet()
        val sessionId = UUID.randomUUID().toString()
        val transport = SyntheticTransport(fact.factRevisionId, fact.sourceRevisionIds, fact.kind, delayed)
        val store = onWorker(queue) {
            SyntheticStorageFixture.create(context, name).also { store ->
                RoomContentInstaller(store.database, System::currentTimeMillis, ownsDatabase = false).use { it.install(bytes) }
            }
        }
        private val client = client(transport)
        lateinit var runtime: PlaybackRuntime
            private set

        init {
            val generation = ApiRuntimeGeneration(client) { request ->
                lastRequest.set(request)
                store.answerContext(request) { runtime.isGenerationCurrent(request) }.also { lastContext.set(it) }
            }
            runtime = PlaybackRuntime(PlaybackCoordinator(UUID.randomUUID().toString(), sessionId), queue,
                AndroidRuntimeClock, persistence(store), speech, generation, object : RuntimeObserver {
                    override fun onTransition(transition: PlaybackTransition) { transitions.offer(transition) }
                    override fun onIssue(issue: RuntimeIssue) {
                        issues.offer(issue)
                        issueCallback(runtime, issue)
                    }
                })
        }

        fun awaitState(allowedIssues: Set<RuntimeIssue> = emptySet(), matches: (PlaybackSnapshot) -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < deadline) {
                while (true) {
                    val issue = issues.poll() ?: break
                    check(issue in allowedIssues) { "content_runtime_issue" }
                }
                if (matches(runtime.snapshot())) return
                transitions.poll(50, TimeUnit.MILLISECONDS)
            }
            error("content_runtime_transition_timeout")
        }

        override fun close() {
            transport.release.countDown()
            runtime.close()
            client.close()
            try {
                onWorker(queue) { speech.close() }
                onWorker(queue) { store.close() }
            } finally {
                queue.close()
                SyntheticStorageFixture.cleanup(context, name)
            }
        }
    }

    private class ScriptedSpeech : RuntimeSpeech {
        val firstStarted = CountDownLatch(1)
        val outputCalls = AtomicInteger()
        @Volatile private var first: ((RuntimeOutputEvent) -> Unit)? = null
        private var input: ((RuntimeInputEvent) -> Unit)? = null
        override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) {
            callback(RuntimeOutputEvent.STARTED)
            if (outputCalls.getAndIncrement() == 0) {
                first = callback
                firstStarted.countDown()
            } else callback(RuntimeOutputEvent.COMPLETED)
        }
        fun completeFirst() { val callback = checkNotNull(first); first = null; callback(RuntimeOutputEvent.COMPLETED) }
        override fun stopOutput(attempt: OperationToken) { first?.invoke(RuntimeOutputEvent.STOPPED); first = null }
        override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) {
            this.input = callback
            callback(RuntimeInputEvent.Ready)
            callback(RuntimeInputEvent.Started)
            callback(RuntimeInputEvent.Final(RuntimeText(FINAL_TEXT)))
            callback(RuntimeInputEvent.Ended)
        }
        override fun stopInput(input: OperationToken) { this.input?.invoke(RuntimeInputEvent.StoppedConfirmed); this.input = null }
        override fun close() { first = null; input = null }
    }

    private class SyntheticTransport(private val fact: String, private val sources: List<String>, private val kind: String, delayed: Boolean = false) : GenerationTransport {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(if (delayed) 1 else 0)
        val returned = CountDownLatch(1)
        override fun execute(configuration: DeviceGenerationConfiguration, path: String, body: ByteArray?, exchange: GenerationExchange): GenerationHttpResponse {
            if (path.endsWith("/cancel")) return GenerationHttpResponse(204, byteArrayOf(), null)
            check(path == "/v1/generations" && body != null)
            calls.incrementAndGet()
            val request = StrictJson.parse(body).jsonObject()
            started.countDown()
            check(release.await(8, TimeUnit.SECONDS)) { "synthetic_transport_release_timeout" }
            val response = request.filterKeys { it !in setOf("payload", "inputHash") } + mapOf(
                "status" to "ok", "result" to mapOf("paragraphs" to listOf(mapOf(
                    "paragraphKey" to "synthetic-answer", "text" to ANSWER_TEXT,
                    "kind" to if (kind == "tradition") "tradition" else "fact", "sourceRevisionIds" to sources, "factRevisionIds" to listOf(fact))),
                    "action" to "continue", "targetContentId" to null),
                "metadata" to mapOf("modelId" to "synthetic-test", "provider" to "synthetic-test", "promptVersion" to "synthetic-test",
                    "inputTokens" to null, "outputTokens" to null))
            returned.countDown()
            return GenerationHttpResponse(200, StrictJson.encode(response), null)
        }
    }

    private fun client(transport: GenerationTransport) = GenerationClient(
        GenerationConfigurationSource { DeviceGenerationConfiguration("https://example.invalid", "synthetic-test-token-0000000000000000") },
        GenerationInputHasher { ContentBundleCodec.sha256(it) }, transport)

    private fun awaitGeneration(generation: RuntimeGeneration, request: RuntimeGenerationRequest): RuntimeGenerationResult {
        val result = CompletableFuture<RuntimeGenerationResult>()
        generation.generate(request) { result.complete(it) }
        return result.get(5, TimeUnit.SECONDS)
    }
    private fun rights(store: RoomRuntimePersistence, source: String, generation: Boolean = true, display: Boolean = true, distribution: Boolean = true) =
        exec(store, "UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id=?",
            StrictJson.stringify(SyntheticStorageFixture.allowedUses(generation, display, distribution)), source)
    private fun exec(store: RoomRuntimePersistence, statement: String, vararg args: Any?) { store.database.openHelper.writableDatabase.execSQL(statement, args) }
    private fun count(store: RoomRuntimePersistence, statement: String, vararg args: Any?): Long =
        store.database.openHelper.writableDatabase.query(SimpleSQLiteQuery(statement, args)).use { check(it.moveToFirst()); it.getLong(0) }
    private fun rejected(code: String, action: () -> Unit) {
        try { action() } catch (failure: RuntimeContentUnavailable) { check(failure.code == code) { "unexpected_content_rejection" }; return }
        error("content_operation_not_rejected")
    }
    private fun <T> onWorker(queue: AndroidRuntimeQueue, action: () -> T): T {
        val result = CompletableFuture<T>()
        queue.execute { try { result.complete(action()) } catch (failure: Throwable) { result.completeExceptionally(failure) } }
        return result.get(10, TimeUnit.SECONDS)
    }
    private fun readBundle(file: File): ByteArray = file.inputStream().use { input ->
        val bytes = ByteArray(StrictJson.MAX_BYTES + 1)
        var used = 0
        while (used < bytes.size) {
            val count = input.read(bytes, used, bytes.size - used)
            if (count < 0) break
            if (count > 0) used += count
        }
        check(used <= StrictJson.MAX_BYTES) { "content_bundle_too_large" }
        bytes.copyOf(used)
    }
}
