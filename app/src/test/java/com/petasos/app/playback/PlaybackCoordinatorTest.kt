package com.petasos.app.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackCoordinatorTest {
    private val story = PreparedStory("story", "v1", listOf("p1", "p2"))

    private class Harness(checkpoint: StoryCheckpoint? = null) {
        private var ids = 0
        var now = 0L
        val core = PlaybackCoordinator("trip", "session", { "id-${++ids}" }, checkpoint)
        fun send(event: PlaybackEvent, at: Long = now + 1): PlaybackTransition { now = at; return core.dispatch(event, at) }
        fun story(story: PreparedStory): PlaybackEffect.StartOutput {
            val plan = send(PlaybackEvent.PlayStory(story)).one<PlaybackEffect.PlanResume>()
            return send(PlaybackEvent.ResumePlanned(plan.request, plan.candidates.single())).one()
        }
        fun done(output: PlaybackEffect.StartOutput): PlaybackTransition {
            val result = send(PlaybackEvent.OutputDone(output.attempt))
            assertTrue(result.effects.none { it is PlaybackEffect.StartOutput || it is PlaybackEffect.RequestInput })
            return send(PlaybackEvent.PlaybackCommitted(output.attempt, true))
        }
        fun input(): PlaybackEffect.RequestInput {
            val prompt = send(PlaybackEvent.Talk).one<PlaybackEffect.StartOutput>()
            val request = done(prompt).one<PlaybackEffect.RequestInput>()
            send(PlaybackEvent.InputReady(request.input))
            return request
        }
        fun generating(): PlaybackEffect.GenerateAnswer {
            val input = input()
            val commit = send(PlaybackEvent.InputFinal(input.input, PayloadHandle("transient"))).one<PlaybackEffect.PersistUtterance>()
            val stopping = send(PlaybackEvent.UtteranceCommitted(commit.commit, PayloadHandle("stored")))
            assertTrue(stopping.effects.none { it is PlaybackEffect.GenerateAnswer || it is PlaybackEffect.StartOutput })
            return send(PlaybackEvent.InputStoppedConfirmed(input.input)).one()
        }
        fun answer(keys: List<String> = listOf("a1", "a2")): PlaybackEffect.StartOutput {
            val request = generating()
            val answer = ValidatedAnswer(PayloadHandle("answer"), keys)
            val commit = send(PlaybackEvent.AnswerReceived(request.request, answer)).one<PlaybackEffect.PersistAnswer>()
            return send(PlaybackEvent.AnswerCommitted(commit.commit, answer)).one()
        }
    }

    @Test fun restoredCheckpointDoesNotAutoplayOrRecord() {
        val h = Harness(StoryCheckpoint(story, 1))
        assertEquals(PlaybackMode.IDLE, h.core.snapshot().mode)
        assertFalse(h.core.snapshot().playIntent)
        assertFalse(h.core.snapshot().inputAvailable)
        assertTrue(h.send(PlaybackEvent.Play).effects.any { it is PlaybackEffect.PlanResume })
    }

    @Test fun talkWaitsForParagraphCompletionAndTransactionBeforePrompt() {
        val h = Harness()
        val paragraph = h.story(story)
        val pending = h.send(PlaybackEvent.Talk)
        assertEquals(PlaybackMode.TALK_PENDING, pending.snapshot.mode)
        assertTrue(pending.effects.none { it is PlaybackEffect.StopOutput || it is PlaybackEffect.RequestInput })
        val prompt = h.done(paragraph).one<PlaybackEffect.StartOutput>()
        assertEquals(OutputReference.FixedPrompt("talk-prompt"), prompt.reference)
        assertEquals(1, h.core.snapshot().checkpoint!!.paragraphIndex)
        val request = h.done(prompt).one<PlaybackEffect.RequestInput>()
        assertFalse(h.core.snapshot().inputAvailable)
        assertTrue(h.send(PlaybackEvent.InputReady(request.input)).snapshot.inputAvailable)
    }

    @Test fun buttonDebouncesThenCancelsWithoutAnotherInput() {
        val h = Harness()
        val prompt = h.send(PlaybackEvent.Talk, 0).one<PlaybackEffect.StartOutput>()
        val ignored = h.send(PlaybackEvent.Talk, 349)
        assertEquals("button_debounced", ignored.ignoredReason)
        val cancelled = h.send(PlaybackEvent.Talk, 350)
        assertFalse(cancelled.snapshot.interactionIntent)
        assertEquals(PlaybackMode.PAUSED, cancelled.snapshot.mode)
        assertTrue(cancelled.effects.any { it == PlaybackEffect.StopOutput(prompt.attempt) })
        assertTrue(cancelled.effects.none { it is PlaybackEffect.RequestInput })
    }

    @Test fun pendingTalkCancellationKeepsCurrentStoryParagraph() {
        val h = Harness()
        val paragraph = h.story(story)
        h.send(PlaybackEvent.Talk, 10)
        val cancel = h.send(PlaybackEvent.Talk, 360)
        assertEquals(PlaybackMode.STORY, cancel.snapshot.mode)
        assertTrue(cancel.effects.none { it is PlaybackEffect.StopOutput })
        val next = h.done(paragraph).one<PlaybackEffect.StartOutput>()
        assertEquals("p2", (next.reference as OutputReference.StoryParagraph).paragraphId)
    }

    @Test fun inputEndedAndStorageAckDoNotImplyMicrophoneStop() {
        val h = Harness()
        val input = h.input()
        h.send(PlaybackEvent.InputEnded(input.input))
        val commit = h.send(PlaybackEvent.InputFinal(input.input, PayloadHandle("raw"))).one<PlaybackEffect.PersistUtterance>()
        val result = h.send(PlaybackEvent.UtteranceCommitted(commit.commit, PayloadHandle("encrypted")))
        assertEquals(AdapterPhase.STOPPING, result.snapshot.inputPhase)
        assertTrue(result.effects.none { it is PlaybackEffect.GenerateAnswer || it is PlaybackEffect.StartOutput })
        assertTrue(h.send(PlaybackEvent.InputStoppedConfirmed(input.input)).effects.any { it is PlaybackEffect.GenerateAnswer })
    }

    @Test fun endedThenFinalWithinGraceCommitsOnce() {
        val h = Harness()
        val input = h.input()
        val end = h.send(PlaybackEvent.InputEnded(input.input))
        val deadline = end.one<PlaybackEffect.ScheduleTimer>().timer.dueAtMs
        val final = h.send(PlaybackEvent.InputFinal(input.input, PayloadHandle("raw")), deadline - 1)
        assertEquals(1, final.effects.filterIsInstance<PlaybackEffect.PersistUtterance>().size)
        val duplicate = h.send(PlaybackEvent.InputFinal(input.input, PayloadHandle("again")))
        assertEquals("duplicate_final", duplicate.ignoredReason)
        assertTrue(duplicate.effects.isEmpty())
    }

    @Test fun lateFinalIsRejectedEvenIfTimerDeliveryWasDelayed() {
        val h = Harness()
        val input = h.input()
        val timer = h.send(PlaybackEvent.InputEnded(input.input)).one<PlaybackEffect.ScheduleTimer>().timer
        val late = h.send(PlaybackEvent.InputFinal(input.input, PayloadHandle("late")), timer.dueAtMs)
        assertTrue(late.effects.none { it is PlaybackEffect.PersistUtterance || it is PlaybackEffect.GenerateAnswer || it is PlaybackEffect.StartOutput })
        assertEquals(AdapterPhase.STOPPING, late.snapshot.inputPhase)
    }

    @Test fun finalThenEndedStillRequiresCommitAndIndependentStop() {
        val h = Harness()
        val input = h.input()
        val commit = h.send(PlaybackEvent.InputFinal(input.input, PayloadHandle("raw"))).one<PlaybackEffect.PersistUtterance>()
        val end = h.send(PlaybackEvent.InputEnded(input.input))
        assertTrue(end.effects.none { it is PlaybackEffect.ScheduleTimer || it is PlaybackEffect.GenerateAnswer })
        h.send(PlaybackEvent.InputStoppedConfirmed(input.input))
        assertTrue(h.send(PlaybackEvent.UtteranceCommitted(commit.commit, PayloadHandle("stored"))).effects.any { it is PlaybackEffect.GenerateAnswer })
    }

    @Test fun confirmedStopBeforeFinalAllowsFinalOnlyDuringGrace() {
        val h = Harness()
        val input = h.input()
        h.send(PlaybackEvent.InputStoppedConfirmed(input.input))
        val commit = h.send(PlaybackEvent.InputFinal(input.input, PayloadHandle("raw"))).one<PlaybackEffect.PersistUtterance>()
        assertTrue(h.send(PlaybackEvent.UtteranceCommitted(commit.commit, PayloadHandle("stored"))).effects.any { it is PlaybackEffect.GenerateAnswer })
    }

    @Test fun finalBeforeInputReadyIsNotPersisted() {
        val h = Harness()
        val prompt = h.send(PlaybackEvent.Talk).one<PlaybackEffect.StartOutput>()
        val input = h.done(prompt).one<PlaybackEffect.RequestInput>()
        assertEquals("input_not_ready", h.send(PlaybackEvent.InputFinal(input.input, PayloadHandle("raw"))).ignoredReason)
    }

    @Test fun utteranceStorageFailureStopsInputAndNeverSubmits() {
        val h = Harness()
        val input = h.input()
        val commit = h.send(PlaybackEvent.InputFinal(input.input, PayloadHandle("raw"))).one<PlaybackEffect.PersistUtterance>()
        val failure = h.send(PlaybackEvent.UtteranceCommitted(commit.commit, null))
        assertTrue(failure.snapshot.blockers.contains(PlaybackBlocker.STORAGE_UNAVAILABLE))
        assertTrue(failure.effects.any { it is PlaybackEffect.StopInput })
        assertTrue(failure.effects.none { it is PlaybackEffect.GenerateAnswer || it is PlaybackEffect.StartOutput })
    }

    @Test fun answerCannotPlayBeforeEncryptedPersistenceAcknowledgment() {
        val h = Harness()
        val request = h.generating()
        val answer = ValidatedAnswer(PayloadHandle("answer"), listOf("key"))
        val received = h.send(PlaybackEvent.AnswerReceived(request.request, answer))
        assertTrue(received.effects.none { it is PlaybackEffect.StartOutput })
        val commit = received.one<PlaybackEffect.PersistAnswer>()
        assertTrue(h.send(PlaybackEvent.AnswerCommitted(commit.commit, answer)).effects.any { it is PlaybackEffect.StartOutput })
    }

    @Test fun answerCompletesOnlyCommittedParagraphsAndDoesNotReopenMicrophone() {
        val h = Harness()
        val first = h.answer()
        val second = h.done(first).one<PlaybackEffect.StartOutput>()
        val finished = h.done(second)
        val frozen = finished.one<PlaybackEffect.FreezeTurn>()
        assertEquals(listOf("a1", "a2"), frozen.completedAnswerKeys)
        assertEquals(TurnOutcome.CLOSED, frozen.outcome)
        assertEquals(PlaybackMode.PAUSED, finished.snapshot.mode)
        assertTrue(finished.effects.none { it is PlaybackEffect.RequestInput })
    }

    @Test fun answerInterruptionFinishesOnlyCurrentParagraphThenStartsNewTurn() {
        val h = Harness()
        val answer = h.answer()
        val oldTurn = (answer.reference as OutputReference.AnswerParagraph).turnId
        val pending = h.send(PlaybackEvent.Talk, h.now + 350)
        assertEquals(PlaybackMode.TALK_PENDING, pending.snapshot.mode)
        assertTrue(pending.effects.none { it is PlaybackEffect.FreezeTurn })
        val completed = h.done(answer)
        val frozen = completed.one<PlaybackEffect.FreezeTurn>()
        assertEquals(oldTurn, frozen.turnId)
        assertEquals(listOf("a1"), frozen.completedAnswerKeys)
        val prompt = completed.one<PlaybackEffect.StartOutput>()
        val nextInput = h.done(prompt).one<PlaybackEffect.RequestInput>()
        assertNotEquals(oldTurn, nextInput.turnId)
    }

    @Test fun newTalkDuringGenerationInvalidatesOldRequestAndCanOpenNewInput() {
        val h = Harness()
        val old = h.generating()
        val next = h.send(PlaybackEvent.Talk, h.now + 350)
        val prompt = next.one<PlaybackEffect.StartOutput>()
        assertTrue(next.effects.any { it == PlaybackEffect.CancelRequest(old.request) })
        val stale = h.send(PlaybackEvent.AnswerReceived(old.request, ValidatedAnswer(PayloadHandle("late"), listOf("a"))))
        assertEquals("stale_generation", stale.ignoredReason)
        assertTrue(h.done(prompt).effects.any { it is PlaybackEffect.RequestInput })
    }

    @Test fun stopThenLateDoneCannotCompleteAttemptOrUnlockOutput() {
        val h = Harness()
        val first = h.story(story)
        val stopped = h.send(PlaybackEvent.Stop)
        assertEquals(AttemptOutcome.INTERRUPTED, stopped.one<PlaybackEffect.CommitPlayback>().outcome)
        val late = h.send(PlaybackEvent.OutputDone(first.attempt))
        assertEquals("output_stopping", late.ignoredReason)
        assertEquals(AdapterPhase.STOPPING, late.snapshot.outputPhase)
        h.send(PlaybackEvent.PlaybackCommitted(first.attempt, true))
        val play = h.send(PlaybackEvent.Play)
        assertTrue(play.effects.none { it is PlaybackEffect.StartOutput || it is PlaybackEffect.PlanResume })
        assertTrue(h.send(PlaybackEvent.OutputStopped(first.attempt)).effects.any { it is PlaybackEffect.PlanResume })
    }

    @Test fun duplicateDoneAndCommitDoNotAdvanceOrIncrementRevision() {
        val h = Harness()
        val first = h.story(story)
        h.send(PlaybackEvent.OutputDone(first.attempt))
        val revision = h.core.snapshot().stateRevision
        assertEquals(revision, h.send(PlaybackEvent.OutputDone(first.attempt)).snapshot.stateRevision)
        h.send(PlaybackEvent.PlaybackCommitted(first.attempt, true))
        val nextRevision = h.core.snapshot().stateRevision
        assertEquals(nextRevision, h.send(PlaybackEvent.PlaybackCommitted(first.attempt, true)).snapshot.stateRevision)
        assertEquals(1, h.core.snapshot().checkpoint!!.paragraphIndex)
    }

    @Test fun focusLossThenManualStopThenGainPreservesManualStop() {
        val h = Harness()
        val first = h.story(story)
        h.send(PlaybackEvent.Block(PlaybackBlocker.FOCUS_TRANSIENT))
        assertTrue(h.core.snapshot().playIntent)
        h.send(PlaybackEvent.Stop)
        h.send(PlaybackEvent.OutputStopped(first.attempt))
        h.send(PlaybackEvent.PlaybackCommitted(first.attempt, true))
        val gain = h.send(PlaybackEvent.Unblock(PlaybackBlocker.FOCUS_TRANSIENT))
        assertFalse(gain.snapshot.playIntent)
        assertTrue(gain.effects.none { it is PlaybackEffect.StartOutput || it is PlaybackEffect.RequestInput || it is PlaybackEffect.PlanResume })
    }

    @Test fun clearingOneBlockerDoesNotResumeWhileAnotherRemains() {
        val h = Harness()
        h.story(story)
        h.send(PlaybackEvent.Block(PlaybackBlocker.FOCUS_LOST))
        h.send(PlaybackEvent.Block(PlaybackBlocker.SERVICE_UNAVAILABLE))
        val cleared = h.send(PlaybackEvent.Unblock(PlaybackBlocker.FOCUS_LOST))
        assertEquals(setOf(PlaybackBlocker.SERVICE_UNAVAILABLE), cleared.snapshot.blockers)
        assertTrue(cleared.effects.none { it is PlaybackEffect.PlanResume })
    }

    @Test fun cancellingInputBlocksStoryResumeUntilPhysicalStopConfirmation() {
        val h = Harness()
        val first = h.story(story)
        h.send(PlaybackEvent.Talk)
        val prompt = h.done(first).one<PlaybackEffect.StartOutput>()
        val input = h.done(prompt).one<PlaybackEffect.RequestInput>()
        val cancelled = h.send(PlaybackEvent.Talk, h.now + 350)
        assertEquals(AdapterPhase.STOPPING, cancelled.snapshot.inputPhase)
        assertTrue(cancelled.effects.none { it is PlaybackEffect.StartOutput || it is PlaybackEffect.PlanResume })
        val end = h.send(PlaybackEvent.InputEnded(input.input))
        assertTrue(end.effects.isEmpty())
        val plan = h.send(PlaybackEvent.InputStoppedConfirmed(input.input)).one<PlaybackEffect.PlanResume>()
        assertEquals("p2", plan.candidates.single().story.paragraphIds[plan.candidates.single().paragraphIndex])
    }

    @Test fun readyTimeoutStopsInputAndStaleTimerCannotCancelNextWork() {
        val h = Harness()
        val prompt = h.send(PlaybackEvent.Talk).one<PlaybackEffect.StartOutput>()
        val requested = h.done(prompt)
        val timer = requested.one<PlaybackEffect.ScheduleTimer>().timer
        val expired = h.send(PlaybackEvent.TimerExpired(timer), timer.dueAtMs)
        assertTrue(expired.effects.any { it is PlaybackEffect.StopInput })
        assertTrue(expired.effects.none { it is PlaybackEffect.StartOutput })
        assertEquals("stale_timer", h.send(PlaybackEvent.TimerExpired(timer)).ignoredReason)
    }

    @Test fun silenceTimerStartsAtReadyAndIsCancelledByRecognitionStart() {
        val h = Harness()
        val prompt = h.send(PlaybackEvent.Talk).one<PlaybackEffect.StartOutput>()
        val input = h.done(prompt).one<PlaybackEffect.RequestInput>()
        val ready = h.send(PlaybackEvent.InputReady(input.input))
        val silence = ready.one<PlaybackEffect.ScheduleTimer>().timer
        assertEquals(h.now + 5_000, silence.dueAtMs)
        val started = h.send(PlaybackEvent.InputStarted(input.input))
        val maximum = started.one<PlaybackEffect.ScheduleTimer>().timer
        assertEquals(h.now + 60_000, maximum.dueAtMs)
        assertEquals("stale_timer", h.send(PlaybackEvent.TimerExpired(silence), silence.dueAtMs).ignoredReason)
        assertTrue(h.send(PlaybackEvent.TimerExpired(maximum), maximum.dueAtMs).effects.any { it is PlaybackEffect.StopInput })
    }

    @Test fun generationRetriesOnceWithSameIdAndOriginalDeadline() {
        val h = Harness()
        val original = h.generating()
        val retry = h.send(PlaybackEvent.AnswerFailed(original.request, true)).one<PlaybackEffect.GenerateAnswer>()
        assertEquals(original.request, retry.request)
        assertEquals(2, retry.attemptNumber)
        val exhausted = h.send(PlaybackEvent.AnswerFailed(original.request, true))
        assertTrue(exhausted.effects.none { it is PlaybackEffect.GenerateAnswer })
        assertEquals(OutputReference.FixedPrompt("generation-failed"), exhausted.one<PlaybackEffect.StartOutput>().reference)
    }

    @Test fun responseAfterGenerationDeadlineIsNotPersistedEvenBeforeTimerCallback() {
        val h = Harness()
        val request = h.generating()
        val response = h.send(PlaybackEvent.AnswerReceived(request.request, ValidatedAnswer(PayloadHandle("late"), listOf("a"))), h.now + 15_000)
        assertTrue(response.effects.none { it is PlaybackEffect.PersistAnswer })
        assertEquals(OutputReference.FixedPrompt("generation-failed"), response.one<PlaybackEffect.StartOutput>().reference)
    }

    @Test fun retryAfterBeyondRemainingBudgetFailsWithoutRetry() {
        val h = Harness()
        val request = h.generating()
        val failure = h.send(PlaybackEvent.AnswerFailed(request.request, true, 20_000))
        assertTrue(failure.effects.none { it is PlaybackEffect.GenerateAnswer || it is PlaybackEffect.ScheduleTimer })
    }

    @Test fun resumeRejectsUnofferedTargetThenFallsBackAtDeadline() {
        val h = Harness()
        val requested = h.send(PlaybackEvent.PlayStory(story))
        val plan = requested.one<PlaybackEffect.PlanResume>()
        val timer = requested.one<PlaybackEffect.ScheduleTimer>().timer
        assertEquals("candidate_not_offered", h.send(PlaybackEvent.ResumePlanned(plan.request, StoryCheckpoint(story, 1))).ignoredReason)
        val fallback = h.send(PlaybackEvent.TimerExpired(timer), timer.dueAtMs).one<PlaybackEffect.StartOutput>()
        assertEquals("p1", (fallback.reference as OutputReference.StoryParagraph).paragraphId)
        assertEquals("stale_resume", h.send(PlaybackEvent.ResumePlanned(plan.request, null)).ignoredReason)
    }

    @Test fun oldSessionCallbackAndClockRegressionCannotChangeState() {
        val h = Harness()
        val first = h.story(story)
        val revision = h.core.snapshot().stateRevision
        assertEquals("stale_output", h.send(PlaybackEvent.OutputDone(first.attempt.copy(sessionId = "old"))).ignoredReason)
        val regressed = h.core.dispatch(PlaybackEvent.Stop, 0)
        assertEquals("clock_regressed", regressed.ignoredReason)
        assertEquals(revision, regressed.snapshot.stateRevision)
    }

    @Test fun pendingCompletionPreventsDifferentStoryFromBeingSelected() {
        val h = Harness()
        val first = h.story(story)
        h.send(PlaybackEvent.OutputDone(first.attempt))
        h.send(PlaybackEvent.Stop)
        val rejected = h.send(PlaybackEvent.PlayStory(PreparedStory("other", "v2", listOf("q1"))))
        assertEquals("playback_settling", rejected.ignoredReason)
        h.send(PlaybackEvent.PlaybackCommitted(first.attempt, true))
        assertEquals(story.storyId, h.core.snapshot().checkpoint!!.story.storyId)
        assertEquals(1, h.core.snapshot().checkpoint!!.paragraphIndex)
    }

    @Test fun stopDuringUtteranceCommitWaitsForAckThenFreezesWithoutGenerating() {
        val h = Harness()
        val input = h.input()
        val commit = h.send(PlaybackEvent.InputFinal(input.input, PayloadHandle("raw"))).one<PlaybackEffect.PersistUtterance>()
        val stop = h.send(PlaybackEvent.Stop)
        assertTrue(stop.effects.none { it is PlaybackEffect.FreezeTurn })
        val lateCommit = h.send(PlaybackEvent.UtteranceCommitted(commit.commit, PayloadHandle("stored")))
        assertTrue(lateCommit.one<PlaybackEffect.FreezeTurn>().hasCommittedUtterance)
        assertTrue(lateCommit.effects.none { it is PlaybackEffect.GenerateAnswer || it is PlaybackEffect.StartOutput })
    }

    @Test fun stopDuringAnswerCommitWaitsForAckWithoutPlayingStoredAnswer() {
        val h = Harness()
        val request = h.generating()
        val answer = ValidatedAnswer(PayloadHandle("answer"), listOf("a1"))
        val commit = h.send(PlaybackEvent.AnswerReceived(request.request, answer)).one<PlaybackEffect.PersistAnswer>()
        assertTrue(h.send(PlaybackEvent.Stop).effects.none { it is PlaybackEffect.FreezeTurn })
        val acknowledged = h.send(PlaybackEvent.AnswerCommitted(commit.commit, answer))
        assertTrue(acknowledged.one<PlaybackEffect.FreezeTurn>().completedAnswerKeys.isEmpty())
        assertTrue(acknowledged.effects.none { it is PlaybackEffect.StartOutput })
    }

    @Test fun suspendDrainsStopAndCommitBeforeClosingSession() {
        val h = Harness()
        val first = h.story(story)
        val suspending = h.send(PlaybackEvent.Suspend)
        assertFalse(suspending.snapshot.closed)
        assertTrue(suspending.effects.none { it is PlaybackEffect.CloseSession })
        assertEquals("session_closing", h.send(PlaybackEvent.Play).ignoredReason)
        h.send(PlaybackEvent.PlaybackCommitted(first.attempt, true))
        val closed = h.send(PlaybackEvent.OutputStopped(first.attempt))
        assertTrue(closed.snapshot.closed)
        assertTrue(closed.effects.contains(PlaybackEffect.CloseSession))
        assertEquals("session_closed", h.send(PlaybackEvent.OutputDone(first.attempt)).ignoredReason)
    }

    @Test fun suspendWaitsForPendingPrivatePayloadCommitAndInputStop() {
        val h = Harness()
        val input = h.input()
        val commit = h.send(PlaybackEvent.InputFinal(input.input, PayloadHandle("raw"))).one<PlaybackEffect.PersistUtterance>()
        h.send(PlaybackEvent.Suspend)
        h.send(PlaybackEvent.InputStoppedConfirmed(input.input))
        assertFalse(h.core.snapshot().closed)
        val closed = h.send(PlaybackEvent.UtteranceCommitted(commit.commit, PayloadHandle("stored")))
        assertTrue(closed.snapshot.closed)
        assertTrue(closed.effects.contains(PlaybackEffect.CloseSession))
        assertTrue(closed.effects.none { it is PlaybackEffect.GenerateAnswer })
    }

    @Test fun payloadHandleDoesNotPrintItsUnderlyingIdentifier() {
        assertFalse(PayloadHandle("private-reference").toString().contains("private-reference"))
    }

    @Test fun rejectedOutputRegistrationDoesNotCreateOrAcknowledgePlaybackTransaction() {
        val h = Harness()
        val prompt = h.send(PlaybackEvent.Talk).one<PlaybackEffect.StartOutput>()
        val rejected = h.send(PlaybackEvent.OutputNotRegistered(prompt.attempt))
        assertEquals(PlaybackMode.PAUSED, rejected.snapshot.mode)
        assertEquals(AdapterPhase.READY, rejected.snapshot.outputPhase)
        assertEquals(0, rejected.snapshot.pendingPlaybackCommits)
        assertTrue(rejected.snapshot.blockers.isEmpty())
        assertTrue(rejected.effects.none { it is PlaybackEffect.CommitPlayback || it is PlaybackEffect.StartOutput || it is PlaybackEffect.RequestInput })
        assertEquals(TurnOutcome.FAILED, rejected.one<PlaybackEffect.FreezeTurn>().outcome)
        assertEquals("stale_unregistered_output", h.send(PlaybackEvent.OutputNotRegistered(prompt.attempt)).ignoredReason)
        assertEquals("stale_output", h.send(PlaybackEvent.OutputDone(prompt.attempt)).ignoredReason)
    }

    @Test fun registrationRejectionAfterStopRemovesUncommittableAttemptWithoutStorageBlocker() {
        val h = Harness()
        val output = h.story(story)
        h.send(PlaybackEvent.Stop)
        assertEquals(1, h.core.snapshot().pendingPlaybackCommits)
        val rejected = h.send(PlaybackEvent.OutputNotRegistered(output.attempt))
        assertEquals(0, rejected.snapshot.pendingPlaybackCommits)
        assertEquals(AdapterPhase.READY, rejected.snapshot.outputPhase)
        assertTrue(rejected.snapshot.blockers.isEmpty())
        assertNull(rejected.snapshot.checkpoint)
        assertFalse(rejected.snapshot.playIntent)
        assertTrue(rejected.effects.none { it is PlaybackEffect.CommitPlayback })
        assertTrue(h.send(PlaybackEvent.PlayStory(story)).effects.any { it is PlaybackEffect.PlanResume })
    }

    @Test fun registrationRejectionDrainsSuspensionWithoutInventingACommit() {
        val h = Harness()
        val prompt = h.send(PlaybackEvent.Talk).one<PlaybackEffect.StartOutput>()
        h.send(PlaybackEvent.Suspend)
        val rejected = h.send(PlaybackEvent.OutputNotRegistered(prompt.attempt))
        assertTrue(rejected.snapshot.closed)
        assertTrue(rejected.effects.contains(PlaybackEffect.CloseSession))
        assertTrue(PlaybackBlocker.STORAGE_UNAVAILABLE !in rejected.snapshot.blockers)
        assertTrue(rejected.effects.none { it is PlaybackEffect.CommitPlayback })
    }

    @Test fun contentRejectionOfAnswerPreservesUtteranceAndFreezesWithoutAnswerCompletion() {
        val h = Harness()
        val generation = h.generating()
        val pending = h.send(PlaybackEvent.AnswerReceived(generation.request, ValidatedAnswer(PayloadHandle("answer"), listOf("a1"))))
            .one<PlaybackEffect.PersistAnswer>()
        val rejected = h.send(PlaybackEvent.AnswerContentRejected(pending.commit))
        val frozen = rejected.one<PlaybackEffect.FreezeTurn>()
        assertTrue(frozen.hasCommittedUtterance)
        assertTrue(frozen.completedAnswerKeys.isEmpty())
        assertEquals(TurnOutcome.FAILED, frozen.outcome)
        assertTrue(rejected.snapshot.blockers.isEmpty())
        assertEquals(OutputReference.FixedPrompt("generation-failed"), rejected.one<PlaybackEffect.StartOutput>().reference)
        assertEquals("stale_answer_commit", h.send(PlaybackEvent.AnswerContentRejected(pending.commit)).ignoredReason)
    }

    @Test fun suspendedAnswerContentRejectionDrainsWithoutFailurePlayback() {
        val h = Harness()
        val generation = h.generating()
        val commit = h.send(PlaybackEvent.AnswerReceived(generation.request, ValidatedAnswer(PayloadHandle("answer"), listOf("a1"))))
            .one<PlaybackEffect.PersistAnswer>()
        h.send(PlaybackEvent.Suspend)
        val rejected = h.send(PlaybackEvent.AnswerContentRejected(commit.commit))
        assertTrue(rejected.snapshot.closed)
        assertTrue(rejected.one<PlaybackEffect.FreezeTurn>().hasCommittedUtterance)
        assertTrue(rejected.effects.contains(PlaybackEffect.CloseSession))
        assertTrue(rejected.effects.none { it is PlaybackEffect.StartOutput })
        assertTrue(PlaybackBlocker.STORAGE_UNAVAILABLE !in rejected.snapshot.blockers)
    }

    @Test fun spontaneousOutputStopIsInterruptedAndPausedRatherThanCompleted() {
        val h = Harness()
        val output = h.story(story)
        val stopped = h.send(PlaybackEvent.OutputStopped(output.attempt))
        assertEquals(PlaybackMode.PAUSED, stopped.snapshot.mode)
        assertEquals(AdapterPhase.READY, stopped.snapshot.outputPhase)
        assertEquals(AttemptOutcome.INTERRUPTED, stopped.one<PlaybackEffect.CommitPlayback>().outcome)
        assertTrue(h.send(PlaybackEvent.PlaybackCommitted(output.attempt, true)).effects.none { it is PlaybackEffect.StartOutput })
    }

    @Test fun oldPromptCommitCannotOpenNewTurnsMicrophoneWithoutItsOwnPrompt() {
        val h = Harness()
        val first = h.send(PlaybackEvent.Talk, 0).one<PlaybackEffect.StartOutput>()
        h.send(PlaybackEvent.OutputDone(first.attempt), 1)
        h.send(PlaybackEvent.Talk, 350)
        val next = h.send(PlaybackEvent.Talk, 700)
        assertTrue(next.effects.none { it is PlaybackEffect.StartOutput })
        val oldCommit = h.send(PlaybackEvent.PlaybackCommitted(first.attempt, true))
        assertTrue(oldCommit.effects.none { it is PlaybackEffect.RequestInput })
        val second = oldCommit.one<PlaybackEffect.StartOutput>()
        assertNotEquals(first.attempt, second.attempt)
        assertTrue(h.done(second).effects.any { it is PlaybackEffect.RequestInput })
    }

    @Test fun lateInputEndedCannotRenewExpiredSilenceDeadline() {
        val h = Harness()
        val input = h.input()
        val ended = h.send(PlaybackEvent.InputEnded(input.input), h.now + 5_000)
        assertTrue(ended.effects.none { it is PlaybackEffect.ScheduleTimer })
        assertTrue(ended.effects.any { it is PlaybackEffect.StopInput })
        assertNull(ended.snapshot.turnId)
    }

    @Test fun latePhysicalInputStopCannotRenewExpiredSilenceDeadline() {
        val h = Harness()
        val input = h.input()
        val stopped = h.send(PlaybackEvent.InputStoppedConfirmed(input.input), h.now + 5_000)
        assertTrue(stopped.effects.none { it is PlaybackEffect.ScheduleTimer || it is PlaybackEffect.GenerateAnswer })
        assertNull(stopped.snapshot.turnId)
        assertEquals(AdapterPhase.READY, stopped.snapshot.inputPhase)
    }

    @Test fun readyAndStartedAfterEndedCannotRenewInputOrReplaceFinalGraceTimer() {
        val h = Harness()
        val input = h.input()
        val ended = h.send(PlaybackEvent.InputEnded(input.input))
        val grace = ended.one<PlaybackEffect.ScheduleTimer>().timer
        val revision = ended.snapshot.stateRevision
        assertFalse(ended.snapshot.inputAvailable)
        val ready = h.send(PlaybackEvent.InputReady(input.input))
        val started = h.send(PlaybackEvent.InputStarted(input.input))
        assertEquals(revision, ready.snapshot.stateRevision)
        assertEquals(revision, started.snapshot.stateRevision)
        assertTrue(ready.effects.isEmpty())
        assertTrue(started.effects.isEmpty())
        val expired = h.send(PlaybackEvent.TimerExpired(grace), grace.dueAtMs)
        assertTrue(expired.effects.any { it is PlaybackEffect.StopInput })
        assertNull(expired.snapshot.turnId)
    }

    @Test fun repeatedOutputFailureWhileStoppingDoesNotInvalidateAgain() {
        val h = Harness()
        val output = h.story(story)
        val failed = h.send(PlaybackEvent.OutputFailed(output.attempt))
        val duplicate = h.send(PlaybackEvent.OutputFailed(output.attempt))
        assertEquals("output_already_stopping", duplicate.ignoredReason)
        assertEquals(failed.snapshot.stateRevision, duplicate.snapshot.stateRevision)
        assertEquals(failed.snapshot.cancelEpoch, duplicate.snapshot.cancelEpoch)
        assertTrue(duplicate.effects.isEmpty())
        assertEquals(AdapterPhase.STOPPING, duplicate.snapshot.outputPhase)
    }

    private companion object {
        inline fun <reified T : PlaybackEffect> PlaybackTransition.one(): T = effects.filterIsInstance<T>().single()
    }
}
