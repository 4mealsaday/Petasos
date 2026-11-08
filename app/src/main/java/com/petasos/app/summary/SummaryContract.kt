package com.petasos.app.summary

/** Only normally completed response bodies enter this transient summary input. */
class SummaryResponseParagraph(val key: String, val text: String, sourceRevisionIds: List<String>) {
    val sourceRevisionIds = sourceRevisionIds.toList()
    override fun toString(): String = "SummaryResponseParagraph([redacted])"
}

class SummaryInput(val userText: String, completedResponses: List<SummaryResponseParagraph>, partialUnknownKeys: List<String>) {
    val completedResponses = completedResponses.toList()
    val partialUnknownKeys = partialUnknownKeys.toList()
    override fun toString(): String = "SummaryInput([redacted])"
}

/** Persisted metadata plus a transient decrypted snapshot; never put this object in WorkManager Data. */
class SummaryWork(
    val requestId: String,
    val sessionId: String,
    val turnId: String,
    val turnVersion: Long,
    val cancelEpoch: Long,
    val inputHash: String,
    val attemptNumber: Int,
    val input: SummaryInput,
    approvedFactSources: Map<String, Set<String>>,
    /** Re-read the claimed frozen input and evidence; callable from the HTTP worker. */
    val checkCurrent: () -> SummaryWorkStatus = { SummaryWorkStatus.STORAGE_UNAVAILABLE },
) {
    val approvedFactSources: Map<String, Set<String>> = approvedFactSources.mapValues { it.value.toSet() }.toMap()
    override fun toString(): String = "SummaryWork([redacted])"
}

enum class SummaryWorkStatus { CURRENT, STALE, EVIDENCE_BLOCKED, PAYLOAD_UNAVAILABLE, STORAGE_UNAVAILABLE }

/** Construct only after checking the HTTP/schema metadata and the claimed snapshot's evidence. */
class ValidatedSummaryResult(
    val summaryText: String,
    userClaims: List<String>,
    verifiedFactRevisionIds: List<String>,
    unresolvedQuestions: List<String>,
    completedResponseKeys: List<String>,
    partialUnknownKeys: List<String>,
    val modelId: String,
    val provider: String,
    val promptVersion: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
) {
    val userClaims = userClaims.toList()
    val verifiedFactRevisionIds = verifiedFactRevisionIds.toList()
    val unresolvedQuestions = unresolvedQuestions.toList()
    val completedResponseKeys = completedResponseKeys.toList()
    val partialUnknownKeys = partialUnknownKeys.toList()
    override fun toString(): String = "ValidatedSummaryResult([redacted])"
}

/** The latest frozen snapshot/evidence rejected a result; carries no generated content. */
class SummaryResultRejected : Exception("SUMMARY_RESULT_INVALID")

data class SummarySchedule(val turnId: String, val notBeforeEpochMs: Long)

sealed interface SummaryClaim {
    class Claimed(val work: SummaryWork) : SummaryClaim
    data class Wait(val schedule: SummarySchedule) : SummaryClaim
    data class Blocked(val code: String) : SummaryClaim
    data object NoOp : SummaryClaim
}

enum class SummaryFailureKind { RETRYABLE, AUTHORIZATION, INVALID_SCHEMA, CONFIGURATION, PERMANENT, EVIDENCE_BLOCKED, PAYLOAD_UNAVAILABLE }

data class SummaryFailure(val kind: SummaryFailureKind, val code: String, val retryAfterMs: Long? = null) {
    init { require(code.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))); require(retryAfterMs == null || retryAfterMs >= 0) }
}

sealed interface SummaryRetryDecision {
    data class RetryAt(val schedule: SummarySchedule) : SummaryRetryDecision
    /** This timestamp is a hint for the next startup batch, never authorization for a fourth run. */
    data class Exhausted(val schedule: SummarySchedule) : SummaryRetryDecision
    data class Blocked(val code: String) : SummaryRetryDecision
    data object NoOp : SummaryRetryDecision
}

/** Blocking, bounded SQLite work only. Calls belong on a worker thread, never the UI thread. */
interface SummaryStore : AutoCloseable {
    fun claim(turnId: String): SummaryClaim
    fun commit(work: SummaryWork, result: ValidatedSummaryResult): Boolean
    fun fail(work: SummaryWork, failure: SummaryFailure): SummaryRetryDecision
    /** Invoke once on process startup, before scheduling new work. Does not unblock configuration/crypto failures. */
    fun recoverOnAppStart(): List<SummarySchedule>
    /** Only after the previous worker for this turn is known stopped; preserves the current batch limit. */
    fun recoverInterruptedWork(turnId: String): SummaryRetryDecision
    /** Invoke only after a real endpoint/auth/schema configuration change, not on every app startup. */
    fun unblockAfterConfigurationChange(): List<SummarySchedule>
    /** Explicit evidence-review change only. Revalidates before starting a new bounded retry batch. */
    fun unblockAfterEvidenceChange(): List<SummarySchedule> = emptyList()
}
