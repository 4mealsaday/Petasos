package com.petasos.app.demo

import com.petasos.app.runtime.RuntimeCancellation
import com.petasos.app.runtime.RuntimeClock
import com.petasos.app.runtime.RuntimeQueue
import java.util.ArrayDeque
import java.util.PriorityQueue

/** Fixed phases: update input authority before output, settle output before a coalesced replan. */
enum class DemoEventPriority { OBSERVATION, OUTPUT, TIMER, PLANNING }

/** Fixed diagnostic code only; callback exception text and causes never leave the demo clock. */
class DemoVirtualTimeException internal constructor(code: String) : IllegalStateException(
    if (code in CODES) code else "DEMO_CALLBACK_FAILED",
) {
    val code: String = checkNotNull(message)
    private companion object {
        val CODES = setOf("DEMO_CLOCK_CLOSED", "DEMO_TIME_RANGE", "DEMO_REENTRANT_RUN", "DEMO_PHASE_REGRESSION",
            "DEMO_PENDING_LIMIT", "DEMO_EVENT_LIMIT", "DEMO_SEQUENCE_LIMIT", "DEMO_CALLBACK_FAILED")
    }
}

/**
 * Deterministic demo-only serial queue. It does not observe wall time, synthesize freshness or run a
 * background thread. Each advance visits every intermediate due time; callbacks see that exact time.
 * Same-time scheduled callbacks use phase then registration FIFO. execute() never calls inline:
 * work posted by a callback runs in FIFO order at its inherited phase before the next scheduled
 * callback. Work posted externally waits as a normal current-time TIMER event.
 *
 * drain/advance cannot be invoked inside a callback. A callback may enqueue/cancel work or close the
 * clock. Scheduling an earlier phase at the currently executing time is rejected rather than moving
 * backwards within that time step. New external commands after a completed drain start a new batch.
 * Failures close the clock, discard all pending work and retain the last reached time.
 */
class DemoVirtualTime : RuntimeQueue, RuntimeClock, AutoCloseable {
    private class Item(val at: Long, val priority: DemoEventPriority, val order: Long, var task: (() -> Unit)?) {
        var pending = true
    }

    private val scheduled = PriorityQueue<Item>(compareBy<Item> { it.at }
        .thenBy { it.priority.ordinal }.thenBy { it.order })
    private val immediate = ArrayDeque<Item>()
    private var nextOrder = 0L
    private var running = false
    private var activePriority: DemoEventPriority? = null
    private var failureCode: String? = null

    @Volatile var now: Long = INITIAL_ELAPSED_MS
        private set
    @Volatile var isClosed: Boolean = false
        private set
    val pendingCount: Int
        @Synchronized get() = scheduled.size + immediate.size

    override fun elapsedRealtimeMs(): Long = now

    @Synchronized override fun execute(task: () -> Unit) {
        requireOpen()
        val phase = activePriority
        if (phase == null) {
            enqueueScheduled(now, DemoEventPriority.TIMER, task)
        } else {
            immediate.addLast(newItem(now, phase, task))
        }
    }

    @Synchronized override fun schedule(delayMs: Long, task: () -> Unit): RuntimeCancellation {
        requireOpen()
        if (delayMs < 0 || delayMs > Long.MAX_VALUE - now) fail("DEMO_TIME_RANGE")
        return scheduleAt(now + delayMs, DemoEventPriority.TIMER, task)
    }

    @Synchronized fun scheduleAt(at: Long, priority: DemoEventPriority, task: () -> Unit): RuntimeCancellation {
        requireOpen()
        if (at < now) fail("DEMO_TIME_RANGE")
        if (at == now && activePriority?.let { priority.ordinal < it.ordinal } == true) fail("DEMO_PHASE_REGRESSION")
        val item = enqueueScheduled(at, priority, task)
        return RuntimeCancellation { cancel(item) }
    }

    /** Runs current-time work only, including current-time work posted by those callbacks. */
    @Synchronized fun drain() { runUntil(now) }

    /** One user advance is bounded to five virtual minutes; no due callback is skipped. */
    @Synchronized fun advanceBy(deltaMs: Long) {
        requireOpen()
        if (deltaMs !in 0..MAX_ADVANCE_MS || deltaMs > Long.MAX_VALUE - now) fail("DEMO_TIME_RANGE")
        runUntil(now + deltaMs)
    }

    @Synchronized override fun close() {
        isClosed = true
        discardPending()
    }

    private fun runUntil(target: Long) {
        requireOpen()
        if (running) fail("DEMO_REENTRANT_RUN")
        running = true
        var events = 0
        try {
            while (!isClosed) {
                val item = if (immediate.isNotEmpty()) immediate.removeFirst() else {
                    val next = scheduled.peek() ?: break
                    if (next.at > target) break
                    if (events >= MAX_EVENTS_PER_RUN) fail("DEMO_EVENT_LIMIT")
                    scheduled.remove()
                }
                if (events >= MAX_EVENTS_PER_RUN) fail("DEMO_EVENT_LIMIT")
                // Scheduled times are checked at insertion; immediate work shares its parent's now.
                if (item.at < now) fail("DEMO_TIME_RANGE")
                now = item.at
                item.pending = false
                val task = checkNotNull(item.task)
                item.task = null
                activePriority = item.priority
                events++
                try {
                    task()
                } catch (failure: DemoVirtualTimeException) {
                    fail(failureCode ?: failure.code)
                } catch (_: Throwable) {
                    fail("DEMO_CALLBACK_FAILED")
                } finally {
                    activePriority = null
                }
                // Even a callback that catches the queue's failure cannot resume a failed batch.
                failureCode?.let { throw DemoVirtualTimeException(it) }
            }
            if (!isClosed) now = target
        } finally {
            activePriority = null
            running = false
        }
    }

    private fun enqueueScheduled(at: Long, priority: DemoEventPriority, task: () -> Unit): Item =
        newItem(at, priority, task).also { scheduled.add(it) }

    private fun newItem(at: Long, priority: DemoEventPriority, task: () -> Unit): Item {
        if (scheduled.size + immediate.size >= MAX_PENDING) fail("DEMO_PENDING_LIMIT")
        if (nextOrder == Long.MAX_VALUE) fail("DEMO_SEQUENCE_LIMIT")
        return Item(at, priority, nextOrder++, task)
    }

    @Synchronized private fun cancel(item: Item) {
        if (!item.pending) return
        item.pending = false
        item.task = null
        // At most 1,024 live items; cancelled entries are removed immediately, not kept as tombstones.
        scheduled.remove(item)
    }

    private fun requireOpen() {
        if (isClosed) throw DemoVirtualTimeException("DEMO_CLOCK_CLOSED")
    }

    private fun fail(code: String): Nothing {
        if (failureCode == null) failureCode = code
        isClosed = true
        discardPending()
        throw DemoVirtualTimeException(checkNotNull(failureCode))
    }

    private fun discardPending() {
        scheduled.forEach { it.pending = false; it.task = null }
        immediate.forEach { it.pending = false; it.task = null }
        scheduled.clear()
        immediate.clear()
    }

    companion object {
        const val INITIAL_ELAPSED_MS = 100_000L
        const val MAX_ADVANCE_MS = 300_000L
        const val MAX_PENDING = 1_024
        const val MAX_EVENTS_PER_RUN = 10_000
    }
}
