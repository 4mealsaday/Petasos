package com.petasos.app.playback

import org.junit.Assert.*
import org.junit.Test

/** Recovery carries a listening boundary, never permission to start playback by itself. */
class RestoredMusicGapTest {
    private val story = PreparedStory("next-story", "next-version", listOf("next-paragraph"))
    private val otherStory = PreparedStory("other-story", "other-version", listOf("other-paragraph"))
    private val music = PreparedMusic("music", 1_000)

    @Test fun restoredIdleBoundaryHasNoCheckpointAndOffersCannotAutoplay() {
        val h = Harness(required = true)
        assertEquals(PlaybackMode.IDLE, h.core.snapshot().mode)
        assertFalse(h.core.snapshot().playIntent)
        assertNull(h.core.snapshot().checkpoint)
        assertNull(h.core.snapshot().musicCheckpoint)
        assertTrue(h.core.snapshot().musicRequiredBeforeAutomaticStory)
        assertSilent(h.send(PlaybackEvent.OfferStory(story)))
        assertSilent(h.send(PlaybackEvent.OfferMusic(music)))
        assertFalse(h.core.snapshot().playIntent)
    }

    @Test fun explicitPlayAndLateStoryWaitForMusicDoneAndSuccessfulCommit() {
        val h = Harness(required = true)
        h.send(PlaybackEvent.OfferStory(story))
        val waiting = h.resume(h.send(PlaybackEvent.Play))
        assertEquals(PlaybackMode.IDLE, waiting.snapshot.mode)
        assertSilent(waiting)
        assertSilent(h.send(PlaybackEvent.OfferStory(otherStory)))
        val track = h.send(PlaybackEvent.OfferMusic(music)).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.MusicTrack("music"), track.reference)
        h.send(PlaybackEvent.OutputStarted(track.attempt))
        val ended = h.send(PlaybackEvent.OutputDone(track.attempt, 1_000))
        assertEquals(AttemptOutcome.COMPLETED, ended.one<PlaybackEffect.CommitPlayback>().outcome)
        assertTrue(ended.snapshot.musicRequiredBeforeAutomaticStory)
        assertSilent(ended)
        assertSilent(h.send(PlaybackEvent.PlaybackCommitted(OperationToken("other-session", 0, "stale"), true)))
        val next = h.send(PlaybackEvent.PlaybackCommitted(track.attempt, true))
        assertFalse(next.snapshot.musicRequiredBeforeAutomaticStory)
        assertEquals(OutputReference.StoryParagraph("other-story", "other-version", "other-paragraph"),
            next.one<PlaybackEffect.StartOutput>().reference)
    }

    @Test fun failedMusicCommitCannotSatisfyRecoveredBoundary() {
        val h = Harness(required = true)
        h.send(PlaybackEvent.OfferStory(story))
        h.send(PlaybackEvent.OfferMusic(music))
        val track = h.resume(h.send(PlaybackEvent.Play)).one<PlaybackEffect.StartOutput>()
        h.send(PlaybackEvent.OutputStarted(track.attempt))
        h.send(PlaybackEvent.OutputDone(track.attempt, 1_000))
        val failed = h.send(PlaybackEvent.PlaybackCommitted(track.attempt, false))
        assertTrue(failed.snapshot.musicRequiredBeforeAutomaticStory)
        assertTrue(PlaybackBlocker.STORAGE_UNAVAILABLE in failed.snapshot.blockers)
        assertSilent(failed)
        assertSilent(h.send(PlaybackEvent.OfferStory(otherStory)))
    }

    @Test fun skippedTrackAndLateOfferKeepBoundaryUntilFollowingTrackCompletes() {
        val h = Harness(required = true)
        h.send(PlaybackEvent.OfferStory(story))
        h.send(PlaybackEvent.OfferMusic(music))
        val first = h.resume(h.send(PlaybackEvent.Play)).one<PlaybackEffect.StartOutput>()
        h.send(PlaybackEvent.OutputStarted(first.attempt))
        h.send(PlaybackEvent.Next)
        assertEquals("output_stopping", h.send(PlaybackEvent.OutputDone(first.attempt, 1_000)).ignoredReason)
        val interrupted = h.send(PlaybackEvent.OutputStopped(first.attempt, 300))
        assertEquals(AttemptOutcome.INTERRUPTED, interrupted.one<PlaybackEffect.CommitPlayback>().outcome)
        val skipped = h.send(PlaybackEvent.PlaybackCommitted(first.attempt, true))
        assertEquals(PlaybackMode.IDLE, skipped.snapshot.mode)
        assertTrue(skipped.snapshot.musicRequiredBeforeAutomaticStory)
        assertSilent(skipped)
        assertSilent(h.send(PlaybackEvent.OfferStory(otherStory)))
        val nextMusic = PreparedMusic("following-music", 2_000)
        val next = h.send(PlaybackEvent.OfferMusic(nextMusic)).one<PlaybackEffect.StartOutput>()
        h.send(PlaybackEvent.OutputStarted(next.attempt))
        h.send(PlaybackEvent.OutputDone(next.attempt, 2_000))
        val completed = h.send(PlaybackEvent.PlaybackCommitted(next.attempt, true))
        assertEquals(OutputReference.StoryParagraph("other-story", "other-version", "other-paragraph"),
            completed.one<PlaybackEffect.StartOutput>().reference)
    }

    @Test fun blockAndManualStopDoNotClearBoundaryOrRestorePlayIntent() {
        val h = Harness(required = true)
        h.send(PlaybackEvent.OfferStory(story))
        assertSilent(h.resume(h.send(PlaybackEvent.Play)))
        h.send(PlaybackEvent.Block(PlaybackBlocker.POWER_AUDIO))
        assertSilent(h.send(PlaybackEvent.OfferMusic(music)))
        h.send(PlaybackEvent.Stop)
        val unblocked = h.send(PlaybackEvent.Unblock(PlaybackBlocker.POWER_AUDIO))
        assertFalse(unblocked.snapshot.playIntent)
        assertTrue(unblocked.snapshot.musicRequiredBeforeAutomaticStory)
        assertSilent(unblocked)
        assertSilent(h.send(PlaybackEvent.OfferStory(otherStory)))
        val explicit = h.resume(h.send(PlaybackEvent.Play)).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.MusicTrack("music"), explicit.reference)
    }

    @Test fun explicitStorySelectionRemainsAllowedWithoutForgivingMusicBoundary() {
        val h = Harness(required = true)
        val selected = h.resume(h.send(PlaybackEvent.PlayStory(story)))
        assertEquals(OutputReference.StoryParagraph("next-story", "next-version", "next-paragraph"),
            selected.one<PlaybackEffect.StartOutput>().reference)
        assertNull(selected.one<PlaybackEffect.StartOutput>().automaticOfferId)
        assertTrue(selected.snapshot.musicRequiredBeforeAutomaticStory)
    }

    @Test fun defaultNewSessionStillAllowsAnAutomaticStoryWithoutInventedMusicDebt() {
        val h = Harness()
        assertFalse(h.core.snapshot().musicRequiredBeforeAutomaticStory)
        h.send(PlaybackEvent.OfferStory(story))
        h.send(PlaybackEvent.OfferMusic(music))
        assertEquals(OutputReference.StoryParagraph("next-story", "next-version", "next-paragraph"),
            h.resume(h.send(PlaybackEvent.Play)).one<PlaybackEffect.StartOutput>().reference)
    }

    @Test fun explicitFalseCannotEraseExistingCompletedStoryOrMusicCheckpointBoundary() {
        val completed = PlaybackCoordinator("trip", "session", restoredCheckpoint = StoryCheckpoint(story, 1),
            restoredMusicRequiredBeforeAutomaticStory = false)
        val interruptedMusic = PlaybackCoordinator("trip", "session", restoredMusicCheckpoint = MusicCheckpoint(music, 300),
            restoredMusicRequiredBeforeAutomaticStory = false)
        assertTrue(completed.snapshot().musicRequiredBeforeAutomaticStory)
        assertTrue(interruptedMusic.snapshot().musicRequiredBeforeAutomaticStory)
        assertFalse(completed.snapshot().playIntent)
        assertFalse(interruptedMusic.snapshot().playIntent)
    }

    private fun assertSilent(transition: PlaybackTransition) {
        assertTrue(transition.effects.none { it is PlaybackEffect.StartOutput || it is PlaybackEffect.RequestInput })
    }

    private class Harness(required: Boolean? = null) {
        private var now = 0L
        private var id = 0
        val core = if (required == null) PlaybackCoordinator("trip", "session", { "restore-${++id}" })
            else PlaybackCoordinator("trip", "session", { "restore-${++id}" }, restoredMusicRequiredBeforeAutomaticStory = required)
        fun send(event: PlaybackEvent): PlaybackTransition = core.dispatch(event, ++now)
        fun resume(transition: PlaybackTransition): PlaybackTransition {
            val plan = transition.one<PlaybackEffect.PlanResume>()
            return send(PlaybackEvent.ResumePlanned(plan.request, plan.candidates.firstOrNull(), plan.musicCandidates.firstOrNull()))
        }
    }

    private companion object {
        inline fun <reified T : PlaybackEffect> PlaybackTransition.one(): T = effects.filterIsInstance<T>().single()
    }
}
