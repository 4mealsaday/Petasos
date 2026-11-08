package com.petasos.app.runtime

import com.petasos.app.playback.*
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

/** Separates connection, worker preparation and SDK submission to expose delayed-start races. */
class PreparedSpeechRuntimeTest {
    @Test fun bodyIsReadAfterConnectionOnWorkerAndOnlyCurrentTextIsSubmitted() {
        val h = Harness()
        val slot = h.play()
        assertEquals(0, h.store.loads)
        h.store.body = RuntimeText("current synthetic body")
        h.speech.connected(slot)
        assertEquals(0, h.store.loads)
        h.queue.drain()
        assertEquals(1, h.store.loads)
        assertEquals(0, h.speech.submissions.size)
        h.speech.submit(slot); h.queue.drain()
        assertEquals(listOf("current synthetic body"), h.speech.submissions.map { it.value })
        assertEquals(1, h.store.started)
    }

    @Test fun rightsRevokedWhileConnectingNeverReachSdkAndCommitOneInterruption() {
        val h = Harness()
        val slot = h.play()
        h.store.rejectBody = true
        h.speech.connected(slot); h.queue.drain()
        assertNull(slot.results.single())
        h.speech.submit(slot); h.queue.drain()
        h.assertInterruptedOnce()
        assertTrue(RuntimeIssue.CONTENT_UNAVAILABLE in h.issues)
        assertFalse(RuntimeIssue.STORAGE_UNAVAILABLE in h.issues)
    }

    @Test fun automaticEligibilityRevokedBeforeConnectedRejectsWorkerPermit() {
        val h = Harness()
        val slot = h.play(automatic = true)
        h.authority.current = false
        h.speech.connected(slot); h.queue.drain()
        h.speech.submit(slot); h.queue.drain()
        h.assertInterruptedOnce()
        assertEquals(1, h.authority.fullChecks)
        assertEquals(0, h.authority.cheapChecks)
    }

    @Test fun preferenceRevokedAfterWorkerPermitIsRejectedAtSdkThreadWithoutDbRead() {
        val h = Harness()
        val slot = h.play(automatic = true)
        h.speech.connected(slot); h.queue.drain()
        assertNotNull(slot.results.single())
        h.authority.offered = false
        h.speech.submit(slot); h.queue.drain()
        h.assertInterruptedOnce()
        assertEquals(1, h.authority.fullChecks)
        assertEquals(1, h.authority.cheapChecks)
        assertEquals(1, h.store.loads)
    }

    @Test fun stopBeforeConnectedRejectsLateConnectionWithoutReadingBody() {
        val h = Harness()
        val slot = h.play()
        h.runtime.dispatch(PlaybackEvent.Stop); h.queue.drain()
        h.speech.connected(slot); h.queue.drain()
        h.speech.submit(slot); h.queue.drain() // An adversarial late callback cannot revive the attempt.
        h.assertInterruptedOnce()
        assertEquals(0, h.store.loads)
        assertFalse(h.runtime.snapshot().playIntent)
    }

    @Test fun stopAfterConnectedButBeforeWorkerReadRejectsQueuedPreparation() {
        val h = Harness()
        val slot = h.play()
        h.speech.connected(slot)
        h.runtime.dispatch(PlaybackEvent.Stop) // Fence changes before either queued action executes.
        h.queue.drain()
        h.speech.submit(slot); h.queue.drain()
        h.assertInterruptedOnce()
        assertEquals(0, h.store.loads)
    }

    @Test fun stopAfterPermitBeforeWorkerStopIsAppliedStillRejectsSdkSubmission() {
        val h = Harness()
        val slot = h.play()
        h.speech.connected(slot); h.queue.drain()
        val permit = slot.results.single()!!
        assertTrue(permit.isCurrent())
        h.runtime.dispatch(PlaybackEvent.Stop)
        assertFalse(permit.isCurrent())
        h.speech.submit(slot); h.queue.drain()
        h.assertInterruptedOnce()
    }

    @Test fun duplicatePreparationRequestsOnlyLoadOnceAndProduceOnePermit() {
        val h = Harness()
        val slot = h.play(automatic = true)
        h.speech.connected(slot); h.speech.connected(slot)
        h.queue.drain()
        assertEquals(1, h.store.loads)
        assertEquals(1, slot.results.size)
        h.speech.submit(slot); h.queue.drain()
        assertEquals(1, h.speech.submissions.size)
        assertEquals(1, h.store.started)
    }

    @Test fun explicitStoryDoesNotUseAutomaticGuardAndTalkMayFinishItsParagraph() {
        val h = Harness()
        val slot = h.play()
        h.runtime.dispatch(PlaybackEvent.Talk); h.queue.drain()
        assertEquals(PlaybackMode.TALK_PENDING, h.runtime.snapshot().mode)
        h.speech.connected(slot); h.queue.drain()
        h.speech.submit(slot); h.queue.drain()
        assertEquals(1, h.speech.submissions.size)
        assertEquals(0, h.authority.fullChecks)
        assertEquals(0, h.authority.cheapChecks)
    }

    @Test fun submittedOutputStillRequiresActualStoppedCallbackBeforeQuiescence() {
        val h = Harness()
        val slot = h.play()
        h.speech.connected(slot); h.queue.drain()
        h.speech.submit(slot); h.queue.drain()
        h.runtime.dispatch(PlaybackEvent.Stop); h.queue.drain()
        assertEquals(AdapterPhase.STOPPING, h.runtime.snapshot().outputPhase)
        slot.callback(RuntimeOutputEvent.STOPPED); h.queue.drain()
        assertEquals(AdapterPhase.READY, h.runtime.snapshot().outputPhase)
        assertEquals(listOf(AttemptOutcome.INTERRUPTED), h.store.commits.map { it.outcome })
    }

    private class Harness {
        val queue = Queue()
        val store = Store(queue)
        val speech = HeldSpeech()
        val authority = Authority(queue)
        val issues = mutableListOf<RuntimeIssue>()
        private var id = 0
        val runtime = PlaybackRuntime(PlaybackCoordinator("trip", "session", { "id-${++id}" }), queue,
            RuntimeClock { 1_000 }, store, speech, contentAuthority = authority,
            observer = object : RuntimeObserver { override fun onIssue(issue: RuntimeIssue) { issues += issue } })
        fun play(automatic: Boolean = false): HeldSpeech.Slot {
            val story = PreparedStory("story", "version", listOf("paragraph"))
            if (automatic) {
                runtime.dispatch(PlaybackEvent.Play); queue.drain()
                runtime.dispatch(PlaybackEvent.OfferStory(story, "automatic-offer"))
            } else runtime.dispatch(PlaybackEvent.PlayStory(story))
            queue.drain()
            return speech.slots.single()
        }
        fun assertInterruptedOnce() {
            assertTrue(speech.submissions.isEmpty())
            assertEquals(listOf(AttemptOutcome.INTERRUPTED), store.commits.map { it.outcome })
            assertTrue(store.attempts.isEmpty())
            assertEquals(AdapterPhase.READY, runtime.snapshot().outputPhase)
            assertEquals(0, store.started)
        }
    }

    private class Queue : RuntimeQueue {
        private val tasks = ArrayDeque<() -> Unit>()
        var running = false
            private set
        override fun execute(task: () -> Unit) { tasks.addLast(task) }
        override fun schedule(delayMs: Long, task: () -> Unit) = RuntimeCancellation { }
        fun drain() {
            var count = 0
            while (tasks.isNotEmpty()) {
                check(++count < 1_000)
                running = true
                try { tasks.removeFirst().invoke() } finally { running = false }
            }
        }
    }

    private class Authority(private val queue: Queue) : RuntimeContentAuthority {
        var current = true
        var offered = true
        var fullChecks = 0
        var cheapChecks = 0
        override fun isAutomaticStoryCurrent(offerId: String, reference: OutputReference.StoryParagraph): Boolean {
            check(queue.running) { "full eligibility must run on the worker" }
            fullChecks++
            return current
        }
        override fun isAutomaticStoryOffered(offerId: String, reference: OutputReference.StoryParagraph): Boolean {
            check(!queue.running) { "test invokes the final SDK guard outside the worker" }
            cheapChecks++
            return offered
        }
    }

    private class Store(private val queue: Queue) : RuntimePersistence {
        var body = RuntimeText("initial synthetic body")
        var rejectBody = false
        var loads = 0
        var started = 0
        val attempts = mutableSetOf<OperationToken>()
        val commits = mutableListOf<PlaybackEffect.CommitPlayback>()
        override fun openSession(tripId: String, sessionId: String) = Unit
        override fun registerTurn(tripId: String, sessionId: String, turnId: String) = Unit
        override fun registerOutput(attempt: OperationToken, reference: OutputReference, turnId: String?) { check(attempts.add(attempt)) }
        override fun markOutputStarted(attempt: OperationToken) { check(attempt in attempts); started++ }
        override fun loadOutput(reference: OutputReference): RuntimeText {
            check(queue.running) { "content must be read on the worker" }
            loads++
            if (rejectBody) throw RuntimeContentUnavailable()
            return body
        }
        override fun loadUtterance(payload: PayloadHandle): RuntimeText = error("unused")
        override fun persistUtterance(commit: OperationToken, turnId: String, text: RuntimeText): PayloadHandle = error("unused")
        override fun persistAnswer(commit: OperationToken, turnId: String, answer: RuntimeAnswer): ValidatedAnswer = error("unused")
        override fun commitPlayback(effect: PlaybackEffect.CommitPlayback, snapshot: PlaybackSnapshot) {
            check(attempts.remove(effect.attempt)); commits += effect
        }
        override fun freezeTurn(effect: PlaybackEffect.FreezeTurn) = Unit
        override fun saveCheckpoint(snapshot: PlaybackSnapshot) = Unit
        override fun closeSession(sessionId: String) = Unit
    }

    private class HeldSpeech : RuntimeSpeech {
        class Slot(val attempt: OperationToken, val prepare: ((RuntimeSpeechPermit?) -> Unit) -> Unit,
                   val callback: (RuntimeOutputEvent) -> Unit) {
            val results = mutableListOf<RuntimeSpeechPermit?>()
            var submitted = false
        }
        val slots = mutableListOf<Slot>()
        val submissions = mutableListOf<RuntimeText>()
        override fun startPreparedOutput(attempt: OperationToken, prepare: ((RuntimeSpeechPermit?) -> Unit) -> Unit,
                                         callback: (RuntimeOutputEvent) -> Unit) { slots += Slot(attempt, prepare, callback) }
        override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) = error("prepared port required")
        fun connected(slot: Slot) { slot.prepare { slot.results += it } }
        fun submit(slot: Slot) {
            val permit = slot.results.single()
            if (permit == null || !permit.isCurrent()) slot.callback(RuntimeOutputEvent.STOPPED)
            else { slot.submitted = true; submissions += permit.text; slot.callback(RuntimeOutputEvent.STARTED) }
        }
        override fun stopOutput(attempt: OperationToken) {
            val slot = slots.single { it.attempt == attempt }
            if (!slot.submitted) slot.callback(RuntimeOutputEvent.STOPPED)
        }
        override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) = error("unused")
        override fun stopInput(input: OperationToken) = Unit
        override fun close() = Unit
    }
}
