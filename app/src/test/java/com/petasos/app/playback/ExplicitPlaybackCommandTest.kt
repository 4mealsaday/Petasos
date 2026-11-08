package com.petasos.app.playback

import org.junit.Assert.*
import org.junit.Test

class ExplicitPlaybackCommandTest {
    private val story = PreparedStory("story", "immutable-v1", listOf("p1", "p2", "p3"))
    private val following = PreparedStory("following", "following-v1", listOf("next-p1"))
    private val music = PreparedMusic("music", 20_000)

    @Test fun committedFinalAndConfirmedInputStopAreBothRequiredBeforeCommandResolution() {
        val h = Harness(StoryCheckpoint(story, 1))
        val forged = OperationToken("session", 0, "not-issued")
        assertIgnored(h.send(PlaybackEvent.ExplicitCommandResolved(forged, ExplicitPlaybackCommand.CONTINUE)))
        val prompt = h.send(PlaybackEvent.Talk).one<PlaybackEffect.StartOutput>()
        assertIgnored(h.send(PlaybackEvent.ExplicitCommandResolved(forged, ExplicitPlaybackCommand.CONTINUE)))
        h.send(PlaybackEvent.OutputDone(prompt.attempt))
        assertIgnored(h.send(PlaybackEvent.ExplicitCommandResolved(forged, ExplicitPlaybackCommand.CONTINUE)))
        val input = h.send(PlaybackEvent.PlaybackCommitted(prompt.attempt, true)).one<PlaybackEffect.RequestInput>()
        h.send(PlaybackEvent.InputReady(input.input))
        val commit = h.send(PlaybackEvent.InputFinal(input.input, PayloadHandle("transient"))).one<PlaybackEffect.PersistUtterance>()
        assertIgnored(h.send(PlaybackEvent.ExplicitCommandResolved(forged, ExplicitPlaybackCommand.CONTINUE)))
        val acknowledged = h.send(PlaybackEvent.InputStoppedConfirmed(input.input))
        assertTrue(acknowledged.effects.none { it is PlaybackEffect.GenerateAnswer })
        assertIgnored(h.send(PlaybackEvent.ExplicitCommandResolved(forged, ExplicitPlaybackCommand.CONTINUE)))
        val generation = h.send(PlaybackEvent.UtteranceCommitted(commit.commit, PayloadHandle("stored"))).one<PlaybackEffect.GenerateAnswer>()
        val command = h.command(generation, ExplicitPlaybackCommand.CONTINUE)
        assertTrue(command.snapshot.playIntent)
        assertFalse(command.one<PlaybackEffect.PlanResume>().afterInteraction)
    }

    @Test fun committedUtteranceAloneCannotExecuteBeforePhysicalInputStop() {
        val h = Harness()
        val input = h.input()
        val commit = h.send(PlaybackEvent.InputFinal(input.input, PayloadHandle("transient"))).one<PlaybackEffect.PersistUtterance>()
        val stored = h.send(PlaybackEvent.UtteranceCommitted(commit.commit, PayloadHandle("stored")))
        assertEquals(AdapterPhase.STOPPING, stored.snapshot.inputPhase)
        assertTrue(stored.effects.none { it is PlaybackEffect.GenerateAnswer })
        assertIgnored(h.send(PlaybackEvent.ExplicitCommandResolved(commit.commit, ExplicitPlaybackCommand.STOP)))
        val generation = h.send(PlaybackEvent.InputStoppedConfirmed(input.input)).one<PlaybackEffect.GenerateAnswer>()
        assertEquals(PlaybackMode.PAUSED, h.command(generation, ExplicitPlaybackCommand.STOP).snapshot.mode)
    }

    @Test fun wrongSessionEpochAndRequestCannotResolveAndAcceptedCommandCancelsOnlyOnce() {
        val h = Harness(StoryCheckpoint(story, 1))
        val generation = h.generating()
        val request = generation.request
        listOf(request.copy(sessionId = "other"), request.copy(cancelEpoch = request.cancelEpoch + 1), request.copy(id = "other"))
            .forEach { wrong -> assertIgnored(h.send(PlaybackEvent.ExplicitCommandResolved(wrong, ExplicitPlaybackCommand.NEXT))) }
        val epoch = h.core.snapshot().cancelEpoch
        val accepted = h.command(generation, ExplicitPlaybackCommand.STOP)
        assertEquals(epoch + 1, accepted.snapshot.cancelEpoch)
        assertEquals(listOf(request), accepted.effects.filterIsInstance<PlaybackEffect.CancelRequest>().map { it.request })
        assertIgnored(h.command(generation, ExplicitPlaybackCommand.CONTINUE))
        assertIgnored(h.send(PlaybackEvent.AnswerReceived(request, ValidatedAnswer(PayloadHandle("answer"), listOf("a1")))))
        assertIgnored(h.send(PlaybackEvent.AnswerFailed(request, true)))
    }

    @Test fun deadlineRejectsCommandAndUsesExistingFailureBoundary() {
        val h = Harness(StoryCheckpoint(story, 1))
        val generation = h.generating()
        val expired = h.send(PlaybackEvent.ExplicitCommandResolved(generation.request, ExplicitPlaybackCommand.NEXT), h.now + 15_000)
        assertEquals(TurnOutcome.FAILED, expired.one<PlaybackEffect.FreezeTurn>().outcome)
        assertEquals(OutputReference.FixedPrompt("generation-failed"), expired.one<PlaybackEffect.StartOutput>().reference)
        assertEquals(StoryCheckpoint(story, 1), expired.snapshot.checkpoint)
        assertTrue(expired.effects.none { it is PlaybackEffect.ExcludeAutomaticStory || it is PlaybackEffect.PlanResume })
    }

    @Test fun stopBlockAndNewTurnRevokePendingCommandAuthority() {
        listOf<PlaybackEvent>(PlaybackEvent.Stop, PlaybackEvent.Block(PlaybackBlocker.POWER_AUDIO)).forEach { interruption ->
            val h = Harness(StoryCheckpoint(story, 1))
            val generation = h.generating()
            h.send(interruption)
            assertIgnored(h.command(generation, ExplicitPlaybackCommand.REPLAY))
            assertEquals(AdapterPhase.READY, h.core.snapshot().outputPhase)
        }
        val h = Harness()
        val old = h.generating()
        h.send(PlaybackEvent.Talk, h.now + 400)
        assertIgnored(h.command(old, ExplicitPlaybackCommand.CONTINUE))
        assertEquals(PlaybackMode.PROMPT, h.core.snapshot().mode)
    }

    @Test fun stopClosesOnlyUserTurnAndSavesWithoutStartingOrGeneratingAnAnswer() {
        val h = Harness(StoryCheckpoint(story, 1))
        val generation = h.generating()
        val stopped = h.command(generation, ExplicitPlaybackCommand.STOP)
        assertEquals(PlaybackMode.PAUSED, stopped.snapshot.mode)
        assertFalse(stopped.snapshot.playIntent)
        assertFalse(stopped.snapshot.interactionIntent)
        assertNull(stopped.snapshot.turnId)
        assertEquals(StoryCheckpoint(story, 1), stopped.one<PlaybackEffect.SaveCheckpoint>().checkpoint)
        assertClosedUserTurn(stopped)
        assertTrue(stopped.effects.none { it is PlaybackEffect.StartOutput || it is PlaybackEffect.PlanResume || it is PlaybackEffect.PersistAnswer })
        h.send(PlaybackEvent.OfferStory(following))
        assertEquals(PlaybackMode.PAUSED, h.core.snapshot().mode)
    }

    @Test fun continueFromIdleTalkCreatesExplicitPlayIntentAndUsesExactSavedParagraph() {
        val h = Harness(StoryCheckpoint(story, 1))
        val generation = h.generating()
        assertFalse(h.core.snapshot().playIntent)
        val continued = h.command(generation, ExplicitPlaybackCommand.CONTINUE)
        assertTrue(continued.snapshot.playIntent)
        assertClosedUserTurn(continued)
        assertOrderedBeforeStart(continued)
        val plan = continued.one<PlaybackEffect.PlanResume>()
        assertFalse(plan.afterInteraction)
        assertEquals(listOf(StoryCheckpoint(story, 1)), plan.candidates)
        assertEquals(OutputReference.StoryParagraph("story", "immutable-v1", "p2"), h.resume(continued).one<PlaybackEffect.StartOutput>().reference)
    }

    @Test fun continueWithoutCheckpointCanChooseExistingGeneralOffer() {
        val h = Harness()
        h.send(PlaybackEvent.OfferStory(following, "eligible-following"))
        val continued = h.command(h.generating(), ExplicitPlaybackCommand.CONTINUE)
        assertEquals(listOf(StoryCheckpoint(following, 0)), continued.one<PlaybackEffect.PlanResume>().candidates)
        val output = h.resume(continued).one<PlaybackEffect.StartOutput>()
        assertEquals("eligible-following", output.automaticOfferId)
        assertEquals("following", (output.reference as OutputReference.StoryParagraph).storyId)
    }

    @Test fun replayRestartsCurrentImmutableStoryEvenAfterCompletionAndKeepsExplicitAuthority() {
        val h = Harness(StoryCheckpoint(story, story.paragraphIds.size))
        h.send(PlaybackEvent.OfferStory(story.copy(versionId = "new-v2"), "new-version-offer"))
        val replay = h.command(h.generating(), ExplicitPlaybackCommand.REPLAY)
        assertOrderedBeforeStart(replay)
        assertEquals(listOf(StoryCheckpoint(story, 0)), replay.one<PlaybackEffect.PlanResume>().candidates)
        val output = h.resume(replay).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.StoryParagraph("story", "immutable-v1", "p1"), output.reference)
        assertNull(output.automaticOfferId)
        assertTrue(replay.effects.none { it is PlaybackEffect.ExcludeAutomaticStory || it is PlaybackEffect.CommitPlayback })
    }

    @Test fun replayMusicRestartsFromZeroInsteadOfRevivingAnExhaustedStory() {
        val h = Harness(StoryCheckpoint(story, story.paragraphIds.size), MusicCheckpoint(music, 4_321))
        val replay = h.command(h.generating(), ExplicitPlaybackCommand.REPLAY)
        val plan = replay.one<PlaybackEffect.PlanResume>()
        assertTrue(plan.candidates.isEmpty())
        assertEquals(listOf(MusicCheckpoint(music, 0)), plan.musicCandidates)
        assertEquals(OutputReference.MusicTrack("music", 0), h.resume(replay).one<PlaybackEffect.StartOutput>().reference)
    }

    @Test fun musicUsesExistingStoppedPositionBeforeAnOfferedTrack() {
        val h = Harness(StoryCheckpoint(story, story.paragraphIds.size), MusicCheckpoint(music, 4_321))
        h.send(PlaybackEvent.OfferMusic(PreparedMusic("different")))
        val command = h.command(h.generating(), ExplicitPlaybackCommand.MUSIC)
        assertNull(command.snapshot.checkpoint)
        assertEquals(listOf(MusicCheckpoint(music, 4_321)), command.one<PlaybackEffect.PlanResume>().musicCandidates)
        assertEquals(OutputReference.MusicTrack("music", 4_321), h.resume(command).one<PlaybackEffect.StartOutput>().reference)
    }

    @Test fun musicSelectsOnlyOfferedTrackAndClearsStoryAfterFindingIt() {
        val h = Harness(StoryCheckpoint(story, 1))
        h.send(PlaybackEvent.OfferMusic(music))
        val command = h.command(h.generating(), ExplicitPlaybackCommand.MUSIC)
        assertOrderedBeforeStart(command)
        assertNull(command.snapshot.checkpoint)
        assertEquals(MusicCheckpoint(music, 0), command.one<PlaybackEffect.SaveCheckpoint>().musicCheckpoint)
        assertEquals(OutputReference.MusicTrack("music", 0), h.resume(command).one<PlaybackEffect.StartOutput>().reference)
        assertTrue(command.effects.none { it is PlaybackEffect.ExcludeAutomaticStory || it is PlaybackEffect.CommitPlayback })
    }

    @Test fun missingMusicAnnouncesOnceThenStaysPausedAndPreservesStoryForExplicitPlay() {
        val h = Harness(StoryCheckpoint(story, 1))
        h.send(PlaybackEvent.OfferStory(following))
        val unavailable = h.command(h.generating(), ExplicitPlaybackCommand.MUSIC)
        assertOrderedBeforeStart(unavailable)
        assertClosedUserTurn(unavailable)
        assertEquals(StoryCheckpoint(story, 1), unavailable.snapshot.checkpoint)
        val notice = unavailable.one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.FixedPrompt("command-music-unavailable"), notice.reference)
        assertPaused(h.done(notice))
        assertPaused(h.send(PlaybackEvent.OfferStory(following, "late-offer")))
        assertPaused(h.send(PlaybackEvent.OfferMusic(music)))
        h.send(PlaybackEvent.Block(PlaybackBlocker.POWER_AUDIO))
        assertPaused(h.send(PlaybackEvent.Unblock(PlaybackBlocker.POWER_AUDIO)))
        val output = h.resume(h.send(PlaybackEvent.Play)).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.StoryParagraph("story", "immutable-v1", "p2"), output.reference)
        assertNull(output.automaticOfferId)
    }

    @Test fun missingReplayDoesNotTreatAutomaticOfferAsCurrentOrResumeAfterNotice() {
        val h = Harness()
        h.send(PlaybackEvent.OfferStory(following))
        h.send(PlaybackEvent.OfferMusic(music))
        val unavailable = h.command(h.generating(), ExplicitPlaybackCommand.REPLAY)
        assertNull(unavailable.snapshot.checkpoint)
        assertNull(unavailable.snapshot.musicCheckpoint)
        val notice = unavailable.one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.FixedPrompt("command-replay-unavailable"), notice.reference)
        assertPaused(h.done(notice))
        assertPaused(h.send(PlaybackEvent.OfferStory(following, "late-offer")))
        assertEquals("following", (h.resume(h.send(PlaybackEvent.Play)).one<PlaybackEffect.StartOutput>().reference as OutputReference.StoryParagraph).storyId)
    }

    @Test fun stopDuringUnavailableNoticeRequiresRealStopAndCannotResumeItAfterPowerRecovery() {
        val h = Harness()
        val notice = h.command(h.generating(), ExplicitPlaybackCommand.MUSIC).one<PlaybackEffect.StartOutput>()
        val stop = h.send(PlaybackEvent.Stop)
        assertEquals(notice.attempt, stop.one<PlaybackEffect.StopOutput>().attempt)
        assertEquals(AttemptOutcome.INTERRUPTED, stop.one<PlaybackEffect.CommitPlayback>().outcome)
        h.send(PlaybackEvent.PlaybackCommitted(notice.attempt, true))
        assertPaused(h.send(PlaybackEvent.OutputStopped(notice.attempt)))
        h.send(PlaybackEvent.Block(PlaybackBlocker.POWER_AUDIO))
        assertPaused(h.send(PlaybackEvent.Unblock(PlaybackBlocker.POWER_AUDIO)))
    }

    @Test fun newTalkInterruptsUnavailableNoticeBeforeStartingItsPrompt() {
        val h = Harness()
        val notice = h.command(h.generating(), ExplicitPlaybackCommand.REPLAY).one<PlaybackEffect.StartOutput>()
        val talk = h.send(PlaybackEvent.Talk, h.now + 400)
        assertEquals(notice.attempt, talk.one<PlaybackEffect.StopOutput>().attempt)
        assertTrue(talk.effects.none { it is PlaybackEffect.StartOutput || it is PlaybackEffect.RequestInput })
        h.send(PlaybackEvent.PlaybackCommitted(notice.attempt, true))
        val prompt = h.send(PlaybackEvent.OutputStopped(notice.attempt)).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.FixedPrompt("talk-prompt"), prompt.reference)
    }

    @Test fun nextExcludesStableStoryAndWithdrawsSameStoryOfferWithoutFabricatingCompletion() {
        val h = Harness(StoryCheckpoint(story, 1))
        h.send(PlaybackEvent.OfferStory(story.copy(versionId = "different-version"), "same-stable-story"))
        val command = h.command(h.generating(), ExplicitPlaybackCommand.NEXT)
        assertEquals(story.storyId, command.one<PlaybackEffect.ExcludeAutomaticStory>().storyId)
        assertNull(command.snapshot.checkpoint)
        assertTrue(command.effects.none { it is PlaybackEffect.CommitPlayback || it is PlaybackEffect.StartOutput })
        assertOrderedBeforeStart(command)
        assertEquals(PlaybackMode.IDLE, h.resume(command).snapshot.mode)
        val followingOutput = h.send(PlaybackEvent.OfferStory(following, "other-story")).one<PlaybackEffect.StartOutput>()
        assertEquals("following", (followingOutput.reference as OutputReference.StoryParagraph).storyId)
        h.stop(followingOutput)
        val replay = h.resume(h.send(PlaybackEvent.PlayStory(story))).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.StoryParagraph("story", "immutable-v1", "p1"), replay.reference)
        assertNull(replay.automaticOfferId)
    }

    @Test fun nextCanSelectAnotherQueuedStoryAndKeepsItsAutomaticAuthority() {
        val h = Harness(StoryCheckpoint(story, 1))
        h.send(PlaybackEvent.OfferStory(following, "different-offer"))
        val command = h.command(h.generating(), ExplicitPlaybackCommand.NEXT)
        assertEquals(story.storyId, command.one<PlaybackEffect.ExcludeAutomaticStory>().storyId)
        assertEquals(listOf(StoryCheckpoint(following, 0)), command.one<PlaybackEffect.PlanResume>().candidates)
        val output = h.resume(command).one<PlaybackEffect.StartOutput>()
        assertEquals("different-offer", output.automaticOfferId)
        assertEquals("following", (output.reference as OutputReference.StoryParagraph).storyId)
    }

    @Test fun nextMusicDoesNotPretendNaturalCompletionOrReleaseAnAutomaticStory() {
        val h = Harness(musicCheckpoint = MusicCheckpoint(music, 4_321))
        h.send(PlaybackEvent.OfferStory(following, "before-skip"))
        val command = h.command(h.generating(), ExplicitPlaybackCommand.NEXT)
        assertNull(command.snapshot.musicCheckpoint)
        assertTrue(command.effects.none { it is PlaybackEffect.ExcludeAutomaticStory || it is PlaybackEffect.CommitPlayback })
        val settled = h.resume(command)
        assertEquals(PlaybackMode.IDLE, settled.snapshot.mode)
        assertTrue(settled.effects.none { it is PlaybackEffect.StartOutput })
        assertTrue(h.send(PlaybackEvent.OfferStory(following, "after-skip")).effects.none { it is PlaybackEffect.StartOutput })
        val nextMusic = h.send(PlaybackEvent.OfferMusic(PreparedMusic("next-music", 1_000))).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.MusicTrack("next-music", 0), nextMusic.reference)
        assertEquals("after-skip", h.done(nextMusic, 1_000).one<PlaybackEffect.StartOutput>().automaticOfferId)
    }

    @Test fun nextExhaustedStoryPreservesTheRequiredMusicBoundary() {
        val h = Harness(StoryCheckpoint(story, story.paragraphIds.size))
        h.send(PlaybackEvent.OfferStory(following))
        val command = h.command(h.generating(), ExplicitPlaybackCommand.NEXT)
        assertEquals(story.storyId, command.one<PlaybackEffect.ExcludeAutomaticStory>().storyId)
        val settled = h.resume(command)
        assertEquals(PlaybackMode.IDLE, settled.snapshot.mode)
        assertTrue(settled.effects.none { it is PlaybackEffect.StartOutput })
        assertTrue(h.send(PlaybackEvent.OfferStory(following, "refresh")).effects.none { it is PlaybackEffect.StartOutput })
    }

    @Test fun physicalNextUsesSameExclusionBeforeStopAndRejectedNextDoesNotExclude() {
        val h = Harness()
        assertIgnored(h.send(PlaybackEvent.Next))
        val output = h.resume(h.send(PlaybackEvent.PlayStory(story))).one<PlaybackEffect.StartOutput>()
        h.send(PlaybackEvent.OfferStory(story.copy(versionId = "different-version")))
        val next = h.send(PlaybackEvent.Next)
        assertEquals(story.storyId, next.one<PlaybackEffect.ExcludeAutomaticStory>().storyId)
        assertTrue(next.effects.indexOfFirst { it is PlaybackEffect.ExcludeAutomaticStory } < next.effects.indexOfFirst { it is PlaybackEffect.StopOutput })
        assertIgnored(h.send(PlaybackEvent.Next))
        h.send(PlaybackEvent.OutputStopped(output.attempt))
        val settled = h.send(PlaybackEvent.PlaybackCommitted(output.attempt, true))
        assertEquals(PlaybackMode.IDLE, settled.snapshot.mode)
        assertTrue(settled.effects.none { it is PlaybackEffect.StartOutput })
    }

    @Test fun storageFailureBeforeResumeAcknowledgementPreventsNewOutputAndRevokesPlan() {
        val h = Harness(StoryCheckpoint(story, 1))
        val command = h.command(h.generating(), ExplicitPlaybackCommand.CONTINUE)
        assertOrderedBeforeStart(command)
        val plan = command.one<PlaybackEffect.PlanResume>()
        h.send(PlaybackEvent.Block(PlaybackBlocker.STORAGE_UNAVAILABLE))
        assertIgnored(h.send(PlaybackEvent.ResumePlanned(plan.request, plan.candidates.single())))
        assertEquals(PlaybackMode.PAUSED, h.core.snapshot().mode)
        assertEquals(AdapterPhase.READY, h.core.snapshot().outputPhase)
    }

    private fun assertClosedUserTurn(transition: PlaybackTransition) {
        val frozen = transition.one<PlaybackEffect.FreezeTurn>()
        assertEquals(TurnOutcome.CLOSED, frozen.outcome)
        assertTrue(frozen.hasCommittedUtterance)
        assertTrue(frozen.completedAnswerKeys.isEmpty())
        assertTrue(transition.effects.none { it is PlaybackEffect.PersistAnswer })
    }

    private fun assertOrderedBeforeStart(transition: PlaybackTransition) {
        val effects = transition.effects
        val freeze = effects.indexOfFirst { it is PlaybackEffect.FreezeTurn }
        val save = effects.indexOfFirst { it is PlaybackEffect.SaveCheckpoint }
        val start = effects.indexOfFirst { it is PlaybackEffect.PlanResume || it is PlaybackEffect.StartOutput }
        assertTrue(freeze >= 0 && save > freeze && start > save)
    }

    private fun assertPaused(transition: PlaybackTransition) {
        assertEquals(PlaybackMode.PAUSED, transition.snapshot.mode)
        assertFalse(transition.snapshot.playIntent)
        assertFalse(transition.snapshot.interactionIntent)
        assertTrue(transition.effects.none { it is PlaybackEffect.StartOutput || it is PlaybackEffect.PlanResume })
    }

    private fun assertIgnored(transition: PlaybackTransition) {
        assertNotNull(transition.ignoredReason)
        assertTrue(transition.effects.isEmpty())
    }

    private class Harness(checkpoint: StoryCheckpoint? = null, musicCheckpoint: MusicCheckpoint? = null) {
        var now = 0L
        private var id = 0
        val core = PlaybackCoordinator("trip", "session", { "operation-${++id}" }, checkpoint, musicCheckpoint)
        fun send(event: PlaybackEvent, at: Long = now + 1): PlaybackTransition {
            now = at
            return core.dispatch(event, at)
        }
        fun command(generation: PlaybackEffect.GenerateAnswer, command: ExplicitPlaybackCommand) =
            send(PlaybackEvent.ExplicitCommandResolved(generation.request, command))
        fun resume(transition: PlaybackTransition): PlaybackTransition {
            val plan = transition.one<PlaybackEffect.PlanResume>()
            return send(PlaybackEvent.ResumePlanned(plan.request, plan.candidates.firstOrNull(), plan.musicCandidates.firstOrNull()))
        }
        fun done(output: PlaybackEffect.StartOutput, positionMs: Long? = null): PlaybackTransition {
            send(PlaybackEvent.OutputDone(output.attempt, positionMs)).one<PlaybackEffect.CommitPlayback>()
            return send(PlaybackEvent.PlaybackCommitted(output.attempt, true))
        }
        fun stop(output: PlaybackEffect.StartOutput) {
            send(PlaybackEvent.Stop)
            send(PlaybackEvent.OutputStopped(output.attempt))
            send(PlaybackEvent.PlaybackCommitted(output.attempt, true))
        }
        fun input(): PlaybackEffect.RequestInput {
            val prompt = send(PlaybackEvent.Talk).one<PlaybackEffect.StartOutput>()
            val input = done(prompt).one<PlaybackEffect.RequestInput>()
            send(PlaybackEvent.InputReady(input.input))
            return input
        }
        fun generating(): PlaybackEffect.GenerateAnswer {
            val input = input()
            val commit = send(PlaybackEvent.InputFinal(input.input, PayloadHandle("transient"))).one<PlaybackEffect.PersistUtterance>()
            send(PlaybackEvent.UtteranceCommitted(commit.commit, PayloadHandle("stored")))
            return send(PlaybackEvent.InputStoppedConfirmed(input.input)).one()
        }
    }

    private companion object {
        inline fun <reified T : PlaybackEffect> PlaybackTransition.one(): T = effects.filterIsInstance<T>().single()
    }
}
