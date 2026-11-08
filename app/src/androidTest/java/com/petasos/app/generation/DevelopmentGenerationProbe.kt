package com.petasos.app.generation

import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import com.petasos.app.platform.android.summary.SummaryBackground
import com.petasos.app.playback.OperationToken
import com.petasos.app.runtime.RuntimeGenerationRequest
import com.petasos.app.runtime.RuntimeText
import com.petasos.app.summary.SummaryInput
import com.petasos.app.summary.SummaryResponseParagraph
import com.petasos.app.summary.SummaryWork
import com.petasos.app.summary.SummaryWorkStatus
import com.petasos.app.summary.ValidatedSummaryResult
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Explicit developer invocation only. No exported receiver, embedded secret, microphone or transcript log. */
object DevelopmentGenerationProbe {
    private const val TOKEN_FILE = "development-generation-token"
    private const val TEST_TEXT = "실제 API 연결을 확인하는 개발 시험입니다. 짧게 인사해 주세요."

    /** The host transfers the token on stdin into app-private cache. Persist only Keystore ciphertext. */
    fun provision(instrumentation: Instrumentation, mark: (String) -> Unit, fixturePort: Int) {
        val context = instrumentation.targetContext
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        require(fixturePort in 1..65535)
        val tokenFile = File(context.cacheDir, TOKEN_FILE)
        var tokenBytes: ByteArray? = null
        try {
            check(tokenFile.isFile && tokenFile.canonicalPath == tokenFile.absolutePath)
            check(tokenFile.length() in 32..4096)
            val bytes = tokenFile.readBytes()
            tokenBytes = bytes
            val configuration = DeviceGenerationConfiguration("https://localhost:$fixturePort", bytes.toString(Charsets.US_ASCII).trim())
            val store = DeviceGenerationConfigStore(context)
            store.save(configuration)
            val persisted = store.load()
            check(persisted != null)
            check(persisted.serverOrigin == configuration.serverOrigin && persisted.bearerToken == configuration.bearerToken)
            mark("development_generation_configuration_encrypted")
        } finally {
            tokenBytes?.fill(0)
            check(!tokenFile.exists() || tokenFile.delete())
        }
        mark("development_generation_plaintext_token_deleted")
        // This is an actual product configuration change; retry only configuration-blocked work.
        SummaryBackground.configurationChanged(context)
        mark("development_generation_configuration_change_reschedule_requested")
        mark("development_generation_provision_complete")
    }

    /** The unmodified production transport uses Android's debug localhost CA and normal hostname verification. */
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(!File(context.cacheDir, TOKEN_FILE).exists())
        val store = DeviceGenerationConfigStore(context)
        val configuration = store.load() ?: throw GenerationException(GenerationFailureKind.CONFIGURATION)
        check(URI(configuration.serverOrigin).host == "localhost")
        val id = UUID.randomUUID().toString()
        val session = "android-live-$id"
        val turn = "android-live-turn-$id"
        val request = RuntimeGenerationRequest(OperationToken(session, 1, id), turn, 1, RuntimeText(TEST_TEXT))
        val answerContext = AnswerGenerationContext(1, null, "", emptyList(), emptyList(), emptyMap(),
            mapOf("continue" to setOf(null), "unsupported" to setOf(null)), { true })
        GenerationClient(store, store).use { client ->
            val first = success(await<ValidatedGenerationAnswer> { client.submitAnswer(request, answerContext, it) }, "answer", mark)
            check(first.paragraphs.isNotEmpty() && first.paragraphs.all { it.sourceRevisionIds.isEmpty() })
            mark("development_generation_platform_https_answer_validated")
            val retry = success(await<ValidatedGenerationAnswer> { client.submitAnswer(request, answerContext, it) }, "replay", mark)
            check(first.action == retry.action && first.targetContentId == retry.targetContentId)
            check(first.paragraphs.map { it.key to it.text.value } == retry.paragraphs.map { it.key to it.text.value })
            mark("development_generation_same_request_replay_equal")
            // This list is test input for API-05, not a durable claim that a user heard speech.
            val work = SummaryWork(UUID.randomUUID().toString(), session, turn, 1, 1,
                store.hash("nonpersonal-android-live-summary".toByteArray()), 1,
                SummaryInput(TEST_TEXT, first.paragraphs.map { SummaryResponseParagraph(it.key, it.text.value, it.sourceRevisionIds) }, emptyList()),
                emptyMap(), { SummaryWorkStatus.CURRENT })
            val summary = success(await<ValidatedSummaryResult> { client.submitSummary(work, it) }, "summary", mark)
            check(summary.completedResponseKeys.toSet() == first.paragraphs.map { it.key }.toSet())
            check(summary.partialUnknownKeys.isEmpty())
            mark("development_generation_platform_https_summary_validated")
        }
        mark("development_generation_live_probe_complete")
    }

    private fun <T> success(outcome: GenerationOutcome<T>, task: String, mark: (String) -> Unit): T = when (outcome) {
        is GenerationOutcome.Success -> outcome.value
        is GenerationOutcome.Failure -> {
            mark("development_generation_${task}_failed_${outcome.failure.kind.name.lowercase()}")
            throw GenerationException(outcome.failure.kind)
        }
    }

    private fun <T> await(start: ((GenerationOutcome<T>) -> Unit) -> Any?): GenerationOutcome<T> {
        val future = CompletableFuture<GenerationOutcome<T>>()
        start { future.complete(it) }
        return future.get(35, TimeUnit.SECONDS)
    }
}
