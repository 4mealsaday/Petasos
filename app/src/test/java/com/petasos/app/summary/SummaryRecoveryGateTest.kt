package com.petasos.app.summary

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SummaryRecoveryGateTest {
    @Test fun simultaneousApplicationAndWorkerWaitForOneRecoveryCommit() {
        val gate = SummaryRecoveryGate()
        val started = CountDownLatch(1)
        val commit = CountDownLatch(1)
        val calls = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<List<SummarySchedule>> { gate.ensure {
                calls.incrementAndGet(); started.countDown()
                check(commit.await(2, TimeUnit.SECONDS))
                listOf(SummarySchedule("turn", 123))
            } }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            val second = pool.submit<List<SummarySchedule>> { gate.ensure { calls.incrementAndGet(); emptyList() } }
            assertFalse(first.isDone)
            commit.countDown()
            assertEquals(first.get(1, TimeUnit.SECONDS), second.get(1, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally { commit.countDown(); pool.shutdownNow() }
    }

    @Test fun failedRecoveryDoesNotAuthorizeClaimAndCanRetry() {
        val gate = SummaryRecoveryGate()
        var failed = false
        try { gate.ensure { error("synthetic_disk_failure") } } catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertEquals(listOf(SummarySchedule("turn", 0)), gate.ensure { listOf(SummarySchedule("turn", 0)) })
    }
}
