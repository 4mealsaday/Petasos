package com.petasos.app.demo

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class DemoControllerCommand {
    PLAY, STOP, NEXT, PREVIOUS, ROUTE_ANSEONG, ROUTE_YUSEONG, OBSERVATION_GAP,
    ADVANCE_1, ADVANCE_10, ADVANCE_30, RESET,
}

internal enum class DemoControllerFailure {
    INITIALIZATION_FAILED, EXECUTION_FAILED, RESET_FAILED, CLOSE_UNCONFIRMED,
}

internal data class DemoControllerState(
    val snapshot: DemoScenarioSnapshot?,
    val busy: Boolean,
    val failure: DemoControllerFailure?,
    val resetFailed: Boolean,
)

/**
 * The Activity supplies only its current interaction gate and explicit commands. All fixture,
 * Room and virtual-clock work belongs to one bounded worker. Gate loss revokes output authority
 * immediately; the queued Stop then commits the paused state without advancing virtual time.
 */
internal class DemoSessionController(context: Context, onState: (DemoControllerState) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val authority = DemoControllerAuthority()
    private val normalBusy = AtomicBoolean(true)
    private val stopPending = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val consumer = AtomicReference<((DemoControllerState) -> Unit)?>(onState)
    private data class Publication(val generation: Long, val state: DemoControllerState)
    private val published = AtomicReference(Publication(0, DemoControllerState(null, true, null, false)))
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(4), { task -> Thread(task, "PetasosDemoWorker").apply { isDaemon = true } })
    private val delivery = Runnable { deliverLatest() }
    private val closeLock = Any()
    private val closeCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var closeResult: Boolean? = null

    // Worker-owned. A failed Reset retains these references so durable close/deletion can retry.
    private var fixture: DemoFixtureStore? = null
    private var session: DemoScenarioSession? = null
    private var snapshot: DemoScenarioSnapshot? = null
    private var failure: DemoControllerFailure? = null
    private var resetFailed = false
    private val timeline = DemoReplayTimeline()

    init {
        publishBusy()
        worker.execute {
            try {
                if (!closing.get()) createSession()
            } catch (_: Throwable) {
                authority.revoke()
                failure = DemoControllerFailure.INITIALIZATION_FAILED
            } finally {
                normalBusy.set(false)
                publishWorker()
            }
        }
    }

    /** Opening the gate never grants a playback permit or resumes a previously paused session. */
    fun setAllowed(value: Boolean) {
        if (closing.get()) return
        authority.setAllowed(value)
        if (!value) requestStop(revoke = false)
    }

    /** Main-thread callers see busy=true synchronously, before any work is enqueued. */
    fun submit(command: DemoControllerCommand): Boolean {
        if (closing.get()) return false
        if (command == DemoControllerCommand.STOP) return requestStop(revoke = true)
        if (!authority.isAllowed() || stopPending.get() ||
            (published.get().state.failure != null && command != DemoControllerCommand.RESET) ||
            !normalBusy.compareAndSet(false, true)) return false
        val permit = if (command == DemoControllerCommand.RESET) {
            authority.revoke(); null
        } else authority.issue()
        if (command != DemoControllerCommand.RESET && permit == null) {
            normalBusy.set(false)
            return false
        }
        publishBusy()
        // A synchronous state observer may close the owner. Do not enqueue after its final close.
        if (closing.get()) { normalBusy.set(false); return false }
        worker.execute {
            try {
                if (command == DemoControllerCommand.RESET) {
                    if (!closing.get()) resetSession()
                } else if (permit != null && authority.isCurrent(permit) && !closing.get()) {
                    val success = execute(command, permit)
                    if (success && command !in ADVANCE_COMMANDS) timeline.record(command)
                    if (!success && authority.isCurrent(permit) && !closing.get()) {
                        authority.revoke()
                        failure = DemoControllerFailure.EXECUTION_FAILED
                    }
                    if (!authority.isCurrent(permit)) stopAndRecord()
                }
            } catch (_: Throwable) {
                authority.revoke()
                if (command == DemoControllerCommand.RESET) {
                    resetFailed = true
                    failure = DemoControllerFailure.RESET_FAILED
                } else failure = DemoControllerFailure.EXECUTION_FAILED
                try { stopAndRecord() } catch (_: Throwable) { /* Keep fixture for acknowledged cleanup. */ }
            } finally {
                normalBusy.set(false)
                publishWorker()
            }
        }
        return true
    }

    /** Advances only this isolated scenario. The Activity's clock never owns output authority. */
    fun advanceBy(deltaMs: Long): Boolean = submitTimed(deltaMs = deltaMs, seekToMs = null)

    /** Rebuilds the demo's own fixture and action history, then stays paused at the new position. */
    fun seekTo(positionMs: Long): Boolean = submitTimed(deltaMs = null, seekToMs = positionMs)

    private fun submitTimed(deltaMs: Long?, seekToMs: Long?): Boolean {
        if ((deltaMs != null && deltaMs !in 1..ADVANCE_CHUNK_MS) ||
            (seekToMs != null && seekToMs !in 0..DemoReplayTimeline.DURATION_MS)) return false
        if (closing.get() || !authority.isAllowed() || stopPending.get() ||
            published.get().state.failure != null || !normalBusy.compareAndSet(false, true)) return false
        val permit = authority.issue() ?: run { normalBusy.set(false); return false }
        publishBusy()
        if (closing.get()) { normalBusy.set(false); return false }
        worker.execute {
            try {
                val success = if (seekToMs != null) replayTo(seekToMs, permit)
                    else advanceBounded(checkNotNull(deltaMs), permit)
                if (!success && authority.isCurrent(permit) && !closing.get()) {
                    authority.revoke()
                    failure = DemoControllerFailure.EXECUTION_FAILED
                }
                if (seekToMs != null) {
                    session?.stop()
                    updateTimelinePosition()
                    timeline.pausedAfterSeek()
                } else if (!authority.isCurrent(permit)) stopAndRecord()
            } catch (_: Throwable) {
                authority.revoke()
                failure = DemoControllerFailure.EXECUTION_FAILED
                try { stopAndRecord() } catch (_: Throwable) { /* Retain owned files. */ }
            } finally {
                normalBusy.set(false)
                publishWorker()
            }
        }
        return true
    }

    /** Only the explicit completion callback may run after close; state observers are detached now. */
    fun close(onClosed: (Boolean) -> Unit = {}) {
        synchronized(closeLock) {
            closeResult?.let { result -> main.post { onClosed(result) }; return }
            // Activity lifecycle has one owner; keep repeated close requests bounded as well.
            if (closeCallbacks.size < MAX_CLOSE_CALLBACKS) closeCallbacks += onClosed
            else main.post { try { onClosed(false) } catch (_: Throwable) { } }
        }
        if (!closing.compareAndSet(false, true)) return
        authority.close()
        consumer.set(null)
        main.removeCallbacks(delivery)
        worker.execute {
            val acknowledged = try { disposeAcknowledged() } catch (_: Throwable) { false }
            if (!acknowledged) {
                try { fixture?.closeKeepingFiles() } catch (_: Throwable) { /* Preserve files. */ }
                try { fixture?.clock?.close() } catch (_: Throwable) { /* No remaining virtual work. */ }
            }
            session = null
            fixture = null
            snapshot = null
            val callbacks = synchronized(closeLock) {
                closeResult = acknowledged
                closeCallbacks.toList().also { closeCallbacks.clear() }
            }
            worker.shutdown()
            main.post { callbacks.forEach { callback -> try { callback(acknowledged) } catch (_: Throwable) { } } }
        }
    }

    private fun requestStop(revoke: Boolean): Boolean {
        if (closing.get()) return false
        if (revoke) authority.revoke()
        val enqueue = stopPending.compareAndSet(false, true)
        publishBusy()
        if (!enqueue) return true
        if (closing.get()) { stopPending.set(false); return false }
        worker.execute {
            try {
                if (!stopAndRecord() && !closing.get()) failure = DemoControllerFailure.EXECUTION_FAILED
            } catch (_: Throwable) {
                failure = DemoControllerFailure.EXECUTION_FAILED
            } finally {
                stopPending.set(false)
                publishWorker()
            }
        }
        return true
    }

    private fun stopAndRecord(): Boolean {
        val active = session ?: return true
        val state = active.snapshot()
        val position = (state.elapsedMs - DemoFixtureStore.START_MS).coerceIn(0, DemoReplayTimeline.DURATION_MS)
        return timeline.stopAndRecord(position, state.playIntent, active::stop)
    }

    private fun execute(command: DemoControllerCommand, permit: DemoControllerAuthority.Permit): Boolean {
        val active = session ?: return false
        return when (command) {
            DemoControllerCommand.PLAY -> active.play()
            DemoControllerCommand.NEXT -> active.next()
            DemoControllerCommand.PREVIOUS -> active.previous()
            DemoControllerCommand.ROUTE_ANSEONG -> active.changeRoute(DemoRouteChoice.ANSEONG)
            DemoControllerCommand.ROUTE_YUSEONG -> active.changeRoute(DemoRouteChoice.YUSEONG)
            DemoControllerCommand.OBSERVATION_GAP -> active.changeRoute(DemoRouteChoice.OBSERVATION_GAP)
            DemoControllerCommand.ADVANCE_1, DemoControllerCommand.ADVANCE_10, DemoControllerCommand.ADVANCE_30 -> {
                val remaining = when (command) {
                    DemoControllerCommand.ADVANCE_1 -> 1_000L
                    DemoControllerCommand.ADVANCE_10 -> 10_000L
                    else -> 30_000L
                }
                advanceBounded(remaining, permit)
            }
            DemoControllerCommand.STOP, DemoControllerCommand.RESET -> false
        }
    }

    private fun advanceBounded(deltaMs: Long, permit: DemoControllerAuthority.Permit): Boolean {
        val active = session ?: return false
        var remaining = minOf(deltaMs,
            DemoReplayTimeline.DURATION_MS - (active.snapshot().elapsedMs - DemoFixtureStore.START_MS))
        while (remaining > 0) {
            if (closing.get() || !authority.isCurrent(permit)) return false
            val step = minOf(remaining, ADVANCE_CHUNK_MS)
            if (!active.advanceBy(step)) return false
            remaining -= step
        }
        updateTimelinePosition()
        if (timeline.positionMs == DemoReplayTimeline.DURATION_MS) active.stop()
        return authority.isCurrent(permit) && !closing.get()
    }

    private fun replayTo(targetMs: Long, permit: DemoControllerAuthority.Permit): Boolean {
        if (closing.get() || !authority.isCurrent(permit)) return false
        val actions = timeline.through(targetMs)
        if (!disposeAcknowledged()) {
            resetFailed = true
            failure = DemoControllerFailure.RESET_FAILED
            return false
        }
        session = null
        fixture = null
        snapshot = null
        // Durable close/deletion completes before any new session or synthetic output starts.
        if (closing.get()) return false
        createSession()
        timeline.moveTo(0)
        if (!authority.isCurrent(permit)) return false
        for (entry in actions) {
            if (!advanceBounded(entry.atMs - timeline.positionMs, permit)) return false
            val active = session ?: return false
            val success = if (entry.command == DemoControllerCommand.STOP) active.stop()
                else execute(entry.command, permit)
            if (!success || !authority.isCurrent(permit) || closing.get()) return false
        }
        if (!advanceBounded(targetMs - timeline.positionMs, permit)) return false
        val paused = session?.stop() == true
        if (paused) timeline.pausedAfterSeek()
        return paused
    }

    private fun updateTimelinePosition() {
        session?.snapshot()?.let {
            timeline.moveTo((it.elapsedMs - DemoFixtureStore.START_MS).coerceIn(0, DemoReplayTimeline.DURATION_MS))
        }
    }

    private fun createSession() {
        check(fixture == null && session == null) { "DEMO_FIXTURE_ALREADY_OWNED" }
        val created = DemoFixtureStore(app, DemoVirtualTime())
        fixture = created
        session = DemoScenarioSession(created, authority::outputAllowed)
        snapshot = session?.snapshot()
    }

    private fun resetSession() {
        if (!disposeAcknowledged()) {
            resetFailed = true
            failure = DemoControllerFailure.RESET_FAILED
            return
        }
        session = null
        fixture = null
        snapshot = null
        failure = null
        resetFailed = false
        timeline.clear()
        // An accepted reset may finish cleanup after gate loss, but never supplies a play permit.
        if (!closing.get()) createSession()
    }

    private fun disposeAcknowledged(): Boolean {
        val owned = fixture ?: return session == null
        val active = session ?: return false // A partial constructor has no durable runtime ACK.
        if (!active.close()) return false
        return owned.closeAndDeleteAfterAcknowledgedClose()
    }

    private fun publishBusy() {
        authority.stamp { generation ->
            published.set(Publication(generation, published.get().state.copy(busy = true)))
        }
        if (Looper.myLooper() == main.looper) deliverLatest() else main.post(delivery)
    }

    private fun publishWorker() {
        if (closing.get()) return
        var generation = authority.generation()
        try { session?.snapshot()?.let { snapshot = it } } catch (_: Throwable) {
            // A failed deletion may already have closed Room; retain its last projection for Reset.
            if (!resetFailed) {
                authority.revoke()
                generation = authority.generation()
                failure = DemoControllerFailure.EXECUTION_FAILED
                try { stopAndRecord() } catch (_: Throwable) { /* Preserve unconfirmed fixture. */ }
            }
        }
        val next = DemoControllerState(snapshot, normalBusy.get() || stopPending.get(), failure, resetFailed)
        if (authority.ifGeneration(generation) { published.set(Publication(generation, next)) }) {
            main.removeCallbacks(delivery)
            main.post(delivery)
        }
    }

    private fun deliverLatest() {
        if (closing.get()) return
        val value = published.get()
        if (authority.generation() != value.generation) return
        val callback = consumer.get() ?: return
        try { callback(value.state) } catch (_: Throwable) { /* UI failure cannot grant output authority. */ }
    }

    private companion object {
        const val ADVANCE_CHUNK_MS = 1_000L
        const val MAX_CLOSE_CALLBACKS = 8
        val ADVANCE_COMMANDS = setOf(DemoControllerCommand.ADVANCE_1,
            DemoControllerCommand.ADVANCE_10, DemoControllerCommand.ADVANCE_30)
    }
}

/** Identity-bound permits prevent a stopped command from reviving after a later gate/Play. */
internal class DemoControllerAuthority {
    internal class Permit internal constructor(internal val generation: Long)
    private var allowed = false
    private var closed = false
    private var epoch = 0L
    private var current: Permit? = null

    @Synchronized fun setAllowed(value: Boolean) {
        if (closed) return
        allowed = value
        if (!value) revoke()
    }
    @Synchronized fun isAllowed(): Boolean = allowed && !closed
    @Synchronized fun issue(): Permit? {
        if (!isAllowed()) return null
        revoke()
        if (closed) return null
        return Permit(epoch).also { current = it }
    }
    @Synchronized fun revoke() {
        current = null
        if (epoch == Long.MAX_VALUE) { closed = true; allowed = false } else epoch++
    }
    @Synchronized fun isCurrent(permit: Permit): Boolean =
        isAllowed() && current === permit && permit.generation == epoch
    @Synchronized fun outputAllowed(): Boolean = current?.let(::isCurrent) == true
    @Synchronized fun generation(): Long = epoch
    @Synchronized fun stamp(action: (Long) -> Unit) { action(epoch) }
    @Synchronized fun ifGeneration(expected: Long, action: () -> Unit): Boolean {
        if (epoch != expected) return false
        action()
        return true
    }
    @Synchronized fun close() { revoke(); allowed = false; closed = true }
}
