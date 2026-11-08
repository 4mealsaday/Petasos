package com.petasos.app.summary

import com.petasos.app.runtime.RuntimeCancellation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

sealed interface SummaryGenerationOutcome {
    class Success(val result: ValidatedSummaryResult) : SummaryGenerationOutcome {
        override fun toString(): String = "Success([redacted])"
    }
    data class Failure(val failure: SummaryFailure) : SummaryGenerationOutcome
}

fun interface SummaryGenerator {
    fun generate(work: SummaryWork, callback: (SummaryGenerationOutcome) -> Unit): RuntimeCancellation
}

sealed interface SummaryExecutionResult {
    data object Finished : SummaryExecutionResult
    data class ScheduleNext(val schedule: SummarySchedule) : SummaryExecutionResult
    data object StorageUnavailable : SummaryExecutionResult
}

/** One claimed version per execution. No transcript or response is ever WorkManager input/output. */
class SummaryExecution(
    private val store: SummaryStore,
    private val generator: SummaryGenerator,
    private val timeoutMs: Long = 60_000,
) {
    private val stopped = AtomicBoolean()
    private val cancellation = AtomicReference<RuntimeCancellation?>()
    private val waiting = AtomicReference<CountDownLatch?>()

    fun stop() {
        stopped.set(true)
        try { cancellation.getAndSet(null)?.cancel() } catch (_: Exception) { /* Best effort transport cancellation. */ }
        waiting.get()?.countDown()
    }

    /** All store operations, including cancellation cleanup, run on this caller's worker thread. */
    fun run(turnId: String): SummaryExecutionResult = try {
        if (stopped.get()) SummaryExecutionResult.Finished else when (val claim = store.claim(turnId)) {
            SummaryClaim.NoOp, is SummaryClaim.Blocked -> SummaryExecutionResult.Finished
            is SummaryClaim.Wait -> SummaryExecutionResult.ScheduleNext(claim.schedule)
            is SummaryClaim.Claimed -> execute(claim.work)
        }
    } catch (_: Exception) {
        SummaryExecutionResult.StorageUnavailable
    }

    private fun execute(work: SummaryWork): SummaryExecutionResult {
        currentFailure(work)?.let { return retryDecision(store.fail(work, it)) }
        val latch = CountDownLatch(1)
        val received = AtomicReference<SummaryGenerationOutcome?>()
        waiting.set(latch)
        var timedOut = false
        try {
            if (!stopped.get()) {
                try {
                    val handle = generator.generate(work) { outcome ->
                        if (!stopped.get() && received.compareAndSet(null, outcome)) latch.countDown()
                    }
                    cancellation.set(handle)
                    if (stopped.get()) stop()
                } catch (_: Exception) {
                    received.compareAndSet(null, SummaryGenerationOutcome.Failure(
                        SummaryFailure(SummaryFailureKind.RETRYABLE, "UPSTREAM_UNAVAILABLE"),
                    ))
                    latch.countDown()
                }
                try { timedOut = !latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
                catch (_: InterruptedException) { stop(); Thread.currentThread().interrupt() }
            }
            val outcome = when {
                stopped.get() -> SummaryGenerationOutcome.Failure(SummaryFailure(SummaryFailureKind.RETRYABLE, "CANCELLED"))
                timedOut -> {
                    stop()
                    SummaryGenerationOutcome.Failure(SummaryFailure(SummaryFailureKind.RETRYABLE, "GENERATION_TIMEOUT"))
                }
                else -> received.get() ?: SummaryGenerationOutcome.Failure(
                    SummaryFailure(SummaryFailureKind.RETRYABLE, "UPSTREAM_UNAVAILABLE"),
                )
            }
            // Also protect callers using a non-HTTP generator. The store repeats this inside TX-03.
            currentFailure(work)?.let { return retryDecision(store.fail(work, it)) }
            return when (outcome) {
                is SummaryGenerationOutcome.Success -> {
                    // The store compares the current frozen version again inside TX-03.
                    try {
                        store.commit(work, outcome.result)
                        SummaryExecutionResult.Finished
                    } catch (_: SummaryResultRejected) {
                        retryDecision(store.fail(work, SummaryFailure(SummaryFailureKind.INVALID_SCHEMA, "INVALID_MODEL_OUTPUT")))
                    } catch (_: Exception) {
                        retryDecision(store.fail(work, SummaryFailure(SummaryFailureKind.RETRYABLE, "STORAGE_UNAVAILABLE")))
                    }
                }
                is SummaryGenerationOutcome.Failure -> retryDecision(store.fail(work, outcome.failure))
            }
        } finally {
            waiting.set(null)
            cancellation.set(null)
        }
    }

    private fun currentFailure(work: SummaryWork): SummaryFailure? = when (
        try { work.checkCurrent() } catch (_: Exception) { SummaryWorkStatus.STORAGE_UNAVAILABLE }
    ) {
        SummaryWorkStatus.CURRENT -> null
        SummaryWorkStatus.STALE -> SummaryFailure(SummaryFailureKind.PERMANENT, "SUMMARY_INPUT_CHANGED")
        SummaryWorkStatus.EVIDENCE_BLOCKED -> SummaryFailure(SummaryFailureKind.EVIDENCE_BLOCKED, "SUMMARY_EVIDENCE_BLOCKED")
        SummaryWorkStatus.PAYLOAD_UNAVAILABLE -> SummaryFailure(SummaryFailureKind.PAYLOAD_UNAVAILABLE, "SUMMARY_PAYLOAD_UNAVAILABLE")
        SummaryWorkStatus.STORAGE_UNAVAILABLE -> SummaryFailure(SummaryFailureKind.RETRYABLE, "STORAGE_UNAVAILABLE")
    }

    private fun retryDecision(decision: SummaryRetryDecision): SummaryExecutionResult = when (decision) {
        is SummaryRetryDecision.RetryAt -> SummaryExecutionResult.ScheduleNext(decision.schedule)
        is SummaryRetryDecision.Exhausted, is SummaryRetryDecision.Blocked,
        SummaryRetryDecision.NoOp -> SummaryExecutionResult.Finished
    }
}
