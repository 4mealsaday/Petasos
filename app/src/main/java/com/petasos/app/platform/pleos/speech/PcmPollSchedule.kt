package com.petasos.app.platform.pleos.speech

/**
 * Absolute 100 ms capture cadence. Work time is subtracted instead of added on every tick.
 * Catch-up still yields at least 20 ms; a long stall resets the deadline instead of enqueuing
 * every missed tick. This schedules reads only and never buffers, fabricates or replays audio.
 */
internal class PcmPollSchedule(startedAtMs: Long) {
    private var nextAtMs = startedAtMs + INTERVAL_MS
    private var lastObservedMs = startedAtMs

    fun firstDelayMs(): Long = INTERVAL_MS

    fun delayAfterRead(nowMs: Long): Long {
        check(nowMs >= lastObservedMs)
        lastObservedMs = nowMs
        nextAtMs += INTERVAL_MS
        if (nextAtMs < nowMs - MAX_CATCH_UP_LAG_MS) nextAtMs = nowMs + MIN_DELAY_MS
        return (nextAtMs - nowMs).coerceAtLeast(MIN_DELAY_MS)
    }

    private companion object {
        const val INTERVAL_MS = 100L
        const val MIN_DELAY_MS = 20L
        const val MAX_CATCH_UP_LAG_MS = 200L
    }
}
