package com.petasos.app.planning

import com.petasos.app.playback.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackLookaheadTest {
    private val story = PreparedStory("story", "version", listOf("p1", "p2", "p3"))
    private val track = PreparedMusic("music", 60_000)

    @Test fun initialIdleProjectsNowOnlyAfterAnExplicitListeningIntent() {
        val h = Harness()
        assertNull(h.project())
        h.send(PlaybackEvent.Play)
        val resume = h.last.effects.filterIsInstance<PlaybackEffect.PlanResume>().single()
        h.send(PlaybackEvent.ResumePlanned(resume.request, null))
        assertEquals(0L, h.project())
        h.send(PlaybackEvent.Stop)
        assertNull(h.project())
    }

    @Test fun requestedOutputDoesNotCountAsStartedAndStartupDelayIsIncluded() {
        val h = Harness()
        val output = h.story(story)
        assertTrue(h.tracker.awaitingActualStart())
        assertNull(h.project())
        h.now = 3_000
        h.started(output)
        assertFalse(h.tracker.awaitingActualStart())
        assertEquals(93_000L, h.project())
        h.now = 7_000
        assertEquals(93_000L, h.project())
    }

    @Test fun onlyCurrentParagraphLosesElapsedTimeAndRemainingParagraphsKeepFullDuration() {
        val h = Harness()
        val first = h.story(story)
        h.started(first)
        h.now = 5_000
        assertEquals(90_000L, h.project())
        val second = h.complete(first)
        assertNull(h.project())
        h.now = 6_000
        h.started(second)
        assertEquals(86_000L, h.project())
    }

    @Test fun checkpointUsesOnlyUncompletedParagraphs() {
        val h = Harness(StoryCheckpoint(story, 2))
        h.send(PlaybackEvent.Play)
        val output = h.resolveResume()
        h.started(output)
        assertEquals(70_000L, h.project())
    }

    @Test fun anOverdueParagraphRequiresRealDoneAndCommitBeforeAnotherProjection() {
        val h = Harness()
        val first = h.story(story)
        h.started(first)
        h.now = 10_000
        assertNull(h.project())
        h.now = 20_000
        assertNull(h.project())
        val commit = h.send(PlaybackEvent.OutputDone(first.attempt))
        assertEquals(1, commit.snapshot.pendingPlaybackCommits)
        assertNull(h.project())
        val second = h.send(PlaybackEvent.PlaybackCommitted(first.attempt, true)).output()
        assertNull(h.project())
        h.started(second)
        assertEquals(100_000L, h.project())
    }

    @Test fun musicUsesConfirmedResumePositionAndElapsedSinceActualStart() {
        val h = Harness(musicCheckpoint = MusicCheckpoint(track, 20_000))
        h.send(PlaybackEvent.Play)
        val output = h.resolveResume()
        h.now = 3_000
        h.started(output)
        h.now = 8_000
        assertEquals(43_000L, h.project())
    }

    @Test fun actualMusicPositionReplacesStartedAnchorWithoutDoubleCountingElapsed() {
        val h = Harness()
        val output = h.music(track)
        h.started(output)
        h.now = 10_000
        h.send(PlaybackEvent.MusicPosition(output.attempt, 12_000))
        assertEquals(58_000L, h.project())
        h.now = 15_000
        assertEquals(58_000L, h.project())
        h.send(PlaybackEvent.OfferStory(story))
        assertEquals(58_000L, h.project())
    }

    @Test fun aBackwardActualMusicPositionResetsTheAnchorRatherThanIgnoringEvidence() {
        val h = Harness()
        val output = h.music(track)
        h.started(output)
        h.now = 10_000
        h.send(PlaybackEvent.MusicPosition(output.attempt, 5_000))
        assertEquals(65_000L, h.project())
    }

    @Test fun musicAtEstimatedEndRemainsUnknownUntilNaturalCompletionCommits() {
        val h = Harness()
        val output = h.music(track)
        h.started(output)
        assertTrue(h.state.musicRequiredBeforeAutomaticStory)
        h.now = 60_000
        assertNull(h.project())
        h.send(PlaybackEvent.OutputDone(output.attempt, 60_000))
        assertNull(h.project())
        assertTrue(h.state.musicRequiredBeforeAutomaticStory)
        h.send(PlaybackEvent.PlaybackCommitted(output.attempt, true))
        assertFalse(h.state.musicRequiredBeforeAutomaticStory)
        assertEquals(60_000L, h.project())
    }

    @Test fun completedStoryRequiresMusicEvenWhenNoTrackWasOfferedYet() {
        val one = story.copy(paragraphIds = listOf("p1"))
        val h = Harness()
        val output = h.story(one)
        h.started(output)
        h.now = 9_000
        h.completeWithoutNext(output)
        assertTrue(h.state.musicRequiredBeforeAutomaticStory)
        assertEquals(PlaybackMode.IDLE, h.state.mode)
        assertNull(h.project())
        val music = h.send(PlaybackEvent.OfferMusic(track)).output()
        assertNull(h.project())
        h.started(music)
        assertEquals(69_000L, h.project())
    }

    @Test fun skippedMusicCannotSatisfyTheNaturalCompletionBoundary() {
        val h = Harness()
        val output = h.music(track)
        h.started(output)
        h.send(PlaybackEvent.Next)
        assertNull(h.project())
        h.send(PlaybackEvent.OutputStopped(output.attempt, 1_000))
        h.send(PlaybackEvent.PlaybackCommitted(output.attempt, true))
        assertTrue(h.state.musicRequiredBeforeAutomaticStory)
        assertNull(h.project())
    }

    @Test fun interactionSuppressesProjectionEvenWhileTheCurrentParagraphFinishes() {
        val h = Harness()
        val output = h.story(story)
        h.started(output)
        h.now = 2_000
        h.send(PlaybackEvent.Talk)
        assertEquals(PlaybackMode.TALK_PENDING, h.state.mode)
        assertNull(h.project())
        h.now = 3_000
        h.send(PlaybackEvent.CancelInteraction)
        assertEquals(PlaybackMode.STORY, h.state.mode)
        assertEquals(90_000L, h.project())
    }

    @Test fun stopAndFocusBlockCannotRetainAnOldProjection() {
        listOf<PlaybackEvent>(PlaybackEvent.Stop, PlaybackEvent.Block(PlaybackBlocker.FOCUS_TRANSIENT), PlaybackEvent.Suspend).forEach { event ->
            val h = Harness()
            val output = h.story(story)
            h.started(output)
            assertNotNull(h.project())
            h.send(event)
            assertFalse(h.tracker.awaitingActualStart())
            assertNull(h.project())
            h.tracker.outputStarted(output.attempt, output.reference, h.now)
            assertNull(h.project())
        }
    }

    @Test fun stoppedAndResumedParagraphStartsANewEstimateFromItsActualRestart() {
        val h = Harness()
        val first = h.story(story)
        h.started(first)
        h.now = 5_000
        h.send(PlaybackEvent.Stop)
        h.send(PlaybackEvent.OutputStopped(first.attempt))
        h.send(PlaybackEvent.PlaybackCommitted(first.attempt, true))
        h.now = 20_000
        h.send(PlaybackEvent.Play)
        val restarted = h.resolveResume()
        assertNull(h.project())
        h.started(restarted)
        assertEquals(110_000L, h.project())
    }

    @Test fun unknownOrMalformedDurationsFailClosed() {
        val h = Harness()
        val output = h.story(story)
        h.started(output)
        for (values in listOf(null, emptyList(), listOf(1L), listOf(10_000L, 0, 10_000), listOf(-1L, 10_000, 10_000))) {
            assertNull(h.tracker.nextStoryStartElapsedMs(h.state, h.now, 60_000) { values })
        }
        assertNull(h.tracker.nextStoryStartElapsedMs(h.state, h.now, 60_000) { error("duration unavailable") })
        assertNull(h.project(musicDuration = null))
        assertNull(h.project(musicDuration = 0))
        assertNull(h.project(musicDuration = -1))
    }

    @Test fun unknownCurrentMusicDurationIsNotReplacedWithTheNextTrackDuration() {
        val h = Harness()
        val output = h.music(PreparedMusic("unknown"))
        h.started(output)
        assertNull(h.project())
    }

    @Test fun mismatchedAndDuplicateStartCallbacksCannotMoveTheProjectionAnchor() {
        val h = Harness()
        val output = h.story(story)
        h.tracker.outputStarted(output.attempt.copy(id = "stale"), output.reference, h.now)
        h.tracker.outputStarted(output.attempt, OutputReference.FixedPrompt("wrong"), h.now)
        assertNull(h.project())
        h.started(output)
        h.now = 5_000
        h.tracker.outputStarted(output.attempt, output.reference, h.now)
        assertEquals(90_000L, h.project())
    }

    @Test fun staleTransitionCannotReviveStoppedAttempt() {
        val h = Harness()
        val output = h.story(story)
        h.started(output)
        val old = h.last
        h.now = 1_000
        h.send(PlaybackEvent.Stop)
        h.tracker.onTransition(old, h.now)
        assertNull(h.tracker.nextStoryStartElapsedMs(old.snapshot, h.now, 60_000) { listOf(10_000, 10_000, 10_000) })
        assertNull(h.project())
    }

    @Test fun replayingTheSameTransitionCannotForgetActualStarted() {
        val h = Harness()
        val output = h.story(story)
        val startTransition = h.last
        h.started(output)
        h.tracker.onTransition(startTransition, 5_000)
        h.now = 5_000
        assertEquals(90_000L, h.project())
    }

    @Test fun clockRegressionAndAnotherSessionNeverAuthorizeOldProjection() {
        val h = Harness()
        val output = h.story(story)
        h.now = 2_000
        h.started(output)
        assertNull(h.tracker.nextStoryStartElapsedMs(h.state, 1_000, 60_000) { listOf(10_000, 10_000, 10_000) })
        val foreign = h.state.copy(sessionId = "other", stateRevision = h.state.stateRevision + 100)
        h.tracker.onTransition(PlaybackTransition(foreign, emptyList()), 2_000)
        assertNull(h.tracker.nextStoryStartElapsedMs(foreign, 2_000, 60_000) { listOf(10_000, 10_000, 10_000) })
        assertEquals(92_000L, h.project())
    }

    @Test fun durationOverflowNeverWrapsIntoAPastTimestamp() {
        val h = Harness()
        val output = h.story(story)
        h.started(output)
        assertNull(h.tracker.nextStoryStartElapsedMs(h.state, h.now, 60_000) { listOf(Long.MAX_VALUE, 1, 1) })
        val music = Harness()
        val active = music.music(PreparedMusic("very-long", Long.MAX_VALUE))
        music.now = 1
        music.started(active)
        assertNull(music.project())
    }

    @Test fun aRegressedTransitionInvalidatesTheOldAttemptClockUntilANewActualStart() {
        val h = Harness()
        val output = h.story(story)
        h.now = 2_000
        h.started(output)
        val next = PlaybackTransition(h.state.copy(stateRevision = h.state.stateRevision + 1), emptyList())
        h.tracker.onTransition(next, 1_000)
        h.tracker.onTransition(next, 3_000)
        assertNull(h.tracker.nextStoryStartElapsedMs(next.snapshot, 3_000, 60_000) { listOf(10_000, 10_000, 10_000) })
    }

    private class Harness(checkpoint: StoryCheckpoint? = null, musicCheckpoint: MusicCheckpoint? = null) {
        var now = 0L
        private var ids = 0
        private val core = PlaybackCoordinator("trip", "session", { "id-${++ids}" }, checkpoint, musicCheckpoint)
        val tracker = PlaybackLookahead()
        var last = PlaybackTransition(core.snapshot(), emptyList())
        val state: PlaybackSnapshot get() = last.snapshot
        init { tracker.onTransition(last, now) }

        fun send(event: PlaybackEvent): PlaybackTransition = core.dispatch(event, now).also {
            last = it; tracker.onTransition(it, now)
        }
        fun story(story: PreparedStory): PlaybackEffect.StartOutput {
            send(PlaybackEvent.PlayStory(story))
            return resolveResume()
        }
        fun music(track: PreparedMusic): PlaybackEffect.StartOutput {
            send(PlaybackEvent.PlayMusic(track))
            return resolveResume()
        }
        fun resolveResume(): PlaybackEffect.StartOutput {
            val plan = last.effects.filterIsInstance<PlaybackEffect.PlanResume>().single()
            return send(PlaybackEvent.ResumePlanned(plan.request, plan.candidates.firstOrNull(), plan.musicCandidates.firstOrNull())).output()
        }
        fun started(output: PlaybackEffect.StartOutput) {
            tracker.outputStarted(output.attempt, output.reference, now)
            send(PlaybackEvent.OutputStarted(output.attempt))
        }
        fun complete(output: PlaybackEffect.StartOutput): PlaybackEffect.StartOutput = completeWithoutNext(output).output()
        fun completeWithoutNext(output: PlaybackEffect.StartOutput): PlaybackTransition {
            send(PlaybackEvent.OutputDone(output.attempt))
            return send(PlaybackEvent.PlaybackCommitted(output.attempt, true))
        }
        fun project(musicDuration: Long? = 60_000): Long? = tracker.nextStoryStartElapsedMs(state, now, musicDuration) {
            List(it.paragraphIds.size) { 10_000L }
        }
    }

    private companion object {
        fun PlaybackTransition.output(): PlaybackEffect.StartOutput = effects.filterIsInstance<PlaybackEffect.StartOutput>().single()
    }
}
