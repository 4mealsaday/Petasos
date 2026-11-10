package com.petasos.app.generation

import com.petasos.app.playback.OperationToken
import com.petasos.app.runtime.*
import com.petasos.app.summary.*
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

/** HTTPS API-01..08 boundary. The model never supplies identity, source authority or executable actions. */
class GenerationClient internal constructor(
    private val configurations: GenerationConfigurationSource,
    private val inputHasher: GenerationInputHasher,
    private val transport: GenerationTransport,
) : AutoCloseable {
    constructor(configurations: GenerationConfigurationSource, inputHasher: GenerationInputHasher) :
        this(configurations, inputHasher, HttpsGenerationTransport())

    private val schema = GenerationSchema()
    private val executor = Executors.newFixedThreadPool(3) { runnable -> Thread(runnable, "petasos-generation").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "petasos-generation-timeout").apply { isDaemon = true } }
    private val active = ConcurrentHashMap.newKeySet<Call<*>>()
    private val closed = AtomicBoolean(false)
    private val hashes = linkedMapOf<String, Pair<ByteArray, String>>()

    fun submitStory(
        request: OperationToken,
        context: StoryGenerationContext,
        callback: (GenerationOutcome<ValidatedGenerationStory>) -> Unit,
    ): RuntimeCancellation = prepare(callback) {
        requirePlanningCurrent(context.isCurrent)
        val payload = mapOf("storyId" to context.storyId, "topicId" to context.topicId, "regionId" to context.regionId,
            "sourceRevisionIds" to context.sourceRevisionIds, "excludedFactIds" to context.excludedFactIds,
            "interestTags" to context.interestTags)
        val wire = planningRequest(request, context.routeRevision, "story", payload)
        submit(wire, 15_000, notifyServerCancellation = true, callback,
            beforeSend = { requirePlanningCurrent(context.isCurrent) },
            beforeAccept = { requirePlanningCurrent(context.isCurrent) }) { response ->
            val result = response["result"].jsonObject()
            if (result.text("storyId") != context.storyId) invalid()
            val paragraphs = (result["paragraphs"] as List<*>).map { item ->
                val paragraph = item.jsonObject()
                val sources = paragraph.strings("sourceRevisionIds")
                val facts = paragraph.strings("factRevisionIds")
                val kind = paragraph.text("kind")
                if (!context.sourceRevisionIds.containsAll(sources)) invalid()
                when (kind) {
                    "fact", "tradition" -> {
                        if (sources.isEmpty() || facts.isEmpty()) invalid()
                        facts.forEach { fact ->
                            val requiredSources = context.approvedFactSources[fact]
                            val stableId = context.approvedFactStableIds[fact]
                            if (requiredSources.isNullOrEmpty() || !sources.containsAll(requiredSources) ||
                                context.approvedFactKinds[fact] != kind || stableId.isNullOrBlank() ||
                                stableId in context.excludedFactIds) invalid()
                        }
                    }
                    // The current public-story store supports reviewed fact/tradition paragraphs.
                    // Neither personal content nor an ungrounded transition may be installed.
                    else -> invalid()
                }
                if (paragraph.text("text").isBlank()) invalid()
                ValidatedStoryParagraph(paragraph.text("paragraphKey"), RuntimeText(paragraph.text("text")), kind, sources, facts)
            }
            if (paragraphs.map { it.key }.distinct().size != paragraphs.size) invalid()
            val provenance = response["metadata"].jsonObject()
            val story = ValidatedGenerationStory(result.text("storyId"), result.text("storyVersionId"), paragraphs,
                result["estimatedDurationMs"] as Long, provenance.text("modelId"), provenance.text("provider"),
                provenance.text("promptVersion"), provenance["inputTokens"] as? Long, provenance["outputTokens"] as? Long,
                wire.text("inputHash"))
            requirePlanningCurrent(context.isCurrent)
            story
        }
    }

    fun submitResume(
        request: OperationToken,
        context: ResumeGenerationContext,
        callback: (GenerationOutcome<ValidatedResumePlan>) -> Unit,
    ): RuntimeCancellation = prepare(callback) {
        requirePlanningCurrent(context.isCurrent)
        // The wire result omits kind. Reject duplicate pairs, including story/music collisions,
        // so an accepted pair identifies exactly one full candidate tuple.
        if (context.candidates.map { it.contentId to it.paragraphId }.distinct().size != context.candidates.size) {
            throw GenerationException(GenerationFailureKind.INVALID_REQUEST)
        }
        val payload = mapOf("contextSummary" to context.contextSummary,
            "candidates" to context.candidates.map { mapOf("contentId" to it.contentId, "paragraphId" to it.paragraphId, "kind" to it.kind) })
        val wire = planningRequest(request, context.routeRevision, "resume", payload)
        submit(wire, 3_000, notifyServerCancellation = true, callback,
            beforeSend = { requirePlanningCurrent(context.isCurrent) },
            beforeAccept = { requirePlanningCurrent(context.isCurrent) }) { response ->
            val result = response["result"].jsonObject()
            val action = result.text("action")
            val target = result["targetContentId"] as? String
            val paragraph = result["paragraphId"] as? String
            if (action == "wait") {
                if (target != null || paragraph != null) invalid()
            } else if (target == null || context.candidates.none { it.contentId == target && it.paragraphId == paragraph }) invalid()
            val transition = (result["transitionText"] as? String)?.let {
                if (it.isBlank()) invalid()
                RuntimeText(it)
            }
            if (result.text("reason").isBlank()) invalid()
            val provenance = response["metadata"].jsonObject()
            val plan = ValidatedResumePlan(action, target, paragraph, transition, result.text("reason"),
                provenance.text("modelId"), provenance.text("provider"), provenance.text("promptVersion"),
                provenance["inputTokens"] as? Long, provenance["outputTokens"] as? Long, wire.text("inputHash"))
            requirePlanningCurrent(context.isCurrent)
            plan
        }
    }

    private fun planningRequest(request: OperationToken, route: Long?, task: String, payload: Map<String, Any?>): Map<String, Any?> {
        val unsigned = metadata(request.id, request.sessionId, request.cancelEpoch, route, null, null, task) + ("payload" to payload)
        val canonical = StrictJson.encode(unsigned)
        val hash = try { stableHash("${request.sessionId.length}:${request.sessionId}${request.id}", canonical) }
            finally { canonical.fill(0) }
        return unsigned + ("inputHash" to hash)
    }

    private fun requirePlanningCurrent(check: () -> Boolean) {
        val current = try { check() } catch (_: Exception) { throw GenerationException(GenerationFailureKind.LOCAL_STORAGE) }
        if (!current) throw GenerationException(GenerationFailureKind.STALE)
    }

    fun submitAnswer(
        request: RuntimeGenerationRequest,
        context: AnswerGenerationContext,
        callback: (GenerationOutcome<ValidatedGenerationAnswer>) -> Unit,
    ): RuntimeCancellation = prepare(callback) {
        if (!context.isCurrent()) throw GenerationException(GenerationFailureKind.STALE)
        val metadata = metadata(request.request.id, request.request.sessionId, request.request.cancelEpoch,
            context.routeRevision, request.turnId, context.turnVersion, "answer")
        val payload = mapOf("userText" to request.utterance.value, "contextSummary" to context.contextSummary,
            "heardParagraphIds" to context.heardParagraphIds, "sourceRevisionIds" to context.sourceRevisionIds)
        val unsigned = metadata + ("payload" to payload)
        val canonical = StrictJson.encode(unsigned)
        val hash = try { stableHash("${request.request.sessionId.length}:${request.request.sessionId}${request.request.id}", canonical) } finally { canonical.fill(0) }
        val wire = unsigned + ("inputHash" to hash)
        submit(wire, 15_000, notifyServerCancellation = true, callback, beforeSend = {
            if (!context.isCurrent()) throw GenerationException(GenerationFailureKind.STALE)
        }) { response ->
            if (!context.isCurrent()) throw GenerationException(GenerationFailureKind.STALE)
            val result = response["result"].jsonObject()
            val action = result.text("action")
            val target = result["targetContentId"] as? String
            if (context.allowedActions[action]?.contains(target) != true) invalid()
            val paragraphs = (result["paragraphs"] as List<*>).map { item ->
                val paragraph = item.jsonObject()
                val sources = paragraph.strings("sourceRevisionIds")
                val facts = paragraph.strings("factRevisionIds")
                if (!context.sourceRevisionIds.containsAll(sources)) invalid()
                when (paragraph.text("kind")) {
                    "fact", "tradition" -> {
                        if (sources.isEmpty() || facts.isEmpty()) invalid()
                        if (facts.any { fact -> context.approvedFactSources[fact]?.let { it.isNotEmpty() && sources.containsAll(it) } != true }) invalid()
                        if (facts.any { context.approvedFactKinds[it] != paragraph.text("kind") }) invalid()
                    }
                    "personal", "transition" -> if (facts.isNotEmpty()) invalid()
                    else -> invalid()
                }
                if (paragraph.text("text").isBlank()) invalid()
                RuntimeParagraph(paragraph.text("paragraphKey"), RuntimeText(paragraph.text("text")), sources)
            }
            if (paragraphs.map { it.key }.distinct().size != paragraphs.size) invalid()
            ValidatedGenerationAnswer(paragraphs, action, target)
        }
    }

    fun submitSummary(work: SummaryWork, callback: (GenerationOutcome<ValidatedSummaryResult>) -> Unit): RuntimeCancellation = prepare(callback) {
        requireSummaryCurrent(work)
        val complete = work.input.completedResponses
        if (complete.map { it.key }.distinct().size != complete.size || complete.any { it.key in work.input.partialUnknownKeys }) invalid()
        val wire = metadata(work.requestId, work.sessionId, work.cancelEpoch, null, work.turnId, work.turnVersion, "summary") + mapOf(
            "inputHash" to work.inputHash,
            "payload" to mapOf("userText" to work.input.userText,
                "completedResponses" to complete.map { mapOf("paragraphKey" to it.key, "text" to it.text, "sourceRevisionIds" to it.sourceRevisionIds) },
                "partialUnknownKeys" to work.input.partialUnknownKeys),
        )
        submit(wire, 30_000, notifyServerCancellation = false, callback,
            beforeSend = { requireSummaryCurrent(work) }, beforeAccept = { requireSummaryCurrent(work) }) { response ->
            val result = response["result"].jsonObject()
            val completedKeys = result.strings("completedResponseKeys")
            val partial = result.strings("partialUnknownKeys")
            if (completedKeys.toSet() != complete.map { it.key }.toSet() || partial.toSet() != work.input.partialUnknownKeys.toSet()) invalid()
            val availableSources = complete.flatMap { it.sourceRevisionIds }.toSet()
            val facts = result.strings("verifiedFactRevisionIds")
            if (facts.any { fact -> work.approvedFactSources[fact]?.let { it.isNotEmpty() && availableSources.containsAll(it) } != true }) invalid()
            if (result.text("summaryText").isBlank()) invalid()
            val provenance = response["metadata"].jsonObject()
            ValidatedSummaryResult(result.text("summaryText"), result.strings("userClaims"), facts,
                result.strings("unresolvedQuestions"), completedKeys, partial,
                provenance.text("modelId"), provenance.text("provider"), provenance.text("promptVersion"),
                provenance["inputTokens"] as? Long, provenance["outputTokens"] as? Long)
        }
    }

    private fun requireSummaryCurrent(work: SummaryWork) {
        val status = try { work.checkCurrent() } catch (_: Exception) { SummaryWorkStatus.STORAGE_UNAVAILABLE }
        val failure = when (status) {
            SummaryWorkStatus.CURRENT -> return
            SummaryWorkStatus.STALE -> GenerationFailureKind.STALE
            SummaryWorkStatus.EVIDENCE_BLOCKED -> GenerationFailureKind.EVIDENCE_BLOCKED
            SummaryWorkStatus.PAYLOAD_UNAVAILABLE -> GenerationFailureKind.LOCAL_PAYLOAD
            SummaryWorkStatus.STORAGE_UNAVAILABLE -> GenerationFailureKind.LOCAL_STORAGE
        }
        throw GenerationException(failure)
    }

    private fun <T> prepare(callback: (GenerationOutcome<T>) -> Unit, block: () -> RuntimeCancellation): RuntimeCancellation = try {
        block()
    } catch (failure: GenerationException) {
        callback(GenerationOutcome.Failure(GenerationFailure(failure.kind))); RuntimeCancellation { }
    } catch (_: Exception) {
        callback(GenerationOutcome.Failure(GenerationFailure(GenerationFailureKind.CONFIGURATION))); RuntimeCancellation { }
    }

    private fun <T> submit(
        request: Map<String, Any?>, timeoutMs: Long, notifyServerCancellation: Boolean, callback: (GenerationOutcome<T>) -> Unit,
        beforeSend: () -> Unit = {},
        beforeAccept: () -> Unit = {},
        decode: (Map<String, Any?>) -> T,
    ): RuntimeCancellation {
        // Serialization/reparse normalizes Int/Long without allowing implicit JSON coercion.
        val body = StrictJson.encode(request)
        val normalized = StrictJson.parse(body).jsonObject()
        try { schema.validate("Request", normalized) }
        catch (_: GenerationException) { body.fill(0); throw GenerationException(GenerationFailureKind.INVALID_REQUEST) }
        val configuration = try { configurations.load() ?: throw GenerationException(GenerationFailureKind.CONFIGURATION) }
        catch (failure: Exception) { body.fill(0); throw failure }
        val call = Call(configuration, normalized.text("requestId"), notifyServerCancellation, callback)
        synchronized(active) {
            if (closed.get()) { body.fill(0); throw GenerationException(GenerationFailureKind.CANCELLED) }
            if (active.size >= 2) { body.fill(0); throw GenerationException(GenerationFailureKind.RETRYABLE) }
            active += call
            call.deadline = timer.schedule({
                call.finish(GenerationOutcome.Failure(GenerationFailure(GenerationFailureKind.RETRYABLE))) {
                    call.exchange.cancelled.set(true)
                    executor.execute { call.exchange.cancel() }
                }
            }, timeoutMs, TimeUnit.MILLISECONDS)
            executor.execute {
                try {
                    if (call.finished.get()) return@execute
                    // A queued request may outlive its turn or source permission. Check on the
                    // sending worker immediately before any utterance/context leaves the device.
                    beforeSend()
                    if (call.finished.get()) return@execute
                    val response = transport.execute(configuration, "/v1/generations", body, call.exchange)
                    try {
                        if (call.finished.get()) return@execute
                        beforeAccept()
                        val parsed = StrictJson.parse(response.body).jsonObject()
                        if (response.status == 200) {
                            schema.validate("SuccessResponse", parsed)
                            correlate(normalized, parsed)
                            call.finish(GenerationOutcome.Success(decode(parsed)))
                        } else {
                            schema.validate("ErrorResponse", parsed)
                            if (parsed["requestId"] != null && parsed["requestId"] != normalized["requestId"]) invalid()
                            call.finish(GenerationOutcome.Failure(error(response, parsed)))
                        }
                    } finally { response.body.fill(0) }
                } catch (failure: GenerationException) {
                    call.finish(GenerationOutcome.Failure(GenerationFailure(failure.kind)))
                } catch (_: SSLException) {
                    call.finish(GenerationOutcome.Failure(GenerationFailure(GenerationFailureKind.CONFIGURATION)))
                } catch (_: IOException) {
                    call.finish(GenerationOutcome.Failure(GenerationFailure(GenerationFailureKind.RETRYABLE)))
                } catch (_: Exception) {
                    call.finish(GenerationOutcome.Failure(GenerationFailure(GenerationFailureKind.INVALID_RESPONSE)))
                } finally { body.fill(0) }
            }
        }
        return RuntimeCancellation { cancel(call) }
    }

    private inner class Call<T>(
        val configuration: DeviceGenerationConfiguration, val requestId: String,
        val notifyServerCancellation: Boolean,
        val callback: (GenerationOutcome<T>) -> Unit,
    ) {
        val finished = AtomicBoolean(false)
        val exchange = GenerationExchange()
        @Volatile var deadline: ScheduledFuture<*>? = null
        fun finish(outcome: GenerationOutcome<T>, beforeCallback: () -> Unit = {}): Boolean {
            synchronized(active) {
                if (!finished.compareAndSet(false, true)) return false
                deadline?.cancel(false)
                active.remove(this)
                beforeCallback()
            }
            callback(outcome)
            return true
        }
    }

    private fun cancel(call: Call<*>) {
        call.finish(GenerationOutcome.Failure(GenerationFailure(GenerationFailureKind.CANCELLED))) {
            // Invalidate locally before touching the network; owner cancel is best effort, never an ACK.
            call.exchange.cancelled.set(true)
            executor.execute {
                call.exchange.cancel()
                // WorkManager may interrupt a summary for scheduling constraints. Detach that HTTP
                // attempt without cancelling the durable requestId, so its next attempt can join or
                // reuse the server result. Only an actual logical answer cancellation marks the server.
                if (call.notifyServerCancellation) try {
                    transport.execute(call.configuration, "/v1/generations/${call.requestId}/cancel", null, GenerationExchange()).body.fill(0)
                } catch (_: Exception) { /* Local cancellation remains authoritative. */ }
            }
        }
    }

    override fun close() {
        synchronized(active) {
            if (!closed.compareAndSet(false, true)) return
            active.toList().forEach { cancel(it) }
            timer.shutdownNow()
            executor.shutdown()
        }
        synchronized(hashes) { hashes.values.forEach { it.first.fill(0) }; hashes.clear() }
    }

    private fun stableHash(key: String, canonical: ByteArray): String = synchronized(hashes) {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical)
        hashes[key]?.let { (original, hash) ->
            if (!MessageDigest.isEqual(original, digest)) { digest.fill(0); throw GenerationException(GenerationFailureKind.CONFLICT) }
            digest.fill(0); return@synchronized hash
        }
        val hash = inputHasher.hash(canonical)
        if (!hash.matches(Regex("[a-f0-9]{64}"))) { digest.fill(0); throw GenerationException(GenerationFailureKind.CONFIGURATION) }
        hashes[key] = digest to hash
        if (hashes.size > 64) { val first = hashes.keys.first(); hashes.remove(first)?.first?.fill(0) }
        hash
    }

    private fun correlate(request: Map<String, Any?>, response: Map<String, Any?>) {
        listOf("schemaVersion", "requestId", "sessionId", "cancelEpoch", "routeDependency", "turnId", "turnVersion", "task").forEach {
            if (request[it] != response[it]) throw GenerationException(GenerationFailureKind.STALE)
        }
    }

    private fun error(http: GenerationHttpResponse, json: Map<String, Any?>): GenerationFailure {
        val code = json.text("code")
        val expectedStatuses = when (code) {
            "INVALID_REQUEST", "UNSUPPORTED_SCHEMA" -> setOf(400)
            "UNAUTHORIZED" -> setOf(401)
            "FORBIDDEN" -> setOf(403)
            "NOT_FOUND" -> setOf(404)
            "IDEMPOTENCY_CONFLICT", "STALE_CONTEXT", "CANCELLED" -> setOf(409)
            "NO_EVIDENCE", "INVALID_MODEL_OUTPUT" -> setOf(422)
            "RATE_LIMITED" -> setOf(429)
            "UPSTREAM_UNAVAILABLE" -> setOf(502, 503, 504)
            "GENERATION_TIMEOUT" -> setOf(504)
            else -> emptySet()
        }
        if (http.status !in expectedStatuses) invalid()
        val canRetry = json["retryable"] == true && code in setOf("RATE_LIMITED", "UPSTREAM_UNAVAILABLE", "GENERATION_TIMEOUT")
        val kind = when {
            canRetry -> GenerationFailureKind.RETRYABLE
            code in setOf("UNAUTHORIZED", "FORBIDDEN") -> GenerationFailureKind.AUTHORIZATION
            code in setOf("INVALID_REQUEST", "UNSUPPORTED_SCHEMA") -> GenerationFailureKind.INVALID_REQUEST
            code == "IDEMPOTENCY_CONFLICT" -> GenerationFailureKind.CONFLICT
            code == "STALE_CONTEXT" -> GenerationFailureKind.STALE
            code == "NO_EVIDENCE" -> GenerationFailureKind.NO_EVIDENCE
            code == "INVALID_MODEL_OUTPUT" -> GenerationFailureKind.INVALID_RESPONSE
            code == "CANCELLED" -> GenerationFailureKind.CANCELLED
            else -> GenerationFailureKind.PERMANENT
        }
        val headerMs = http.retryAfterSeconds?.let { if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000 } ?: 0
        return GenerationFailure(kind, maxOf(json["retryAfterMs"] as? Long ?: 0, headerMs))
    }

    private fun metadata(requestId: String, sessionId: String, epoch: Long, route: Long?, turnId: String?, turnVersion: Long?, task: String) =
        mapOf("schemaVersion" to "0.8", "requestId" to requestId, "sessionId" to sessionId, "cancelEpoch" to epoch,
            "routeDependency" to route?.let { mapOf("routeRevision" to it) }, "turnId" to turnId, "turnVersion" to turnVersion, "task" to task)

    private fun invalid(): Nothing = throw GenerationException(GenerationFailureKind.INVALID_RESPONSE)
}

class GenerationSummaryProvider(private val client: GenerationClient) : SummaryGenerator {
    override fun generate(work: SummaryWork, callback: (SummaryGenerationOutcome) -> Unit): RuntimeCancellation =
        client.submitSummary(work) { outcome -> callback(when (outcome) {
            is GenerationOutcome.Success -> SummaryGenerationOutcome.Success(outcome.value)
            is GenerationOutcome.Failure -> {
                val failure = outcome.failure
                val kind = when (failure.kind) {
                    GenerationFailureKind.RETRYABLE, GenerationFailureKind.CANCELLED, GenerationFailureKind.LOCAL_STORAGE -> SummaryFailureKind.RETRYABLE
                    GenerationFailureKind.EVIDENCE_BLOCKED -> SummaryFailureKind.EVIDENCE_BLOCKED
                    GenerationFailureKind.LOCAL_PAYLOAD -> SummaryFailureKind.PAYLOAD_UNAVAILABLE
                    GenerationFailureKind.AUTHORIZATION -> SummaryFailureKind.AUTHORIZATION
                    GenerationFailureKind.CONFIGURATION -> SummaryFailureKind.CONFIGURATION
                    GenerationFailureKind.INVALID_RESPONSE, GenerationFailureKind.INVALID_REQUEST -> SummaryFailureKind.INVALID_SCHEMA
                    else -> SummaryFailureKind.PERMANENT
                }
                val code = when (failure.kind) {
                    GenerationFailureKind.EVIDENCE_BLOCKED -> "SUMMARY_EVIDENCE_BLOCKED"
                    GenerationFailureKind.LOCAL_PAYLOAD -> "SUMMARY_PAYLOAD_UNAVAILABLE"
                    GenerationFailureKind.LOCAL_STORAGE -> "STORAGE_UNAVAILABLE"
                    else -> failure.kind.name
                }
                SummaryGenerationOutcome.Failure(SummaryFailure(kind, code, failure.retryAfterMs))
            }
        }) }
}

private fun debugLog(message: String) {
    if (com.petasos.app.BuildConfig.DEBUG) try { android.util.Log.i("PetasosTalk", message) } catch (_: RuntimeException) { }
}

class ApiRuntimeGeneration(
    private val client: GenerationClient,
    private val contextSupplier: (RuntimeGenerationRequest) -> AnswerGenerationContext?,
) : RuntimeGeneration {
    override fun generate(request: RuntimeGenerationRequest, callback: (RuntimeGenerationResult) -> Unit): RuntimeCancellation {
        val context = contextSupplier(request)
        if (context == null) { debugLog("answer context unavailable"); callback(RuntimeGenerationResult.Failure(false)); return RuntimeCancellation { } }
        val startedAt = System.nanoTime()
        return client.submitAnswer(request, context) { outcome ->
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            when (outcome) {
                is GenerationOutcome.Failure -> debugLog("answer failed after ${elapsedMs}ms: ${outcome.failure.kind}")
                is GenerationOutcome.Success -> debugLog("answer after ${elapsedMs}ms action=${outcome.value.action} " +
                    "target=${outcome.value.targetContentId} text=${outcome.value.paragraphs.joinToString(" ") { it.text.value }}")
            }
            callback(when (outcome) {
                is GenerationOutcome.Failure -> RuntimeGenerationResult.Failure(
                    outcome.failure.kind == GenerationFailureKind.RETRYABLE, outcome.failure.retryAfterMs,
                    // No device configuration, TLS trouble or an I/O error: the server is not reachable.
                    unreachable = outcome.failure.kind == GenerationFailureKind.CONFIGURATION || outcome.failure.kind == GenerationFailureKind.RETRYABLE)
                is GenerationOutcome.Success -> {
                    val answer = outcome.value
                    // Runtime's command-routing contract is not implemented yet. Never silently execute/ignore
                    // model-selected controls, or grant music/next/replay/stop authority through this adapter.
                    if (!context.isCurrent() || answer.action !in setOf("continue", "unsupported") || answer.targetContentId != null) {
                        RuntimeGenerationResult.Failure(false)
                    } else RuntimeGenerationResult.Success(RuntimeAnswer(answer.paragraphs))
                }
            })
        }
    }
}
