package com.petasos.app.summary

import org.junit.Assert.*
import org.junit.Test

class SummaryRetryPolicyTest {
    private val offline = SummaryFailure(SummaryFailureKind.RETRYABLE, "UPSTREAM_UNAVAILABLE")

    @Test fun threeExecutionsUseBoundedBackoffAndNeverAuthorizeFourthExecution() {
        assertEquals(SummaryRetryDecision.RetryAt(SummarySchedule("turn", 31_000)), SummaryRetryPolicy.afterFailure("turn", 1, 1_000, offline))
        assertEquals(SummaryRetryDecision.RetryAt(SummarySchedule("turn", 121_000)), SummaryRetryPolicy.afterFailure("turn", 2, 1_000, offline))
        assertEquals(SummaryRetryDecision.Exhausted(SummarySchedule("turn", 601_000)), SummaryRetryPolicy.afterFailure("turn", 3, 1_000, offline))
    }

    @Test fun serverRetryAfterMayIncreaseButNeverReducePolicyDelay() {
        assertEquals(SummaryRetryDecision.RetryAt(SummarySchedule("t", 300_000)), SummaryRetryPolicy.afterFailure("t", 1, 0, offline.copy(retryAfterMs = 300_000)))
        assertEquals(SummaryRetryDecision.RetryAt(SummarySchedule("t", 30_000)), SummaryRetryPolicy.afterFailure("t", 1, 0, offline.copy(retryAfterMs = 1)))
    }

    @Test fun authSchemaAndConfigurationFailureBlockInsteadOfRetrying() {
        listOf(SummaryFailureKind.AUTHORIZATION, SummaryFailureKind.INVALID_SCHEMA, SummaryFailureKind.CONFIGURATION, SummaryFailureKind.PERMANENT, SummaryFailureKind.EVIDENCE_BLOCKED, SummaryFailureKind.PAYLOAD_UNAVAILABLE).forEach {
            assertEquals(SummaryRetryDecision.Blocked("FAILURE"), SummaryRetryPolicy.afterFailure("t", 1, 0, SummaryFailure(it, "FAILURE")))
        }
    }

    @Test fun unavailablePayloadNeverRetriesRegardlessOfAttemptOrRetryAfterHint() {
        (1..SummaryRetryPolicy.MAX_BATCH_ATTEMPTS).forEach { attempt ->
            assertEquals(SummaryRetryDecision.Blocked("SUMMARY_PAYLOAD_UNAVAILABLE"), SummaryRetryPolicy.afterFailure(
                "turn", attempt, 0, SummaryFailure(SummaryFailureKind.PAYLOAD_UNAVAILABLE, "SUMMARY_PAYLOAD_UNAVAILABLE", 60_000)))
        }
    }

    @Test fun nextRetryCannotOverflowEpochTimestamp() {
        assertEquals(SummaryRetryDecision.RetryAt(SummarySchedule("t", Long.MAX_VALUE)), SummaryRetryPolicy.afterFailure("t", 1, Long.MAX_VALUE - 5, offline))
    }

    @Test fun privateInputResultAndHashNeverAppearInDiagnosticRepresentations() {
        val input = SummaryInput("private user", listOf(SummaryResponseParagraph("key", "private answer", listOf("source"))), listOf("partial"))
        val work = SummaryWork("request", "session", "turn", 1, 0, "secret-hmac", 1, input, emptyMap())
        val result = ValidatedSummaryResult("private summary", emptyList(), emptyList(), emptyList(), listOf("key"), listOf("partial"), "model", "provider", "prompt")
        assertEquals("SummaryInput([redacted])", input.toString())
        assertEquals("SummaryWork([redacted])", work.toString())
        assertEquals(SummaryWorkStatus.STORAGE_UNAVAILABLE, work.checkCurrent())
        assertEquals("ValidatedSummaryResult([redacted])", result.toString())
        assertEquals("SummaryResponseParagraph([redacted])", input.completedResponses.single().toString())
    }
}
