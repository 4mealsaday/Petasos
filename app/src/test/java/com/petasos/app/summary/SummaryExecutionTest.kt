package com.petasos.app.summary

import com.petasos.app.runtime.RuntimeCancellation
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SummaryExecutionTest {
    @Test fun savedOrBlockedTurnNeverCallsGeneration() {
        val store = Store()
        var calls = 0
        val execution = SummaryExecution(store, SummaryGenerator { _, _ -> calls++; RuntimeCancellation {} })
        listOf(SummaryClaim.NoOp, SummaryClaim.Blocked("UNAUTHORIZED")).forEach {
            store.claimed = it
            assertEquals(SummaryExecutionResult.Finished, execution.run("turn"))
        }
        assertEquals(0, calls)
    }

    @Test fun notYetDueReturnsScheduleWithoutSendingPlaintext() {
        val store = Store().apply { claimed = SummaryClaim.Wait(SummarySchedule("turn", 45_000)) }
        val execution = SummaryExecution(store, SummaryGenerator { _, _ -> error("not_due") })
        assertEquals(SummaryExecutionResult.ScheduleNext(SummarySchedule("turn", 45_000)), execution.run("turn"))
    }

    @Test fun duplicateNetworkCallbacksCommitOnlyOnce() {
        val store = Store()
        val execution = SummaryExecution(store, SummaryGenerator { _, callback ->
            callback(SummaryGenerationOutcome.Success(result))
            callback(SummaryGenerationOutcome.Success(result))
            RuntimeCancellation {}
        })
        assertEquals(SummaryExecutionResult.Finished, execution.run("turn"))
        assertEquals(1, store.commits)
        assertNull(store.failed)
    }

    @Test fun evidenceBlockedBeforeGenerationNeverCallsGeneratorOrCommits() {
        val store = Store().apply {
            claimed = SummaryClaim.Claimed(work { SummaryWorkStatus.EVIDENCE_BLOCKED })
            failureDecision = SummaryRetryDecision.Blocked("SUMMARY_EVIDENCE_BLOCKED")
        }
        var calls = 0
        val execution = SummaryExecution(store, SummaryGenerator { _, _ -> calls++; RuntimeCancellation {} })
        assertEquals(SummaryExecutionResult.Finished, execution.run("turn"))
        assertEquals(0, calls)
        assertEquals(0, store.commits)
        assertEquals(SummaryFailureKind.EVIDENCE_BLOCKED, store.failed?.kind)
    }

    @Test fun evidenceChangedDuringGenerationBlocksLateResultWithoutCommit() {
        var status = SummaryWorkStatus.CURRENT
        val store = Store().apply {
            claimed = SummaryClaim.Claimed(work { status })
            failureDecision = SummaryRetryDecision.Blocked("SUMMARY_EVIDENCE_BLOCKED")
        }
        val execution = SummaryExecution(store, SummaryGenerator { _, callback ->
            status = SummaryWorkStatus.EVIDENCE_BLOCKED
            callback(SummaryGenerationOutcome.Success(result))
            RuntimeCancellation {}
        })
        assertEquals(SummaryExecutionResult.Finished, execution.run("turn"))
        assertEquals(0, store.commits)
        assertEquals(SummaryFailureKind.EVIDENCE_BLOCKED, store.failed?.kind)
    }

    @Test fun brokenCurrentGuardFailsClosedWithoutCallingGenerator() {
        val store = Store().apply { claimed = SummaryClaim.Claimed(work { error("synthetic_unavailable") }) }
        var calls = 0
        val execution = SummaryExecution(store, SummaryGenerator { _, _ -> calls++; RuntimeCancellation {} })
        assertTrue(execution.run("turn") is SummaryExecutionResult.ScheduleNext)
        assertEquals(0, calls)
        assertEquals("STORAGE_UNAVAILABLE", store.failed?.code)
    }

    @Test fun lostPayloadKeyBeforeGenerationBlocksWithoutSendingOrSchedulingRetry() {
        val store = Store().apply {
            claimed = SummaryClaim.Claimed(work { SummaryWorkStatus.PAYLOAD_UNAVAILABLE })
            failureDecision = SummaryRetryDecision.Blocked("SUMMARY_PAYLOAD_UNAVAILABLE")
        }
        var calls = 0
        val execution = SummaryExecution(store, SummaryGenerator { _, _ -> calls++; RuntimeCancellation {} })
        assertEquals(SummaryExecutionResult.Finished, execution.run("turn"))
        assertEquals(0, calls)
        assertEquals(0, store.commits)
        assertEquals(SummaryFailureKind.PAYLOAD_UNAVAILABLE, store.failed?.kind)
        assertEquals("SUMMARY_PAYLOAD_UNAVAILABLE", store.failed?.code)
    }

    @Test fun payloadLostDuringGenerationRejectsSuccessWithoutCommitOrRetry() {
        var status = SummaryWorkStatus.CURRENT
        val store = Store().apply {
            claimed = SummaryClaim.Claimed(work { status })
            failureDecision = SummaryRetryDecision.Blocked("SUMMARY_PAYLOAD_UNAVAILABLE")
        }
        val execution = SummaryExecution(store, SummaryGenerator { _, callback ->
            status = SummaryWorkStatus.PAYLOAD_UNAVAILABLE
            callback(SummaryGenerationOutcome.Success(result))
            RuntimeCancellation {}
        })
        assertEquals(SummaryExecutionResult.Finished, execution.run("turn"))
        assertEquals(0, store.commits)
        assertEquals(SummaryFailureKind.PAYLOAD_UNAVAILABLE, store.failed?.kind)
        assertEquals("SUMMARY_PAYLOAD_UNAVAILABLE", store.failed?.code)
    }

    @Test fun staleStoreComparisonDoesNotDeleteOrRetryTheNewVersion() {
        val store = Store().apply { acceptCommit = false }
        val execution = SummaryExecution(store, success)
        assertEquals(SummaryExecutionResult.Finished, execution.run("turn"))
        assertEquals(0, store.saved)
        assertNull(store.failed)
    }

    @Test fun stopCancelsTransportAndDiscardsLateSuccess() {
        val store = Store()
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        lateinit var callback: (SummaryGenerationOutcome) -> Unit
        val execution = SummaryExecution(store, SummaryGenerator { _, done ->
            callback = done; started.countDown(); RuntimeCancellation { cancelled.countDown() }
        })
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit<SummaryExecutionResult> { execution.run("turn") }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            execution.stop()
            assertTrue(cancelled.await(1, TimeUnit.SECONDS))
            callback(SummaryGenerationOutcome.Success(result))
            assertTrue(pending.get(1, TimeUnit.SECONDS) is SummaryExecutionResult.ScheduleNext)
            assertEquals(0, store.commits)
            assertEquals("CANCELLED", store.failed?.code)
        } finally { pool.shutdownNow() }
    }

    @Test fun timedOutCallIsCancelledAndRetainsRetry() {
        val store = Store()
        var cancelled = false
        val execution = SummaryExecution(store, SummaryGenerator { _, _ -> RuntimeCancellation { cancelled = true } }, timeoutMs = 10)
        assertTrue(execution.run("turn") is SummaryExecutionResult.ScheduleNext)
        assertTrue(cancelled)
        assertEquals("GENERATION_TIMEOUT", store.failed?.code)
        assertEquals(0, store.commits)
    }

    @Test fun exhaustedBatchDoesNotScheduleAnotherAutomaticExecution() {
        val store = Store().apply { failureDecision = SummaryRetryDecision.Exhausted(SummarySchedule("turn", 600_000)) }
        val execution = SummaryExecution(store, SummaryGenerator { _, done ->
            done(SummaryGenerationOutcome.Failure(SummaryFailure(SummaryFailureKind.RETRYABLE, "UPSTREAM_UNAVAILABLE")))
            RuntimeCancellation {}
        })
        assertEquals(SummaryExecutionResult.Finished, execution.run("turn"))
        assertEquals(0, store.saved)
    }

    @Test fun failedCommitIsReleasedToRetryInsteadOfLosingTheRunningClaim() {
        val store = Store().apply { failCommit = true }
        assertTrue(SummaryExecution(store, success).run("turn") is SummaryExecutionResult.ScheduleNext)
        assertEquals(0, store.saved)
        assertEquals("STORAGE_UNAVAILABLE", store.failed?.code)
    }

    @Test fun revokedEvidenceAtCommitBlocksInsteadOfRetryingInvalidSummary() {
        val store = Store().apply {
            rejectResult = true
            failureDecision = SummaryRetryDecision.Blocked("INVALID_MODEL_OUTPUT")
        }
        assertEquals(SummaryExecutionResult.Finished, SummaryExecution(store, success).run("turn"))
        assertEquals(0, store.saved)
        assertEquals(SummaryFailureKind.INVALID_SCHEMA, store.failed?.kind)
    }

    private class Store : SummaryStore {
        var claimed: SummaryClaim = SummaryClaim.Claimed(work())
        var failureDecision: SummaryRetryDecision = SummaryRetryDecision.RetryAt(SummarySchedule("turn", 30_000))
        var commits = 0
        var saved = 0
        var acceptCommit = true
        var failCommit = false
        var rejectResult = false
        var failed: SummaryFailure? = null
        override fun claim(turnId: String) = claimed
        override fun commit(work: SummaryWork, result: ValidatedSummaryResult): Boolean {
            commits++
            if (rejectResult) throw SummaryResultRejected()
            if (failCommit) error("synthetic_storage_failure")
            if (acceptCommit) saved++
            return acceptCommit
        }
        override fun fail(work: SummaryWork, failure: SummaryFailure): SummaryRetryDecision { failed = failure; return failureDecision }
        override fun recoverOnAppStart() = emptyList<SummarySchedule>()
        override fun recoverInterruptedWork(turnId: String) = SummaryRetryDecision.NoOp
        override fun unblockAfterConfigurationChange() = emptyList<SummarySchedule>()
        override fun close() = Unit
    }

    companion object {
        private fun work(current: () -> SummaryWorkStatus = { SummaryWorkStatus.CURRENT }) = SummaryWork("request", "session", "turn", 1, 0, "a".repeat(64), 1, SummaryInput("synthetic private input", emptyList(), emptyList()), emptyMap(), current)
        private val result = ValidatedSummaryResult("synthetic summary", listOf("test claim"), emptyList(), emptyList(), emptyList(), emptyList(), "test-model", "test-provider", "test-v1")
        private val success = SummaryGenerator { _, done -> done(SummaryGenerationOutcome.Success(result)); RuntimeCancellation {} }
    }
}
