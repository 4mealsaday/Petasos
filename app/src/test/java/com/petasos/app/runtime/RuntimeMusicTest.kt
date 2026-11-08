package com.petasos.app.runtime

import com.petasos.app.playback.*
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class RuntimeMusicTest {
    @Test fun debouncedTalkAfterMusicResumeDoesNotFenceQueuedNaturalCompletion() {
        val h = Harness()
        val resumed = resumeMusicAfterTalkCancellation(h)
        h.queue.now = 500
        resumed.callback(RuntimeMusicEvent.Completed(9000))
        h.runtime.dispatch(PlaybackEvent.Talk) // 100 ms after the accepted cancel button.
        h.queue.now = 900 // A delayed worker must not turn this rejected button into a new Talk.
        h.queue.drain()
        assertEquals(1, h.music.stops.size)
        assertEquals(AttemptOutcome.COMPLETED, h.persistence.commits.last().outcome)
        assertEquals(resumed.attempt, h.persistence.commits.last().attempt)
        assertEquals(PlaybackMode.IDLE, h.runtime.snapshot().mode)
        assertEquals(AdapterPhase.READY, h.runtime.snapshot().outputPhase)
        assertFalse(h.runtime.snapshot().interactionIntent)
    }

    @Test fun talkAtDebounceBoundaryStillFencesCompletionAndWaitsForMusicStop() {
        val h = Harness()
        val resumed = resumeMusicAfterTalkCancellation(h)
        h.queue.now = 750
        resumed.callback(RuntimeMusicEvent.Completed(9000))
        h.runtime.dispatch(PlaybackEvent.Talk) // The 350 ms boundary is accepted.
        h.queue.drain()
        assertEquals(resumed.attempt, h.music.stops.last())
        assertTrue(h.persistence.commits.none { it.attempt == resumed.attempt })
        assertEquals(1, h.speech.outputs.size)
        resumed.callback(RuntimeMusicEvent.Stopped(2345)); h.queue.drain()
        assertEquals(AttemptOutcome.INTERRUPTED, h.persistence.commits.last().outcome)
        assertEquals(2345L, h.persistence.commits.last().outputPositionMs)
        assertEquals(2, h.speech.outputs.size)
    }

    private fun resumeMusicAfterTalkCancellation(h: Harness): Music.Output {
        h.play()
        h.runtime.dispatch(PlaybackEvent.Talk); h.queue.drain()
        h.music.outputs.single().callback(RuntimeMusicEvent.Stopped(1000)); h.queue.drain()
        h.queue.now = 400
        h.runtime.dispatch(PlaybackEvent.Talk); h.queue.drain()
        h.speech.outputs.single()(RuntimeOutputEvent.STOPPED); h.queue.drain()
        check(h.runtime.snapshot().mode == PlaybackMode.MUSIC)
        check(h.music.outputs.size == 2)
        return h.music.outputs.last()
    }

    @Test fun musicUsesDedicatedPortAndOnlyConfirmedStopCommitsPositionBeforeSpeech() {
        val h = Harness()
        h.play()
        assertEquals(1, h.music.outputs.size)
        assertTrue(h.speech.outputs.isEmpty())
        assertEquals(0, h.persistence.speechLoads)
        val music = h.music.outputs.single()
        music.callback(RuntimeMusicEvent.Position(1000)); h.queue.drain()
        h.runtime.dispatch(PlaybackEvent.Talk); h.queue.drain()
        assertEquals(listOf(music.attempt), h.music.stops)
        assertTrue(h.persistence.commits.isEmpty())
        assertTrue(h.speech.outputs.isEmpty())
        music.callback(RuntimeMusicEvent.Completed(9000)); h.queue.drain()
        assertTrue(h.persistence.commits.isEmpty())
        music.callback(RuntimeMusicEvent.Stopped(1234)); h.queue.drain()
        val commit = h.persistence.commits.single()
        assertEquals(AttemptOutcome.INTERRUPTED, commit.outcome)
        assertEquals(1234L, commit.outputPositionMs)
        assertEquals(1234L, commit.musicCheckpoint!!.positionMs)
        assertEquals(1, h.speech.outputs.size)
        assertTrue(h.log.indexOf("commit-music") < h.log.indexOf("speech"))
    }

    @Test fun nextIngressFencesQueuedNaturalEndAndStartsNextTrackOnlyAfterStoppedCommit() {
        val h = Harness()
        h.play()
        h.runtime.dispatch(PlaybackEvent.OfferMusic(PreparedMusic("song-2", 1000))); h.queue.drain()
        val first = h.music.outputs.single()
        first.callback(RuntimeMusicEvent.Completed(9000))
        h.runtime.dispatch(PlaybackEvent.Next)
        h.queue.drain()
        assertTrue(h.persistence.commits.isEmpty())
        assertEquals(1, h.music.outputs.size)
        first.callback(RuntimeMusicEvent.Stopped(2345)); h.queue.drain()
        assertEquals(AttemptOutcome.INTERRUPTED, h.persistence.commits.single().outcome)
        assertEquals("song-2", h.music.outputs.last().source.assetId)
        assertNotEquals(first.attempt, h.music.outputs.last().attempt)
    }

    @Test fun musicPermissionRevokedBetweenRegisterAndStartNeverInvokesPlayerAndCleansAttempt() {
        val h = Harness()
        h.runtime.playInstalledMusic("song-1")
        h.queue.runNext() // open
        h.queue.runNext() // prepare + resume planner
        h.queue.runNext() // planner returns and registers
        h.persistence.rejectLoad = true
        h.queue.drain()
        assertTrue(h.music.outputs.isEmpty())
        assertEquals(AttemptOutcome.INTERRUPTED, h.persistence.commits.single().outcome)
        assertTrue(h.issues.contains(RuntimeIssue.CONTENT_UNAVAILABLE))
        assertFalse(h.issues.contains(RuntimeIssue.STORAGE_UNAVAILABLE))
        assertEquals(AdapterPhase.READY, h.runtime.snapshot().outputPhase)
    }

    @Test fun previousRestartsAtZeroThroughNewRegisteredAttempt() {
        val h = Harness()
        h.play()
        val first = h.music.outputs.single()
        first.callback(RuntimeMusicEvent.Position(3456)); h.queue.drain()
        h.runtime.dispatch(PlaybackEvent.Previous); h.queue.drain()
        first.callback(RuntimeMusicEvent.Stopped(3457)); h.queue.drain()
        assertEquals(2, h.music.outputs.size)
        assertEquals(0L, h.music.outputs.last().position)
        assertNotEquals(first.attempt, h.music.outputs.last().attempt)
        assertEquals(3457L, h.persistence.commits.single().outputPositionMs)
    }

    @Test fun stopBeforeQueuedMusicStartUsesKnownSilenceAndDoesNotActivatePlayer() {
        val h = Harness()
        h.runtime.playInstalledMusic("song-1")
        h.queue.runNext()
        h.queue.runNext()
        h.queue.runNext()
        h.runtime.dispatch(PlaybackEvent.Stop)
        h.queue.drain()
        assertTrue(h.music.outputs.isEmpty())
        assertEquals(AttemptOutcome.INTERRUPTED, h.persistence.commits.single().outcome)
        assertEquals(0L, h.persistence.commits.single().outputPositionMs)
        assertFalse(h.runtime.snapshot().playIntent)
    }

    @Test fun closeWaitsForRealMusicStopThenCommitsAndReleasesBothAdapters() {
        val h = Harness()
        h.play()
        h.runtime.close(); h.queue.drain()
        assertEquals(0, h.persistence.closed)
        assertFalse(h.music.closed)
        h.music.outputs.single().callback(RuntimeMusicEvent.Stopped(4567)); h.queue.drain()
        assertEquals(1, h.persistence.closed)
        assertTrue(h.music.closed)
        assertTrue(h.speech.closed)
        assertEquals(4567L, h.persistence.commits.single().musicCheckpoint!!.positionMs)
    }

    @Test fun unavailableMusicFailsExplicitlyWithoutInventingCompletionOrHangingStop() {
        val h = Harness(useUnavailable = true)
        h.play()
        assertTrue(h.music.outputs.isEmpty())
        assertTrue(h.issues.contains(RuntimeIssue.MUSIC_UNAVAILABLE))
        assertEquals(AttemptOutcome.INTERRUPTED, h.persistence.commits.single().outcome)
        assertEquals(AdapterPhase.READY, h.runtime.snapshot().outputPhase)
        assertTrue(h.runtime.snapshot().blockers.contains(PlaybackBlocker.SERVICE_UNAVAILABLE))
    }

    @Test fun latePlannerResponseCannotRestartAfterStopAndUnrelatedNavigationDoesNotFenceSpeech() {
        val planner = HeldPlanner()
        val h = Harness(planner = planner)
        h.runtime.playInstalledMusic("song-1"); h.queue.drain()
        val held = planner.jobs.single()
        h.runtime.dispatch(PlaybackEvent.Stop); h.queue.drain()
        assertTrue(held.cancelled)
        held.callback(RuntimeResumeSelection(musicCheckpoint = held.effect.musicCandidates.single()))
        h.queue.drain()
        assertTrue(h.music.outputs.isEmpty())
        h.runtime.dispatch(PlaybackEvent.Talk); h.queue.drain()
        h.runtime.dispatch(PlaybackEvent.Next) // No music/story is active; core ignores this.
        h.speech.outputs.single()(RuntimeOutputEvent.COMPLETED)
        h.queue.drain()
        assertEquals(PlaybackMode.LISTENING, h.runtime.snapshot().mode)
    }

    @Test fun automaticStoryIsRevalidatedAtSdkBoundaryAndFailsClosedWithoutAuthority() {
        var authorized = true
        val h = Harness(authority = RuntimeContentAuthority { _, _ -> authorized })
        h.runtime.dispatch(PlaybackEvent.Play); h.queue.drain()
        h.runtime.dispatch(PlaybackEvent.OfferStory(PreparedStory("s", "v", listOf("p")), "offer-1"))
        h.queue.runNext() // Offered content is registered; SDK start is still queued.
        authorized = false
        h.queue.drain()
        assertTrue(h.speech.outputs.isEmpty())
        assertEquals(AttemptOutcome.INTERRUPTED, h.persistence.commits.single().outcome)
        assertTrue(h.issues.contains(RuntimeIssue.CONTENT_UNAVAILABLE))
        assertFalse(h.issues.contains(RuntimeIssue.STORAGE_UNAVAILABLE))
    }

    @Test fun automaticOfferIsConsumedOnlyOnActualStartAndLaterRegionChangeDoesNotInterruptStory() {
        var authorized = true
        var checks = 0
        val h = Harness(authority = RuntimeContentAuthority { offer, _ -> checks++; offer == "offer-1" && authorized })
        h.runtime.dispatch(PlaybackEvent.Play); h.queue.drain()
        h.runtime.dispatch(PlaybackEvent.OfferStory(PreparedStory("s", "v", listOf("p1", "p2")), "offer-1")); h.queue.drain()
        assertTrue(h.startedOffers.isEmpty())
        h.speech.outputs.single()(RuntimeOutputEvent.STARTED); h.queue.drain()
        assertEquals(listOf("offer-1"), h.startedOffers)
        assertTrue(h.startedStories.isEmpty()) // Automatic token consumption stays on its exact-offer callback.
        authorized = false
        h.runtime.dispatch(PlaybackEvent.ClearOfferedStory("s", "v"))
        h.speech.outputs.single()(RuntimeOutputEvent.COMPLETED); h.queue.drain()
        assertEquals(2, h.speech.outputs.size)
        assertEquals(2, checks) // Worker preparation and the final cheap guard; none after Started.
        assertEquals(PlaybackMode.STORY, h.runtime.snapshot().mode)
    }

    @Test fun explicitStorySelectionDoesNotRequireAutomaticLocationEligibility() {
        val h = Harness(authority = RuntimeContentAuthority { _, _ -> error("must not consult automatic authority") })
        h.runtime.dispatch(PlaybackEvent.PlayStory(PreparedStory("s", "v", listOf("p")))); h.queue.drain()
        assertEquals(1, h.speech.outputs.size)
        assertTrue(h.issues.isEmpty())
    }

    @Test fun onlyActualCurrentExplicitStartNotifiesStoryExclusionAndExplicitReplayNotifiesAgain() {
        val h = Harness()
        val story = PreparedStory("s", "v", listOf("p1", "p2"))
        h.runtime.dispatch(PlaybackEvent.PlayStory(story)); h.queue.drain()
        val first = h.speech.outputs.single()
        assertTrue(h.startedStories.isEmpty())
        first(RuntimeOutputEvent.STARTED); h.queue.drain()
        assertEquals(listOf("s" to "v"), h.startedStories)
        first(RuntimeOutputEvent.STARTED); h.queue.drain()
        assertEquals(1, h.startedStories.size)
        h.runtime.dispatch(PlaybackEvent.Next); h.queue.drain()
        first(RuntimeOutputEvent.STARTED); h.queue.drain()
        assertEquals(1, h.startedStories.size)
        first(RuntimeOutputEvent.STOPPED); h.queue.drain()
        h.runtime.dispatch(PlaybackEvent.PlayStory(story)); h.queue.drain()
        assertEquals(2, h.speech.outputs.size)
        first(RuntimeOutputEvent.STARTED); h.queue.drain() // Retired output callback cannot consume the new attempt.
        assertEquals(1, h.startedStories.size)
        h.speech.outputs.last()(RuntimeOutputEvent.STARTED); h.queue.drain()
        assertEquals(listOf("s" to "v", "s" to "v"), h.startedStories)
        assertTrue(h.startedOffers.isEmpty())
    }

    private class Harness(useUnavailable: Boolean = false, planner: RuntimeResumePlanner = CheckpointRuntimeResumePlanner(),
                          authority: RuntimeContentAuthority = RuntimeContentAuthority { _, _ -> false }) {
        val log = mutableListOf<String>()
        val queue = Queue()
        val persistence = Persistence(log)
        val speech = Speech(log)
        val music = Music()
        val issues = mutableListOf<RuntimeIssue>()
        val startedOffers = mutableListOf<String>()
        val startedStories = mutableListOf<Pair<String, String>>()
        private var id = 0
        val runtime = PlaybackRuntime(PlaybackCoordinator("trip", "session", { "op-${++id}" }), queue, queue, persistence, speech,
            observer = object : RuntimeObserver {
                override fun onIssue(issue: RuntimeIssue) { issues += issue }
                override fun onAutomaticStoryStarted(offerId: String, storyId: String, versionId: String) { startedOffers += offerId }
                override fun onStoryStarted(storyId: String, versionId: String) { startedStories += storyId to versionId }
            },
            music = if (useUnavailable) UnavailableRuntimeMusic() else music, resumePlanner = planner, contentAuthority = authority)
        fun play() { runtime.playInstalledMusic("song-1"); queue.drain() }
    }

    private class Queue : RuntimeQueue, RuntimeClock {
        private val tasks = ArrayDeque<() -> Unit>()
        var now = 0L
        override fun elapsedRealtimeMs() = now
        override fun execute(task: () -> Unit) { tasks.addLast(task) }
        override fun schedule(delayMs: Long, task: () -> Unit) = RuntimeCancellation { }
        fun runNext() { tasks.removeFirst().invoke() }
        fun drain() { var count = 0; while (tasks.isNotEmpty()) { check(++count < 100); runNext() } }
    }

    private class Persistence(private val log: MutableList<String>) : RuntimePersistence {
        val registered = mutableSetOf<OperationToken>()
        val commits = mutableListOf<PlaybackEffect.CommitPlayback>()
        var rejectLoad = false
        var speechLoads = 0
        var closed = 0
        override fun openSession(tripId: String, sessionId: String) = Unit
        override fun prepareMusic(assetId: String) = PreparedMusic(assetId, 9000)
        override fun loadMusic(reference: OutputReference.MusicTrack): RuntimeMusicSource {
            if (rejectLoad) throw RuntimeContentUnavailable()
            return RuntimeMusicSource(reference.assetId, "file:///synthetic-test-only.wav", 9000)
        }
        override fun registerTurn(tripId: String, sessionId: String, turnId: String) = Unit
        override fun registerOutput(attempt: OperationToken, reference: OutputReference, turnId: String?) { check(registered.add(attempt)) }
        override fun markOutputStarted(attempt: OperationToken) = Unit
        override fun loadOutput(reference: OutputReference): RuntimeText { speechLoads++; return RuntimeText("합성 유도 문장") }
        override fun loadUtterance(payload: PayloadHandle) = RuntimeText("합성 발화")
        override fun persistUtterance(commit: OperationToken, turnId: String, text: RuntimeText) = PayloadHandle("stored")
        override fun persistAnswer(commit: OperationToken, turnId: String, answer: RuntimeAnswer) = ValidatedAnswer(PayloadHandle("answer"), answer.paragraphs.map { it.key })
        override fun commitPlayback(effect: PlaybackEffect.CommitPlayback, snapshot: PlaybackSnapshot) {
            check(registered.remove(effect.attempt)); commits += effect
            if (effect.reference is OutputReference.MusicTrack) log += "commit-music"
        }
        override fun freezeTurn(effect: PlaybackEffect.FreezeTurn) = Unit
        override fun saveCheckpoint(snapshot: PlaybackSnapshot) = Unit
        override fun closeSession(sessionId: String) { closed++ }
    }

    private class Music : RuntimeMusic {
        class Output(val attempt: OperationToken, val source: RuntimeMusicSource, val position: Long, val callback: (RuntimeMusicEvent) -> Unit)
        val outputs = mutableListOf<Output>()
        val stops = mutableListOf<OperationToken>()
        var closed = false
        override fun startOutput(attempt: OperationToken, source: RuntimeMusicSource, positionMs: Long, callback: (RuntimeMusicEvent) -> Unit) { outputs += Output(attempt, source, positionMs, callback) }
        override fun stopOutput(attempt: OperationToken) { stops += attempt }
        override fun close() { closed = true }
    }

    private class Speech(private val log: MutableList<String>) : RuntimeSpeech {
        val outputs = mutableListOf<(RuntimeOutputEvent) -> Unit>()
        var closed = false
        override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) { log += "speech"; outputs += callback }
        override fun stopOutput(attempt: OperationToken) = Unit
        override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) = Unit
        override fun stopInput(input: OperationToken) = Unit
        override fun close() { closed = true }
    }

    private class HeldPlanner : RuntimeResumePlanner {
        class Job(val effect: PlaybackEffect.PlanResume, val callback: (RuntimeResumeSelection) -> Unit) { var cancelled = false }
        val jobs = mutableListOf<Job>()
        override fun plan(effect: PlaybackEffect.PlanResume, callback: (RuntimeResumeSelection) -> Unit): RuntimeCancellation {
            val job = Job(effect, callback); jobs += job
            return RuntimeCancellation { job.cancelled = true }
        }
    }
}
