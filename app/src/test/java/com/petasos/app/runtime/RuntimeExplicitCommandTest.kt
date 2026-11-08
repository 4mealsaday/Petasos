package com.petasos.app.runtime

import com.petasos.app.planning.*
import com.petasos.app.playback.*
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

/** Product parser and runtime, with explicit synthetic SDK acknowledgements and durable-store boundaries. */
class RuntimeExplicitCommandTest {
    @Test fun allRecognizedCommandsBypassAnswerProviderAndInteractionResumeProvider() {
        listOf("멈춰", "계속 들려줘", "건너뛰어", "다시 들려줘", "음악 틀어줘").forEach { text ->
            val h = Harness()
            h.listen()
            h.submitFinal(text)
            h.confirmInputStopped()
            assertEquals(1, h.commandTransitions.size)
            assertEquals(1, h.store.savedFinals)
            assertEquals(1, h.store.loadedFinals)
            assertTrue(h.generation.jobs.isEmpty())
            assertEquals(0, h.store.savedAnswers)
            assertEquals(0, h.interactionResumeCalls)
            val frozen = h.store.frozen.single()
            assertTrue(frozen.hasCommittedUtterance)
            assertTrue(frozen.completedAnswerKeys.isEmpty())
        }
    }

    @Test fun committedFinalWaitsForActualInputStopAndDuplicateCallbacksApplyCommandOnce() {
        val h = Harness()
        h.listen()
        val input = h.speech.inputs.last()
        h.submitFinal("멈춰")
        assertEquals(1, h.store.savedFinals)
        assertEquals(1, h.speech.inputStops)
        assertEquals(0, h.store.loadedFinals)
        assertTrue(h.commandTransitions.isEmpty())
        assertTrue(h.generation.jobs.isEmpty())
        assertEquals(AdapterPhase.STOPPING, h.runtime.snapshot().inputPhase)
        input.callback(RuntimeInputEvent.Final(RuntimeText("건너뛰어")))
        h.queue.drain()
        assertEquals(1, h.store.savedFinals)
        h.confirmInputStopped()
        input.callback(RuntimeInputEvent.StoppedConfirmed)
        input.callback(RuntimeInputEvent.Final(RuntimeText("다시 들려줘")))
        h.queue.drain()
        assertEquals(1, h.commandTransitions.size)
        assertEquals(1, h.store.loadedFinals)
        assertEquals(1, h.store.frozen.size)
        assertEquals(PlaybackMode.PAUSED, h.runtime.snapshot().mode)
        assertFalse(h.runtime.snapshot().playIntent)
        assertEquals(1, h.speech.outputs.size) // The input prompt only; no command answer is fabricated.
    }

    @Test fun finalCommitFailureNeverClassifiesOrCallsProvider() {
        val h = Harness()
        h.listen()
        h.store.failFinal = true
        h.submitFinal("멈춰")
        h.confirmInputStopped()
        assertTrue(PlaybackBlocker.STORAGE_UNAVAILABLE in h.runtime.snapshot().blockers)
        assertEquals(0, h.store.loadedFinals)
        assertTrue(h.commandTransitions.isEmpty())
        assertTrue(h.generation.jobs.isEmpty())
    }

    @Test fun finalReadFailureNeverClassifiesOrCallsProvider() {
        val h = Harness()
        h.listen()
        h.submitFinal("멈춰")
        h.store.failRead = true
        h.confirmInputStopped()
        assertTrue(PlaybackBlocker.STORAGE_UNAVAILABLE in h.runtime.snapshot().blockers)
        assertTrue(h.commandTransitions.isEmpty())
        assertTrue(h.generation.jobs.isEmpty())
        assertEquals(0, h.store.savedAnswers)
    }

    @Test fun classifierUsesTheDurablyLoadedFinalRatherThanAnEarlierTransientCallback() {
        val h = Harness()
        h.listen()
        h.submitFinal("합성 일반 질문입니다")
        h.store.finalText = RuntimeText("멈춰")
        h.confirmInputStopped()
        assertEquals(1, h.commandTransitions.size)
        assertEquals(PlaybackMode.PAUSED, h.runtime.snapshot().mode)
        assertTrue(h.generation.jobs.isEmpty())
    }

    @Test fun unmatchedOrNegatedTextKeepsExistingRemoteAnswerPathAndDoesNotParseTheAnswer() {
        val h = Harness()
        h.listen()
        h.submitFinal("멈추지 마")
        h.confirmInputStopped()
        val job = h.generation.jobs.single()
        assertEquals("멈추지 마", job.request.utterance.value)
        assertTrue(h.runtime.isGenerationCurrent(job.request))
        job.callback(RuntimeGenerationResult.Success(RuntimeAnswer(listOf(RuntimeParagraph("synthetic-answer", RuntimeText("멈춰"))))))
        h.queue.drain()
        assertEquals(1, h.store.savedAnswers)
        assertEquals(PlaybackMode.ANSWERING, h.runtime.snapshot().mode)
        assertTrue(h.commandTransitions.isEmpty())
        assertFalse(h.runtime.isGenerationCurrent(job.request))
    }

    @Test fun stopDuringFinalReadPreventsBothLocalAndRemoteDispatch() {
        listOf("건너뛰어", "합성 일반 질문입니다").forEach { text ->
            val h = Harness()
            h.listen()
            h.submitFinal(text)
            h.store.onRead = { h.runtime.dispatch(PlaybackEvent.Stop) }
            h.confirmInputStopped()
            assertEquals(1, h.store.loadedFinals)
            assertTrue(h.commandTransitions.isEmpty())
            assertTrue(h.generation.jobs.isEmpty())
            assertEquals(PlaybackMode.PAUSED, h.runtime.snapshot().mode)
            assertFalse(h.runtime.snapshot().playIntent)
        }
    }

    @Test fun stopFromGenerationTransitionCannotRebaseTheOriginalEffectFence() {
        listOf("다시 들려줘", "합성 일반 질문입니다").forEach { text ->
            val h = Harness()
            h.listen()
            h.submitFinal(text)
            h.onTransition = { transition ->
                if (transition.effects.any { it is PlaybackEffect.GenerateAnswer }) h.runtime.dispatch(PlaybackEvent.Stop)
            }
            h.confirmInputStopped()
            assertEquals(0, h.store.loadedFinals)
            assertTrue(h.commandTransitions.isEmpty())
            assertTrue(h.generation.jobs.isEmpty())
            assertFalse(h.runtime.snapshot().playIntent)
        }
    }

    @Test fun cancelOrPowerLossDuringReadRevokesPendingCommand() {
        listOf<PlaybackEvent>(PlaybackEvent.CancelInteraction, PlaybackEvent.Block(PlaybackBlocker.POWER_AUDIO)).forEach { event ->
            val h = Harness()
            h.listen()
            h.submitFinal("계속 들려줘")
            h.store.onRead = { h.runtime.dispatch(event) }
            h.confirmInputStopped()
            assertTrue(h.commandTransitions.isEmpty())
            assertTrue(h.generation.jobs.isEmpty())
            assertEquals(0, h.store.savedAnswers)
            assertEquals(PlaybackMode.PAUSED, h.runtime.snapshot().mode)
        }
    }

    @Test fun newTalkQueuedDuringReadCancelsOldCommandWithoutStartingAnotherInputUntilItsPromptCompletes() {
        val h = Harness()
        h.listen()
        val oldTurn = h.runtime.snapshot().turnId
        h.submitFinal("멈춰")
        h.store.onRead = {
            h.queue.now += 400
            h.runtime.dispatch(PlaybackEvent.Talk)
        }
        h.confirmInputStopped()
        assertTrue(h.commandTransitions.isEmpty())
        assertTrue(h.generation.jobs.isEmpty())
        assertNotEquals(oldTurn, h.runtime.snapshot().turnId)
        assertEquals(PlaybackMode.PROMPT, h.runtime.snapshot().mode)
        assertEquals(1, h.speech.inputs.size)
        assertEquals(oldTurn, h.store.frozen.single().turnId)
        assertEquals(TurnOutcome.INTERRUPTED, h.store.frozen.single().outcome)
    }

    @Test fun staleResolvedCommandAndLateInputCannotExecuteAgainstANewTurn() {
        val h = Harness()
        h.listen()
        val oldInput = h.speech.inputs.last()
        h.submitFinal("멈춰")
        h.confirmInputStopped()
        val oldRequest = h.generatedRequests.single()
        h.queue.now += 400
        h.runtime.dispatch(PlaybackEvent.Talk); h.queue.drain()
        val newTurn = h.runtime.snapshot().turnId
        h.runtime.dispatch(PlaybackEvent.ExplicitCommandResolved(oldRequest, ExplicitPlaybackCommand.NEXT))
        oldInput.callback(RuntimeInputEvent.Final(RuntimeText("음악 틀어줘")))
        oldInput.callback(RuntimeInputEvent.StoppedConfirmed)
        h.queue.drain()
        assertEquals(newTurn, h.runtime.snapshot().turnId)
        assertEquals(PlaybackMode.PROMPT, h.runtime.snapshot().mode)
        assertEquals(1, h.commandTransitions.size)
        assertTrue(h.generation.jobs.isEmpty())
        assertTrue(h.skipped.isEmpty())
    }

    @Test fun explicitReplayUsesStoryStartAndContinueUsesCheckpointWithoutInteractionPlanner() {
        listOf("다시 들려줘" to "a-p1", "계속 들려줘" to "a-p2").forEach { (text, paragraph) ->
            val h = Harness()
            h.playStoryAndListen()
            val previousAttempt = h.speech.outputs.first().attempt
            h.submitFinal(text); h.confirmInputStopped()
            val last = h.speech.outputs.last()
            assertEquals(OutputReference.StoryParagraph("a", "a-v1", paragraph), h.store.references[last.attempt])
            assertNotEquals(previousAttempt, last.attempt)
            assertEquals(0, h.interactionResumeCalls)
            assertTrue(h.generation.jobs.isEmpty())
            assertEquals(0, h.store.savedAnswers)
            assertTrue(h.plans.all { !it.afterInteraction })
        }
    }

    @Test fun explicitNextExcludesBeforePlannerRefreshAndSelectsAnotherStoryWithoutFakeCompletion() {
        val h = Harness(automatic = true)
        h.playStoryAndListen()
        val completedBefore = h.store.commits.count { it.outcome == AttemptOutcome.COMPLETED }
        h.submitFinal("건너뛰어"); h.confirmInputStopped()
        assertEquals(listOf("a"), h.skipped)
        assertTrue(h.store.references[h.speech.outputs.last().attempt] is OutputReference.StoryParagraph)
        assertEquals("b", (h.store.references[h.speech.outputs.last().attempt] as OutputReference.StoryParagraph).storyId)
        assertEquals(completedBefore, h.store.commits.count { it.outcome == AttemptOutcome.COMPLETED })
        assertTrue(h.log.indexOf("exclude-a") < h.log.indexOf("refresh-excluded-a"))
        assertEquals(0, h.interactionResumeCalls)
        assertTrue(h.generation.jobs.isEmpty())
    }

    @Test fun explicitMusicSelectsReviewedOfferWithoutAnswerOrInteractionResumeProvider() {
        val h = Harness()
        h.playStoryAndListen()
        h.runtime.dispatch(PlaybackEvent.OfferMusic(h.track)); h.queue.drain()
        h.submitFinal("음악 틀어줘"); h.confirmInputStopped()
        assertEquals(1, h.music.outputs.size)
        assertEquals(h.track.assetId, h.music.outputs.single().source.assetId)
        assertEquals(0L, h.music.outputs.single().positionMs)
        assertEquals(0, h.store.savedAnswers)
        assertEquals(0, h.interactionResumeCalls)
        assertTrue(h.generation.jobs.isEmpty())
    }

    @Test fun expiryDuringFinalReadCannotApplyARecognizedCommand() {
        val h = Harness()
        h.playStoryAndListen()
        h.submitFinal("다시 들려줘")
        h.store.onRead = { h.queue.now += 15_000 }
        h.confirmInputStopped()
        assertTrue(h.commandTransitions.isEmpty())
        assertTrue(h.generation.jobs.isEmpty())
        assertEquals(TurnOutcome.FAILED, h.store.frozen.single().outcome)
        assertEquals(0, h.store.savedAnswers)
    }

    @Test fun failedCommandFreezeOrCheckpointCommitCannotStartNewSdkOutput() {
        listOf(false, true).forEach { failCheckpoint ->
            val h = Harness()
            h.playStoryAndListen()
            h.submitFinal("다시 들려줘")
            if (failCheckpoint) h.store.failCheckpoint = true else h.store.failFreeze = true
            val outputCount = h.speech.outputs.size
            h.confirmInputStopped()
            assertTrue(PlaybackBlocker.STORAGE_UNAVAILABLE in h.runtime.snapshot().blockers)
            assertEquals(outputCount, h.speech.outputs.size)
            assertTrue(h.music.outputs.isEmpty())
            assertTrue(h.generation.jobs.isEmpty())
            assertEquals(0, h.store.savedAnswers)
        }
    }

    private class Harness(automatic: Boolean = false) {
        val a = PreparedStory("a", "a-v1", listOf("a-p1", "a-p2", "a-p3"))
        val b = PreparedStory("b", "b-v1", listOf("b-p1"))
        val track = PreparedMusic("synthetic-track", 10_000)
        val queue = Queue()
        val log = mutableListOf<String>()
        val store = Store(log)
        val speech = Speech()
        val music = Music()
        val generation = Generation()
        val plans = mutableListOf<PlaybackEffect.PlanResume>()
        var interactionResumeCalls = 0
        val commandTransitions = mutableListOf<PlaybackTransition>()
        val generatedRequests = mutableListOf<OperationToken>()
        val skipped = mutableListOf<String>()
        var onTransition: ((PlaybackTransition) -> Unit)? = null
        private var id = 0
        lateinit var runtime: PlaybackRuntime
        private val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, excluded ->
            if ("a" in excluded) log += "refresh-excluded-a"
            listOf(a, b).filter { it.storyId !in excluded }.map {
                AutomaticStoryCandidate(it, CandidateReason.INTEREST, null, "synthetic-${it.storyId}")
            }
        }, object : PlannedStoryOutput {
            override fun offer(story: PreparedStory, offerId: String) { runtime.dispatch(PlaybackEvent.OfferStory(story, offerId)) }
            override fun withdraw(storyId: String, versionId: String) { runtime.dispatch(PlaybackEvent.ClearOfferedStory(storyId, versionId)) }
        }, { queue.now }, { _, _ -> true })

        init {
            runtime = PlaybackRuntime(PlaybackCoordinator("trip", "session", { "op-${++id}" }), queue, queue, store, speech,
                generation, observer = object : RuntimeObserver {
                    override fun onTransition(transition: PlaybackTransition) {
                        if (transition.effects.any { it is PlaybackEffect.FreezeTurn && it.outcome == TurnOutcome.CLOSED })
                            commandTransitions += transition
                        transition.effects.filterIsInstance<PlaybackEffect.GenerateAnswer>().forEach { generatedRequests += it.request }
                        if (automatic) bridge.update(PlanningContext(null, null, queue.now, "clock", false, transition.snapshot.interactionIntent))
                        onTransition?.invoke(transition)
                    }
                    override fun onStorySkipped(storyId: String) {
                        skipped += storyId; log += "exclude-$storyId"
                        if (automatic) bridge.storySkipped(storyId)
                    }
                    // Leave initial explicit A eligible so the command itself must exclude it.
                }, nextHandle = { "handle-${++id}" }, music = music,
                contentAuthority = if (automatic) bridge else RuntimeContentAuthority { _, _ -> false },
                resumePlanner = object : RuntimeResumePlanner {
                    override fun plan(effect: PlaybackEffect.PlanResume, callback: (RuntimeResumeSelection) -> Unit): RuntimeCancellation {
                        plans += effect
                        if (effect.afterInteraction) interactionResumeCalls++
                        callback(RuntimeResumeSelection(effect.candidates.firstOrNull(), effect.musicCandidates.firstOrNull()))
                        return RuntimeCancellation { }
                    }
                })
            queue.drain()
        }
        fun completeOutput() {
            val output = speech.outputs.last()
            output.callback(RuntimeOutputEvent.STARTED); output.callback(RuntimeOutputEvent.COMPLETED); queue.drain()
        }
        fun listen() {
            runtime.dispatch(PlaybackEvent.Talk); queue.drain(); completeOutput()
            speech.inputs.last().callback(RuntimeInputEvent.Ready); queue.drain()
        }
        fun playStoryAndListen() {
            runtime.dispatch(PlaybackEvent.PlayStory(a)); queue.drain()
            runtime.dispatch(PlaybackEvent.Talk); queue.drain()
            completeOutput() // Finish the current story paragraph before the input prompt.
            completeOutput()
            speech.inputs.last().callback(RuntimeInputEvent.Ready); queue.drain()
        }
        fun submitFinal(text: String) { speech.inputs.last().callback(RuntimeInputEvent.Final(RuntimeText(text))); queue.drain() }
        fun confirmInputStopped() { speech.inputs.last().callback(RuntimeInputEvent.StoppedConfirmed); queue.drain() }
    }

    private class Queue : RuntimeQueue, RuntimeClock {
        private val tasks = ArrayDeque<() -> Unit>()
        var now = 1_000L
        override fun elapsedRealtimeMs() = now
        override fun execute(task: () -> Unit) { tasks.addLast(task) }
        override fun schedule(delayMs: Long, task: () -> Unit) = RuntimeCancellation { }
        fun drain() { var count = 0; while (tasks.isNotEmpty()) { check(++count < 1_000); tasks.removeFirst().invoke() } }
    }

    private class Store(private val log: MutableList<String>) : RuntimePersistence {
        var failFinal = false
        var failRead = false
        var failFreeze = false
        var failCheckpoint = false
        var savedFinals = 0
        var loadedFinals = 0
        var savedAnswers = 0
        var finalText: RuntimeText? = null
        var onRead: (() -> Unit)? = null
        val references = mutableMapOf<OperationToken, OutputReference>()
        val frozen = mutableListOf<PlaybackEffect.FreezeTurn>()
        val commits = mutableListOf<PlaybackEffect.CommitPlayback>()
        override fun openSession(tripId: String, sessionId: String) = Unit
        override fun registerTurn(tripId: String, sessionId: String, turnId: String) = Unit
        override fun registerOutput(attempt: OperationToken, reference: OutputReference, turnId: String?) { references[attempt] = reference }
        override fun markOutputStarted(attempt: OperationToken) = Unit
        override fun prepareMusic(assetId: String) = PreparedMusic(assetId, 10_000)
        override fun loadMusic(reference: OutputReference.MusicTrack) = RuntimeMusicSource(reference.assetId, "file:///synthetic-test-tone.wav", 10_000)
        override fun loadOutput(reference: OutputReference) = RuntimeText("Synthetic public output")
        override fun loadUtterance(payload: PayloadHandle): RuntimeText {
            loadedFinals++; log += "load-final"
            if (failRead) error("synthetic_storage_failure")
            onRead?.invoke()
            return finalText!!
        }
        override fun persistUtterance(commit: OperationToken, turnId: String, text: RuntimeText): PayloadHandle {
            if (failFinal) error("synthetic_storage_failure")
            savedFinals++; finalText = text; log += "commit-final"
            return PayloadHandle(turnId)
        }
        override fun persistAnswer(commit: OperationToken, turnId: String, answer: RuntimeAnswer): ValidatedAnswer {
            savedAnswers++
            return ValidatedAnswer(PayloadHandle(turnId), answer.paragraphs.map { it.key })
        }
        override fun commitPlayback(effect: PlaybackEffect.CommitPlayback, snapshot: PlaybackSnapshot) { commits += effect }
        override fun freezeTurn(effect: PlaybackEffect.FreezeTurn) {
            if (failFreeze) error("synthetic_storage_failure")
            frozen += effect
        }
        override fun saveCheckpoint(snapshot: PlaybackSnapshot) {
            if (failCheckpoint) error("synthetic_storage_failure")
        }
        override fun closeSession(sessionId: String) = Unit
    }

    private class Speech : RuntimeSpeech {
        class Output(val attempt: OperationToken, val callback: (RuntimeOutputEvent) -> Unit)
        class Input(val token: OperationToken, val callback: (RuntimeInputEvent) -> Unit)
        val outputs = mutableListOf<Output>()
        val inputs = mutableListOf<Input>()
        var inputStops = 0
        override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) { outputs += Output(attempt, callback) }
        override fun stopOutput(attempt: OperationToken) { outputs.single { it.attempt == attempt }.callback(RuntimeOutputEvent.STOPPED) }
        override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) { inputs += Input(input, callback) }
        override fun stopInput(input: OperationToken) { inputStops++ } // The test must deliver the actual ACK separately.
        override fun close() = Unit
    }

    private class Music : RuntimeMusic {
        class Output(val source: RuntimeMusicSource, val positionMs: Long)
        val outputs = mutableListOf<Output>()
        override fun startOutput(attempt: OperationToken, source: RuntimeMusicSource, positionMs: Long, callback: (RuntimeMusicEvent) -> Unit) {
            outputs += Output(source, positionMs); callback(RuntimeMusicEvent.Started)
        }
        override fun stopOutput(attempt: OperationToken) = Unit
        override fun close() = Unit
    }

    private class Generation : RuntimeGeneration {
        class Job(val request: RuntimeGenerationRequest, val callback: (RuntimeGenerationResult) -> Unit)
        val jobs = mutableListOf<Job>()
        override fun generate(request: RuntimeGenerationRequest, callback: (RuntimeGenerationResult) -> Unit): RuntimeCancellation {
            jobs += Job(request, callback)
            return RuntimeCancellation { }
        }
    }
}
