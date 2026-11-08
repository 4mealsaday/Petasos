package com.petasos.app.storage

import android.app.Instrumentation
import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.content.ContentParagraph
import com.petasos.app.generation.StrictJson
import com.petasos.app.playback.*
import com.petasos.app.runtime.*
import com.petasos.app.summary.SummaryClaim
import com.petasos.app.summary.ValidatedSummaryResult
import java.util.UUID

/** Real disposable Room/Keystore, scripted input/output only. No microphone, speaker, HTTP or model. */
object ExplicitPlaybackCommandProbe {
    class Failure(val code: String) : IllegalStateException(code)

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        stage("LOCAL_COMMAND_STOP_STORAGE_GATE") {
            Fixture(context).use { f ->
                f.listen()
                f.finalBeforeStopped(STOP)
                check(f.runtime.snapshot().inputPhase == AdapterPhase.STOPPING)
                check(f.number("SELECT count(*) FROM conversation_turn WHERE frozen=1") == 0L)
                check(f.number("SELECT count(*) FROM pending_conversation") == 1L)
                check(f.speech.stopRequests == 1)
                f.stopped()
                check(f.runtime.snapshot().mode == PlaybackMode.PAUSED)
                check(!f.runtime.snapshot().playIntent && !f.runtime.snapshot().interactionIntent)
                f.assertCommandTurn()
                f.assertUserOnlySummary(STOP, save = true)
                check(f.number("SELECT count(*) FROM pending_conversation") == 0L)
                check(f.number("SELECT count(*) FROM conversation_summary") == 1L)
                check(f.number("SELECT count(*) FROM playback_attempt WHERE kind<>'prompt'") == 0L)
            }
        }
        mark("local_command_requires_durable_final_and_scripted_stop_ack_user_only_summary_tx03")

        stage("LOCAL_COMMAND_CONTINUE") {
            Fixture(context).use { f ->
                f.listenFromStory()
                f.command(CONTINUE)
                check(f.runtime.snapshot().mode == PlaybackMode.STORY)
                check(f.lastStarted() == OutputReference.StoryParagraph("test-story", "test-story-v1", "test-p2"))
                f.assertCommandTurn()
                f.assertUserOnlySummary(CONTINUE)
                check(f.number("SELECT count(*) FROM playback_attempt WHERE kind='story' AND state='completed'") == 1L)
                check(f.number("SELECT count(*) FROM collection_record") == 0L)
            }
        }
        mark("local_command_continue_uses_checkpoint_without_answer_or_resume_provider")

        stage("LOCAL_COMMAND_NEXT") {
            Fixture(context).use { f ->
                f.listenFromStory()
                f.runtime.dispatch(PlaybackEvent.OfferStory(f.following, "synthetic-following"))
                f.queue.drain()
                f.command(NEXT)
                check(f.runtime.snapshot().checkpoint?.story?.storyId == "following-story")
                check(f.lastStarted() == OutputReference.StoryParagraph("following-story", "following-v1", "following-p1"))
                check(f.skipped == listOf("test-story"))
                check(f.number("SELECT count(*) FROM playback_attempt WHERE paragraph_id='test-p2'") == 0L)
                check(f.number("SELECT count(*) FROM playback_attempt WHERE kind='story' AND state='completed'") == 1L)
                check(f.number("SELECT count(*) FROM collection_record") == 0L)
                f.assertCommandTurn()
            }
        }
        mark("local_command_next_skips_prior_story_without_completion_or_collection_credit")

        stage("LOCAL_COMMAND_REPLAY") {
            Fixture(context).use { f ->
                f.listenFromStory()
                f.command(REPLAY)
                check(f.lastStarted() == OutputReference.StoryParagraph("test-story", "test-story-v1", "test-p1"))
                check(f.number("SELECT count(*) FROM playback_attempt WHERE paragraph_id='test-p1'") == 2L)
                check(f.number("SELECT count(*) FROM playback_attempt WHERE paragraph_id='test-p1' AND state='completed'") == 1L)
                check(f.number("SELECT count(*) FROM collection_record") == 0L)
                f.assertCommandTurn()
            }
        }
        mark("local_command_replay_creates_new_attempt_and_preserves_completed_history")

        stage("LOCAL_COMMAND_MISSING_TARGETS") {
            listOf(MUSIC to "command-music-unavailable", REPLAY to "command-replay-unavailable").forEach { (command, prompt) ->
                Fixture(context).use { f ->
                    f.listen()
                    f.runtime.dispatch(PlaybackEvent.OfferStory(f.following, "synthetic-following"))
                    f.queue.drain()
                    f.command(command)
                    check(f.lastStarted() == OutputReference.FixedPrompt(prompt))
                    f.completeOutput()
                    check(f.runtime.snapshot().mode == PlaybackMode.PAUSED)
                    check(!f.runtime.snapshot().playIntent && !f.runtime.snapshot().interactionIntent)
                    f.runtime.dispatch(PlaybackEvent.OfferStory(f.story, "synthetic-late-story"))
                    f.queue.drain()
                    check(f.number("SELECT count(*) FROM playback_attempt WHERE kind IN ('story','music','answer')") == 0L)
                    check(f.number("SELECT count(*) FROM playback_attempt WHERE asset_id=? AND state='completed'", prompt) == 1L)
                    f.assertCommandTurn()
                    f.assertUserOnlySummary(command)
                }
            }
        }
        mark("local_command_unavailable_music_or_replay_uses_public_prompt_then_pauses_without_story_fallback")

        stage("LOCAL_COMMAND_REVOKED_REPLAY") {
            Fixture(context).use { f ->
                f.listenFromStory()
                f.finalBeforeStopped(REPLAY)
                val starts = f.speech.started.size
                f.exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'",
                    StrictJson.stringify(SyntheticStorageFixture.allowedUses(display = false)))
                f.stopped()
                check(f.speech.started.drop(starts).none { f.references[it] is OutputReference.StoryParagraph })
                check(f.number("SELECT count(*) FROM playback_attempt WHERE paragraph_id='test-p1'") == 1L)
                f.assertNoAnswer()
                check(f.number("SELECT count(*) FROM pending_conversation") == 1L)
            }
        }
        mark("local_command_replay_rechecks_current_content_rights_before_scripted_output")

        stage("LOCAL_COMMAND_LATE_STOP") {
            Fixture(context).use { f ->
                f.listenFromStory()
                f.finalBeforeStopped(REPLAY)
                val starts = f.speech.started.size
                // The input callback is already queued; Stop revokes its start authority at ingress.
                f.speech.confirmStopped()
                f.runtime.dispatch(PlaybackEvent.Stop)
                f.queue.drain()
                check(f.speech.started.size == starts)
                check(f.runtime.snapshot().mode == PlaybackMode.PAUSED)
                check(!f.runtime.snapshot().playIntent && !f.runtime.snapshot().interactionIntent)
                f.assertNoAnswer()
                check(f.text("SELECT lifecycle FROM conversation_turn") == "interrupted")
                f.assertUserOnlySummary(REPLAY)
            }
        }
        mark("local_command_queued_input_stop_after_manual_stop_cannot_restart_output")

        stage("LOCAL_COMMAND_STORAGE_FAILURES") {
            listOf("freeze", "checkpoint").forEach { boundary ->
                Fixture(context).use { f ->
                    f.listenFromStory()
                    f.finalBeforeStopped(REPLAY)
                    val starts = f.speech.started.size
                    if (boundary == "freeze") f.exec("CREATE TRIGGER local_command_fail BEFORE UPDATE OF frozen ON conversation_turn WHEN NEW.frozen=1 BEGIN SELECT RAISE(ABORT,'synthetic_failure'); END")
                    else f.exec("CREATE TRIGGER local_command_fail BEFORE UPDATE ON playback_checkpoint BEGIN SELECT RAISE(ABORT,'synthetic_failure'); END")
                    f.stopped()
                    check(f.speech.started.size == starts)
                    check(PlaybackBlocker.STORAGE_UNAVAILABLE in f.runtime.snapshot().blockers)
                    check(f.number("SELECT count(*) FROM pending_conversation") == 1L)
                    check(f.number("SELECT count(*) FROM conversation_summary") == 0L)
                    f.assertNoAnswer()
                    f.exec("DROP TRIGGER local_command_fail")
                }
            }
        }
        mark("local_command_freeze_or_checkpoint_failure_blocks_output_and_preserves_encrypted_payload")

        stage("LOCAL_COMMAND_RECOVERY") {
            Fixture(context).use { f ->
                f.listen()
                f.finalBeforeStopped(REPLAY)
                val starts = f.speech.started.size
                val attempts = f.number("SELECT count(*) FROM playback_attempt")
                val sessions = f.number("SELECT count(*) FROM session")
                // Model a lost process after TX-01, before the independent input-stop callback.
                f.abandonRuntimeForRecovery = true
                f.store.sessionRecovery().use { recovery ->
                    recovery.recoverOnAppStart()
                    recovery.recoverOnAppStart()
                }
                check(f.number("SELECT count(*) FROM playback_attempt") == attempts)
                check(f.number("SELECT count(*) FROM session") == sessions)
                check(f.number("SELECT count(*) FROM session WHERE status='active'") == 0L)
                check(f.number("SELECT count(*) FROM conversation_turn WHERE frozen=1 AND lifecycle='interrupted'") == 1L)
                check(f.speech.started.size == starts && f.speech.inputRequests == 1)
                f.assertNoAnswer()
                f.assertUserOnlySummary(REPLAY)
            }
        }
        mark("local_command_recovery_freezes_committed_user_only_turn_without_reexecuting_command")
    }

    private inline fun stage(code: String, action: () -> Unit) {
        try { action() } catch (_: Throwable) { throw Failure(code) }
    }

    private class Fixture(private val context: Context) : AutoCloseable {
        private val name = "local-command-${UUID.randomUUID()}.db"
        val store = SyntheticStorageFixture.create(context, name)
        val story = SyntheticStorageFixture.install(store)
        val following = installFollowing(store)
        val queue = ScriptedQueue()
        val references = mutableMapOf<OperationToken, OutputReference>()
        val speech = ScriptedSpeech()
        val skipped = mutableListOf<String>()
        var generationCalls = 0
        var answerWrites = 0
        var resumeCalls = 0
        var abandonRuntimeForRecovery = false
        private val persistence = object : RuntimePersistence by store {
            override fun registerOutput(attempt: OperationToken, reference: OutputReference, turnId: String?) {
                store.registerOutput(attempt, reference, turnId)
                references[attempt] = reference
            }
            override fun persistAnswer(commit: OperationToken, turnId: String, answer: RuntimeAnswer): ValidatedAnswer {
                answerWrites++
                return store.persistAnswer(commit, turnId, answer)
            }
        }
        val runtime = PlaybackRuntime(PlaybackCoordinator("command-trip", "command-session"), queue,
            RuntimeClock { 1_000 }, persistence, speech,
            generation = object : RuntimeGeneration {
                override fun generate(request: RuntimeGenerationRequest, callback: (RuntimeGenerationResult) -> Unit): RuntimeCancellation {
                    generationCalls++
                    callback(RuntimeGenerationResult.Failure(false))
                    return RuntimeCancellation { }
                }
            },
            resumePlanner = object : RuntimeResumePlanner {
                override fun plan(effect: PlaybackEffect.PlanResume, callback: (RuntimeResumeSelection) -> Unit): RuntimeCancellation {
                    check(!effect.afterInteraction)
                    resumeCalls++
                    return CheckpointRuntimeResumePlanner().plan(effect, callback)
                }
            },
            observer = object : RuntimeObserver {
                override fun onStorySkipped(storyId: String) { skipped += storyId }
            },
            contentAuthority = RuntimeContentAuthority { offer, ref ->
                offer == "synthetic-following" && ref.storyId == following.storyId && ref.versionId == following.versionId
            })

        init { queue.drain() }

        fun listenFromStory() {
            runtime.dispatch(PlaybackEvent.PlayStory(story))
            queue.drain()
            check(lastStarted() == OutputReference.StoryParagraph("test-story", "test-story-v1", "test-p1"))
            runtime.dispatch(PlaybackEvent.Talk)
            queue.drain()
            completeOutput()
            check(lastStarted() == OutputReference.FixedPrompt("talk-prompt"))
            completeOutput()
            check(runtime.snapshot().inputAvailable)
        }

        fun listen() {
            runtime.dispatch(PlaybackEvent.Talk)
            queue.drain()
            check(lastStarted() == OutputReference.FixedPrompt("talk-prompt"))
            completeOutput()
            check(runtime.snapshot().inputAvailable)
        }

        fun finalBeforeStopped(command: String) {
            val starts = speech.started.size
            speech.deliverFinal(command)
            queue.drain()
            check(runtime.snapshot().inputPhase == AdapterPhase.STOPPING)
            check(number("SELECT count(*) FROM conversation_turn WHERE utterance_finalized_at IS NOT NULL") == 1L)
            check(number("SELECT count(*) FROM pending_conversation") == 1L)
            check(speech.started.size == starts)
            assertNoAnswer()
        }

        fun stopped() { speech.confirmStopped(); queue.drain() }
        fun command(command: String) { finalBeforeStopped(command); stopped() }
        fun completeOutput() { speech.complete(); queue.drain() }
        fun lastStarted(): OutputReference = checkNotNull(references[speech.started.last()])

        fun assertNoAnswer() {
            check(generationCalls == 0 && answerWrites == 0)
            check(number("SELECT count(*) FROM generation_request WHERE task IN ('answer','resume')") == 0L)
            check(number("SELECT count(*) FROM playback_attempt WHERE kind='answer'") == 0L)
        }

        fun assertCommandTurn() {
            assertNoAnswer()
            check(number("SELECT count(*) FROM conversation_turn WHERE frozen=1 AND lifecycle='closed' AND summary_state='pending'") == 1L)
            check(number("SELECT count(*) FROM pending_conversation") == 1L)
            check(!runtime.snapshot().interactionIntent)
        }

        fun assertUserOnlySummary(command: String, save: Boolean = false) {
            val turnId = text("SELECT turn_id FROM conversation_turn")
            store.summaryStore().use { summary ->
                val claim = summary.claim(turnId)
                check(claim is SummaryClaim.Claimed)
                val work = claim.work
                check(work.input.userText == command)
                check(work.input.completedResponses.isEmpty() && work.input.partialUnknownKeys.isEmpty())
                check(work.approvedFactSources.isEmpty())
                check(!work.input.toString().contains(command))
                if (save) {
                    check(summary.commit(work, ValidatedSummaryResult("합성 명령 요약 저장소 검증입니다.", emptyList(),
                        emptyList(), emptyList(), emptyList(), emptyList(), "synthetic-model", "synthetic-provider", "synthetic-prompt")))
                    check(!summary.commit(work, ValidatedSummaryResult("합성 중복 결과입니다.", emptyList(),
                        emptyList(), emptyList(), emptyList(), emptyList(), "synthetic-model", "synthetic-provider", "synthetic-prompt")))
                }
            }
        }

        fun exec(statement: String, vararg args: Any?) = store.database.openHelper.writableDatabase.execSQL(statement, args)
        fun number(statement: String, vararg args: Any?): Long = store.database.openHelper.writableDatabase.query(SimpleSQLiteQuery(statement, args)).use {
            check(it.moveToFirst()); it.getLong(0)
        }
        fun text(statement: String): String = store.database.openHelper.writableDatabase.query(statement).use {
            check(it.moveToFirst()); it.getString(0)
        }

        override fun close() {
            try {
                if (!abandonRuntimeForRecovery) {
                    runtime.close()
                    queue.drain()
                    speech.confirmStopped()
                    queue.drain()
                }
            } finally {
                try { store.close() } finally { SyntheticStorageFixture.cleanup(context, name) }
            }
        }
    }

    private class ScriptedQueue : RuntimeQueue {
        private val tasks = ArrayDeque<() -> Unit>()
        override fun execute(task: () -> Unit) { tasks.addLast(task) }
        override fun schedule(delayMs: Long, task: () -> Unit) = RuntimeCancellation { }
        fun drain() {
            var count = 0
            while (tasks.isNotEmpty()) { check(++count < 1_000); tasks.removeFirst().invoke() }
        }
    }

    private class ScriptedSpeech : RuntimeSpeech {
        val started = mutableListOf<OperationToken>()
        private val outputs = mutableMapOf<OperationToken, (RuntimeOutputEvent) -> Unit>()
        private var input: ((RuntimeInputEvent) -> Unit)? = null
        var stopRequests = 0
        var inputRequests = 0
        override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) {
            check(outputs.isEmpty())
            outputs[attempt] = callback
            started += attempt
            callback(RuntimeOutputEvent.STARTED)
        }
        override fun stopOutput(attempt: OperationToken) { outputs.remove(attempt)?.invoke(RuntimeOutputEvent.STOPPED) }
        override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) {
            inputRequests++
            this.input = callback
            callback(RuntimeInputEvent.Ready)
            callback(RuntimeInputEvent.Started)
        }
        override fun stopInput(input: OperationToken) { stopRequests++ }
        fun deliverFinal(command: String) { checkNotNull(input).invoke(RuntimeInputEvent.Final(RuntimeText(command))) }
        fun confirmStopped() { input?.invoke(RuntimeInputEvent.StoppedConfirmed); input = null }
        fun complete() { val token = outputs.keys.single(); outputs.remove(token)!!.invoke(RuntimeOutputEvent.COMPLETED) }
        override fun close() { check(outputs.isEmpty()); input = null }
    }

    private fun installFollowing(store: RoomRuntimePersistence): PreparedStory {
        val paragraph = ContentParagraph("following-p1", 0, true, "합성 후속 이야기 문단입니다.", null, 1_000, listOf("test-fact-r2"))
        @Suppress("UNCHECKED_CAST")
        val base = StrictJson.parse(SyntheticStorageFixture.bundle()) as Map<String, Any?>
        val bundle = StrictJson.encode(base + mapOf(
            "stories" to listOf(mapOf("storyId" to "following-story", "topicId" to "test-topic", "title" to "SYNTHETIC TEST ONLY",
                "coreQuestion" to "Synthetic command verification", "interestTags" to emptyList<String>(), "placeId" to null)),
            "versions" to listOf(mapOf("storyVersionId" to "following-v1", "storyId" to "following-story", "version" to 1,
                "contentHash" to ContentBundleCodec.versionHash("following-story", 1, listOf(paragraph)), "createdAt" to 1,
                "verifiedAt" to 1, "validUntil" to null, "estimatedDurationMs" to 1_000, "status" to "published",
                "paragraphs" to listOf(mapOf("paragraphId" to paragraph.paragraphId, "ordinal" to 0, "required" to true,
                    "text" to paragraph.text, "audioRef" to null, "estimatedDurationMs" to 1_000, "factRevisionIds" to paragraph.factRevisionIds)))),
        ))
        return RoomContentInstaller(store.database, System::currentTimeMillis, ownsDatabase = false).use {
            it.install(bundle)
            it.publishedStory("following-v1")
        }
    }

    // Fixed test input is held only transiently and in each disposable encrypted payload.
    private const val STOP = "멈춰"
    private const val CONTINUE = "계속 재생해"
    private const val NEXT = "건너뛰어"
    private const val REPLAY = "다시 재생해"
    private const val MUSIC = "음악 틀어줘"
}
