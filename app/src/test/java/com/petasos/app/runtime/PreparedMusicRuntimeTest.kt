package com.petasos.app.runtime

import com.petasos.app.playback.*
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

/** Holds the adapter's main-thread slot, worker preparation and final submission independently. */
class PreparedMusicRuntimeTest {
    @Test fun currentSourceIsLoadedOnWorkerAfterAdapterReservation() {
        val h = Harness()
        val slot = h.play()
        assertEquals(0, h.store.loads)
        h.store.source = RuntimeMusicSource("song", "file:///new-reviewed-synthetic.wav", 9000)
        h.music.prepare(slot)
        assertEquals(0, h.store.loads)
        h.queue.drain()
        assertEquals(1, h.store.loads)
        assertTrue(h.music.submissions.isEmpty())
        h.music.submit(slot); h.queue.drain()
        assertSame(h.store.source, h.music.submissions.single())
        assertEquals(1, h.store.started)
        assertEquals(PlaybackMode.MUSIC, h.runtime.snapshot().mode)
    }

    @Test fun deniedFileOrRightsBeforePreparationStopsWithoutPlayerAndRequiresNewIntent() {
        val h = Harness()
        val slot = h.play()
        h.store.reject = true
        h.music.prepare(slot); h.queue.drain()
        assertNull(slot.results.single())
        h.music.submit(slot); h.queue.drain()
        h.assertInterruptedOnce()
        assertTrue(RuntimeIssue.CONTENT_UNAVAILABLE in h.issues)
        assertFalse(RuntimeIssue.STORAGE_UNAVAILABLE in h.issues)
        assertFalse(h.runtime.snapshot().playIntent)
        h.runtime.dispatch(PlaybackEvent.Unblock(PlaybackBlocker.SERVICE_UNAVAILABLE)); h.queue.drain()
        assertEquals(1, h.music.slots.size) // Unblock cannot silently retry the same rejected intent.
    }

    @Test fun wrongAssetFromPersistenceCannotBecomeAPlayablePermit() {
        val h = Harness()
        val slot = h.play()
        h.store.source = RuntimeMusicSource("other-song", "file:///synthetic-other.wav", 9000)
        h.music.prepare(slot); h.queue.drain()
        assertNull(slot.results.single())
        h.assertInterruptedOnce()
        assertFalse(h.runtime.snapshot().playIntent)
    }

    @Test fun stopBeforeAdapterPreparationAvoidsFileReadAndRejectsLateRequest() {
        val h = Harness()
        val slot = h.play()
        h.runtime.dispatch(PlaybackEvent.Stop); h.queue.drain()
        h.music.prepare(slot); h.queue.drain()
        h.music.submit(slot); h.queue.drain()
        assertEquals(0, h.store.loads)
        h.assertInterruptedOnce()
    }

    @Test fun stopIngressBeforeQueuedWorkerReadImmediatelyRevokesPreparation() {
        val h = Harness()
        val slot = h.play()
        h.music.prepare(slot)
        h.runtime.dispatch(PlaybackEvent.Stop)
        h.queue.drain()
        assertEquals(0, h.store.loads)
        assertNull(slot.results.single())
        h.assertInterruptedOnce()
    }

    @Test fun stopDuringFileReadCannotRebaseTheOldStartFence() {
        val h = Harness()
        val slot = h.play()
        h.store.onLoad = { h.runtime.dispatch(PlaybackEvent.Stop) }
        h.music.prepare(slot); h.queue.drain()
        assertEquals(1, h.store.loads)
        assertNull(slot.results.single())
        h.assertInterruptedOnce()
    }

    @Test fun stopAfterPermitBeforeWorkerProcessesStopStillPreventsMainSubmission() {
        val h = Harness()
        val slot = h.prepared()
        val permit = slot.results.single()!!
        assertTrue(permit.isCurrent())
        h.runtime.dispatch(PlaybackEvent.Stop)
        assertFalse(permit.isCurrent())
        h.music.submit(slot); h.queue.drain()
        h.assertInterruptedOnce()
        assertFalse(h.runtime.snapshot().playIntent)
    }

    @Test fun talkAfterPermitRevokesMusicAndOnlyPromptsAfterSilentAttemptCommit() {
        val h = Harness()
        val slot = h.prepared()
        h.runtime.dispatch(PlaybackEvent.Talk)
        assertFalse(slot.results.single()!!.isCurrent())
        h.music.submit(slot); h.queue.drain()
        h.assertInterruptedOnce()
        assertEquals(1, h.speech.outputs.size)
        assertEquals(PlaybackMode.PROMPT, h.runtime.snapshot().mode)
        assertTrue(h.log.indexOf("commit-music") < h.log.indexOf("speech"))
    }

    @Test fun closeAfterPermitCannotStartAndClosesOnlyAfterAttemptIsDrained() {
        val h = Harness()
        val slot = h.prepared()
        val closed = mutableListOf<Boolean>()
        h.runtime.close(closed::add)
        assertFalse(slot.results.single()!!.isCurrent())
        assertEquals(0, h.store.closed)
        h.queue.drain()
        h.music.submit(slot); h.queue.drain()
        h.assertInterruptedOnce()
        assertEquals(listOf(true), closed)
        assertEquals(1, h.store.closed)
    }

    @Test fun powerLossAfterPermitPreventsPlayerWithoutMainThreadStorageReads() {
        val h = Harness()
        val slot = h.prepared()
        h.runtime.dispatch(PlaybackEvent.Block(PlaybackBlocker.POWER_AUDIO))
        assertFalse(slot.results.single()!!.isCurrent())
        h.music.submit(slot); h.queue.drain()
        h.assertInterruptedOnce()
        assertEquals(1, h.store.loads)
        assertTrue(PlaybackBlocker.POWER_AUDIO in h.runtime.snapshot().blockers)
    }

    @Test fun rightsExpiryAfterWorkerPermitRejectsMainStartAndDoesNotAutoRetry() {
        val h = Harness()
        var now = 100L
        h.store.source = RuntimeMusicSource("song", "file:///synthetic-reviewed.wav", 9000) { now < 200 }
        val slot = h.prepared()
        assertTrue(slot.results.single()!!.isCurrent())
        now = 200
        h.music.submit(slot); h.queue.drain()
        h.assertInterruptedOnce()
        assertFalse(h.runtime.snapshot().playIntent)
        assertEquals(1, h.store.loads)
        h.runtime.dispatch(PlaybackEvent.Unblock(PlaybackBlocker.SERVICE_UNAVAILABLE)); h.queue.drain()
        assertEquals(1, h.music.slots.size)
    }

    @Test fun sourceAuthorityFailureAfterPermitIsFailClosed() {
        val h = Harness()
        var fail = false
        h.store.source = RuntimeMusicSource("song", "file:///synthetic-reviewed.wav", 9000) {
            if (fail) error("synthetic review failure") else true
        }
        val slot = h.prepared()
        fail = true
        h.music.submit(slot); h.queue.drain()
        h.assertInterruptedOnce()
        assertFalse(h.runtime.snapshot().playIntent)
    }

    @Test fun duplicatePrepareAndStartedCallbacksHaveOneReadAndOneDurableStart() {
        val h = Harness()
        val slot = h.play()
        h.music.prepare(slot); h.music.prepare(slot); h.queue.drain()
        assertEquals(1, h.store.loads)
        assertEquals(1, slot.results.size)
        h.music.submit(slot)
        slot.callback(RuntimeMusicEvent.Started); slot.callback(RuntimeMusicEvent.Started)
        h.queue.drain()
        assertEquals(1, h.store.started)
        assertEquals(1, h.music.submissions.size)
    }

    @Test fun submittedPlayerRequiresRealStopAckAndCannotCompleteAfterStop() {
        val h = Harness()
        val slot = h.prepared()
        h.music.submit(slot); h.queue.drain()
        h.runtime.dispatch(PlaybackEvent.Stop); h.queue.drain()
        assertEquals(AdapterPhase.STOPPING, h.runtime.snapshot().outputPhase)
        assertTrue(h.store.commits.isEmpty())
        slot.callback(RuntimeMusicEvent.Completed(9000)); h.queue.drain()
        assertTrue(h.store.commits.isEmpty())
        slot.callback(RuntimeMusicEvent.Stopped(1234)); h.queue.drain()
        slot.callback(RuntimeMusicEvent.Stopped(1234)); h.queue.drain()
        assertEquals(listOf(AttemptOutcome.INTERRUPTED), h.store.commits.map { it.outcome })
        assertEquals(1234L, h.store.commits.single().outputPositionMs)
        assertEquals(AdapterPhase.READY, h.runtime.snapshot().outputPhase)
    }

    @Test fun naturalEndCommitsOnceAndRevokesItsPermit() {
        val h = Harness()
        val slot = h.prepared()
        h.music.submit(slot); h.queue.drain()
        slot.callback(RuntimeMusicEvent.Completed(9000)); slot.callback(RuntimeMusicEvent.Completed(9000))
        h.queue.drain()
        assertEquals(listOf(AttemptOutcome.COMPLETED), h.store.commits.map { it.outcome })
        assertFalse(slot.results.single()!!.isCurrent())
        assertEquals(AdapterPhase.READY, h.runtime.snapshot().outputPhase)
    }

    @Test fun storageReadFailureDrainsNeverStartedSlotWithoutFalseCompletion() {
        val h = Harness()
        val slot = h.play()
        h.store.failRead = true
        h.music.prepare(slot); h.queue.drain()
        h.music.submit(slot); h.queue.drain()
        assertTrue(h.music.submissions.isEmpty())
        assertEquals(AdapterPhase.READY, h.runtime.snapshot().outputPhase)
        assertTrue(PlaybackBlocker.STORAGE_UNAVAILABLE in h.runtime.snapshot().blockers)
        assertTrue(RuntimeIssue.STORAGE_UNAVAILABLE in h.issues)
        assertTrue(h.store.commits.isEmpty()) // A failed store never fabricates a committed attempt.
    }

    @Test fun oldPermitCannotStartWhenANewExplicitPlayHasAlreadyBegun() {
        val h = Harness()
        val first = h.prepared()
        h.runtime.dispatch(PlaybackEvent.Stop); h.queue.drain()
        val second = h.prepared()
        assertNotEquals(first.attempt, second.attempt)
        assertFalse(first.results.single()!!.isCurrent())
        h.music.submit(first)
        first.callback(RuntimeMusicEvent.Started)
        h.music.submit(second); h.queue.drain()
        assertEquals(1, h.store.started)
        assertEquals(1, h.music.submissions.size)
        assertEquals(PlaybackMode.MUSIC, h.runtime.snapshot().mode)
    }

    private class Harness {
        val queue = Queue()
        val log = mutableListOf<String>()
        val store = Store(queue, log)
        val music = HeldMusic()
        val speech = Speech(log)
        val issues = mutableListOf<RuntimeIssue>()
        private var id = 0
        val runtime = PlaybackRuntime(PlaybackCoordinator("trip", "session", { "id-${++id}" }), queue,
            RuntimeClock { 1000 }, store, speech, music = music,
            observer = object : RuntimeObserver { override fun onIssue(issue: RuntimeIssue) { issues += issue } })
        fun play(): HeldMusic.Slot {
            runtime.playInstalledMusic("song"); queue.drain()
            return music.slots.last()
        }
        fun prepared(): HeldMusic.Slot = play().also { music.prepare(it); queue.drain() }
        fun assertInterruptedOnce() {
            assertTrue(music.submissions.isEmpty())
            assertEquals(listOf(AttemptOutcome.INTERRUPTED), store.commits.map { it.outcome })
            assertTrue(store.attempts.none { it in music.slots.map { slot -> slot.attempt } })
            // Talk may already have started its separate prompt by this boundary.
            if (speech.outputs.isEmpty()) assertEquals(AdapterPhase.READY, runtime.snapshot().outputPhase)
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
                check(++count < 1000)
                running = true
                try { tasks.removeFirst().invoke() } finally { running = false }
            }
        }
    }

    private class Store(private val queue: Queue, private val log: MutableList<String>) : RuntimePersistence {
        var source = RuntimeMusicSource("song", "file:///initial-reviewed-synthetic.wav", 9000)
        var reject = false
        var failRead = false
        var onLoad: () -> Unit = {}
        var loads = 0
        var started = 0
        var closed = 0
        val attempts = mutableSetOf<OperationToken>()
        val commits = mutableListOf<PlaybackEffect.CommitPlayback>()
        override fun openSession(tripId: String, sessionId: String) = Unit
        override fun prepareMusic(assetId: String) = PreparedMusic(assetId, 9000)
        override fun loadMusic(reference: OutputReference.MusicTrack): RuntimeMusicSource {
            check(queue.running) { "music rights and files must be read on the worker" }
            loads++
            onLoad()
            if (reject) throw RuntimeContentUnavailable()
            if (failRead) error("synthetic database failure")
            return source
        }
        override fun registerTurn(tripId: String, sessionId: String, turnId: String) = Unit
        override fun registerOutput(attempt: OperationToken, reference: OutputReference, turnId: String?) { check(attempts.add(attempt)) }
        override fun markOutputStarted(attempt: OperationToken) { check(attempt in attempts); started++ }
        override fun loadOutput(reference: OutputReference) = RuntimeText("synthetic prompt")
        override fun loadUtterance(payload: PayloadHandle): RuntimeText = error("unused")
        override fun persistUtterance(commit: OperationToken, turnId: String, text: RuntimeText): PayloadHandle = error("unused")
        override fun persistAnswer(commit: OperationToken, turnId: String, answer: RuntimeAnswer): ValidatedAnswer = error("unused")
        override fun commitPlayback(effect: PlaybackEffect.CommitPlayback, snapshot: PlaybackSnapshot) {
            check(attempts.remove(effect.attempt)); commits += effect
            if (effect.reference is OutputReference.MusicTrack) log += "commit-music"
        }
        override fun freezeTurn(effect: PlaybackEffect.FreezeTurn) = Unit
        override fun saveCheckpoint(snapshot: PlaybackSnapshot) = Unit
        override fun closeSession(sessionId: String) { closed++ }
    }

    private class HeldMusic : RuntimeMusic {
        class Slot(val attempt: OperationToken, val positionMs: Long,
                   val prepare: ((RuntimeMusicPermit?) -> Unit) -> Unit, val callback: (RuntimeMusicEvent) -> Unit) {
            val results = mutableListOf<RuntimeMusicPermit?>()
            var submitted = false
            var stopped = false
        }
        val slots = mutableListOf<Slot>()
        val submissions = mutableListOf<RuntimeMusicSource>()
        override fun startPreparedOutput(attempt: OperationToken, positionMs: Long,
                                         prepare: ((RuntimeMusicPermit?) -> Unit) -> Unit,
                                         callback: (RuntimeMusicEvent) -> Unit) { slots += Slot(attempt, positionMs, prepare, callback) }
        override fun startOutput(attempt: OperationToken, source: RuntimeMusicSource, positionMs: Long,
                                 callback: (RuntimeMusicEvent) -> Unit) = error("prepared port required")
        fun prepare(slot: Slot) { slot.prepare { slot.results += it } }
        fun submit(slot: Slot) {
            if (slot.stopped || slot.submitted) return
            val permit = slot.results.single()
            if (permit == null || !permit.isCurrent()) {
                slot.stopped = true
                slot.callback(RuntimeMusicEvent.Stopped(slot.positionMs))
            } else {
                slot.submitted = true
                submissions += permit.source
                slot.callback(RuntimeMusicEvent.Started)
            }
        }
        override fun stopOutput(attempt: OperationToken) {
            val slot = slots.single { it.attempt == attempt }
            if (!slot.submitted && !slot.stopped) {
                slot.stopped = true
                slot.callback(RuntimeMusicEvent.Stopped(slot.positionMs))
            }
        }
        override fun close() = Unit
    }

    private class Speech(private val log: MutableList<String>) : RuntimeSpeech {
        val outputs = mutableListOf<(RuntimeOutputEvent) -> Unit>()
        override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) {
            outputs += callback; log += "speech"
        }
        override fun stopOutput(attempt: OperationToken) = Unit
        override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) = Unit
        override fun stopInput(input: OperationToken) = Unit
        override fun close() = Unit
    }
}
