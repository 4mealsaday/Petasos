package com.petasos.app.demo

import com.petasos.app.runtime.RuntimeCancellation
import org.junit.Assert.*
import org.junit.Test

class DemoVirtualTimeTest {
    @Test fun advanceVisitsIntermediateDeadlinesAndStablePhaseOrderAtEachExactTime() {
        val clock = DemoVirtualTime()
        val seen = mutableListOf<Pair<String, Long>>()
        fun add(at: Long, phase: DemoEventPriority, name: String) {
            clock.scheduleAt(at, phase) { seen += name to clock.elapsedRealtimeMs() }
        }
        add(100_200, DemoEventPriority.PLANNING, "plan")
        add(100_200, DemoEventPriority.TIMER, "timer-a")
        add(100_200, DemoEventPriority.OUTPUT, "output-a")
        add(100_100, DemoEventPriority.TIMER, "earlier")
        add(100_200, DemoEventPriority.OBSERVATION, "observe-a")
        add(100_200, DemoEventPriority.OUTPUT, "output-b")
        add(100_200, DemoEventPriority.OBSERVATION, "observe-b")
        clock.schedule(200) { seen += "timer-b" to clock.now }
        clock.advanceBy(300)
        assertEquals(listOf("earlier" to 100_100L, "observe-a" to 100_200L, "observe-b" to 100_200L,
            "output-a" to 100_200L, "output-b" to 100_200L, "timer-a" to 100_200L,
            "timer-b" to 100_200L, "plan" to 100_200L), seen)
        assertEquals(100_300L, clock.now)
        assertEquals(0, clock.pendingCount)
    }

    @Test fun executeIsDeferredFifoAndEachScheduledCallbackSettlesBeforeTheNextOutput() {
        val clock = DemoVirtualTime()
        val seen = mutableListOf<String>()
        clock.execute { seen += "external-a" }
        clock.execute { seen += "external-b" }
        assertTrue(seen.isEmpty())
        clock.scheduleAt(clock.now, DemoEventPriority.OUTPUT) {
            seen += "output-a"
            clock.execute { seen += "runtime-a"; clock.execute { seen += "runtime-nested" } }
            clock.execute { seen += "runtime-b" }
            assertEquals(listOf("observe", "observed-state", "output-a"), seen)
        }
        clock.scheduleAt(clock.now, DemoEventPriority.OUTPUT) { seen += "output-b" }
        clock.scheduleAt(clock.now, DemoEventPriority.OBSERVATION) {
            seen += "observe"
            clock.execute { seen += "observed-state" }
        }
        clock.scheduleAt(clock.now, DemoEventPriority.PLANNING) { seen += "plan" }
        clock.drain()
        assertEquals(listOf("observe", "observed-state", "output-a", "runtime-a", "runtime-b", "runtime-nested",
            "output-b", "external-a", "external-b", "plan"), seen)
        assertEquals(DemoVirtualTime.INITIAL_ELAPSED_MS, clock.now)
    }

    @Test fun futureWorkWaitsAndTasksCreatedMidAdvanceUseTheirTrueDeadline() {
        val clock = DemoVirtualTime()
        val times = mutableListOf<Long>()
        clock.schedule(1_000) {
            times += clock.now
            clock.schedule(250) { times += clock.now }
            clock.scheduleAt(clock.now, DemoEventPriority.PLANNING) { times += clock.now }
        }
        clock.drain()
        assertTrue(times.isEmpty())
        assertEquals(1, clock.pendingCount)
        clock.advanceBy(999)
        assertTrue(times.isEmpty())
        clock.advanceBy(2_001)
        assertEquals(listOf(101_000L, 101_000L, 101_250L), times)
        assertEquals(103_000L, clock.now)
        clock.advanceBy(0)
        assertEquals(103_000L, clock.now)
    }

    @Test fun cancellationRemovesEntriesImmediatelyAndLateOrRepeatedCancellationIsHarmless() {
        val clock = DemoVirtualTime()
        repeat(2_048) {
            val cancelled = clock.schedule(50_000) { error("cancelled_callback_must_not_run") }
            cancelled.cancel(); cancelled.cancel()
            assertEquals(0, clock.pendingCount)
        }
        var completed = 0
        lateinit var done: RuntimeCancellation
        done = clock.schedule(10) { completed++; done.cancel() }
        val skipped = clock.schedule(20) { error("cancelled_callback_must_not_run") }
        clock.scheduleAt(100_020, DemoEventPriority.OBSERVATION) { skipped.cancel() }
        clock.advanceBy(20)
        done.cancel(); skipped.cancel()
        assertEquals(1, completed)
        assertEquals(0, clock.pendingCount)
        clock.close(); done.cancel()
    }

    @Test fun closeCancelsAllWorkAndCallbackCloseStopsAtItsReachedTime() {
        val closed = DemoVirtualTime()
        closed.execute { error("closed_callback_must_not_run") }
        closed.schedule(100) { error("closed_callback_must_not_run") }
        closed.close(); closed.close()
        assertTrue(closed.isClosed)
        assertEquals(0, closed.pendingCount)
        failure("DEMO_CLOCK_CLOSED") { closed.drain() }
        failure("DEMO_CLOCK_CLOSED") { closed.execute {} }
        failure("DEMO_CLOCK_CLOSED") { closed.advanceBy(1) }

        val inside = DemoVirtualTime()
        inside.schedule(5) { inside.execute { error("discarded_immediate_must_not_run") }; inside.close() }
        inside.schedule(10) { error("closed_callback_must_not_run") }
        inside.advanceBy(100)
        assertEquals(100_005L, inside.now)
        assertTrue(inside.isClosed)
        assertEquals(0, inside.pendingCount)
    }

    @Test fun invalidTimeAndReentrantOrEarlierPhaseOperationsFailClosedWithoutRewinding() {
        val maximum = DemoVirtualTime()
        maximum.advanceBy(DemoVirtualTime.MAX_ADVANCE_MS)
        assertEquals(400_000L, maximum.now)
        for (operation in listOf<(DemoVirtualTime) -> Unit>(
            { it.advanceBy(-1) }, { it.advanceBy(300_001) }, { it.schedule(-1) {} },
            { it.schedule(Long.MAX_VALUE) {} }, { it.scheduleAt(99_999, DemoEventPriority.TIMER) {} },
        )) {
            val clock = DemoVirtualTime()
            failure("DEMO_TIME_RANGE") { operation(clock) }
            assertTrue(clock.isClosed)
            assertEquals(100_000L, clock.now)
        }
        for (operation in listOf<(DemoVirtualTime) -> Unit>({ it.drain() }, { it.advanceBy(0) })) {
            val clock = DemoVirtualTime()
            clock.schedule(10) { operation(clock) }
            failure("DEMO_REENTRANT_RUN") { clock.advanceBy(20) }
            assertTrue(clock.isClosed)
            assertEquals(100_010L, clock.now)
        }
        val backwardsPhase = DemoVirtualTime()
        backwardsPhase.scheduleAt(100_010, DemoEventPriority.PLANNING) {
            backwardsPhase.scheduleAt(backwardsPhase.now, DemoEventPriority.OBSERVATION) {}
        }
        failure("DEMO_PHASE_REGRESSION") { backwardsPhase.advanceBy(20) }
        assertEquals(100_010L, backwardsPhase.now)
    }

    @Test fun pendingAndEventBudgetsAreExactAndAProducerCannotHideTheTerminalFailure() {
        val pending = DemoVirtualTime()
        repeat(DemoVirtualTime.MAX_PENDING) { pending.schedule(10) {} }
        assertEquals(1_024, pending.pendingCount)
        failure("DEMO_PENDING_LIMIT") { pending.execute {} }
        assertTrue(pending.isClosed)
        assertEquals(0, pending.pendingCount)

        val looping = DemoVirtualTime()
        var calls = 0
        lateinit var again: () -> Unit
        again = { calls++; looping.execute(again) }
        looping.execute(again)
        failure("DEMO_EVENT_LIMIT") { looping.drain() }
        assertEquals(10_000, calls)
        assertTrue(looping.isClosed)
        assertEquals(0, looping.pendingCount)

        val swallowed = DemoVirtualTime()
        swallowed.execute { try { swallowed.drain() } catch (_: DemoVirtualTimeException) { } }
        failure("DEMO_REENTRANT_RUN") { swallowed.drain() }
        assertTrue(swallowed.isClosed)
    }

    @Test fun callbackFailureDiscardsOutputsAndExposesOnlyAFixedCodeWithoutCause() {
        val clock = DemoVirtualTime()
        var output = false
        clock.scheduleAt(100_100, DemoEventPriority.OBSERVATION) {
            clock.execute { throw IllegalArgumentException("synthetic_private_callback_text") }
        }
        clock.scheduleAt(100_100, DemoEventPriority.OUTPUT) { output = true }
        val exception = failure("DEMO_CALLBACK_FAILED") { clock.advanceBy(1_000) }
        assertNull(exception.cause)
        assertEquals("DEMO_CALLBACK_FAILED", exception.message)
        assertFalse(output)
        assertTrue(clock.isClosed)
        assertEquals(100_100L, clock.now)
        assertEquals(0, clock.pendingCount)
        val forged = DemoVirtualTime()
        forged.execute { throw DemoVirtualTimeException("synthetic_private_unrecognized_code") }
        failure("DEMO_CALLBACK_FAILED") { forged.drain() }
    }

    private fun failure(code: String, action: () -> Unit): DemoVirtualTimeException {
        try { action(); throw AssertionError("expected_fixed_clock_failure") }
        catch (failure: DemoVirtualTimeException) { assertEquals(code, failure.code); return failure }
    }
}
