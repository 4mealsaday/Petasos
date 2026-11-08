package com.petasos.app.generation

import com.petasos.app.playback.OperationToken
import com.petasos.app.runtime.*
import com.petasos.app.summary.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class GenerationClientTest {
    private val config = DeviceGenerationConfiguration("https://generation.example.invalid", "synthetic-test-token-0000000000000000")
    private val hash = "a".repeat(64)
    private val request = RuntimeGenerationRequest(OperationToken("session-1", 2, "request-1"), "turn-1", 1, RuntimeText("합성 질문입니다."))
    private fun context(current: () -> Boolean = { true }) = AnswerGenerationContext(3, 4, "합성 문맥", listOf("heard-1"), listOf("source-1"),
        mapOf("fact-1" to setOf("source-1")), mapOf("continue" to setOf(null), "unsupported" to setOf(null)), current,
        approvedFactKinds = mapOf("fact-1" to "fact"))
    private fun client(transport: GenerationTransport) = client(transport, GenerationInputHasher { hash })
    private fun client(transport: GenerationTransport, hasher: GenerationInputHasher) =
        GenerationClient(GenerationConfigurationSource { config }, hasher, transport)
    private fun answer(request: Map<String, Any?>): Map<String, Any?> = request.filterKeys { it !in setOf("payload", "inputHash") } + mapOf(
        "status" to "ok", "result" to mapOf("paragraphs" to listOf(mapOf("paragraphKey" to "answer-1", "text" to "합성 답변입니다.",
            "kind" to "fact", "sourceRevisionIds" to listOf("source-1"), "factRevisionIds" to listOf("fact-1"))),
            "action" to "continue", "targetContentId" to null),
        "metadata" to mapOf("modelId" to "test-model", "provider" to "test-provider", "promptVersion" to "test-prompt", "inputTokens" to null, "outputTokens" to null))
    private fun response(map: Map<String, Any?>, status: Int = 200) = GenerationHttpResponse(status, StrictJson.encode(map), null)
    private fun result(client: GenerationClient, context: AnswerGenerationContext = context()): GenerationOutcome<ValidatedGenerationAnswer> {
        val pending = CompletableFuture<GenerationOutcome<ValidatedGenerationAnswer>>()
        client.submitAnswer(request, context) { pending.complete(it) }
        return pending.get(3, TimeUnit.SECONDS)
    }
    private fun failure(result: GenerationOutcome<*>): GenerationFailureKind = (result as GenerationOutcome.Failure).failure.kind

    @Test fun canonicalResourceExactlyMatchesVersionedSpec() {
        val path = listOf(File("../spec/generation-v0.8.schema.json"), File("spec/generation-v0.8.schema.json")).first { it.exists() }
        assertArrayEquals(path.readBytes(), javaClass.getResourceAsStream("/generation/generation-v0.8.schema.json")!!.use { it.readBytes() })
    }
    @Test fun validAnswerPassesSchemaAndEvidence() {
        client { _, _, body, _ -> response(answer(StrictJson.parse(body!!).jsonObject())) }.use {
            val success = result(it) as GenerationOutcome.Success
            assertEquals("answer-1", success.value.paragraphs.single().key)
        }
    }
    @Test fun nonNullProviderUsageAndRealProviderMetadataPassCanonicalValidation() {
        client { _, _, body, _ ->
            val original = answer(StrictJson.parse(body!!).jsonObject())
            response(original + ("metadata" to mapOf("modelId" to "anthropic/claude-4.6-sonnet-20260217",
                "provider" to "Amazon Bedrock", "promptVersion" to "petasos-v0.8-2", "inputTokens" to 1093, "outputTokens" to 103)))
        }.use { assertTrue(result(it) is GenerationOutcome.Success) }
    }
    @Test fun staleMetadataNeverBecomesPlayable() {
        listOf("requestId" to "other", "sessionId" to "other", "cancelEpoch" to 99L, "turnVersion" to 99L,
            "routeDependency" to mapOf("routeRevision" to 99L), "turnId" to "other").forEach { (key, value) ->
            client { _, _, body, _ -> response(answer(StrictJson.parse(body!!).jsonObject()) + (key to value)) }.use {
                assertEquals(key, GenerationFailureKind.STALE, failure(result(it)))
            }
        }
    }
    @Test fun unknownPropertiesAndMissingMetadataAreRejected() {
        client { _, _, body, _ -> response(answer(StrictJson.parse(body!!).jsonObject()) + ("unknown" to true)) }.use {
            assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(result(it)))
        }
        client { _, _, body, _ -> response(answer(StrictJson.parse(body!!).jsonObject()) - "metadata") }.use {
            assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(result(it)))
        }
    }
    @Test fun unknownFactsSourcesAndPersonalFactClaimsAreRejected() {
        listOf("sourceRevisionIds" to listOf("untrusted-source"), "factRevisionIds" to listOf("unknown-fact"), "kind" to "personal").forEach { change ->
            client { _, _, body, _ ->
                val original = answer(StrictJson.parse(body!!).jsonObject())
                val result = original["result"].jsonObject()
                val paragraph = (result["paragraphs"] as List<*>).single().jsonObject() + change
                response(original + ("result" to (result + ("paragraphs" to listOf(paragraph)))))
            }.use { assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(result(it))) }
        }
    }
    @Test fun duplicateParagraphKeysAreRejected() {
        client { _, _, body, _ ->
            val original = answer(StrictJson.parse(body!!).jsonObject())
            val result = original["result"].jsonObject()
            val paragraph = (result["paragraphs"] as List<*>).single()
            response(original + ("result" to (result + ("paragraphs" to listOf(paragraph, paragraph)))))
        }.use { assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(result(it))) }
    }
    @Test fun actionOutsideLocalAuthorityIsRejected() {
        client { _, _, body, _ ->
            val original = answer(StrictJson.parse(body!!).jsonObject())
            response(original + ("result" to (original["result"].jsonObject() + mapOf("action" to "music", "targetContentId" to "unknown"))))
        }.use { assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(result(it))) }
    }
    @Test fun validWireStopActionWithNullTargetDoesNotBypassLocalAuthority() {
        client { _, _, body, _ ->
            val original = answer(StrictJson.parse(body!!).jsonObject())
            response(original + ("result" to (original["result"].jsonObject() + mapOf("action" to "stop", "targetContentId" to null))))
        }.use { assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(result(it))) }
    }
    @Test fun contextRecheckedAfterNetworkReturns() {
        var current = true
        client { _, _, body, _ -> current = false; response(answer(StrictJson.parse(body!!).jsonObject())) }.use {
            assertEquals(GenerationFailureKind.STALE, failure(result(it, context { current })))
        }
    }
    @Test fun revokedContextAfterPreparationNeverLeavesDevice() {
        val current = AtomicBoolean(true)
        val calls = AtomicInteger()
        GenerationClient(GenerationConfigurationSource { current.set(false); config },
            GenerationInputHasher { hash }, GenerationTransport { _, _, _, _ ->
                calls.incrementAndGet()
                throw AssertionError("Revoked context must not reach transport")
            }).use {
            assertEquals(GenerationFailureKind.STALE, failure(result(it, context { current.get() })))
            assertEquals(0, calls.get())
        }
    }
    @Test fun failedFinalContextCheckNeverLeavesDevice() {
        val checks = AtomicInteger()
        val calls = AtomicInteger()
        client { _, _, _, _ -> calls.incrementAndGet(); throw AssertionError("Context check failed") }.use {
            val outcome = result(it, context {
                if (checks.incrementAndGet() > 1) throw IllegalStateException("Synthetic database unavailable")
                true
            })
            assertTrue(outcome is GenerationOutcome.Failure)
            assertEquals(0, calls.get())
        }
    }
    @Test fun reviewedFactKindCannotBeChangedByModel() {
        val approved = AnswerGenerationContext(3, 4, "", emptyList(), listOf("source-1"),
            mapOf("fact-1" to setOf("source-1")), mapOf("continue" to setOf(null)), { true },
            approvedFactKinds = mapOf("fact-1" to "tradition"))
        client { _, _, body, _ -> response(answer(StrictJson.parse(body!!).jsonObject())) }.use {
            assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(result(it, approved)))
        }
    }
    @Test fun missingReviewedFactKindCannotAuthorizeFactResponse() {
        val missingKind = AnswerGenerationContext(3, 4, "", emptyList(), listOf("source-1"),
            mapOf("fact-1" to setOf("source-1")), mapOf("continue" to setOf(null)), { true })
        client { _, _, body, _ -> response(answer(StrictJson.parse(body!!).jsonObject())) }.use {
            assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(result(it, missingKind)))
        }
    }
    @Test fun retryUsesIdenticalRequestAndHmacWithoutRehash() {
        val bodies = arrayListOf<ByteArray>(); val hashes = AtomicInteger()
        client(GenerationTransport { _, _, body, _ ->
            bodies += body!!.copyOf(); response(answer(StrictJson.parse(body).jsonObject()))
        }, GenerationInputHasher { hashes.incrementAndGet(); hash }).use {
            assertTrue(result(it) is GenerationOutcome.Success)
            assertTrue(result(it) is GenerationOutcome.Success)
            assertArrayEquals(bodies[0], bodies[1]); assertEquals(1, hashes.get())
        }
    }
    @Test fun sameIdDifferentInputFailsBeforeNetwork() {
        val calls = AtomicInteger()
        client { _, _, body, _ -> calls.incrementAndGet(); response(answer(StrictJson.parse(body!!).jsonObject())) }.use {
            result(it)
            val changed = AnswerGenerationContext(3, 4, "변경한 문맥", listOf("heard-1"), listOf("source-1"),
                mapOf("fact-1" to setOf("source-1")), mapOf("continue" to setOf(null)), { true })
            assertEquals(GenerationFailureKind.CONFLICT, failure(result(it, changed))); assertEquals(1, calls.get())
        }
    }
    @Test fun authenticationFailureWithNullRequestIdIsNotRetried() {
        client { _, _, _, _ -> response(mapOf("schemaVersion" to "0.8", "requestId" to null, "status" to "error",
            "code" to "UNAUTHORIZED", "retryable" to true, "retryAfterMs" to null), 401) }.use {
            assertEquals(GenerationFailureKind.AUTHORIZATION, failure(result(it)))
        }
    }
    @Test fun httpStatusAndErrorCodeMustAgree() {
        client { _, _, _, _ -> response(mapOf("schemaVersion" to "0.8", "requestId" to "request-1", "status" to "error",
            "code" to "UNAUTHORIZED", "retryable" to false, "retryAfterMs" to null), 503) }.use {
            assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(result(it)))
        }
    }
    @Test fun retryAfterUsesLargerBodyAndHeaderDelay() {
        client { _, _, _, _ -> GenerationHttpResponse(429, StrictJson.encode(mapOf("schemaVersion" to "0.8", "requestId" to "request-1",
            "status" to "error", "code" to "RATE_LIMITED", "retryable" to true, "retryAfterMs" to 500)), 2) }.use {
            val failure = (result(it) as GenerationOutcome.Failure).failure
            assertEquals(GenerationFailureKind.RETRYABLE, failure.kind); assertEquals(2000, failure.retryAfterMs)
        }
    }
    @Test fun missingConfigurationNeverOpensNetwork() {
        GenerationClient(GenerationConfigurationSource { null }, GenerationInputHasher { hash }, GenerationTransport { _, _, _, _ ->
            throw AssertionError("Network must not start")
        }).use { assertEquals(GenerationFailureKind.CONFIGURATION, failure(result(it))) }
    }
    @Test fun cancelIsImmediateAndLateSuccessSuppressedAndOwnerEndpointCalled() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val ownerCancel = CountDownLatch(1)
        val callbacks = AtomicInteger(); val pending = CompletableFuture<GenerationOutcome<ValidatedGenerationAnswer>>()
        client { configuration, path, body, _ ->
            assertSame(config, configuration)
            if (path.endsWith("/cancel")) { assertEquals("/v1/generations/request-1/cancel", path); ownerCancel.countDown(); GenerationHttpResponse(204, byteArrayOf(), null) }
            else { started.countDown(); release.await(3, TimeUnit.SECONDS); response(answer(StrictJson.parse(body!!).jsonObject())) }
        }.use {
            val cancellation = it.submitAnswer(request, context()) { value -> callbacks.incrementAndGet(); pending.complete(value) }
            assertTrue(started.await(3, TimeUnit.SECONDS)); cancellation.cancel()
            assertEquals(GenerationFailureKind.CANCELLED, failure(pending.get(1, TimeUnit.SECONDS)))
            assertTrue(ownerCancel.await(3, TimeUnit.SECONDS)); release.countDown()
            it.close(); assertEquals(1, callbacks.get())
        }
    }
    @Test fun summaryInterruptionDetachesLocallyAndSameRequestCanRetry() {
        val work = SummaryWork("summary-1", "session-1", "turn-1", 3, 2, hash, 1,
            SummaryInput("합성 발화", emptyList(), emptyList()), emptyMap(), checkCurrent = { SummaryWorkStatus.CURRENT })
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstReturning = CountDownLatch(1)
        val requestCount = AtomicInteger()
        val serverCancelCount = AtomicInteger()
        val firstCallbacks = AtomicInteger()
        val firstResult = CompletableFuture<SummaryGenerationOutcome>()
        val firstExchange = CompletableFuture<GenerationExchange>()
        val requestBodies = java.util.Collections.synchronizedList(arrayListOf<ByteArray>())
        client { _, path, body, exchange ->
            if (path.endsWith("/cancel")) {
                serverCancelCount.incrementAndGet()
                GenerationHttpResponse(204, byteArrayOf(), null)
            } else {
                val received = StrictJson.parse(body!!).jsonObject()
                requestBodies += body.copyOf()
                if (requestCount.incrementAndGet() == 1) {
                    firstExchange.complete(exchange)
                    firstStarted.countDown()
                    releaseFirst.await(3, TimeUnit.SECONDS)
                    firstReturning.countDown()
                }
                response(received.filterKeys { it !in setOf("inputHash", "payload") } + mapOf("status" to "ok",
                    "metadata" to answer(received)["metadata"], "result" to mapOf("summaryText" to "합성 요약", "userClaims" to emptyList<String>(),
                        "verifiedFactRevisionIds" to emptyList<String>(), "unresolvedQuestions" to emptyList<String>(),
                        "completedResponseKeys" to emptyList<String>(), "partialUnknownKeys" to emptyList<String>())))
            }
        }.use { client ->
            val provider = GenerationSummaryProvider(client)
            val cancellation = provider.generate(work) { value -> firstCallbacks.incrementAndGet(); firstResult.complete(value) }
            assertTrue(firstStarted.await(3, TimeUnit.SECONDS))
            cancellation.cancel()
            val interrupted = firstResult.get(1, TimeUnit.SECONDS) as SummaryGenerationOutcome.Failure
            assertEquals(SummaryFailureKind.RETRYABLE, interrupted.failure.kind)
            assertTrue(firstExchange.get().cancelled.get())
            val retry = CompletableFuture<SummaryGenerationOutcome>()
            provider.generate(work) { retry.complete(it) }
            assertTrue(retry.get(3, TimeUnit.SECONDS) is SummaryGenerationOutcome.Success)
            assertEquals(2, requestCount.get())
            assertArrayEquals(requestBodies[0], requestBodies[1])
            releaseFirst.countDown()
            assertTrue(firstReturning.await(3, TimeUnit.SECONDS))
            assertEquals(1, firstCallbacks.get())
            assertEquals(0, serverCancelCount.get())
        }
    }
    @Test fun serverCancelledSummaryIsRetryableInsteadOfPermanentlyBlocked() {
        val work = SummaryWork("summary-1", "session-1", "turn-1", 3, 2, hash, 1,
            SummaryInput("합성 발화", emptyList(), emptyList()), emptyMap(), checkCurrent = { SummaryWorkStatus.CURRENT })
        client { _, _, _, _ -> response(mapOf("schemaVersion" to "0.8", "requestId" to "summary-1", "status" to "error",
            "code" to "CANCELLED", "retryable" to false, "retryAfterMs" to null), 409) }.use { client ->
            val future = CompletableFuture<SummaryGenerationOutcome>()
            GenerationSummaryProvider(client).generate(work) { future.complete(it) }
            val failure = future.get(3, TimeUnit.SECONDS) as SummaryGenerationOutcome.Failure
            assertEquals(SummaryFailureKind.RETRYABLE, failure.failure.kind)
            assertEquals("CANCELLED", failure.failure.code)
        }
    }
    @Test fun summaryRequiresExactCompletedAndPartialKeysAndEvidence() {
        val work = SummaryWork("summary-1", "session-1", "turn-1", 3, 2, hash, 1,
            SummaryInput("합성 발화", listOf(SummaryResponseParagraph("answer-1", "합성 완료답변", listOf("source-1"))), listOf("partial-1")),
            mapOf("fact-1" to setOf("source-1")), checkCurrent = { SummaryWorkStatus.CURRENT })
        listOf(false, true).forEach { wrong ->
            client { _, _, body, _ ->
                val received = StrictJson.parse(body!!).jsonObject()
                response(received.filterKeys { it !in setOf("inputHash", "payload") } + mapOf("status" to "ok",
                    "metadata" to answer(received)["metadata"], "result" to mapOf("summaryText" to "합성 요약", "userClaims" to listOf("합성 사용자 주장"),
                        "verifiedFactRevisionIds" to listOf("fact-1"), "unresolvedQuestions" to emptyList<String>(),
                        "completedResponseKeys" to listOf(if (wrong) "unplayed-answer" else "answer-1"), "partialUnknownKeys" to listOf("partial-1"))))
            }.use {
                val future = CompletableFuture<GenerationOutcome<ValidatedSummaryResult>>()
                it.submitSummary(work) { value -> future.complete(value) }
                val outcome = future.get(3, TimeUnit.SECONDS)
                if (wrong) assertEquals(GenerationFailureKind.INVALID_RESPONSE, failure(outcome))
                else { assertEquals("test-model", (outcome as GenerationOutcome.Success).value.modelId); assertNull(outcome.value.inputTokens) }
            }
        }
    }
    private fun summaryWork(checkCurrent: () -> SummaryWorkStatus) = SummaryWork(
        "summary-1", "session-1", "turn-1", 3, 2, hash, 1,
        SummaryInput("합성 발화", emptyList(), emptyList()), emptyMap(), checkCurrent = checkCurrent)

    private fun summaryResult(client: GenerationClient, work: SummaryWork): GenerationOutcome<ValidatedSummaryResult> {
        val future = CompletableFuture<GenerationOutcome<ValidatedSummaryResult>>()
        client.submitSummary(work) { future.complete(it) }
        return future.get(3, TimeUnit.SECONDS)
    }

    @Test fun unguardedSummaryWorkCannotLeaveDevice() {
        val work = SummaryWork("summary-1", "session-1", "turn-1", 3, 2, hash, 1,
            SummaryInput("합성 발화", emptyList(), emptyList()), emptyMap())
        val calls = AtomicInteger()
        client { _, _, _, _ -> calls.incrementAndGet(); throw AssertionError("Missing authorization guard") }.use {
            assertEquals(GenerationFailureKind.LOCAL_STORAGE, failure(summaryResult(it, work)))
            assertEquals(0, calls.get())
        }
    }

    @Test fun summaryRevokedBetweenPreparationAndSendingNeverCallsTransport() {
        val status = AtomicReference(SummaryWorkStatus.CURRENT)
        val calls = AtomicInteger()
        GenerationClient(GenerationConfigurationSource { status.set(SummaryWorkStatus.EVIDENCE_BLOCKED); config },
            GenerationInputHasher { hash }, GenerationTransport { _, _, _, _ ->
                calls.incrementAndGet(); throw AssertionError("Revoked summary context")
            }).use {
            assertEquals(GenerationFailureKind.EVIDENCE_BLOCKED, failure(summaryResult(it, summaryWork { status.get() })))
            assertEquals(0, calls.get())
        }
    }

    @Test fun summaryRightsRecheckedBeforeAcceptingSuccessOrErrorResponses() {
        listOf(200, 503).forEach { httpStatus ->
            val status = AtomicReference(SummaryWorkStatus.CURRENT)
            client { _, _, _, _ ->
                status.set(SummaryWorkStatus.EVIDENCE_BLOCKED)
                // Neither a success nor a retryable upstream error may override current local rights.
                GenerationHttpResponse(httpStatus, "untrusted response".toByteArray(), null)
            }.use {
                assertEquals(GenerationFailureKind.EVIDENCE_BLOCKED, failure(summaryResult(it, summaryWork { status.get() })))
            }
        }
    }

    @Test fun summaryEvidenceDenialAndLocalStorageFailureKeepDistinctRetryPolicies() {
        val cases: List<Triple<() -> SummaryWorkStatus, SummaryFailureKind, String>> = listOf(
            Triple({ SummaryWorkStatus.EVIDENCE_BLOCKED }, SummaryFailureKind.EVIDENCE_BLOCKED, "SUMMARY_EVIDENCE_BLOCKED"),
            Triple({ SummaryWorkStatus.PAYLOAD_UNAVAILABLE }, SummaryFailureKind.PAYLOAD_UNAVAILABLE, "SUMMARY_PAYLOAD_UNAVAILABLE"),
            Triple({ SummaryWorkStatus.STORAGE_UNAVAILABLE }, SummaryFailureKind.RETRYABLE, "STORAGE_UNAVAILABLE"),
            Triple({ throw IllegalStateException("synthetic private error") }, SummaryFailureKind.RETRYABLE, "STORAGE_UNAVAILABLE"),
            Triple({ SummaryWorkStatus.STALE }, SummaryFailureKind.PERMANENT, "STALE"),
        )
        cases.forEach { (guard, kind, code) ->
            client { _, _, _, _ -> throw AssertionError("Ineligible summary") }.use { client ->
                val future = CompletableFuture<SummaryGenerationOutcome>()
                GenerationSummaryProvider(client).generate(summaryWork(guard)) { future.complete(it) }
                val outcome = future.get(3, TimeUnit.SECONDS) as SummaryGenerationOutcome.Failure
                assertEquals(kind, outcome.failure.kind)
                assertEquals(code, outcome.failure.code)
            }
        }
    }

    @Test fun redactedDiagnosticObjectsNeverExposePlaintextOrCredentials() {
        assertFalse(config.toString().contains(config.bearerToken))
        assertFalse(context().toString().contains("합성"))
        assertFalse(GenerationHttpResponse(200, "private".toByteArray(), null).toString().contains("private"))
    }
}
