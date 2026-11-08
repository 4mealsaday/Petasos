package com.petasos.app.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackMusicCoordinatorTest {
    private val track = PreparedMusic("song-1", 20_000)
    private val story = PreparedStory("story", "version", listOf("p1", "p2"))

    private class Harness(restoredMusic: MusicCheckpoint? = null) {
        private var id = 0
        var now = 0L
        val core = PlaybackCoordinator("trip", "session", { "op-${++id}" }, restoredMusicCheckpoint = restoredMusic)
        fun send(event: PlaybackEvent): PlaybackTransition = core.dispatch(event, ++now)
        fun plan(effect: PlaybackEffect.PlanResume): PlaybackTransition = send(PlaybackEvent.ResumePlanned(effect.request,
            effect.candidates.firstOrNull(), effect.musicCandidates.firstOrNull()))
        fun music(track: PreparedMusic): PlaybackEffect.StartOutput = plan(send(PlaybackEvent.PlayMusic(track)).one()).one()
        fun done(output: PlaybackEffect.StartOutput, position: Long? = null): PlaybackTransition {
            send(PlaybackEvent.OutputDone(output.attempt, position)).one<PlaybackEffect.CommitPlayback>()
            return send(PlaybackEvent.PlaybackCommitted(output.attempt, true))
        }
    }

    @Test fun musicTalkWaitsForPhysicalStopAndPositionCommitBeforePrompt() {
        val h = Harness()
        val music = h.music(track)
        h.send(PlaybackEvent.MusicPosition(music.attempt, 3000))
        val talk = h.send(PlaybackEvent.Talk)
        assertEquals(AdapterPhase.STOPPING, talk.snapshot.outputPhase)
        assertEquals(PlaybackMode.PROMPT, talk.snapshot.mode)
        assertEquals(listOf(PlaybackEffect.StopOutput(music.attempt)), talk.effects.filterIsInstance<PlaybackEffect.StopOutput>())
        assertTrue(talk.effects.none { it is PlaybackEffect.CommitPlayback || it is PlaybackEffect.StartOutput })
        val stopped = h.send(PlaybackEvent.OutputStopped(music.attempt, 3125))
        val commit = stopped.one<PlaybackEffect.CommitPlayback>()
        assertEquals(AttemptOutcome.INTERRUPTED, commit.outcome)
        assertEquals(3125L, commit.outputPositionMs)
        assertEquals(MusicCheckpoint(track, 3125), commit.musicCheckpoint)
        assertTrue(stopped.effects.none { it is PlaybackEffect.StartOutput })
        val prompt = h.send(PlaybackEvent.PlaybackCommitted(music.attempt, true)).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.FixedPrompt("talk-prompt"), prompt.reference)
    }

    @Test fun completedDialogueResumesOriginalMusicAtConfirmedStoppedPosition() {
        val h = Harness()
        val music = h.music(track)
        h.send(PlaybackEvent.Talk)
        h.send(PlaybackEvent.OutputStopped(music.attempt, 4200))
        val prompt = h.send(PlaybackEvent.PlaybackCommitted(music.attempt, true)).one<PlaybackEffect.StartOutput>()
        val input = h.done(prompt).one<PlaybackEffect.RequestInput>()
        h.send(PlaybackEvent.InputReady(input.input))
        val utterance = h.send(PlaybackEvent.InputFinal(input.input, PayloadHandle("raw"))).one<PlaybackEffect.PersistUtterance>()
        h.send(PlaybackEvent.UtteranceCommitted(utterance.commit, PayloadHandle("stored")))
        val generation = h.send(PlaybackEvent.InputStoppedConfirmed(input.input)).one<PlaybackEffect.GenerateAnswer>()
        val answer = ValidatedAnswer(PayloadHandle("answer"), listOf("a1"))
        val stored = h.send(PlaybackEvent.AnswerReceived(generation.request, answer)).one<PlaybackEffect.PersistAnswer>()
        val speech = h.send(PlaybackEvent.AnswerCommitted(stored.commit, answer)).one<PlaybackEffect.StartOutput>()
        val plan = h.done(speech).one<PlaybackEffect.PlanResume>()
        assertTrue(plan.afterInteraction)
        assertTrue(plan.candidates.isEmpty())
        assertEquals(1, plan.musicCandidates.size)
        assertEquals(listOf(MusicCheckpoint(track, 4200)), plan.musicCandidates)
        assertTrue(plan.candidates.isEmpty())
        val resumed = h.plan(plan).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.MusicTrack(track.assetId, 4200), resumed.reference)
        assertNotEquals(music.attempt, resumed.attempt)
        assertEquals("stale_output", h.send(PlaybackEvent.OutputDone(music.attempt, 20_000)).ignoredReason)
    }

    @Test fun automaticStoryWaitsForNaturalEndAndCommittedMusicCompletion() {
        val h = Harness()
        val music = h.music(track)
        val offered = h.send(PlaybackEvent.OfferStory(story))
        assertTrue(offered.effects.isEmpty())
        assertEquals(PlaybackMode.MUSIC, offered.snapshot.mode)
        val completed = h.send(PlaybackEvent.OutputDone(music.attempt, 20_000))
        assertEquals(AttemptOutcome.COMPLETED, completed.one<PlaybackEffect.CommitPlayback>().outcome)
        assertTrue(completed.effects.none { it is PlaybackEffect.StartOutput })
        val next = h.send(PlaybackEvent.PlaybackCommitted(music.attempt, true)).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.StoryParagraph("story", "version", "p1"), next.reference)
        assertNull(h.core.snapshot().musicCheckpoint)
    }

    @Test fun nextMusicIsInterruptionAndCannotPretendNaturalEndToStartAutomaticStory() {
        val h = Harness()
        val music = h.music(track)
        h.send(PlaybackEvent.OfferStory(story))
        val next = h.send(PlaybackEvent.Next)
        assertTrue(next.effects.none { it is PlaybackEffect.CommitPlayback || it is PlaybackEffect.StartOutput })
        assertEquals("output_stopping", h.send(PlaybackEvent.OutputDone(music.attempt, 20_000)).ignoredReason)
        assertEquals(AttemptOutcome.INTERRUPTED, h.send(PlaybackEvent.OutputStopped(music.attempt, 500)).one<PlaybackEffect.CommitPlayback>().outcome)
        val settled = h.send(PlaybackEvent.PlaybackCommitted(music.attempt, true))
        assertEquals(PlaybackMode.IDLE, settled.snapshot.mode)
        assertTrue(settled.effects.none { it is PlaybackEffect.StartOutput })
        assertNull(settled.snapshot.musicCheckpoint)
        assertNull(settled.one<PlaybackEffect.SaveCheckpoint>().musicCheckpoint)
    }

    @Test fun nextMusicCanStartOfferedNextTrackWhileStoryRemainsDeferred() {
        val h = Harness()
        val first = h.music(track)
        val secondTrack = PreparedMusic("song-2", 5000)
        h.send(PlaybackEvent.OfferStory(story))
        h.send(PlaybackEvent.OfferMusic(secondTrack))
        h.send(PlaybackEvent.Next)
        h.send(PlaybackEvent.OutputStopped(first.attempt, 500))
        val second = h.send(PlaybackEvent.PlaybackCommitted(first.attempt, true)).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.MusicTrack("song-2", 0), second.reference)
        assertEquals(OutputReference.StoryParagraph("story", "version", "p1"),
            h.done(second, 5000).one<PlaybackEffect.StartOutput>().reference)
    }

    @Test fun previousMusicStartsNewAttemptFromZeroAfterStopAndCommit() {
        val h = Harness()
        val first = h.music(track)
        h.send(PlaybackEvent.MusicPosition(first.attempt, 8000))
        h.send(PlaybackEvent.Previous)
        h.send(PlaybackEvent.OutputStopped(first.attempt, 8100))
        val committed = h.send(PlaybackEvent.PlaybackCommitted(first.attempt, true))
        assertEquals(0L, committed.one<PlaybackEffect.SaveCheckpoint>().musicCheckpoint!!.positionMs)
        val plan = committed.one<PlaybackEffect.PlanResume>()
        assertEquals(listOf(MusicCheckpoint(track, 0)), plan.musicCandidates)
        val restarted = h.plan(plan).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.MusicTrack(track.assetId, 0), restarted.reference)
        assertNotEquals(first.attempt, restarted.attempt)
    }

    @Test fun previousStoryRestartsFirstParagraphWithNewAttemptAfterPhysicalStop() {
        val h = Harness()
        val plan = h.send(PlaybackEvent.PlayStory(story)).one<PlaybackEffect.PlanResume>()
        val first = h.plan(plan).one<PlaybackEffect.StartOutput>()
        val second = h.done(first).one<PlaybackEffect.StartOutput>()
        val previous = h.send(PlaybackEvent.Previous)
        assertEquals(AttemptOutcome.INTERRUPTED, previous.one<PlaybackEffect.CommitPlayback>().outcome)
        val committed = h.send(PlaybackEvent.PlaybackCommitted(second.attempt, true))
        assertTrue(committed.effects.none { it is PlaybackEffect.StartOutput || it is PlaybackEffect.PlanResume })
        val resume = h.send(PlaybackEvent.OutputStopped(second.attempt)).one<PlaybackEffect.PlanResume>()
        val restarted = h.plan(resume).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.StoryParagraph("story", "version", "p1"), restarted.reference)
        assertNotEquals(first.attempt, restarted.attempt)
    }

    @Test fun musicCompletionRequiresPositionAndStopNeverCompletesTrack() {
        val h = Harness()
        val music = h.music(track)
        assertEquals("music_position_required", h.send(PlaybackEvent.OutputDone(music.attempt)).ignoredReason)
        h.send(PlaybackEvent.Stop)
        assertEquals("music_position_required", h.send(PlaybackEvent.OutputStopped(music.attempt)).ignoredReason)
        val interrupted = h.send(PlaybackEvent.OutputStopped(music.attempt, 77)).one<PlaybackEffect.CommitPlayback>()
        assertEquals(AttemptOutcome.INTERRUPTED, interrupted.outcome)
        assertEquals(77L, interrupted.musicCheckpoint!!.positionMs)
    }

    @Test fun manualStopWinsOverPreviousAndPreservesActualStopPosition() {
        val h = Harness()
        val music = h.music(track)
        h.send(PlaybackEvent.Previous)
        h.send(PlaybackEvent.Stop)
        h.send(PlaybackEvent.OutputStopped(music.attempt, 8123))
        val result = h.send(PlaybackEvent.PlaybackCommitted(music.attempt, true))
        assertEquals(PlaybackMode.PAUSED, result.snapshot.mode)
        assertFalse(result.snapshot.playIntent)
        assertEquals(8123L, result.snapshot.musicCheckpoint!!.positionMs)
        assertTrue(result.effects.none { it is PlaybackEffect.StartOutput || it is PlaybackEffect.PlanResume })
    }

    @Test fun restoringMusicIsSilentUntilExplicitPlayAndPlannerTimeoutUsesFixedPosition() {
        val h = Harness(MusicCheckpoint(track, 999))
        assertEquals(PlaybackMode.IDLE, h.core.snapshot().mode)
        assertFalse(h.core.snapshot().playIntent)
        val play = h.send(PlaybackEvent.Play)
        val timer = play.effects.filterIsInstance<PlaybackEffect.ScheduleTimer>().single().timer
        h.now = timer.dueAtMs
        val fallback = h.send(PlaybackEvent.TimerExpired(timer)).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.MusicTrack(track.assetId, 999), fallback.reference)
    }

    @Test fun resumePlannerCannotReplaceOfferedMusicOrSelectTwoTargets() {
        val h = Harness(MusicCheckpoint(track, 999))
        val plan = h.send(PlaybackEvent.Play).one<PlaybackEffect.PlanResume>()
        assertEquals("candidate_not_offered", h.send(PlaybackEvent.ResumePlanned(plan.request, null,
            MusicCheckpoint(PreparedMusic("unauthorized"), 0))).ignoredReason)
        assertEquals("multiple_resume_targets", h.send(PlaybackEvent.ResumePlanned(plan.request,
            StoryCheckpoint(story, 0), MusicCheckpoint(track, 999))).ignoredReason)
        assertEquals(OutputReference.MusicTrack(track.assetId, 999), h.plan(plan).one<PlaybackEffect.StartOutput>().reference)
    }

    @Test fun suspendWaitsForMusicPositionCommitBeforeClosingSession() {
        val h = Harness()
        val music = h.music(track)
        val suspend = h.send(PlaybackEvent.Suspend)
        assertFalse(suspend.snapshot.closed)
        assertTrue(suspend.effects.none { it == PlaybackEffect.CloseSession || it is PlaybackEffect.CommitPlayback })
        h.send(PlaybackEvent.OutputStopped(music.attempt, 1200))
        val committed = h.send(PlaybackEvent.PlaybackCommitted(music.attempt, true))
        assertTrue(committed.snapshot.closed)
        assertTrue(committed.effects.contains(PlaybackEffect.CloseSession))
    }

    @Test fun musicInterruptionStorageFailureCannotStartPrompt() {
        val h = Harness()
        val music = h.music(track)
        h.send(PlaybackEvent.Talk)
        h.send(PlaybackEvent.OutputStopped(music.attempt, 300))
        val failed = h.send(PlaybackEvent.PlaybackCommitted(music.attempt, false))
        assertTrue(failed.snapshot.blockers.contains(PlaybackBlocker.STORAGE_UNAVAILABLE))
        assertTrue(failed.effects.none { it is PlaybackEffect.StartOutput || it is PlaybackEffect.RequestInput })
    }

    @Test fun naturalEndBeforeTalkIsCommittedWithoutRepeatingEndedMusic() {
        val h = Harness()
        val music = h.music(track)
        h.send(PlaybackEvent.OutputDone(music.attempt, 20_000))
        h.send(PlaybackEvent.Talk)
        val prompt = h.send(PlaybackEvent.PlaybackCommitted(music.attempt, true)).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.FixedPrompt("talk-prompt"), prompt.reference)
        assertNull(h.core.snapshot().musicCheckpoint)
    }

    @Test fun withdrawnAutomaticOfferDoesNotPlayAtEndAndDoesNotInterruptCurrentMusic() {
        val h = Harness()
        val music = h.music(track)
        h.send(PlaybackEvent.OfferStory(story, "offer-1"))
        assertEquals("offer_not_found", h.send(PlaybackEvent.ClearOfferedStory(story.storyId, "other-version")).ignoredReason)
        val withdrawn = h.send(PlaybackEvent.ClearOfferedStory(story.storyId, story.versionId))
        assertTrue(withdrawn.effects.isEmpty())
        val ended = h.done(music, 20_000)
        assertEquals(PlaybackMode.IDLE, ended.snapshot.mode)
        assertTrue(ended.effects.none { it is PlaybackEffect.StartOutput })
    }

    @Test fun automaticEligibilityAppliesUntilFirstActualStartAndThenPreservesCurrentStory() {
        val h = Harness()
        val music = h.music(track)
        h.send(PlaybackEvent.OfferStory(story, "offer-1"))
        val first = h.done(music, 20_000).one<PlaybackEffect.StartOutput>()
        assertEquals("offer-1", first.automaticOfferId)
        h.send(PlaybackEvent.OutputStarted(first.attempt))
        val withdrawn = h.send(PlaybackEvent.ClearOfferedStory(story.storyId, story.versionId))
        assertTrue(withdrawn.effects.isEmpty())
        val second = h.done(first).one<PlaybackEffect.StartOutput>()
        assertNull(second.automaticOfferId)
        assertEquals(OutputReference.StoryParagraph("story", "version", "p2"), second.reference)
    }

    @Test fun cancellingInteractionWhileMusicStopsNeverPromptsOrOpensInputAndPreservesPosition() {
        val h = Harness()
        val music = h.music(track)
        h.send(PlaybackEvent.Talk)
        val cancelled = h.send(PlaybackEvent.CancelInteraction)
        assertTrue(cancelled.snapshot.playIntent)
        assertFalse(cancelled.snapshot.interactionIntent)
        assertTrue(cancelled.effects.none { it is PlaybackEffect.StartOutput || it is PlaybackEffect.RequestInput })
        h.send(PlaybackEvent.OutputStopped(music.attempt, 567))
        val plan = h.send(PlaybackEvent.PlaybackCommitted(music.attempt, true)).one<PlaybackEffect.PlanResume>()
        assertEquals(OutputReference.MusicTrack(track.assetId, 567), h.plan(plan).one<PlaybackEffect.StartOutput>().reference)
    }

    @Test fun cancellingPendingStoryQuestionPreservesParagraphAndDoesNotCreateReplacementTurn() {
        val h = Harness()
        val first = h.plan(h.send(PlaybackEvent.PlayStory(story)).one()).one<PlaybackEffect.StartOutput>()
        h.send(PlaybackEvent.Talk)
        val cancelled = h.send(PlaybackEvent.CancelInteraction)
        assertNull(cancelled.snapshot.turnId)
        assertEquals(PlaybackMode.STORY, cancelled.snapshot.mode)
        assertTrue(cancelled.effects.none { it is PlaybackEffect.StopOutput || it is PlaybackEffect.RequestInput })
        assertEquals(OutputReference.StoryParagraph("story", "version", "p2"), h.done(first).one<PlaybackEffect.StartOutput>().reference)
    }

    @Test fun powerAudioBlockUsesStoppedCheckpointWithoutClosingSessionAndPreservesManualStop() {
        val h = Harness()
        val music = h.music(track)
        h.send(PlaybackEvent.Block(PlaybackBlocker.POWER_AUDIO))
        h.send(PlaybackEvent.OutputStopped(music.attempt, 678))
        val committed = h.send(PlaybackEvent.PlaybackCommitted(music.attempt, true))
        assertFalse(committed.snapshot.closed)
        assertTrue(committed.snapshot.playIntent)
        h.send(PlaybackEvent.Stop)
        val unblocked = h.send(PlaybackEvent.Unblock(PlaybackBlocker.POWER_AUDIO))
        assertEquals(PlaybackMode.PAUSED, unblocked.snapshot.mode)
        assertTrue(unblocked.effects.none { it is PlaybackEffect.PlanResume || it is PlaybackEffect.StartOutput })
    }

    private companion object {
        inline fun <reified T : PlaybackEffect> PlaybackTransition.one(): T = effects.filterIsInstance<T>().single()
    }
}
