package com.petasos.app.platform.pleos.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmPollScheduleTest {
    @Test fun processingCostDoesNotAccumulateAcrossTicks() {
        val schedule = PcmPollSchedule(10_000)
        var due = 10_000L + schedule.firstDelayMs()
        repeat(600) { tick ->
            assertEquals(10_000L + (tick + 1) * 100, due)
            val afterRead = due + 7 // Non-blocking capture/conversion/SDK enqueue overhead.
            val delay = schedule.delayAfterRead(afterRead)
            assertEquals(93L, delay)
            due = afterRead + delay
        }
    }

    @Test fun delayedTickCatchesUpWithoutImmediateMainThreadLoop() {
        val schedule = PcmPollSchedule(0)
        assertEquals(20L, schedule.delayAfterRead(250))
        assertEquals(30L, schedule.delayAfterRead(270))
        assertEquals(100L, schedule.delayAfterRead(300))
    }

    @Test fun longStallDoesNotReplayHundredsOfMissedTicks() {
        val schedule = PcmPollSchedule(0)
        assertEquals(20L, schedule.delayAfterRead(30_000))
        assertEquals(100L, schedule.delayAfterRead(30_020))
        assertEquals(100L, schedule.delayAfterRead(30_120))
    }

    @Test fun variableDelaysAlwaysYieldAndRejectClockRegression() {
        val schedule = PcmPollSchedule(0)
        listOf(100L, 290L, 310L, 700L, 725L, 10_000L).forEach { now ->
            assertTrue(schedule.delayAfterRead(now) >= 20)
        }
        assertThrows(IllegalStateException::class.java) { schedule.delayAfterRead(9_999) }
    }
}
