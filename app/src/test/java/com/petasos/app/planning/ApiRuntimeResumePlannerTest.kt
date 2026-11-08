package com.petasos.app.planning

import com.petasos.app.generation.*
import com.petasos.app.playback.*
import com.petasos.app.runtime.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ApiRuntimeResumePlannerTest {
    private val first = StoryCheckpoint(PreparedStory("story-a", "version-a", listOf("a1", "a2")), 1)
    private val second = StoryCheckpoint(PreparedStory("story-b", "version-b", listOf("b1", "b2")), 0)
    private val music = MusicCheckpoint(PreparedMusic("music-a", 90_000), 12_345)
    private val request = OperationToken("session", 4, "resume-request")
    private val effect = PlaybackEffect.PlanResume(request, listOf(first, second))

    @Test fun sendsOnlyExactCheckpointReferencesAndTrustedContext() {
        val transport = ManualSubmitter()
        val withMusic = effect.copy(musicCandidates = listOf(music))
        val planner = ApiRuntimeResumePlanner(transport) {
            assertEquals(withMusic, it)
            ApiResumeContext("trusted summary", 7) { true }
        }
        var result: RuntimeResumeSelection? = null
        planner.plan(withMusic) { result = it }

        assertEquals(request, transport.request)
        assertEquals("trusted summary", transport.context.contextSummary)
        assertEquals(7L, transport.context.routeRevision)
        assertEquals(listOf(Triple("version-a", "a2", "story"), Triple("version-b", "b1", "story"),
            Triple("music-a", null, "music")), transport.context.candidates.map { Triple(it.contentId, it.paragraphId, it.kind) })
        transport.deliver(plan("switch", "version-b", "b1"))
        assertSame(second, result?.checkpoint)
    }

    @Test fun supportedActionsMapToOriginalCandidateWithoutChangingParagraphOrMusicPosition() {
        for (action in listOf("resume", "skip", "switch")) {
            val transport = ManualSubmitter()
            var result: RuntimeResumeSelection? = null
            planner(transport).plan(effect) { result = it }
            transport.deliver(plan(action, "version-b", "b1"))
            assertSame(second, result?.checkpoint)
        }
        val transport = ManualSubmitter()
        val another = MusicCheckpoint(PreparedMusic("music-b", 60_000), 8_765)
        var result: RuntimeResumeSelection? = null
        planner(transport).plan(effect.copy(musicCandidates = listOf(music, another))) { result = it }
        transport.deliver(plan("resume", "music-b", null))
        assertNull(result?.checkpoint)
        assertSame(another, result?.musicCheckpoint)
        assertEquals(8_765L, result?.musicCheckpoint?.positionMs)
    }

    @Test fun validWaitDoesNotFallBackToAnAvailableStoryOrMusic() {
        val transport = ManualSubmitter()
        var result: RuntimeResumeSelection? = null
        planner(transport).plan(effect.copy(musicCandidates = listOf(music))) { result = it }
        transport.deliver(plan("wait", null, null))
        assertEquals(RuntimeResumeSelection(), result)
    }

    @Test fun multipleParagraphCandidatesInOneVersionRemainDistinctExactTuples() {
        val transport = ManualSubmitter()
        val beginning = StoryCheckpoint(first.story, 0)
        var result: RuntimeResumeSelection? = null
        planner(transport).plan(effect.copy(candidates = listOf(beginning, first))) { result = it }
        assertEquals(listOf("a1", "a2"), transport.context.candidates.map { it.paragraphId })
        transport.deliver(plan("skip", "version-a", "a2"))
        assertSame(first, result?.checkpoint)
    }

    @Test fun inventedTargetsAndParagraphsCannotSelectAnotherInstalledParagraph() {
        val invalid = listOf(
            plan("resume", "story-b", "b1"), // storyId is not the offered versionId.
            plan("resume", "version-b", "b2"), // Existing paragraph, but not this checkpoint.
            plan("resume", "version-b", null),
            plan("resume", "unoffered", "b1"),
            plan("wait", "version-b", "b1"),
            plan("execute", "version-b", "b1"),
        )
        invalid.forEach { outcome ->
            val transport = ManualSubmitter()
            var result: RuntimeResumeSelection? = null
            planner(transport).plan(effect) { result = it }
            transport.deliver(outcome)
            assertEquals(RuntimeResumeSelection(first), result)
        }
    }

    @Test fun privateTransitionResultIsRejectedInsteadOfSpeakingOrDroppingTheSentence() {
        for (action in listOf("switch", "wait")) {
            val transport = ManualSubmitter()
            var result: RuntimeResumeSelection? = null
            planner(transport).plan(effect) { result = it }
            transport.deliver(plan(action, if (action == "wait") null else "version-b",
                if (action == "wait") null else "b1", RuntimeText("synthetic private transition")))
            assertEquals(RuntimeResumeSelection(first), result)
        }
    }

    @Test fun allFailuresIncludingTimeoutAndStaleUseExistingMusicFirstFallback() {
        GenerationFailureKind.entries.forEach { kind ->
            val transport = ManualSubmitter()
            var result: RuntimeResumeSelection? = null
            planner(transport).plan(effect.copy(musicCandidates = listOf(music))) { result = it }
            transport.deliver(GenerationOutcome.Failure(GenerationFailure(kind)))
            assertEquals(RuntimeResumeSelection(musicCheckpoint = music), result)
        }
    }

    @Test fun changedCurrentnessRejectsAnOtherwiseValidModelChoice() {
        var current = true
        val transport = ManualSubmitter()
        var result: RuntimeResumeSelection? = null
        val planner = ApiRuntimeResumePlanner(transport) { ApiResumeContext("summary", 7) { current } }
        planner.plan(effect) { result = it }
        current = false
        transport.deliver(plan("switch", "version-b", "b1"))
        assertEquals(RuntimeResumeSelection(first), result)
    }

    @Test fun unavailableOrAlreadyStaleTrustedContextNeverSubmits() {
        for (supplier in listOf<(PlaybackEffect.PlanResume) -> ApiResumeContext?>(
            { null },
            { throw RuntimeContentUnavailable() },
            { ApiResumeContext("summary", null) { false } },
            { ApiResumeContext("summary", null) { throw IllegalStateException("synthetic") } },
        )) {
            val transport = ManualSubmitter()
            var result: RuntimeResumeSelection? = null
            ApiRuntimeResumePlanner(transport, supplier).plan(effect) { result = it }
            assertEquals(0, transport.calls)
            assertEquals(RuntimeResumeSelection(first), result)
        }
    }

    @Test fun completedOrEmptyStoryCandidatesNeverProduceAnInventedResumeParagraph() {
        val transport = ManualSubmitter()
        var result: RuntimeResumeSelection? = null
        planner(transport).plan(effect.copy(candidates = listOf(StoryCheckpoint(first.story, 2)))) { result = it }
        assertEquals(0, transport.calls)
        assertEquals(RuntimeResumeSelection(), result)
    }

    @Test fun ambiguousMusicPositionsAndTooManyCandidatesDoNotBecomeModelAuthority() {
        val transport = ManualSubmitter()
        var result: RuntimeResumeSelection? = null
        planner(transport).plan(effect.copy(musicCandidates = listOf(music, music.copy(positionMs = 88)))) { result = it }
        assertEquals(0, transport.calls)
        assertEquals(RuntimeResumeSelection(musicCheckpoint = music), result)

        val many = (1..21).map { StoryCheckpoint(PreparedStory("story-$it", "version-$it", listOf("p-$it")), 0) }
        planner(transport).plan(effect.copy(candidates = many)) { result = it }
        assertEquals(0, transport.calls)
        assertEquals(RuntimeResumeSelection(many.first()), result)
    }

    @Test fun outerContentChecksFilterRequestsAndRejectRevokedSuccessAndFallback() {
        var permitted = true
        val transport = ManualSubmitter()
        val wrapped = ContentCheckedResumePlanner(planner(transport), { permitted && it != second.story }, { true })
        var result: RuntimeResumeSelection? = null
        wrapped.plan(effect) { result = it }
        assertEquals(listOf("version-a"), transport.context.candidates.map { it.contentId })
        permitted = false
        transport.deliver(plan("resume", "version-a", "a2"))
        assertEquals(RuntimeResumeSelection(), result)

        permitted = true
        wrapped.plan(effect) { result = it }
        permitted = false
        transport.deliver(GenerationOutcome.Failure(GenerationFailure(GenerationFailureKind.RETRYABLE)))
        assertEquals(RuntimeResumeSelection(), result)
    }

    @Test fun cancellationSuppressesTheClientsSynchronousCancelCallbackAndAllLateResults() {
        val transport = ManualSubmitter()
        var callbacks = 0
        val handle = planner(transport).plan(effect) { callbacks++ }
        handle.cancel()
        handle.cancel()
        transport.deliver(plan("switch", "version-b", "b1"))
        transport.deliver(GenerationOutcome.Failure(GenerationFailure(GenerationFailureKind.STALE)))
        assertEquals(1, transport.cancellations)
        assertEquals(0, callbacks)
    }

    @Test fun synchronousCallbackBeforeHandleAssignmentReleasesHandleAndDeliversOnlyOnce() {
        val transport = ManualSubmitter().apply { immediate = plan("switch", "version-b", "b1") }
        var callbacks = 0
        var result: RuntimeResumeSelection? = null
        val handle = planner(transport).plan(effect) { callbacks++; result = it }
        handle.cancel()
        transport.deliver(plan("resume", "version-a", "a2"))
        assertEquals(1, transport.cancellations)
        assertEquals(1, callbacks)
        assertEquals(RuntimeResumeSelection(second), result)
    }

    @Test fun synchronousSubmissionFailureAndRepeatedProviderCallbacksCannotDoubleDeliver() {
        val transport = ManualSubmitter().apply { failSubmission = true }
        var callbacks = 0
        var result: RuntimeResumeSelection? = null
        planner(transport).plan(effect) { callbacks++; result = it }
        transport.deliver(plan("switch", "version-b", "b1"))
        assertEquals(1, callbacks)
        assertEquals(RuntimeResumeSelection(first), result)

        val afterCallback = ManualSubmitter().apply { immediate = plan("switch", "version-b", "b1"); failSubmission = true }
        callbacks = 0
        planner(afterCallback).plan(effect) { callbacks++; result = it }
        assertEquals(1, callbacks)
        assertEquals(RuntimeResumeSelection(second), result)
    }

    @Test fun workerResponseReleasedAfterCancellationCannotFallBackOrSelect() {
        val transport = ManualSubmitter()
        var callbacks = 0
        val handle = planner(transport).plan(effect) { callbacks++ }
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = Thread {
            ready.countDown()
            release.await()
            transport.deliver(plan("switch", "version-b", "b1"))
            finished.countDown()
        }
        worker.start()
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            handle.cancel()
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(0, callbacks)
        } finally {
            release.countDown()
            worker.join(2_000)
        }
    }

    @Test fun contextDiagnosticsDoNotExposeSummary() {
        assertEquals("ApiResumeContext([redacted])", ApiResumeContext("synthetic private summary", 7) { true }.toString())
    }

    private fun planner(transport: ManualSubmitter) = ApiRuntimeResumePlanner(transport) { ApiResumeContext("summary", null) { true } }

    private fun plan(action: String, contentId: String?, paragraphId: String?, transition: RuntimeText? = null) =
        GenerationOutcome.Success(ValidatedResumePlan(action, contentId, paragraphId, transition, "synthetic reason",
            "synthetic-model", "synthetic-provider", "synthetic-prompt", null, null, "a".repeat(64)))

    private class ManualSubmitter : ResumeGenerationSubmitter {
        lateinit var request: OperationToken
        lateinit var context: ResumeGenerationContext
        private lateinit var callback: (GenerationOutcome<ValidatedResumePlan>) -> Unit
        var calls = 0
        var cancellations = 0
        var immediate: GenerationOutcome<ValidatedResumePlan>? = null
        var failSubmission = false
        override fun submit(request: OperationToken, context: ResumeGenerationContext,
            callback: (GenerationOutcome<ValidatedResumePlan>) -> Unit): RuntimeCancellation {
            calls++
            this.request = request
            this.context = context
            this.callback = callback
            immediate?.let(callback)
            if (failSubmission) throw IllegalStateException("synthetic")
            return RuntimeCancellation {
                cancellations++
                callback(GenerationOutcome.Failure(GenerationFailure(GenerationFailureKind.CANCELLED)))
            }
        }
        fun deliver(outcome: GenerationOutcome<ValidatedResumePlan>) = callback(outcome)
    }
}
