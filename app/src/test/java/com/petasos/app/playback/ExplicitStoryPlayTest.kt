package com.petasos.app.playback

import org.junit.Assert.*
import org.junit.Test

class ExplicitStoryPlayTest {
    private val story = PreparedStory("story", "version", listOf("first", "second"))

    @Test fun completedSelectedStoryStartsNewFirstParagraphAfterExplicitPlay() {
        val h = Harness()
        val first = h.explicitPlay(story.versionId)
        val second = h.complete(first).one<PlaybackEffect.StartOutput>()
        val finished = h.complete(second)
        assertEquals(PlaybackMode.IDLE, finished.snapshot.mode)
        assertFalse(checkNotNull(finished.snapshot.checkpoint).hasNext)
        assertTrue(finished.effects.none { it is PlaybackEffect.StartOutput })

        val replay = h.explicitPlay(story.versionId)
        assertEquals(OutputReference.StoryParagraph(story.storyId, story.versionId, "first"), replay.reference)
        assertNotEquals(first.attempt, replay.attempt)
        assertNull(replay.automaticOfferId)
    }

    @Test fun unfinishedSelectedStoryResumesSavedParagraphAfterExplicitPlay() {
        val h = Harness(StoryCheckpoint(story, 1))
        assertFalse(h.core.snapshot().playIntent)
        assertNull(explicitStoryVersionToPrepare(story.versionId, h.core.snapshot().checkpoint))
        val resumed = h.explicitPlay(story.versionId, selectionPending = true)
        assertEquals(OutputReference.StoryParagraph(story.storyId, story.versionId, "second"), resumed.reference)
    }

    @Test fun missingSelectionDoesNotAuthorizeReplayAndNewSelectionRequiresPreparation() {
        assertNull(explicitStoryVersionToPrepare(null, StoryCheckpoint(story, 2)))
        assertEquals("other-version", explicitStoryVersionToPrepare("other-version", StoryCheckpoint(story, 1)))
        assertEquals(story.versionId, explicitStoryVersionToPrepare(story.versionId, null))
    }

    @Test fun ordinaryPlayResumesStoppedMusicDespiteRetainedCompletedStorySelection() {
        val h = stoppedMusicAfterCompletedStory()
        val resumed = h.explicitPlay(story.versionId)
        assertEquals(OutputReference.MusicTrack("track", 1_234), resumed.reference)
        assertFalse(checkNotNull(h.core.snapshot().checkpoint).hasNext)
    }

    @Test fun freshSelectionOfSameOldStoryReplacesStoppedMusicWithFirstParagraph() {
        val h = stoppedMusicAfterCompletedStory()
        val selectedStory = h.explicitPlay(story.versionId, selectionPending = true)
        assertEquals(OutputReference.StoryParagraph(story.storyId, story.versionId, "first"), selectedStory.reference)
        assertNull(h.core.snapshot().musicCheckpoint)
    }

    private fun stoppedMusicAfterCompletedStory(): Harness {
        val h = Harness(StoryCheckpoint(story, 2), MusicCheckpoint(PreparedMusic("track"), 0))
        val music = h.explicitPlay(story.versionId)
        assertEquals(OutputReference.MusicTrack("track", 0), music.reference)
        h.send(PlaybackEvent.Stop)
        val commit = h.send(PlaybackEvent.OutputStopped(music.attempt, 1_234)).one<PlaybackEffect.CommitPlayback>()
        assertEquals(AttemptOutcome.INTERRUPTED, commit.outcome)
        val stopped = h.send(PlaybackEvent.PlaybackCommitted(music.attempt, true))
        assertEquals(PlaybackMode.PAUSED, stopped.snapshot.mode)
        assertFalse(stopped.snapshot.playIntent)
        return h
    }

    private inner class Harness(checkpoint: StoryCheckpoint? = null, music: MusicCheckpoint? = null) {
        private var nextId = 0
        private var now = 0L
        val core = PlaybackCoordinator("trip", "session", { "operation-${++nextId}" },
            restoredCheckpoint = checkpoint, restoredMusicCheckpoint = music)
        fun send(event: PlaybackEvent) = core.dispatch(event, ++now)
        fun explicitPlay(selected: String?, selectionPending: Boolean = false): PlaybackEffect.StartOutput {
            val state = core.snapshot()
            val version = explicitStoryVersionToPrepare(selected, state.checkpoint, state.musicCheckpoint, selectionPending)
            val event = if (version == null) PlaybackEvent.Play else {
                check(version == story.versionId)
                PlaybackEvent.PlayStory(story)
            }
            val plan = send(event).one<PlaybackEffect.PlanResume>()
            return send(PlaybackEvent.ResumePlanned(plan.request, plan.candidates.singleOrNull(), plan.musicCandidates.singleOrNull())).one()
        }
        fun complete(output: PlaybackEffect.StartOutput): PlaybackTransition {
            val commit = send(PlaybackEvent.OutputDone(output.attempt)).one<PlaybackEffect.CommitPlayback>()
            assertEquals(AttemptOutcome.COMPLETED, commit.outcome)
            return send(PlaybackEvent.PlaybackCommitted(output.attempt, true))
        }
    }

    private companion object {
        inline fun <reified T : PlaybackEffect> PlaybackTransition.one(): T = effects.filterIsInstance<T>().single()
    }
}
