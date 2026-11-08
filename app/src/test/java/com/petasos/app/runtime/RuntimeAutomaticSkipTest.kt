package com.petasos.app.runtime

import com.petasos.app.planning.*
import com.petasos.app.playback.*
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

/** The host's real observer ordering with a held connection and explicit synthetic stop acknowledgements. */
class RuntimeAutomaticSkipTest {
    @Test fun nextBeforeConnectedExcludesAOffersBAndKeepsExplicitReplayAvailable() {
        val h = Harness()
        h.play(h.a)
        val first = h.speech.outputs.single()
        assertTrue(h.speech.spoken.isEmpty())
        assertTrue(h.persistence.started.isEmpty())
        h.runtime.dispatch(PlaybackEvent.Next); h.queue.drain()
        assertEquals(listOf(h.a.storyId), h.skipped)
        assertEquals(listOf(h.a.storyId), h.exclusionsAtRefresh.first { it.isNotEmpty() }.toList())
        assertEquals(h.b.storyId, h.reference(h.speech.outputs.last()).storyId)
        assertEquals(2, h.speech.outputs.size)
        assertTrue(h.persistence.commits.all { it.outcome == AttemptOutcome.INTERRUPTED })
        assertTrue(h.persistence.started.isEmpty()) // Skip never fabricates actual-start or completed-listening evidence.
        assertTrue(h.log.indexOf("skip") < h.log.indexOf("stop"))
        first.callback(RuntimeOutputEvent.STARTED); h.queue.drain()
        assertTrue(h.started.isEmpty())
        h.speech.connect(h.speech.outputs.last()); h.queue.drain()
        assertEquals(listOf(h.b.storyId), h.speech.spoken.map { h.persistence.references.getValue(it) }
            .map { (it as OutputReference.StoryParagraph).storyId })
        h.runtime.dispatch(PlaybackEvent.Stop); h.queue.drain()
        h.play(h.a)
        h.speech.connect(h.speech.outputs.last()); h.queue.drain()
        assertEquals(h.a.storyId, h.reference(h.speech.outputs.last()).storyId)
        assertEquals(2, h.speech.spoken.size)
    }

    @Test fun nextIngressFencesQueuedStartedButSkipStillRemovesAFromAutomaticPlanning() {
        val h = Harness()
        h.play(h.a)
        val first = h.speech.outputs.single()
        h.speech.connect(first); h.queue.drain()
        first.callback(RuntimeOutputEvent.STARTED) // Actual callback is queued but has not reached the worker.
        h.runtime.dispatch(PlaybackEvent.Next)
        h.queue.drain()
        assertTrue(h.started.isEmpty())
        assertTrue(h.persistence.started.isEmpty())
        assertEquals(listOf(h.a.storyId), h.skipped)
        assertEquals(h.b.storyId, h.reference(h.speech.outputs.last()).storyId)
        assertEquals(2, h.speech.outputs.size)
        first.callback(RuntimeOutputEvent.STARTED); h.queue.drain()
        assertTrue(h.started.isEmpty())
        assertEquals(setOf(h.a.storyId), h.exclusionsAtRefresh.last())
    }

    @Test fun rejectedNextInIdleOrPausedDoesNotExcludeAnUnstartedStory() {
        val h = Harness()
        h.runtime.dispatch(PlaybackEvent.Next); h.queue.drain()
        assertTrue(h.skipped.isEmpty())
        h.play(h.a)
        h.runtime.dispatch(PlaybackEvent.Stop); h.queue.drain()
        h.runtime.dispatch(PlaybackEvent.Next); h.queue.drain()
        assertEquals(PlaybackMode.PAUSED, h.runtime.snapshot().mode)
        assertTrue(h.skipped.isEmpty())
        assertTrue(h.started.isEmpty())
        assertTrue(h.exclusionsAtRefresh.all { it.isEmpty() })
    }

    @Test fun actualAutomaticStartIsConsumedBeforeItsDurationCanReplaceTheRegionalOffer() {
        val h = Harness(replanAfterStart = true)
        h.runtime.dispatch(PlaybackEvent.Play); h.queue.drain()
        val first = h.speech.outputs.single()
        assertEquals(h.a.storyId, h.reference(first).storyId)
        h.speech.connect(first); h.queue.drain()
        first.callback(RuntimeOutputEvent.STARTED); h.queue.drain()
        assertEquals(listOf(first.attempt), h.persistence.started)
        assertEquals(listOf(h.a.storyId), h.automaticStarted)
        assertEquals(setOf(h.a.storyId), h.exclusionsAtRefresh.last())
        assertEquals(h.a, h.runtime.snapshot().checkpoint?.story)
        assertEquals(1, h.speech.outputs.size) // Preparing B never interrupts A.
        first.callback(RuntimeOutputEvent.STARTED); h.queue.drain()
        assertEquals(1, h.persistence.started.size)
        assertEquals(listOf(h.a.storyId), h.automaticStarted)
    }

    @Test fun explicitStartedStoryIsExcludedBeforeTheNextPlanningRefresh() {
        val h = Harness(replanAfterStart = true)
        h.play(h.a)
        val first = h.speech.outputs.single()
        h.speech.connect(first); h.queue.drain()
        first.callback(RuntimeOutputEvent.STARTED); h.queue.drain()
        assertEquals(listOf(h.a.storyId), h.started)
        assertEquals(setOf(h.a.storyId), h.exclusionsAtRefresh.last())
        assertTrue(h.automaticStarted.isEmpty())
        assertEquals(1, h.speech.outputs.size)
    }

    @Test fun failedStartedCommitCannotConsumeTheAutomaticOfferOrNotifyAnActualStart() {
        val h = Harness(replanAfterStart = true)
        h.runtime.dispatch(PlaybackEvent.Play); h.queue.drain()
        val first = h.speech.outputs.single()
        h.speech.connect(first); h.queue.drain()
        h.persistence.failStarted = true
        first.callback(RuntimeOutputEvent.STARTED); h.queue.drain()
        first.callback(RuntimeOutputEvent.STARTED); h.queue.drain()
        assertTrue(h.persistence.started.isEmpty())
        assertTrue(h.automaticStarted.isEmpty())
        assertTrue(h.started.isEmpty())
        assertTrue(h.exclusionsAtRefresh.all { it.isEmpty() })
        assertTrue(PlaybackBlocker.STORAGE_UNAVAILABLE in h.runtime.snapshot().blockers)
    }

    @Test fun stopDuringStartedCommitRevokesTheNotificationBeforePlanningCanConsumeIt() {
        val h = Harness()
        h.runtime.dispatch(PlaybackEvent.Play); h.queue.drain()
        val first = h.speech.outputs.single()
        h.speech.connect(first); h.queue.drain()
        h.persistence.afterStarted = { h.runtime.dispatch(PlaybackEvent.Stop) }
        first.callback(RuntimeOutputEvent.STARTED); h.queue.drain()
        assertEquals(listOf(first.attempt), h.persistence.started)
        assertTrue(h.automaticStarted.isEmpty())
        assertTrue(h.started.isEmpty())
        assertTrue(h.exclusionsAtRefresh.all { it.isEmpty() })
        assertFalse(h.runtime.snapshot().playIntent)
    }

    private class Harness(replanAfterStart: Boolean = false) {
        val a = PreparedStory("a", "a-v1", listOf("a-p1", "a-p2"))
        val b = PreparedStory("b", "b-v1", listOf("b-p1"))
        val queue = Queue()
        val log = mutableListOf<String>()
        val persistence = Persistence()
        val speech = Speech(log)
        val skipped = mutableListOf<String>()
        val started = mutableListOf<String>()
        val automaticStarted = mutableListOf<String>()
        val exclusionsAtRefresh = mutableListOf<Set<String>>()
        lateinit var runtime: PlaybackRuntime
        private val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("clock"), { _, excluded ->
            exclusionsAtRefresh += excluded
            // An actual Started callback makes its duration available to the host, which can
            // immediately move the next generation target to another region on that transition.
            (if (replanAfterStart && persistence.started.isNotEmpty()) listOf(b) else listOf(a, b))
                .filter { it.storyId !in excluded }.map {
                AutomaticStoryCandidate(it, CandidateReason.INTEREST, null, "synthetic-key-${it.storyId}")
            }
        }, object : PlannedStoryOutput {
            override fun offer(story: PreparedStory, offerId: String) { runtime.dispatch(PlaybackEvent.OfferStory(story, offerId)) }
            override fun withdraw(storyId: String, versionId: String) { runtime.dispatch(PlaybackEvent.ClearOfferedStory(storyId, versionId)) }
        }, { 1_000 }, { _, _ -> true })

        init {
            runtime = PlaybackRuntime(PlaybackCoordinator("trip", "session"), queue, RuntimeClock { 1_000 }, persistence, speech,
                contentAuthority = bridge, observer = object : RuntimeObserver {
                    override fun onTransition(transition: PlaybackTransition) {
                        bridge.update(PlanningContext(null, null, 1_000, "clock", false, transition.snapshot.interactionIntent))
                    }
                    override fun onStorySkipped(storyId: String) {
                        log += "skip"
                        skipped += storyId
                        bridge.storySkipped(storyId)
                    }
                    override fun onStoryStarted(storyId: String, versionId: String) {
                        started += storyId
                        bridge.storyStartedWithoutOffer(storyId)
                    }
                    override fun onAutomaticStoryStarted(offerId: String, storyId: String, versionId: String) {
                        automaticStarted += storyId
                        bridge.storyStarted(offerId, versionId)
                    }
                })
            queue.drain()
        }
        fun play(story: PreparedStory) { runtime.dispatch(PlaybackEvent.PlayStory(story)); queue.drain() }
        fun reference(output: Speech.Output) = persistence.references.getValue(output.attempt) as OutputReference.StoryParagraph
    }

    private class Queue : RuntimeQueue {
        private val tasks = ArrayDeque<() -> Unit>()
        override fun execute(task: () -> Unit) { tasks.addLast(task) }
        override fun schedule(delayMs: Long, task: () -> Unit) = RuntimeCancellation { }
        fun drain() { var count = 0; while (tasks.isNotEmpty()) { check(++count < 200); tasks.removeFirst().invoke() } }
    }

    private class Speech(private val log: MutableList<String>) : RuntimeSpeech {
        class Output(val attempt: OperationToken, val prepare: ((RuntimeSpeechPermit?) -> Unit) -> Unit,
                     val callback: (RuntimeOutputEvent) -> Unit)
        val outputs = mutableListOf<Output>()
        val spoken = mutableListOf<OperationToken>()
        override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) {
            error("prepared_output_required")
        }
        override fun startPreparedOutput(attempt: OperationToken, prepare: ((RuntimeSpeechPermit?) -> Unit) -> Unit,
                                         callback: (RuntimeOutputEvent) -> Unit) { outputs += Output(attempt, prepare, callback) }
        fun connect(output: Output) {
            output.prepare { permit ->
                if (permit != null && permit.isCurrent()) spoken += output.attempt
                else output.callback(RuntimeOutputEvent.STOPPED)
            }
        }
        override fun stopOutput(attempt: OperationToken) {
            log += "stop"
            outputs.single { it.attempt == attempt }.callback(RuntimeOutputEvent.STOPPED)
        }
        override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) { error("no_input") }
        override fun stopInput(input: OperationToken) = Unit
        override fun close() = Unit
    }

    private class Persistence : RuntimePersistence {
        val references = mutableMapOf<OperationToken, OutputReference>()
        val started = mutableListOf<OperationToken>()
        val commits = mutableListOf<PlaybackEffect.CommitPlayback>()
        var failStarted = false
        var afterStarted: () -> Unit = {}
        override fun openSession(tripId: String, sessionId: String) = Unit
        override fun registerTurn(tripId: String, sessionId: String, turnId: String) = Unit
        override fun registerOutput(attempt: OperationToken, reference: OutputReference, turnId: String?) { references[attempt] = reference }
        override fun markOutputStarted(attempt: OperationToken) {
            check(!failStarted) { "synthetic_started_commit_failure" }
            started += attempt
            afterStarted()
        }
        override fun loadOutput(reference: OutputReference) = RuntimeText("Synthetic public paragraph")
        override fun loadUtterance(payload: PayloadHandle): RuntimeText = error("no_input")
        override fun persistUtterance(commit: OperationToken, turnId: String, text: RuntimeText): PayloadHandle = error("no_input")
        override fun persistAnswer(commit: OperationToken, turnId: String, answer: RuntimeAnswer): ValidatedAnswer = error("no_generation")
        override fun commitPlayback(effect: PlaybackEffect.CommitPlayback, snapshot: PlaybackSnapshot) { commits += effect }
        override fun freezeTurn(effect: PlaybackEffect.FreezeTurn) = Unit
        override fun saveCheckpoint(snapshot: PlaybackSnapshot) = Unit
        override fun closeSession(sessionId: String) = Unit
    }
}
