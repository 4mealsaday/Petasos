package com.petasos.app.generation

import com.petasos.app.runtime.RuntimeParagraph
import java.net.URI

/** Sanitized categories only; never retain provider exception messages or response bodies. */
enum class GenerationFailureKind {
    CONFIGURATION, AUTHORIZATION, INVALID_REQUEST, INVALID_RESPONSE, STALE, CONFLICT, NO_EVIDENCE,
    EVIDENCE_BLOCKED, LOCAL_PAYLOAD, LOCAL_STORAGE, RETRYABLE, CANCELLED, PERMANENT,
}
class GenerationException(val kind: GenerationFailureKind) : Exception(kind.name)
data class GenerationFailure(val kind: GenerationFailureKind, val retryAfterMs: Long = 0)
sealed interface GenerationOutcome<out T> {
    class Success<T>(val value: T) : GenerationOutcome<T> { override fun toString() = "Success([redacted])" }
    data class Failure(val failure: GenerationFailure) : GenerationOutcome<Nothing>
}

class DeviceGenerationConfiguration(val serverOrigin: String, val bearerToken: String) {
    init {
        val origin = try { URI(serverOrigin) } catch (_: Exception) { throw GenerationException(GenerationFailureKind.CONFIGURATION) }
        if (origin.scheme != "https" || origin.host.isNullOrBlank() || origin.rawUserInfo != null ||
            origin.rawQuery != null || origin.rawFragment != null || origin.rawPath !in listOf("", "/") ||
            origin.port !in -1..65535 || origin.port == 0 ||
            bearerToken.length !in 32..4096 || !bearerToken.matches(Regex("[A-Za-z0-9._~+/-]+=*"))) {
            throw GenerationException(GenerationFailureKind.CONFIGURATION)
        }
    }
    internal fun url(path: String): String = serverOrigin.trimEnd('/') + path
    override fun toString() = "DeviceGenerationConfiguration([redacted])"
}
fun interface GenerationConfigurationSource { fun load(): DeviceGenerationConfiguration? }
fun interface GenerationInputHasher { fun hash(canonicalInput: ByteArray): String }

/** All context is supplied by a trusted local snapshot. There are deliberately no fabricated defaults. */
class AnswerGenerationContext(
    val turnVersion: Long,
    val routeRevision: Long?,
    val contextSummary: String,
    heardParagraphIds: List<String>,
    sourceRevisionIds: List<String>,
    approvedFactSources: Map<String, Set<String>>,
    allowedActions: Map<String, Set<String?>>,
    val isCurrent: () -> Boolean,
    approvedFactKinds: Map<String, String> = emptyMap(),
) {
    val heardParagraphIds = heardParagraphIds.toList()
    val sourceRevisionIds = sourceRevisionIds.toList()
    val approvedFactSources = approvedFactSources.mapValues { it.value.toSet() }.toMap()
    val allowedActions = allowedActions.mapValues { it.value.toSet() }.toMap()
    val approvedFactKinds = approvedFactKinds.toMap()
    override fun toString() = "AnswerGenerationContext([redacted])"
}

class ValidatedGenerationAnswer internal constructor(
    paragraphs: List<RuntimeParagraph>, val action: String, val targetContentId: String?,
) {
    val paragraphs = paragraphs.toList()
    override fun toString() = "ValidatedGenerationAnswer([redacted])"
}
