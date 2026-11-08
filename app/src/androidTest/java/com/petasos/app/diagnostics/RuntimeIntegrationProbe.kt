package com.petasos.app.diagnostics

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import com.petasos.app.MainActivity
import com.petasos.app.platform.android.runtime.AndroidRuntimeClock
import com.petasos.app.platform.android.runtime.AndroidRuntimeQueue
import com.petasos.app.platform.pleos.speech.PleosRuntimeSpeech
import com.petasos.app.playback.*
import com.petasos.app.runtime.*
import com.petasos.app.storage.RoomRuntimePersistence
import com.petasos.app.storage.SyntheticStorageFixture
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Test APK only. A real TTS path and an explicitly scripted conversation are separate probes. */
object RuntimeIntegrationProbe {
    /** Real TTS interruption: Started and Stopped must both come from the Pleos bridge. */
    fun runTtsStop(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "runtime-stop-probe-${UUID.randomUUID()}.db"
        val queue = AndroidRuntimeQueue()
        val issues = LinkedBlockingQueue<RuntimeIssue>()
        val transitions = LinkedBlockingQueue<PlaybackTransition>()
        val setup = onWorker(queue) {
            val store = SyntheticStorageFixture.create(context, name)
            // Keep the synthetic first paragraph long enough to stop after the real Started
            // callback has also reached Room. Install its matching reviewed-fixture hash too.
            val story = SyntheticStorageFixture.install(
                store, "이 문장은 실제 음성 출력 중단을 검증하는 합성 시험 문장입니다. ".repeat(8), 20_000,
            )
            store to story
        }
        val store = setup.first
        val story = setup.second
        val firstStarted = CountDownLatch(1)
        val outputStopped = CountDownLatch(1)
        val actual = PleosRuntimeSpeech(context, onIssue = { issues.offer(it) })
        val speech = object : RuntimeSpeech by actual {
            override fun startPreparedOutput(attempt: OperationToken, prepare: ((RuntimeSpeechPermit?) -> Unit) -> Unit,
                callback: (RuntimeOutputEvent) -> Unit) {
                actual.startPreparedOutput(attempt, prepare) { event ->
                    callback(event)
                    if (event == RuntimeOutputEvent.STARTED) firstStarted.countDown()
                    if (event == RuntimeOutputEvent.STOPPED) outputStopped.countDown()
                }
            }
        }
        val trip = UUID.randomUUID().toString()
        val session = UUID.randomUUID().toString()
        val runtime = PlaybackRuntime(
            PlaybackCoordinator(trip, session), queue, AndroidRuntimeClock, store, speech,
            observer = object : RuntimeObserver {
                override fun onTransition(transition: PlaybackTransition) { transitions.offer(transition) }
                override fun onIssue(issue: RuntimeIssue) { issues.offer(issue) }
            },
        )
        try {
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            mark("runtime_actual_tts_stop_with_room_started")
            runtime.dispatch(PlaybackEvent.PlayStory(story))
            check(firstStarted.await(10, TimeUnit.SECONDS)) { "actual_tts_not_started" }
            // The callback was queued before this barrier. Do not race Stop's ingress fence
            // against persistence of the real Started event.
            onWorker(queue) {
                check(count(store, "SELECT count(*) FROM playback_attempt WHERE kind='story' AND state='playing' AND started_at IS NOT NULL") == 1L)
            }
            mark("runtime_actual_started_callback_and_started_row_verified")
            runtime.dispatch(PlaybackEvent.Stop)
            check(outputStopped.await(5, TimeUnit.SECONDS)) { "actual_tts_stop_not_confirmed" }
            awaitState(transitions, issues) {
                it.mode == PlaybackMode.PAUSED && it.outputPhase == AdapterPhase.READY &&
                    it.pendingPlaybackCommits == 0 && !it.playIntent && !it.interactionIntent
            }
            onWorker(queue) { verifyInterruptedStory(store) }
            mark("runtime_actual_stopped_callback_interrupted_row_no_completion_or_card_verified")

            runtime.close()
            awaitState(transitions, issues) { it.closed }
            onWorker(queue) {
                check(count(store, "SELECT count(*) FROM session WHERE status='closed'") == 1L)
                store.close()
            }
            mark("runtime_stop_graceful_close_committed")
            onWorker(queue) {
                SyntheticStorageFixture.create(context, name).use { reopened ->
                    verifyInterruptedStory(reopened)
                    check(count(reopened, "SELECT count(*) FROM session WHERE status='closed'") == 1L)
                    val restored = PlaybackCoordinator(trip, UUID.randomUUID().toString(), restoredCheckpoint = StoryCheckpoint(story, 0))
                    check(!restored.snapshot().playIntent && restored.snapshot().mode == PlaybackMode.IDLE)
                }
            }
            mark("runtime_stop_reopen_retains_interruption_without_autoplay")
        } finally {
            runtime.close()
            speech.close()
            // Resource cleanup only. No stop acknowledgment or PASS is inferred from this delay.
            SystemClock.sleep(1_100)
            onWorker(queue) { store.close() }
            queue.close()
            SyntheticStorageFixture.cleanup(context, name)
        }
    }

    private fun verifyInterruptedStory(store: RoomRuntimePersistence) {
        check(count(store, "SELECT count(*) FROM playback_attempt") == 1L)
        check(count(store, "SELECT count(*) FROM playback_attempt WHERE kind='story' AND state='interrupted' AND started_at IS NOT NULL") == 1L)
        check(count(store, "SELECT count(*) FROM playback_attempt WHERE state='completed'") == 0L)
        check(count(store, "SELECT count(*) FROM collection_record") == 0L)
        check(count(store, "SELECT count(*) FROM collection_proof") == 0L)
        check(count(store, "SELECT count(*) FROM playback_checkpoint WHERE paragraph_id='test-p1' AND last_completed_paragraph_id IS NULL AND play_intent=0") == 1L)
    }

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit, realTts: Boolean) {
        val context = instrumentation.targetContext
        val name = "runtime-probe-${UUID.randomUUID()}.db"
        val queue = AndroidRuntimeQueue()
        val issues = LinkedBlockingQueue<RuntimeIssue>()
        val transitions = LinkedBlockingQueue<PlaybackTransition>()
        val setup = onWorker(queue) {
            val store = SyntheticStorageFixture.create(context, name)
            store to SyntheticStorageFixture.install(store)
        }
        val store = setup.first
        val story = setup.second
        val scripted = ScriptedSpeech()
        val speech: RuntimeSpeech = if (realTts) PleosRuntimeSpeech(context, onIssue = { issues.offer(it) }) else scripted
        val trip = UUID.randomUUID().toString()
        val session = UUID.randomUUID().toString()
        var requestCount = 0
        val generation = object : RuntimeGeneration {
            override fun generate(request: RuntimeGenerationRequest, callback: (RuntimeGenerationResult) -> Unit): RuntimeCancellation {
                check(!realTts) { "unexpected_generation_in_tts_probe" }
                check(request.utterance.value == SCRIPTED_FINAL) { "stored_utterance_not_loaded" }
                requestCount++
                callback(RuntimeGenerationResult.Success(RuntimeAnswer(listOf(
                    RuntimeParagraph("synthetic-answer", RuntimeText("이 응답은 저장과 상태 전이를 검증하는 합성 시험 문장입니다."), listOf("test-source-r1")),
                ))))
                return RuntimeCancellation { }
            }
        }
        val runtime = PlaybackRuntime(
            PlaybackCoordinator(trip, session), queue, AndroidRuntimeClock, store, speech, generation,
            object : RuntimeObserver {
                override fun onTransition(transition: PlaybackTransition) { transitions.offer(transition) }
                override fun onIssue(issue: RuntimeIssue) { issues.offer(issue) }
            },
        )
        try {
            if (realTts) {
                instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                mark("runtime_actual_tts_with_room_started")
            } else mark("runtime_scripted_speech_and_generation_with_real_room_started")
            runtime.dispatch(PlaybackEvent.PlayStory(story))
            if (!realTts) {
                check(scripted.firstStarted.await(5, TimeUnit.SECONDS)) { "first_output_not_started" }
                runtime.dispatch(PlaybackEvent.Talk)
                scripted.finishFirstOutput()
            }
            awaitState(transitions, issues) {
                it.mode == PlaybackMode.IDLE && it.checkpoint?.paragraphIndex == 2 && it.pendingPlaybackCommits == 0
            }
            onWorker(queue) {
                check(count(store, "SELECT count(*) FROM playback_attempt WHERE kind='story' AND state='completed' AND started_at IS NOT NULL") == 2L)
                check(count(store, "SELECT count(*) FROM collection_record WHERE collection_type='story'") == 1L)
                check(count(store, "SELECT count(*) FROM collection_proof") == 2L)
                if (!realTts) {
                    check(requestCount == 1)
                    check(count(store, "SELECT count(*) FROM conversation_turn WHERE frozen=1 AND lifecycle='closed' AND summary_state='pending'") == 1L)
                    check(count(store, "SELECT count(*) FROM pending_conversation") == 1L)
                    check(count(store, "SELECT count(*) FROM playback_attempt WHERE kind='answer' AND state='completed'") == 1L)
                }
            }
            mark(if (realTts) "runtime_actual_tts_two_completions_and_atomic_card_verified"
                else "runtime_scripted_turn_encrypted_commits_answer_freeze_resume_verified")

            runtime.close()
            awaitState(transitions, issues) { it.closed }
            onWorker(queue) {
                check(count(store, "SELECT count(*) FROM session WHERE status='closed'") == 1L)
                store.close()
            }
            mark("runtime_graceful_close_committed")

            onWorker(queue) {
                SyntheticStorageFixture.create(context, name).use { reopened ->
                    check(count(reopened, "SELECT count(*) FROM collection_record") == 1L)
                    check(count(reopened, "SELECT count(*) FROM collection_proof") == 2L)
                    val restored = PlaybackCoordinator(trip, UUID.randomUUID().toString(), restoredCheckpoint = StoryCheckpoint(story, 2))
                    check(!restored.snapshot().playIntent && restored.snapshot().mode == PlaybackMode.IDLE)
                    if (!realTts) check(count(reopened, "SELECT count(*) FROM pending_conversation") == 1L)
                }
            }
            mark("runtime_reopen_retains_records_without_autoplay")
        } finally {
            runtime.close()
            speech.close()
            // Allow only resource cleanup; no success or stop ACK is inferred from this delay.
            SystemClock.sleep(1_100)
            onWorker(queue) { store.close() }
            queue.close()
            SyntheticStorageFixture.cleanup(context, name)
        }
    }

    private fun awaitState(
        transitions: LinkedBlockingQueue<PlaybackTransition>,
        issues: LinkedBlockingQueue<RuntimeIssue>,
        matches: (PlaybackSnapshot) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            check(issues.isEmpty()) { "runtime_issue_${issues.peek()?.name}" }
            val next = transitions.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (matches(next.snapshot)) return
        }
        error("runtime_transition_timeout")
    }

    private fun count(store: RoomRuntimePersistence, sql: String): Long =
        store.database.openHelper.writableDatabase.query(sql).use { check(it.moveToFirst()); it.getLong(0) }

    private fun <T> onWorker(queue: AndroidRuntimeQueue, action: () -> T): T {
        val result = CompletableFuture<T>()
        queue.execute { try { result.complete(action()) } catch (failure: Throwable) { result.completeExceptionally(failure) } }
        return result.get(10, TimeUnit.SECONDS)
    }

    private class ScriptedSpeech : RuntimeSpeech {
        val firstStarted = CountDownLatch(1)
        @Volatile private var firstOutput: ((RuntimeOutputEvent) -> Unit)? = null
        private var outputCount = 0
        private var input: ((RuntimeInputEvent) -> Unit)? = null
        override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) {
            callback(RuntimeOutputEvent.STARTED)
            if (outputCount++ == 0) {
                firstOutput = callback
                firstStarted.countDown()
            } else callback(RuntimeOutputEvent.COMPLETED)
        }
        fun finishFirstOutput() { checkNotNull(firstOutput)(RuntimeOutputEvent.COMPLETED); firstOutput = null }
        override fun stopOutput(attempt: OperationToken) { firstOutput?.invoke(RuntimeOutputEvent.STOPPED); firstOutput = null }
        override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) {
            this.input = callback
            callback(RuntimeInputEvent.Ready)
            callback(RuntimeInputEvent.Started)
            callback(RuntimeInputEvent.Final(RuntimeText(SCRIPTED_FINAL)))
            callback(RuntimeInputEvent.Ended)
        }
        override fun stopInput(input: OperationToken) {
            // Scripted test input has no microphone; it explicitly confirms its own completion.
            this.input?.invoke(RuntimeInputEvent.StoppedConfirmed)
            this.input = null
        }
        override fun close() { firstOutput = null; input = null }
    }

    private const val SCRIPTED_FINAL = "이 문장은 실제 사용자 발화가 아닌 통합 검사용 합성 입력입니다."
}
