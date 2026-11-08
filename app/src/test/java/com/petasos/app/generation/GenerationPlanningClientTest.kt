package com.petasos.app.generation

import com.petasos.app.playback.OperationToken
import com.petasos.app.runtime.RuntimeCancellation
import com.petasos.app.runtime.RuntimeText
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GenerationPlanningClientTest {
    private val configuration = DeviceGenerationConfiguration("https://generation.example.invalid", "synthetic-test-token-0000000000000000")
    private val hash = "a".repeat(64)
    private val token = OperationToken("session-1", 2, "planning-request-1")
    private val candidates = listOf(ResumeGenerationCandidate("story-1", "paragraph-2", "story"),
        ResumeGenerationCandidate("music-1", null, "music"))
    private fun storyContext(
        sources: List<String> = listOf("source-1", "source-2"),
        excluded: List<String> = emptyList(),
        factSources: Map<String, Set<String>> = mapOf("fact-revision-1" to setOf("source-1", "source-2")),
        kinds: Map<String, String> = mapOf("fact-revision-1" to "fact"),
        stableIds: Map<String, String> = mapOf("fact-revision-1" to "stable-fact-1"),
        current: () -> Boolean = { true },
    ) = StoryGenerationContext("story-1", "topic-1", null, sources, excluded, listOf("합성 관심사"),
        factSources, kinds, stableIds, 4, current)
    private fun resumeContext(items: List<ResumeGenerationCandidate> = candidates, current: () -> Boolean = { true }) =
        ResumeGenerationContext("합성 대화 문맥", items, 4, current)
    private fun client(transport: GenerationTransport, hasher: GenerationInputHasher = GenerationInputHasher { hash }) =
        GenerationClient(GenerationConfigurationSource { configuration }, hasher, transport)
    private fun response(map: Map<String, Any?>) = GenerationHttpResponse(200, StrictJson.encode(map), null)
    private fun paragraph() = mapOf("paragraphKey" to "paragraph-1", "text" to "검토된 합성 이야기입니다.", "kind" to "fact",
        "sourceRevisionIds" to listOf("source-1", "source-2"), "factRevisionIds" to listOf("fact-revision-1"))
    private fun storyResult(paragraphs: List<Map<String, Any?>> = listOf(paragraph())) = mapOf("storyId" to "story-1",
        "storyVersionId" to "version-1", "paragraphs" to paragraphs, "estimatedDurationMs" to 4_000)
    private fun resumeResult(action: String = "resume", target: String? = "story-1", paragraph: String? = "paragraph-2") =
        mapOf("action" to action, "targetContentId" to target, "paragraphId" to paragraph,
            "transitionText" to "합성 연결 문장입니다.", "reason" to "합성 복귀 이유")
    private fun envelope(request: Map<String, Any?>, result: Map<String, Any?>) =
        request.filterKeys { it !in setOf("payload", "inputHash") } + mapOf("status" to "ok", "result" to result,
            "metadata" to mapOf("modelId" to "test-model", "provider" to "test-provider", "promptVersion" to "test-prompt",
                "inputTokens" to 15, "outputTokens" to 10))
    private fun awaitStory(client: GenerationClient, context: StoryGenerationContext = storyContext()): GenerationOutcome<ValidatedGenerationStory> {
        val result = CompletableFuture<GenerationOutcome<ValidatedGenerationStory>>()
        client.submitStory(token, context) { result.complete(it) }
        return result.get(3, TimeUnit.SECONDS)
    }
    private fun awaitResume(client: GenerationClient, context: ResumeGenerationContext = resumeContext()): GenerationOutcome<ValidatedResumePlan> {
        val result = CompletableFuture<GenerationOutcome<ValidatedResumePlan>>()
        client.submitResume(token, context) { result.complete(it) }
        return result.get(3, TimeUnit.SECONDS)
    }
    private fun failure(result: GenerationOutcome<*>) = (result as GenerationOutcome.Failure).failure.kind
    private fun submit(client: GenerationClient, story: Boolean, callback: (GenerationOutcome<*>) -> Unit): RuntimeCancellation =
        if (story) client.submitStory(token, storyContext(), callback) else client.submitResume(token, resumeContext(), callback)
    private fun defaultResult(story: Boolean) = if (story) storyResult() else resumeResult()

    @Test fun storyPreservesCanonicalPayloadNullTurnAndValidatedEvidenceProvenance() {
        client(GenerationTransport { config, path, body, _ ->
            assertSame(configuration, config); assertEquals("/v1/generations", path)
            val request = StrictJson.parse(body!!).jsonObject()
            assertEquals("story", request["task"]); assertNull(request["turnId"]); assertNull(request["turnVersion"])
            assertEquals(mapOf("routeRevision" to 4L), request["routeDependency"]); assertEquals(hash, request["inputHash"])
            assertEquals(mapOf("storyId" to "story-1", "topicId" to "topic-1", "regionId" to null,
                "sourceRevisionIds" to listOf("source-1", "source-2"), "excludedFactIds" to emptyList<String>(),
                "interestTags" to listOf("합성 관심사")), request["payload"])
            response(envelope(request, storyResult()))
        }).use {
            val story = (awaitStory(it) as GenerationOutcome.Success).value
            assertEquals("story-1", story.storyId); assertEquals("version-1", story.storyVersionId)
            assertEquals(4_000L, story.estimatedDurationMs); assertEquals(hash, story.inputHash)
            assertEquals("test-model", story.modelId); assertEquals("test-provider", story.provider)
            assertEquals("test-prompt", story.promptVersion); assertEquals(15L, story.inputTokens); assertEquals(10L, story.outputTokens)
            val paragraph = story.paragraphs.single()
            assertEquals("fact", paragraph.kind); assertEquals("paragraph-1", paragraph.key)
            assertEquals(listOf("source-1", "source-2"), paragraph.sourceRevisionIds)
            assertEquals(listOf("fact-revision-1"), paragraph.factRevisionIds)
        }
    }

    @Test fun publicStoryRejectsPersonalParagraphEvenWithoutFactClaims() {
        client(GenerationTransport { _, _, body, _ ->
            response(envelope(StrictJson.parse(body!!).jsonObject(), storyResult(listOf(paragraph() +
                mapOf("kind" to "personal", "factRevisionIds" to emptyList<String>())))))
        }).use { assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(awaitStory(it))) }
    }

    @Test fun storyRejectsUnknownFactsUnknownSourcesMissingSourcesAndEmptyFactClaims() {
        val changes = listOf("sourceRevisionIds" to listOf("unapproved-source"), "sourceRevisionIds" to listOf("source-1"),
            "factRevisionIds" to listOf("unapproved-fact"), "factRevisionIds" to emptyList<String>())
        changes.forEach { change ->
            client(GenerationTransport { _, _, body, _ ->
                response(envelope(StrictJson.parse(body!!).jsonObject(), storyResult(listOf(paragraph() + change))))
            }).use { assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(awaitStory(it))) }
        }
    }

    @Test fun storyRejectsIncompleteLocalAuthorityAndFactKindChanges() {
        val contexts = listOf(storyContext(factSources = emptyMap()), storyContext(kinds = emptyMap()),
            storyContext(stableIds = emptyMap()), storyContext(kinds = mapOf("fact-revision-1" to "tradition")),
            storyContext(factSources = mapOf("fact-revision-1" to emptySet())),
            storyContext(stableIds = mapOf("fact-revision-1" to " ")))
        contexts.forEach { context ->
            client(GenerationTransport { _, _, body, _ -> response(envelope(StrictJson.parse(body!!).jsonObject(), storyResult())) }).use {
                assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(awaitStory(it, context)))
            }
        }
    }

    @Test fun excludedStableFactIdRejectsApprovedRevision() {
        client(GenerationTransport { _, _, body, _ -> response(envelope(StrictJson.parse(body!!).jsonObject(), storyResult())) }).use {
            assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(awaitStory(it, storyContext(excluded = listOf("stable-fact-1")))))
        }
    }

    @Test fun traditionRemainsTraditionAndUnrelatedExcludedStableFactDoesNotReject() {
        client(GenerationTransport { _, _, body, _ ->
            response(envelope(StrictJson.parse(body!!).jsonObject(), storyResult(listOf(paragraph() + ("kind" to "tradition")))))
        }).use {
            val context = storyContext(excluded = listOf("other-stable-fact"), kinds = mapOf("fact-revision-1" to "tradition"))
            val story = (awaitStory(it, context) as GenerationOutcome.Success).value
            assertEquals("tradition", story.paragraphs.single().kind)
        }
    }

    @Test fun transitionIsNotAcceptedAsAnInstallablePublicStoryParagraph() {
        listOf(emptyList<String>(), listOf("fact-revision-1")).forEach { facts ->
            client(GenerationTransport { _, _, body, _ ->
                response(envelope(StrictJson.parse(body!!).jsonObject(), storyResult(listOf(paragraph() +
                    mapOf("kind" to "transition", "factRevisionIds" to facts)))))
            }).use { assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(awaitStory(it))) }
        }
    }

    @Test fun storyIdentityAndParagraphUniquenessAreEnforced() {
        val results = listOf(storyResult() + ("storyId" to "another-story"), storyResult(listOf(paragraph(), paragraph())),
            storyResult(listOf(paragraph() + ("text" to "   "))))
        results.forEach { result ->
            client(GenerationTransport { _, _, body, _ -> response(envelope(StrictJson.parse(body!!).jsonObject(), result)) }).use {
                assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(awaitStory(it)))
            }
        }
    }

    @Test fun resumePayloadPreservesExactCandidatesAndNullTurn() {
        client(GenerationTransport { _, _, body, _ ->
            val request = StrictJson.parse(body!!).jsonObject()
            assertEquals("resume", request["task"]); assertNull(request["turnId"]); assertNull(request["turnVersion"])
            assertEquals(mapOf("contextSummary" to "합성 대화 문맥", "candidates" to listOf(
                mapOf("contentId" to "story-1", "paragraphId" to "paragraph-2", "kind" to "story"),
                mapOf("contentId" to "music-1", "paragraphId" to null, "kind" to "music"))), request["payload"])
            response(envelope(request, resumeResult()))
        }).use {
            val plan = (awaitResume(it) as GenerationOutcome.Success).value
            assertEquals("resume", plan.action); assertEquals("story-1", plan.targetContentId); assertEquals("paragraph-2", plan.paragraphId)
            assertEquals("합성 연결 문장입니다.", plan.transitionText!!.value); assertEquals("합성 복귀 이유", plan.reason)
            assertEquals("test-model", plan.modelId); assertEquals("test-provider", plan.provider); assertEquals("test-prompt", plan.promptVersion)
            assertEquals(15L, plan.inputTokens); assertEquals(10L, plan.outputTokens); assertEquals(hash, plan.inputHash)
        }
    }

    @Test fun resumeAllowsEachCanonicalNonWaitActionOnlyForExactSuppliedTuple() {
        listOf("resume", "skip", "switch").forEach { action ->
            listOf("story-1" to "paragraph-2", "music-1" to null).forEach { (target, paragraph) ->
                client(GenerationTransport { _, _, body, _ ->
                    response(envelope(StrictJson.parse(body!!).jsonObject(), resumeResult(action, target, paragraph)))
                }).use { assertTrue(awaitResume(it) is GenerationOutcome.Success) }
            }
        }
    }

    @Test fun resumeRejectsUnknownTargetAndCrossCandidateParagraphAndNullMismatch() {
        val targets = listOf("unapproved" to "paragraph-2", "music-1" to "paragraph-2", "story-1" to null,
            "story-1" to "unapproved-paragraph", null to null)
        targets.forEach { (target, paragraph) ->
            client(GenerationTransport { _, _, body, _ ->
                response(envelope(StrictJson.parse(body!!).jsonObject(), resumeResult(target = target, paragraph = paragraph)))
            }).use { assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(awaitResume(it))) }
        }
    }

    @Test fun waitWithNoCandidatesNeedsNullTargetAndNullParagraph() {
        client(GenerationTransport { _, _, body, _ ->
            response(envelope(StrictJson.parse(body!!).jsonObject(), resumeResult("wait", null, null) + ("transitionText" to null)))
        }).use {
            val plan = (awaitResume(it, resumeContext(emptyList())) as GenerationOutcome.Success).value
            assertEquals("wait", plan.action); assertNull(plan.targetContentId); assertNull(plan.paragraphId); assertNull(plan.transitionText)
        }
        listOf("story-1" to null, null to "paragraph-2").forEach { (target, paragraph) ->
            client(GenerationTransport { _, _, body, _ ->
                response(envelope(StrictJson.parse(body!!).jsonObject(), resumeResult("wait", target, paragraph)))
            }).use { assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(awaitResume(it))) }
        }
    }

    @Test fun resumeRejectsBlankTransitionAndReason() {
        listOf("transitionText" to " ", "reason" to " ").forEach { change ->
            client(GenerationTransport { _, _, body, _ ->
                response(envelope(StrictJson.parse(body!!).jsonObject(), resumeResult() + change))
            }).use { assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(awaitResume(it))) }
        }
    }

    @Test fun ambiguousOrDuplicateCandidatePairNeverLeavesDevice() {
        val same = ResumeGenerationCandidate("same-id", null, "story")
        listOf(listOf(same, same), listOf(same, same.copy(kind = "music"))).forEach { items ->
            client(GenerationTransport { _, _, _, _ -> throw AssertionError("Ambiguous candidate authorization") }).use {
                assertEquals(GenerationFailureKind.INVALID_REQUEST, failure(awaitResume(it, resumeContext(items))))
            }
        }
    }

    @Test fun invalidPlanningInputsFailBeforeNetwork() {
        client(GenerationTransport { _, _, _, _ -> throw AssertionError("Invalid request") }).use {
            assertEquals(GenerationFailureKind.INVALID_REQUEST, failure(awaitStory(it, storyContext(sources = emptyList()))))
        }
        client(GenerationTransport { _, _, _, _ -> throw AssertionError("Invalid request") }).use {
            assertEquals(GenerationFailureKind.INVALID_REQUEST,
                failure(awaitResume(it, resumeContext(listOf(ResumeGenerationCandidate("item", null, "unknown-kind"))))))
        }
    }

    @Test fun planningMetadataAndCanonicalSchemaRemainStrict() {
        listOf(true, false).forEach { story ->
            val changes = listOf("requestId" to "other-request", "sessionId" to "other-session", "cancelEpoch" to 99,
                "routeDependency" to mapOf("routeRevision" to 99))
            changes.forEach { change ->
                client(GenerationTransport { _, _, body, _ ->
                    response(envelope(StrictJson.parse(body!!).jsonObject(), defaultResult(story)) + change)
                }).use {
                    val result = if (story) awaitStory(it) else awaitResume(it)
                    assertEquals(GenerationFailureKind.STALE, failure(result))
                }
            }
            client(GenerationTransport { _, _, body, _ ->
                response(envelope(StrictJson.parse(body!!).jsonObject(), defaultResult(story)) + ("unexpected" to true))
            }).use {
                assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(if (story) awaitStory(it) else awaitResume(it)))
            }
        }
    }

    @Test fun revokedPlanningContextNeverLeavesDevice() {
        listOf(true, false).forEach { story ->
            val current = AtomicBoolean(true)
            GenerationClient(GenerationConfigurationSource { current.set(false); configuration }, GenerationInputHasher { hash },
                GenerationTransport { _, _, _, _ -> throw AssertionError("Revoked context") }).use {
                val result = if (story) awaitStory(it, storyContext(current = current::get)) else awaitResume(it, resumeContext(current = current::get))
                assertEquals(GenerationFailureKind.STALE, failure(result))
            }
        }
    }

    @Test fun revokedContextOverridesBothSuccessAndErrorBodies() {
        listOf(true, false).forEach { story ->
            listOf(200, 503).forEach { status ->
                val current = AtomicBoolean(true)
                client(GenerationTransport { _, _, _, _ ->
                    current.set(false); GenerationHttpResponse(status, "untrusted response".toByteArray(), null)
                }).use {
                    val result = if (story) awaitStory(it, storyContext(current = current::get)) else awaitResume(it, resumeContext(current = current::get))
                    assertEquals(GenerationFailureKind.STALE, failure(result))
                }
            }
        }
    }

    @Test fun finalPlanningCurrentnessCheckRunsAfterResponseValidation() {
        listOf(true, false).forEach { story ->
            val checks = AtomicInteger()
            val current = { checks.incrementAndGet() < 4 }
            client(GenerationTransport { _, _, body, _ -> response(envelope(StrictJson.parse(body!!).jsonObject(), defaultResult(story))) }).use {
                val result = if (story) awaitStory(it, storyContext(current = current)) else awaitResume(it, resumeContext(current = current))
                assertEquals(GenerationFailureKind.STALE, failure(result)); assertEquals(4, checks.get())
            }
        }
    }

    @Test fun planningGuardExceptionFailsClosedWithoutTransmitting() {
        val unavailable: () -> Boolean = { throw IllegalStateException("synthetic private local failure") }
        client(GenerationTransport { _, _, _, _ -> throw AssertionError("Unavailable authority") }).use {
            assertEquals(GenerationFailureKind.LOCAL_STORAGE, failure(awaitStory(it, storyContext(current = unavailable))))
            assertEquals(GenerationFailureKind.LOCAL_STORAGE, failure(awaitResume(it, resumeContext(current = unavailable))))
        }
    }

    @Test fun planningRetryPreservesRequestBytesAndHmac() {
        listOf(true, false).forEach { story ->
            val bodies = arrayListOf<ByteArray>(); val hashes = AtomicInteger()
            client(GenerationTransport { _, _, body, _ ->
                bodies += body!!.copyOf(); response(envelope(StrictJson.parse(body).jsonObject(), defaultResult(story)))
            }, GenerationInputHasher { hashes.incrementAndGet(); hash }).use {
                repeat(2) { _ -> assertTrue((if (story) awaitStory(it) else awaitResume(it)) is GenerationOutcome.Success) }
                assertArrayEquals(bodies[0], bodies[1]); assertEquals(1, hashes.get())
            }
        }
    }

    @Test fun sameRequestIdCannotChangeTaskOrPlanningInput() {
        val calls = AtomicInteger()
        client(GenerationTransport { _, _, body, _ ->
            calls.incrementAndGet(); response(envelope(StrictJson.parse(body!!).jsonObject(), storyResult()))
        }).use {
            assertTrue(awaitStory(it) is GenerationOutcome.Success)
            assertEquals(GenerationFailureKind.CONFLICT, failure(awaitStory(it, storyContext(excluded = listOf("other-fact")))))
            assertEquals(GenerationFailureKind.CONFLICT, failure(awaitResume(it))); assertEquals(1, calls.get())
        }
    }

    @Test fun planningCancelIsImmediateAndLateSuccessCannotDeliverTwice() {
        listOf(true, false).forEach { story ->
            val started = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1); val ownerCancel = CountDownLatch(1)
            val callbacks = AtomicInteger(); val pending = CompletableFuture<GenerationOutcome<*>>()
            client(GenerationTransport { _, path, body, _ ->
                if (path.endsWith("/cancel")) {
                    assertEquals("/v1/generations/${token.id}/cancel", path)
                    ownerCancel.countDown(); GenerationHttpResponse(204, byteArrayOf(), null)
                } else {
                    started.countDown(); release.await(3, TimeUnit.SECONDS)
                    val response = response(envelope(StrictJson.parse(body!!).jsonObject(), defaultResult(story)))
                    returned.countDown(); response
                }
            }).use {
                val cancellation = submit(it, story) { result -> callbacks.incrementAndGet(); pending.complete(result) }
                assertTrue(started.await(3, TimeUnit.SECONDS)); cancellation.cancel()
                assertEquals(GenerationFailureKind.CANCELLED, failure(pending.get(1, TimeUnit.SECONDS)))
                assertTrue(ownerCancel.await(3, TimeUnit.SECONDS)); release.countDown(); assertTrue(returned.await(3, TimeUnit.SECONDS))
                it.close(); assertEquals(1, callbacks.get())
            }
        }
    }

    @Test fun resumeDeadlineIsThreeSecondsAndDisconnectsWithoutAcceptingLateSelection() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1)
        val pending = CompletableFuture<GenerationOutcome<ValidatedResumePlan>>()
        val exchange = CompletableFuture<GenerationExchange>(); val callbacks = AtomicInteger()
        client(GenerationTransport { _, _, body, connection ->
            exchange.complete(connection); started.countDown(); release.await(5, TimeUnit.SECONDS)
            val response = response(envelope(StrictJson.parse(body!!).jsonObject(), resumeResult()))
            returned.countDown(); response
        }).use {
            val startedAt = System.nanoTime()
            it.submitResume(token, resumeContext()) { value -> callbacks.incrementAndGet(); pending.complete(value) }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertEquals(GenerationFailureKind.RETRYABLE, failure(pending.get(4, TimeUnit.SECONDS)))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) >= 2_900)
            assertTrue(exchange.get().cancelled.get()); release.countDown(); assertTrue(returned.await(1, TimeUnit.SECONDS))
            it.close(); assertEquals(1, callbacks.get())
        }
    }

    @Test fun planningContextsSnapshotMutableCollectionsAndDiagnosticsRedactText() {
        val sources = mutableListOf("source-1"); val factSources = mutableSetOf("source-1")
        val facts = mutableMapOf("fact-1" to factSources); val excluded = mutableListOf("excluded-1")
        val tags = mutableListOf("private-interest"); val kinds = mutableMapOf("fact-1" to "fact")
        val stable = mutableMapOf("fact-1" to "stable-1")
        val context = StoryGenerationContext("story-1", "topic-1", null, sources, excluded, tags, facts, kinds, stable, null) { true }
        sources.clear(); factSources.clear(); facts.clear(); excluded.clear(); tags.clear(); kinds.clear(); stable.clear()
        assertEquals(listOf("source-1"), context.sourceRevisionIds); assertEquals(setOf("source-1"), context.approvedFactSources["fact-1"])
        assertEquals(listOf("excluded-1"), context.excludedFactIds); assertEquals(listOf("private-interest"), context.interestTags)
        assertEquals("fact", context.approvedFactKinds["fact-1"]); assertEquals("stable-1", context.approvedFactStableIds["fact-1"])
        val items = mutableListOf(ResumeGenerationCandidate("private-content", null, "story"))
        val resume = ResumeGenerationContext("private-context", items, null) { true }
        items.clear(); assertEquals(1, resume.candidates.size)
        val paragraph = ValidatedStoryParagraph("p", RuntimeText("private-story"), "transition", emptyList(), emptyList())
        val story = ValidatedGenerationStory("s", "v", listOf(paragraph), 1, "m", "p", "v", null, null, hash)
        val plan = ValidatedResumePlan("wait", null, null, RuntimeText("private-transition"), "private-reason", "m", "p", "v", null, null, hash)
        listOf(context, resume, resume.candidates.single(), paragraph, story, plan).forEach { assertFalse(it.toString().contains("private")) }
        assertFalse(story.toString().contains(hash))
    }
}
