package com.petasos.app.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackResumeCoordinatorTest {
    private val story = PreparedStory("story", "version", (0 until 30).map { "paragraph-$it" })

    private class Harness(checkpoint: StoryCheckpoint, music: MusicCheckpoint? = null) {
        private var ids = 0
        var now = 0L
        val core = PlaybackCoordinator("trip", "session", { "request-${++ids}" }, checkpoint, music)
        val effects = mutableListOf<PlaybackEffect>()
        fun send(event: PlaybackEvent, at: Long = now + 1): PlaybackTransition {
            now = at
            return core.dispatch(event, now).also { effects += it.effects }
        }
        fun done(output: PlaybackEffect.StartOutput): PlaybackTransition {
            send(PlaybackEvent.OutputDone(output.attempt))
            return send(PlaybackEvent.PlaybackCommitted(output.attempt, true))
        }
        fun listening(): PlaybackEffect.RequestInput {
            val plan = send(PlaybackEvent.Play).one<PlaybackEffect.PlanResume>()
            val output = send(PlaybackEvent.ResumePlanned(plan.request, plan.candidates.single())).one<PlaybackEffect.StartOutput>()
            send(PlaybackEvent.Talk)
            val prompt = done(output).one<PlaybackEffect.StartOutput>()
            val input = done(prompt).one<PlaybackEffect.RequestInput>()
            send(PlaybackEvent.InputReady(input.input))
            return input
        }
        fun cancelledConversation(): PlaybackTransition {
            val input = listening()
            send(PlaybackEvent.CancelInteraction)
            return send(PlaybackEvent.InputStoppedConfirmed(input.input))
        }
        fun generating(): PlaybackEffect.GenerateAnswer {
            val input = listening()
            val commit = send(PlaybackEvent.InputFinal(input.input, PayloadHandle("transient"))).one<PlaybackEffect.PersistUtterance>()
            send(PlaybackEvent.UtteranceCommitted(commit.commit, PayloadHandle("stored")))
            return send(PlaybackEvent.InputStoppedConfirmed(input.input)).one()
        }
    }

    @Test fun explicitPlayKeepsOnlyOriginalCheckpointAndNoInteractionFlag() {
        val h = Harness(StoryCheckpoint(story, 3))
        val plan = h.send(PlaybackEvent.Play).one<PlaybackEffect.PlanResume>()
        assertFalse(plan.afterInteraction); assertEquals(listOf(StoryCheckpoint(story, 3)), plan.candidates)
        assertTrue(plan.musicCandidates.isEmpty())
        val explicit = Harness(StoryCheckpoint(story, 3)).send(PlaybackEvent.PlayStory(story)).one<PlaybackEffect.PlanResume>()
        assertFalse(explicit.afterInteraction); assertEquals(listOf(StoryCheckpoint(story, 0)), explicit.candidates)
        val music = Harness(StoryCheckpoint(story, 3)).send(PlaybackEvent.PlayMusic(PreparedMusic("song"))).one<PlaybackEffect.PlanResume>()
        assertFalse(music.afterInteraction); assertTrue(music.candidates.isEmpty()); assertEquals(1, music.musicCandidates.size)
    }

    @Test fun cancelledInteractionOffersAtMostTwentyRemainingParagraphsFromCurrentCheckpoint() {
        val h = Harness(StoryCheckpoint(story, 3))
        val plan = h.cancelledConversation().one<PlaybackEffect.PlanResume>()
        assertTrue(plan.afterInteraction)
        assertEquals((4..23).toList(), plan.candidates.map { it.paragraphIndex })
        assertTrue(plan.candidates.all { it.story == story }); assertTrue(plan.musicCandidates.isEmpty())
        val nearEnd = Harness(StoryCheckpoint(story, 27)).cancelledConversation().one<PlaybackEffect.PlanResume>()
        assertEquals(listOf(28, 29), nearEnd.candidates.map { it.paragraphIndex })
    }

    @Test fun selectingLaterOfferedParagraphDoesNotMarkSkippedParagraphsCompleted() {
        val h = Harness(StoryCheckpoint(story, 3))
        val plan = h.cancelledConversation().one<PlaybackEffect.PlanResume>()
        val selected = plan.candidates[6]
        val transition = h.send(PlaybackEvent.ResumePlanned(plan.request, selected))
        assertEquals(selected, transition.snapshot.checkpoint)
        assertEquals(OutputReference.StoryParagraph(story.storyId, story.versionId, "paragraph-10"),
            transition.one<PlaybackEffect.StartOutput>().reference)
        assertTrue(transition.effects.none { it is PlaybackEffect.CommitPlayback })
        assertEquals(listOf("paragraph-3"), h.effects.filterIsInstance<PlaybackEffect.CommitPlayback>()
            .filter { it.outcome == AttemptOutcome.COMPLETED }.mapNotNull { (it.reference as? OutputReference.StoryParagraph)?.paragraphId })
    }

    @Test fun unofferedVersionParagraphGraphAndBeyondTwentyAreRejected() {
        val h = Harness(StoryCheckpoint(story, 3))
        val plan = h.cancelledConversation().one<PlaybackEffect.PlanResume>()
        val wrong = listOf(StoryCheckpoint(story.copy(versionId = "another-version"), 4),
            StoryCheckpoint(story.copy(paragraphIds = story.paragraphIds.map { if (it == "paragraph-4") "forged" else it }), 4),
            StoryCheckpoint(story, 24), StoryCheckpoint(story, 2))
        wrong.forEach { checkpoint ->
            val rejected = h.send(PlaybackEvent.ResumePlanned(plan.request, checkpoint))
            assertEquals("candidate_not_offered", rejected.ignoredReason); assertTrue(rejected.effects.isEmpty())
        }
    }

    @Test fun threeSecondTimeoutKeepsOriginalCheckpointAndIgnoresLaterModelSelection() {
        val h = Harness(StoryCheckpoint(story, 3))
        val requested = h.cancelledConversation()
        val plan = requested.one<PlaybackEffect.PlanResume>()
        val timer = requested.one<PlaybackEffect.ScheduleTimer>().timer
        assertEquals(3_000L, timer.dueAtMs - h.now)
        val fallback = h.send(PlaybackEvent.TimerExpired(timer), timer.dueAtMs)
        assertEquals(OutputReference.StoryParagraph(story.storyId, story.versionId, "paragraph-4"), fallback.one<PlaybackEffect.StartOutput>().reference)
        assertEquals("stale_resume", h.send(PlaybackEvent.ResumePlanned(plan.request, plan.candidates.last())).ignoredReason)
    }

    @Test fun delayedTimerCannotAcceptSelectionAtOrAfterDeadline() {
        val h = Harness(StoryCheckpoint(story, 3))
        val requested = h.cancelledConversation()
        val plan = requested.one<PlaybackEffect.PlanResume>()
        val timer = requested.one<PlaybackEffect.ScheduleTimer>().timer
        val fallback = h.send(PlaybackEvent.ResumePlanned(plan.request, plan.candidates.last()), timer.dueAtMs)
        assertEquals("paragraph-4", (fallback.one<PlaybackEffect.StartOutput>().reference as OutputReference.StoryParagraph).paragraphId)
    }

    @Test fun successfulAnswerAndGenerationFailureSetInteractionFlag() {
        val completed = Harness(StoryCheckpoint(story, 3))
        val request = completed.generating()
        val answer = ValidatedAnswer(PayloadHandle("answer"), listOf("answer-1"))
        val commit = completed.send(PlaybackEvent.AnswerReceived(request.request, answer)).one<PlaybackEffect.PersistAnswer>()
        val output = completed.send(PlaybackEvent.AnswerCommitted(commit.commit, answer)).one<PlaybackEffect.StartOutput>()
        assertTrue(completed.done(output).one<PlaybackEffect.PlanResume>().afterInteraction)

        val failed = Harness(StoryCheckpoint(story, 3))
        val generation = failed.generating()
        val failurePrompt = failed.send(PlaybackEvent.AnswerFailed(generation.request, false)).one<PlaybackEffect.StartOutput>()
        assertTrue(failed.done(failurePrompt).one<PlaybackEffect.PlanResume>().afterInteraction)
    }

    @Test fun inputFailureAndDebouncedButtonCancellationPreserveInteractionFlagUntilQuiescent() {
        val failed = Harness(StoryCheckpoint(story, 3))
        val input = failed.listening()
        assertTrue(failed.send(PlaybackEvent.InputFailed(input.input)).effects.none { it is PlaybackEffect.PlanResume })
        assertTrue(failed.send(PlaybackEvent.InputStoppedConfirmed(input.input)).one<PlaybackEffect.PlanResume>().afterInteraction)

        val cancelled = Harness(StoryCheckpoint(story, 3))
        val pending = cancelled.listening()
        cancelled.send(PlaybackEvent.Talk, cancelled.now + 350)
        assertTrue(cancelled.send(PlaybackEvent.InputStoppedConfirmed(pending.input)).one<PlaybackEffect.PlanResume>().afterInteraction)
    }

    @Test fun stopCancelsPlanningAndNextExplicitPlayHasNoInheritedInteractionAuthority() {
        val h = Harness(StoryCheckpoint(story, 3))
        val old = h.cancelledConversation().one<PlaybackEffect.PlanResume>()
        h.send(PlaybackEvent.Stop)
        assertEquals("stale_resume", h.send(PlaybackEvent.ResumePlanned(old.request, old.candidates.last())).ignoredReason)
        val next = h.send(PlaybackEvent.Play).one<PlaybackEffect.PlanResume>()
        assertFalse(next.afterInteraction); assertEquals(listOf(StoryCheckpoint(story, 4)), next.candidates)
    }

    private companion object {
        inline fun <reified T : PlaybackEffect> PlaybackTransition.one(): T = effects.filterIsInstance<T>().single()
    }
}
