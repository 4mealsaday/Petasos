package com.petasos.app.demo

import com.petasos.app.playback.OperationToken
import com.petasos.app.runtime.RuntimeCancellation
import com.petasos.app.runtime.RuntimeInputEvent
import com.petasos.app.runtime.RuntimeMusic
import com.petasos.app.runtime.RuntimeMusicEvent
import com.petasos.app.runtime.RuntimeMusicSource
import com.petasos.app.runtime.RuntimeOutputEvent
import com.petasos.app.runtime.RuntimeSpeech
import com.petasos.app.runtime.RuntimeText

/** Silent fixture output, not TTS. Text is neither retained, displayed, recorded nor logged. */
internal class DemoSilentSpeech(
    private val time: DemoVirtualTime,
    private val durationMs: (OperationToken) -> Long,
    private val allowed: () -> Boolean,
    private val gateLost: () -> Unit,
) : RuntimeSpeech {
    private class Slot(
        val attempt: OperationToken,
        val callback: (RuntimeOutputEvent) -> Unit,
        val startedAtMs: Long,
        val durationMs: Long,
        var completion: RuntimeCancellation? = null,
    )
    private var active: Slot? = null

    /** Display timing of a silent public script; never presented as measured TTS word alignment. */
    fun progress(): Pair<Long, Long>? = active?.let {
        (time.now - it.startedAtMs).coerceIn(0, it.durationMs) to it.durationMs
    }

    override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) {
        check(active == null) { "DEMO_OUTPUT_BUSY" }
        if (!allowed()) { callback(RuntimeOutputEvent.STOPPED); gateLost(); return }
        val duration = durationMs(attempt)
        check(duration in 1..3_600_000L) { "DEMO_DURATION_UNAVAILABLE" }
        val slot = Slot(attempt, callback, time.now, duration)
        active = slot
        slot.completion = time.scheduleAt(Math.addExact(time.now, duration), DemoEventPriority.OUTPUT) {
            if (active === slot) {
                if (!allowed()) gateLost()
                else { active = null; callback(RuntimeOutputEvent.COMPLETED) }
            }
        }
        callback(RuntimeOutputEvent.STARTED)
    }

    override fun stopOutput(attempt: OperationToken) {
        val slot = active ?: return
        if (slot.attempt != attempt) return
        active = null
        slot.completion?.cancel()
        slot.callback(RuntimeOutputEvent.STOPPED)
    }

    override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) =
        error("DEMO_INPUT_UNAVAILABLE")

    override fun stopInput(input: OperationToken) = error("DEMO_INPUT_UNAVAILABLE")
    override fun close() { check(active == null) { "DEMO_OUTPUT_NOT_STOPPED" } }
}

/** A deterministic ten-second silent source. Only natural completion emits Completed. */
internal class DemoSilentMusic(
    private val time: DemoVirtualTime,
    private val allowed: () -> Boolean,
    private val gateLost: () -> Unit,
) : RuntimeMusic {
    private class Slot(
        val attempt: OperationToken,
        val initialPositionMs: Long,
        val startedAtMs: Long,
        val callback: (RuntimeMusicEvent) -> Unit,
        var completion: RuntimeCancellation? = null,
    )
    private var active: Slot? = null

    fun positionMs(): Long? = active?.let { position(it) }

    override fun startOutput(
        attempt: OperationToken,
        source: RuntimeMusicSource,
        positionMs: Long,
        callback: (RuntimeMusicEvent) -> Unit,
    ) {
        check(active == null && source.assetId == DemoFixtureStore.MUSIC_ID &&
            source.uri == "synthetic://no-audio" && source.durationMs == DemoFixtureStore.MUSIC_DURATION_MS) {
            "DEMO_MUSIC_UNAVAILABLE"
        }
        check(positionMs in 0 until DemoFixtureStore.MUSIC_DURATION_MS) { "DEMO_POSITION_UNAVAILABLE" }
        if (!allowed()) { callback(RuntimeMusicEvent.Stopped(positionMs)); gateLost(); return }
        val slot = Slot(attempt, positionMs, time.now, callback)
        active = slot
        slot.completion = time.scheduleAt(
            Math.addExact(time.now, DemoFixtureStore.MUSIC_DURATION_MS - positionMs), DemoEventPriority.OUTPUT,
        ) {
            if (active === slot) {
                if (!allowed()) gateLost()
                else { active = null; callback(RuntimeMusicEvent.Completed(DemoFixtureStore.MUSIC_DURATION_MS)) }
            }
        }
        callback(RuntimeMusicEvent.Started)
    }

    override fun stopOutput(attempt: OperationToken) {
        val slot = active ?: return
        if (slot.attempt != attempt) return
        val position = position(slot)
        active = null
        slot.completion?.cancel()
        slot.callback(RuntimeMusicEvent.Stopped(position))
    }

    private fun position(slot: Slot): Long =
        (slot.initialPositionMs + time.now - slot.startedAtMs).coerceIn(0, DemoFixtureStore.MUSIC_DURATION_MS)

    override fun close() { check(active == null) { "DEMO_OUTPUT_NOT_STOPPED" } }
}
