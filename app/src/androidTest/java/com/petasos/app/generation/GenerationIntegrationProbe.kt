package com.petasos.app.generation

import android.app.Instrumentation
import android.content.Context
import com.petasos.app.storage.SummaryStorageFixture
import com.petasos.app.storage.SyntheticStorageFixture
import com.petasos.app.summary.SummaryClaim
import com.petasos.app.playback.OperationToken
import com.petasos.app.runtime.RuntimeGenerationRequest
import com.petasos.app.runtime.RuntimeText
import com.petasos.app.summary.SummaryInput
import com.petasos.app.summary.SummaryResponseParagraph
import com.petasos.app.summary.SummaryWork
import com.petasos.app.summary.SummaryWorkStatus
import com.petasos.app.summary.ValidatedSummaryResult
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Disposable loopback fixture only. Real TLS/Keystore, synthetic provider; no live model or microphone. */
object GenerationIntegrationProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit, fixturePort: Int) {
        require(fixturePort in 1..65535)
        val context = instrumentation.targetContext
        val namespace = "generation-probe-${UUID.randomUUID()}"
        val configurationFile = File(context.noBackupFilesDir, "$namespace.bin")
        val caFile = File(context.cacheDir, "generation-probe-ca.pem")
        val tokenFile = File(context.cacheDir, "generation-probe-token")
        val store = DeviceGenerationConfigStore(context, namespace)
        try {
            check(store.load() == null)
            check(tokenFile.length() in 32..4096)
            val token = tokenFile.readText(Charsets.US_ASCII).trim()
            val configuration = DeviceGenerationConfiguration("https://localhost:$fixturePort", token)
            store.save(configuration)
            check(store.load()?.bearerToken == token)
            check(store.load()?.serverOrigin == configuration.serverOrigin)
            val bytes = configurationFile.readBytes()
            check(!bytes.toString(Charsets.ISO_8859_1).contains(token))
            check(!bytes.toString(Charsets.ISO_8859_1).contains(configuration.serverOrigin))
            val testInput = "synthetic-input-only".toByteArray()
            val hash = store.hash(testInput)
            check(hash.length == 64 && store.hash(testInput) == hash)
            mark("generation_config_encrypted_and_hmac_stable")

            val cert = caFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("one-day-test-ca", cert) }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            val tls = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
            // Test-only CA is scoped to this connection factory. Product trust and hostname checks stay intact.
            val transport = HttpsGenerationTransport { url ->
                (url.openConnection() as HttpsURLConnection).apply { sslSocketFactory = tls.socketFactory }
            }
            val id = "probe-${UUID.randomUUID()}"
            val request = RuntimeGenerationRequest(OperationToken("probe-session", 2, id), "probe-turn", 1, RuntimeText("합성 질문입니다."))
            val answerContext = AnswerGenerationContext(1, null, "", emptyList(), listOf("test-source-r1"),
                mapOf("test-fact-r1" to setOf("test-source-r1")), mapOf("continue" to setOf(null), "unsupported" to setOf(null)), { true },
                approvedFactKinds = mapOf("test-fact-r1" to "fact"))

            GenerationClient(store, store).use { untrusted ->
                val rejected = await<ValidatedGenerationAnswer> { callback -> untrusted.submitAnswer(request, answerContext, callback) }
                check(rejected is GenerationOutcome.Failure && rejected.failure.kind == GenerationFailureKind.CONFIGURATION)
            }
            mark("generation_untrusted_tls_rejected")

            GenerationClient(store, store, transport).use { client ->
                val first = await<ValidatedGenerationAnswer> { callback -> client.submitAnswer(request, answerContext, callback) }
                check(first is GenerationOutcome.Success)
                check(first.value.paragraphs.isNotEmpty())
                val retry = await<ValidatedGenerationAnswer> { callback -> client.submitAnswer(request, answerContext, callback) }
                check(retry is GenerationOutcome.Success)
                check(first.value.paragraphs.map { it.key } == retry.value.paragraphs.map { it.key })
                mark("generation_https_answer_schema_evidence_and_idempotent_retry")

                val paragraph = first.value.paragraphs.first()
                val work = SummaryWork("summary-${UUID.randomUUID()}", "probe-session", "probe-turn", 2, 2,
                    store.hash("synthetic-summary-only".toByteArray()), 1,
                    SummaryInput("합성 질문입니다.", listOf(SummaryResponseParagraph(paragraph.key, paragraph.text.value, paragraph.sourceRevisionIds)), listOf("test-partial")),
                    mapOf("test-fact-r1" to setOf("test-source-r1")), { SummaryWorkStatus.CURRENT })
                val summary = await<ValidatedSummaryResult> { callback -> client.submitSummary(work, callback) }
                check(summary is GenerationOutcome.Success)
                check(summary.value.completedResponseKeys == listOf(paragraph.key))
                check(summary.value.partialUnknownKeys == listOf("test-partial"))
                check(summary.value.inputTokens == null && summary.value.outputTokens == null)
                mark("generation_https_summary_frozen_version_and_provenance")

                verifySummaryTransaction(context, client)
                mark("generation_https_room_tx03_saved_and_ciphertext_deleted")

                val cancel = transport.execute(configuration, "/v1/generations/$id/cancel", null, GenerationExchange())
                check(cancel.status == 204)
                val unknown = transport.execute(configuration, "/v1/generations/unknown-${UUID.randomUUID()}/cancel", null, GenerationExchange())
                check(unknown.status == 404)
                cancel.body.fill(0); unknown.body.fill(0)
                mark("generation_https_owner_cancel_and_unknown_request")
            }
            val badConfig = DeviceGenerationConfiguration(configuration.serverOrigin, "0".repeat(43))
            GenerationClient(GenerationConfigurationSource { badConfig }, store, transport).use { client ->
                val failure = await<ValidatedGenerationAnswer> { callback -> client.submitAnswer(request, answerContext, callback) }
                check(failure is GenerationOutcome.Failure && failure.failure.kind == GenerationFailureKind.AUTHORIZATION)
            }
            mark("generation_https_invalid_device_token_rejected")
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keys.deleteEntry("com.petasos.app.$namespace.aes")
            try { store.load(); error("Expected unavailable configuration key") }
            catch (failure: GenerationException) { check(failure.kind == GenerationFailureKind.CONFIGURATION) }
            check(configurationFile.exists())
            mark("generation_configuration_key_loss_preserves_ciphertext")
        } finally {
            store.clear()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.also {
                it.deleteEntry("com.petasos.app.$namespace.aes")
                it.deleteEntry("com.petasos.app.$namespace.hmac")
            }
            caFile.delete(); tokenFile.delete()
        }
        mark("generation_https_probe_complete")
    }

    /** Real frozen Room payload -> HTTPS generation -> atomic TX-03, on the instrumentation worker. */
    private fun verifySummaryTransaction(context: Context, client: GenerationClient) {
        val databaseName = "generation-summary-probe-${UUID.randomUUID()}.db"
        try {
            SyntheticStorageFixture.create(context, databaseName).use { runtime ->
                SyntheticStorageFixture.install(runtime)
                runtime.openSession("summary-trip", "summary-session")
                val keys = SummaryStorageFixture.prepareFrozenTurn(runtime, "https-turn")
                runtime.summaryStore().use { summaryStore ->
                    val claim = summaryStore.claim("https-turn")
                    check(claim is SummaryClaim.Claimed)
                    val work = claim.work
                    check(work.input.completedResponses.map { it.key } == listOf(keys.completed))
                    check(work.input.partialUnknownKeys == listOf(keys.interrupted))
                    check(work.input.completedResponses.flatMap { it.sourceRevisionIds }.toSet() == setOf("test-source-r1"))
                    check(work.approvedFactSources.keys == setOf("test-fact-r1"))
                    val db = runtime.database.openHelper.writableDatabase
                    fun count(query: String): Int = db.query(query).use { check(it.moveToFirst()); it.getInt(0) }
                    check(count("SELECT count(*) FROM pending_conversation") == 1)
                    check(count("SELECT count(*) FROM conversation_summary") == 0)

                    val generated = await<ValidatedSummaryResult> { callback -> client.submitSummary(work, callback) }
                    check(generated is GenerationOutcome.Success)
                    check(summaryStore.commit(work, generated.value))
                    check(count("SELECT count(*) FROM conversation_summary WHERE turn_id='https-turn'") == 1)
                    check(count("SELECT count(*) FROM pending_conversation") == 0)
                    check(count("SELECT count(*) FROM conversation_turn WHERE turn_id='https-turn' AND frozen=1 AND summary_state='saved'") == 1)
                    check(count("SELECT count(*) FROM generation_request WHERE task='summary' AND status='succeeded'") == 1)
                    // The durable turn survives cleanup, and replaying the same completion is a no-op.
                    check(!summaryStore.commit(work, generated.value))
                    check(count("SELECT count(*) FROM conversation_summary") == 1)
                }
            }
        } finally {
            SyntheticStorageFixture.cleanup(context, databaseName)
        }
    }

    private fun <T> await(start: ((GenerationOutcome<T>) -> Unit) -> Any?): GenerationOutcome<T> {
        val future = CompletableFuture<GenerationOutcome<T>>()
        start { future.complete(it) }
        return future.get(35, TimeUnit.SECONDS)
    }
}
