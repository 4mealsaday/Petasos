package com.petasos.app.playback

import org.junit.Assert.*
import org.junit.Test

class AutomaticMusicCoordinatorTest {
    private val story = PreparedStory("story", "v1", listOf("p1"))
    private val nextStory = PreparedStory("next", "v2", listOf("p2"))
    private val music = PreparedMusic("music", 1_000)
    private val nextMusic = PreparedMusic("next-music", 2_000)

    @Test fun musicOffersAloneCannotStartOnOpenOrAfterManualStop() {
        val h = Harness()
        assertSilent(h.send(PlaybackEvent.OfferMusic(music)))
        h.send(PlaybackEvent.Stop)
        assertSilent(h.send(PlaybackEvent.OfferMusic(nextMusic)))
        assertFalse(h.core.snapshot().playIntent)
        assertEquals(OutputReference.MusicTrack("next-music"), h.resume(h.send(PlaybackEvent.Play)).reference)
    }

    @Test fun withdrawnMusicCannotStartAtStoryEndAndCannotWithdrawDifferentOrCurrentTrack() {
        val h = Harness()
        val playing = h.resume(h.send(PlaybackEvent.PlayStory(story)))
        h.send(PlaybackEvent.OfferMusic(music))
        assertEquals("offer_not_found", h.send(PlaybackEvent.ClearOfferedMusic("other")).ignoredReason)
        h.send(PlaybackEvent.ClearOfferedMusic(music.assetId))
        assertSilent(h.done(playing))
        val track = h.send(PlaybackEvent.OfferMusic(music)).one<PlaybackEffect.StartOutput>()
        assertEquals("offer_not_found", h.send(PlaybackEvent.ClearOfferedMusic(music.assetId)).ignoredReason)
        assertEquals(PlaybackMode.MUSIC, h.core.snapshot().mode)
        assertEquals(AdapterPhase.ACTIVE, h.core.snapshot().outputPhase)
        assertEquals(OutputReference.MusicTrack("music"), track.reference)
    }

    @Test fun storyMusicStoryAndNextTrackRespectNormalCompletionCommits() {
        val h = Harness()
        val first = h.resume(h.send(PlaybackEvent.PlayStory(story)))
        h.send(PlaybackEvent.OfferMusic(music))
        h.send(PlaybackEvent.OfferStory(nextStory))
        val track = h.done(first).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.MusicTrack("music"), track.reference)
        h.send(PlaybackEvent.OfferMusic(nextMusic))
        val completed = h.send(PlaybackEvent.OutputDone(track.attempt, 1_000))
        assertSilent(completed)
        val next = h.send(PlaybackEvent.PlaybackCommitted(track.attempt, true)).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.StoryParagraph("next", "v2", "p2"), next.reference)
        assertEquals(OutputReference.MusicTrack("next-music"), h.done(next).one<PlaybackEffect.StartOutput>().reference)
    }

    @Test fun nextMusicIsInterruptedAndOnlyFollowingTracksNaturalCompletionUnlocksStory() {
        val h = Harness()
        val first = h.resume(h.send(PlaybackEvent.PlayMusic(music)))
        h.send(PlaybackEvent.OfferMusic(nextMusic))
        h.send(PlaybackEvent.OfferStory(nextStory))
        h.send(PlaybackEvent.Next)
        assertEquals(AttemptOutcome.INTERRUPTED,
            h.send(PlaybackEvent.OutputStopped(first.attempt, 300)).one<PlaybackEffect.CommitPlayback>().outcome)
        val next = h.send(PlaybackEvent.PlaybackCommitted(first.attempt, true)).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.MusicTrack("next-music"), next.reference)
        assertEquals(OutputReference.StoryParagraph("next", "v2", "p2"),
            h.done(next, 2_000).one<PlaybackEffect.StartOutput>().reference)
    }

    @Test fun singleTrackCanBeSelectedAgainAsNewAttemptOnlyAfterNaturalCompletion() {
        val h = Harness()
        val first = h.resume(h.send(PlaybackEvent.PlayMusic(music)))
        h.send(PlaybackEvent.OfferMusic(music))
        val next = h.done(first, 1_000).one<PlaybackEffect.StartOutput>()
        assertNotEquals(first.attempt, next.attempt)
        assertEquals(OutputReference.MusicTrack("music", 0), next.reference)
    }

    @Test fun musicFailureClearsIntentAndLateOffersOrServiceRecoveryCannotRestartIt() {
        val h = Harness()
        val first = h.resume(h.send(PlaybackEvent.PlayMusic(music)))
        h.send(PlaybackEvent.OfferMusic(nextMusic))
        val failed = h.send(PlaybackEvent.OutputFailed(first.attempt))
        assertFalse(failed.snapshot.playIntent)
        assertEquals(PlaybackMode.PAUSED, failed.snapshot.mode)
        assertFalse(failed.one<PlaybackEffect.SaveCheckpoint>().playIntent)
        h.send(PlaybackEvent.OutputStopped(first.attempt, 300))
        assertSilent(h.send(PlaybackEvent.PlaybackCommitted(first.attempt, true)))
        assertSilent(h.send(PlaybackEvent.OfferMusic(music)))
        assertSilent(h.send(PlaybackEvent.Unblock(PlaybackBlocker.SERVICE_UNAVAILABLE)))
        assertFalse(h.core.snapshot().playIntent)
        val retry = h.resume(h.send(PlaybackEvent.Play))
        assertEquals(OutputReference.MusicTrack("music", 300), retry.reference)
        assertNotEquals(first.attempt, retry.attempt)
    }

    @Test fun unregisteredMusicClearsItsCheckpointAndWaitsForExplicitPlayAfterNewOffer() {
        val h = Harness()
        val first = h.resume(h.send(PlaybackEvent.PlayMusic(music)))
        h.send(PlaybackEvent.OfferMusic(nextMusic))
        val rejected = h.send(PlaybackEvent.OutputNotRegistered(first.attempt))
        assertFalse(rejected.snapshot.playIntent)
        assertNull(rejected.snapshot.musicCheckpoint)
        assertEquals(PlaybackMode.PAUSED, rejected.snapshot.mode)
        assertSilent(rejected)
        assertSilent(h.send(PlaybackEvent.OfferMusic(music)))
        assertSilent(h.send(PlaybackEvent.OfferStory(nextStory)))
        // An attempted music start still requires a real music completion before another automatic story.
        val retry = h.resume(h.send(PlaybackEvent.Play))
        assertEquals(OutputReference.MusicTrack("music"), retry.reference)
    }

    @Test fun musicFailureBeforeSdkStartRejectsLateStartedAndNeverFabricatesCompletion() {
        val h = Harness()
        val first = h.resume(h.send(PlaybackEvent.PlayMusic(music)))
        h.send(PlaybackEvent.OutputFailed(first.attempt))
        assertEquals("stale_output_start", h.send(PlaybackEvent.OutputStarted(first.attempt)).ignoredReason)
        assertEquals("output_stopping", h.send(PlaybackEvent.OutputDone(first.attempt, 1_000)).ignoredReason)
        val stopped = h.send(PlaybackEvent.OutputStopped(first.attempt, 0)).one<PlaybackEffect.CommitPlayback>()
        assertEquals(AttemptOutcome.INTERRUPTED, stopped.outcome)
        assertEquals(0L, stopped.musicCheckpoint!!.positionMs)
        assertSilent(h.send(PlaybackEvent.PlaybackCommitted(first.attempt, true)))
        assertFalse(h.core.snapshot().playIntent)
    }

    @Test fun storyFailureBehaviorIsUnchangedByMusicSpecificHold() {
        val h = Harness()
        val first = h.resume(h.send(PlaybackEvent.PlayStory(story)))
        val failed = h.send(PlaybackEvent.OutputFailed(first.attempt))
        assertTrue(failed.snapshot.playIntent)
        assertTrue(PlaybackBlocker.SERVICE_UNAVAILABLE in failed.snapshot.blockers)
    }

    private fun assertSilent(transition: PlaybackTransition) {
        assertTrue(transition.effects.none { it is PlaybackEffect.StartOutput })
    }

    private class Harness {
        private var now = 0L
        private var id = 0
        val core = PlaybackCoordinator("trip", "session", { "operation-${++id}" })
        fun send(event: PlaybackEvent): PlaybackTransition = core.dispatch(event, ++now)
        fun resume(transition: PlaybackTransition): PlaybackEffect.StartOutput {
            val plan = transition.one<PlaybackEffect.PlanResume>()
            return send(PlaybackEvent.ResumePlanned(plan.request, plan.candidates.firstOrNull(), plan.musicCandidates.firstOrNull())).one()
        }
        fun done(output: PlaybackEffect.StartOutput, position: Long? = null): PlaybackTransition {
            send(PlaybackEvent.OutputDone(output.attempt, position))
            return send(PlaybackEvent.PlaybackCommitted(output.attempt, true))
        }
    }

    private companion object {
        inline fun <reified T : PlaybackEffect> PlaybackTransition.one(): T = effects.filterIsInstance<T>().single()
    }
}
