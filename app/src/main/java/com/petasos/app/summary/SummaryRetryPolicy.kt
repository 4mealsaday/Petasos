package com.petasos.app.summary

/** REC-06: three executions per batch. Exhaustion retains text and never schedules execution four. */
object SummaryRetryPolicy {
    const val MAX_BATCH_ATTEMPTS = 3
    fun afterFailure(turnId: String, attemptsInBatch: Int, nowEpochMs: Long, failure: SummaryFailure): SummaryRetryDecision {
        require(attemptsInBatch in 1..MAX_BATCH_ATTEMPTS)
        if (failure.kind != SummaryFailureKind.RETRYABLE) return SummaryRetryDecision.Blocked(failure.code)
        val baseDelay = when (attemptsInBatch) { 1 -> 30_000L; 2 -> 120_000L; else -> 600_000L }
        val delay = maxOf(baseDelay, failure.retryAfterMs ?: 0)
        val due = if (nowEpochMs > Long.MAX_VALUE - delay) Long.MAX_VALUE else nowEpochMs + delay
        val schedule = SummarySchedule(turnId, due)
        return if (attemptsInBatch == MAX_BATCH_ATTEMPTS) SummaryRetryDecision.Exhausted(schedule) else SummaryRetryDecision.RetryAt(schedule)
    }
}
