package com.petasos.app.playback

import org.junit.Assert.*
import org.junit.Test

class AutomaticStoryCadenceTest {
    private val first = PreparedStory("first", "first-v1", listOf("first-p1"))
    private val next = PreparedStory("next", "next-v1", listOf("next-p1"))
    private val music = PreparedMusic("music", 1_000)

    @Test fun queuedAndLateStoriesWaitForMusicAfterStoryCompletion() {
        val h = Harness()
        val output = h.play(first)
        h.send(PlaybackEvent.OfferStory(next, "before-completion"))
        assertIdle(h.done(output))
        assertIdle(h.send(PlaybackEvent.OfferStory(next, "late-refresh")))
        val track = h.send(PlaybackEvent.OfferMusic(music)).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.MusicTrack("music", 0), track.reference)
        val completion = h.send(PlaybackEvent.OutputDone(track.attempt, 1_000))
        assertTrue(completion.effects.none { it is PlaybackEffect.StartOutput })
        val nextOutput = h.send(PlaybackEvent.PlaybackCommitted(track.attempt, true)).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.StoryParagraph("next", "next-v1", "next-p1"), nextOutput.reference)
        assertEquals("late-refresh", nextOutput.automaticOfferId)
    }

    @Test fun skippingMusicAndRefreshingOfferCannotPretendTrackCompletion() {
        val h = Harness()
        val track = h.resume(h.send(PlaybackEvent.PlayMusic(music))).one<PlaybackEffect.StartOutput>()
        h.send(PlaybackEvent.OfferStory(next, "before-skip"))
        h.send(PlaybackEvent.Next)
        h.send(PlaybackEvent.OutputStopped(track.attempt, 250))
        assertIdle(h.send(PlaybackEvent.PlaybackCommitted(track.attempt, true)))
        assertIdle(h.send(PlaybackEvent.OfferStory(next, "after-skip")))
    }

    @Test fun explicitStorySelectionRemainsAvailableWhileWaitingForMusic() {
        val h = Harness()
        assertIdle(h.done(h.play(first)))
        val replay = h.play(first)
        assertEquals(OutputReference.StoryParagraph("first", "first-v1", "first-p1"), replay.reference)
        assertNull(replay.automaticOfferId)
    }

    @Test fun stopAndPowerRecoveryCannotReleaseQueuedAutomaticStory() {
        val h = Harness()
        h.done(h.play(first))
        h.send(PlaybackEvent.Stop)
        h.send(PlaybackEvent.OfferStory(next))
        h.send(PlaybackEvent.Block(PlaybackBlocker.POWER_AUDIO))
        val recovery = h.send(PlaybackEvent.Unblock(PlaybackBlocker.POWER_AUDIO))
        assertFalse(recovery.snapshot.playIntent)
        assertTrue(recovery.effects.none { it is PlaybackEffect.StartOutput })
        assertIdle(h.resume(h.send(PlaybackEvent.Play)))
    }

    @Test fun constructorRestoredCompletedCheckpointDoesNotBypassMusicBoundaryOnPlay() {
        val h = Harness(StoryCheckpoint(first, first.paragraphIds.size))
        h.send(PlaybackEvent.OfferStory(next))
        assertIdle(h.resume(h.send(PlaybackEvent.Play)))
        assertIdle(h.send(PlaybackEvent.OfferStory(next, "refresh")))
    }

    @Test fun failedMusicCommitCannotStartTheQueuedStory() {
        val h = Harness()
        val track = h.resume(h.send(PlaybackEvent.PlayMusic(music))).one<PlaybackEffect.StartOutput>()
        h.send(PlaybackEvent.OfferStory(next))
        h.send(PlaybackEvent.OutputDone(track.attempt, 1_000))
        val failed = h.send(PlaybackEvent.PlaybackCommitted(track.attempt, false))
        assertTrue(PlaybackBlocker.STORAGE_UNAVAILABLE in failed.snapshot.blockers)
        assertTrue(failed.effects.none { it is PlaybackEffect.StartOutput })
    }

    @Test fun musicCompletionCommittedDuringFocusLossIsRetainedForEmptyResume() {
        val h = Harness()
        val track = h.resume(h.send(PlaybackEvent.PlayMusic(music))).one<PlaybackEffect.StartOutput>()
        h.send(PlaybackEvent.OfferStory(next, "waiting"))
        h.send(PlaybackEvent.OutputDone(track.attempt, 1_000))
        h.send(PlaybackEvent.Block(PlaybackBlocker.FOCUS_TRANSIENT))
        h.send(PlaybackEvent.PlaybackCommitted(track.attempt, true))
        val resume = h.send(PlaybackEvent.Unblock(PlaybackBlocker.FOCUS_TRANSIENT))
        assertTrue(resume.one<PlaybackEffect.PlanResume>().candidates.isEmpty())
        val output = h.resume(resume).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.StoryParagraph("next", "next-v1", "next-p1"), output.reference)
        assertEquals("waiting", output.automaticOfferId)
    }

    @Test fun completedMusicBeforeCancelledTalkStillPermitsWaitingStoryAfterResume() {
        val h = Harness()
        val track = h.resume(h.send(PlaybackEvent.PlayMusic(music))).one<PlaybackEffect.StartOutput>()
        h.send(PlaybackEvent.OfferStory(next))
        h.send(PlaybackEvent.OutputDone(track.attempt, 1_000))
        h.send(PlaybackEvent.Talk)
        h.send(PlaybackEvent.CancelInteraction)
        val resume = h.send(PlaybackEvent.PlaybackCommitted(track.attempt, true))
        val output = h.resume(resume).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.StoryParagraph("next", "next-v1", "next-p1"), output.reference)
    }

    @Test fun waitWithAnExistingResumeCandidateIsNotOverriddenByLateAutomaticOffers() {
        val h = Harness()
        val plan = h.send(PlaybackEvent.PlayStory(first)).one<PlaybackEffect.PlanResume>()
        h.send(PlaybackEvent.OfferStory(next))
        assertIdle(h.send(PlaybackEvent.ResumePlanned(plan.request, null)))
        assertIdle(h.send(PlaybackEvent.OfferStory(next, "late")))
        assertIdle(h.send(PlaybackEvent.OfferMusic(music)))
    }

    private fun assertIdle(transition: PlaybackTransition) {
        assertEquals(PlaybackMode.IDLE, transition.snapshot.mode)
        assertTrue(transition.effects.none { it is PlaybackEffect.StartOutput })
    }

    private class Harness(checkpoint: StoryCheckpoint? = null) {
        private var time = 0L
        private var id = 0
        private val core = PlaybackCoordinator("trip", "session", { "operation-${++id}" }, restoredCheckpoint = checkpoint)
        fun send(event: PlaybackEvent) = core.dispatch(event, ++time)
        fun resume(transition: PlaybackTransition): PlaybackTransition {
            val plan = transition.one<PlaybackEffect.PlanResume>()
            return send(PlaybackEvent.ResumePlanned(plan.request, plan.candidates.firstOrNull(), plan.musicCandidates.firstOrNull()))
        }
        fun play(story: PreparedStory) = resume(send(PlaybackEvent.PlayStory(story))).one<PlaybackEffect.StartOutput>()
        fun done(output: PlaybackEffect.StartOutput): PlaybackTransition {
            send(PlaybackEvent.OutputDone(output.attempt)).one<PlaybackEffect.CommitPlayback>()
            return send(PlaybackEvent.PlaybackCommitted(output.attempt, true))
        }
    }

    private companion object {
        inline fun <reified T : PlaybackEffect> PlaybackTransition.one() = effects.filterIsInstance<T>().single()
    }
}
