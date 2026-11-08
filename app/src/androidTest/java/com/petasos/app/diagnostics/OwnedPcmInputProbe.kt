package com.petasos.app.diagnostics

import android.app.Instrumentation
import android.os.SystemClock
import com.petasos.app.capability.speech.SpeechInputEvent
import com.petasos.app.platform.pleos.speech.PcmRecorder
import com.petasos.app.platform.pleos.speech.PcmInputLifecycleEvent
import com.petasos.app.platform.pleos.speech.PleosPcmSpeechInput
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Actual Pleos PCM adapter; synthetic capture by default. Real microphone is explicit opt-in. */
object OwnedPcmInputProbe {
    private const val EXPECTED = "지금 지나가는 지역의 이야기를 알려 주세요."
    private val separators = Regex("[\\p{P}\\p{Z}\\s]+")

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit, realMicrophone: Boolean = false, continuousSilence: Boolean = false, prefixSilenceMs: Int = 0, finiteEof: Boolean = false) {
        require(prefixSilenceMs in 0..2_000)
        require(!realMicrophone || (!continuousSilence && prefixSilenceMs == 0))
        require(!finiteEof || (!realMicrophone && !continuousSilence && prefixSilenceMs == 0))
        val beganAt = SystemClock.elapsedRealtime()
        var originalFixtureSamples = 0
        var trimmedTrailingZeroSamples = 0
        val fixture = if (realMicrophone) null else instrumentation.context.assets.open("stt_fixture.pcm").use {
            val bytes = it.readBytes().also { bytes -> check(bytes.isNotEmpty() && bytes.size <= 480_000 && bytes.size % 2 == 0) }
            originalFixtureSamples = bytes.size / 2
            if (!finiteEof) bytes else {
                var retained = bytes.size
                // PCM16 sample equality only: no amplitude threshold, VAD or speech-content trimming.
                while (retained >= 2 && bytes[retained - 1] == 0.toByte() && bytes[retained - 2] == 0.toByte()) retained -= 2
                check(retained > 0 && retained < bytes.size) { "OWNED_INPUT_NO_TRAILING_ZERO_SILENCE" }
                trimmedTrailingZeroSamples = (bytes.size - retained) / 2
                bytes.copyOf(retained).also { bytes.fill(0) }
            }
        }
        val source = fixture?.let { FixtureRecorder(it, continuousSilence, prefixSilenceMs) }
        val queue = LinkedBlockingQueue<Observation>()
        fun offer(event: Event) { queue.offer(Observation(event, SystemClock.elapsedRealtime())) }
        var adapter: PleosPcmSpeechInput? = null
        var requested = false
        var finalSeen = false
        var started = false
        var stopped = false
        var finalAt = 0L
        var readyAt = 0L
        var startedAt = 0L
        var endedAt = 0L
        val lifecycleTimes = mutableMapOf<PcmInputLifecycleEvent, Long>()
        val lifecycleCounts = mutableMapOf<PcmInputLifecycleEvent, Int>()
        fun recordLifecycle(event: PcmInputLifecycleEvent, at: Long) {
            lifecycleCounts[event] = (lifecycleCounts[event] ?: 0) + 1
            if (lifecycleTimes.putIfAbsent(event, at) == null) {
                val name = when (event) {
                    PcmInputLifecycleEvent.REQUEST_ENTERED -> "request_entered"
                    PcmInputLifecycleEvent.EOF_OBSERVED -> "eof_observed"
                    PcmInputLifecycleEvent.CAPTURE_STOP_FINISHED -> "capture_stop_finished"
                    PcmInputLifecycleEvent.COMPLETE_AUDIO_SEND_ENTERED -> "complete_audio_send_entered"
                    PcmInputLifecycleEvent.COMPLETE_AUDIO_SEND_RETURNED -> "complete_audio_send_returned"
                    PcmInputLifecycleEvent.SDK_ENDED_CALLBACK -> "sdk_ended_callback"
                    PcmInputLifecycleEvent.SDK_FINAL_CALLBACK -> "sdk_final_callback"
                    PcmInputLifecycleEvent.STOP_ENTERED -> "stop_entered"
                }
                mark("owned_input_${name}_at_${at - beganAt}ms")
            }
        }
        val listener: (SpeechInputEvent) -> Unit = { event ->
            when (event) {
                SpeechInputEvent.Connected -> offer(Event.Connected)
                SpeechInputEvent.Ready -> offer(Event.Ready)
                SpeechInputEvent.Started -> offer(Event.Started)
                SpeechInputEvent.Ended -> offer(Event.Ended)
                is SpeechInputEvent.Failed -> offer(Event.Failed(event.failure.code.name))
                is SpeechInputEvent.Transcript -> if (event.isFinal) offer(Event.Final(
                    event.text.isNotBlank(), event.text.length <= 4_000,
                    event.text.replace(separators, "") == EXPECTED.replace(separators, ""),
                ))
            }
        }
        try {
            instrumentation.runOnMainSync {
                val callback: (Boolean) -> Unit = { offer(Event.Stopped(it)) }
                adapter = if (source == null) {
                    PleosPcmSpeechInput(instrumentation.targetContext, callback, listener)
                } else {
                    PleosPcmSpeechInput(instrumentation.targetContext, { source }, callback, listener,
                        onLifecycle = { event, at -> if (finiteEof) queue.offer(Observation(Event.Lifecycle(event), at)) })
                }
                adapter!!.connect()
            }
            val deadline = SystemClock.elapsedRealtime() + if (realMicrophone) 75_000 else 30_000
            while (!stopped) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                check(remaining > 0) { "OWNED_INPUT_TIMEOUT" }
                val observation = queue.poll(remaining, TimeUnit.MILLISECONDS) ?: error("OWNED_INPUT_TIMEOUT")
                when (val event = observation.event) {
                    Event.Connected -> {
                        check(!requested)
                        requested = true
                        mark("owned_input_connected_at_${observation.at - beganAt}ms")
                        instrumentation.runOnMainSync { adapter!!.request() }
                    }
                    Event.Ready -> {
                        readyAt = observation.at
                        mark(if (realMicrophone) "owned_microphone_recording" else "owned_fixture_recording")
                        mark("owned_input_ready_at_${readyAt - beganAt}ms")
                    }
                    Event.Started -> {
                        started = true
                        startedAt = observation.at
                        val elapsed = observation.at
                        mark("owned_input_started_at_${elapsed - beganAt}ms")
                        mark("owned_input_ready_to_started_${elapsed - readyAt}ms")
                        if (readyAt > 0 && elapsed - readyAt > 5_000) mark("owned_input_product_first_speech_deadline_exceeded")
                    }
                    Event.Ended -> {
                        endedAt = observation.at
                        mark("owned_input_epd_ended_at_${endedAt - beganAt}ms")
                    }
                    is Event.Final -> {
                        check(!finalSeen && event.nonempty && event.bounded) { "OWNED_INPUT_INVALID_FINAL" }
                        check(realMicrophone || event.fixtureMatch) { "OWNED_INPUT_FIXTURE_MISMATCH" }
                        finalSeen = true
                        finalAt = observation.at
                        mark("owned_input_final_at_${finalAt - beganAt}ms")
                        if (endedAt > 0) {
                            mark("owned_input_ended_to_final_${finalAt - endedAt}ms")
                            if (finalAt - endedAt > 500) mark("owned_input_product_final_grace_exceeded")
                        }
                        mark(if (realMicrophone) "owned_microphone_final_nonempty" else "owned_input_fixture_matched")
                        instrumentation.runOnMainSync { adapter!!.stop() }
                    }
                    is Event.Stopped -> {
                        check(event.confirmed && finalSeen) { "OWNED_INPUT_STOP_NOT_CONFIRMED" }
                        stopped = true
                        mark("owned_input_stop_confirmed")
                        mark("owned_input_stop_confirmed_at_${observation.at - beganAt}ms")
                    }
                    is Event.Failed -> error("OWNED_INPUT_${event.code}")
                    is Event.Lifecycle -> recordLifecycle(event.kind, observation.at)
                }
            }
            check(started && finalAt > 0)
            source?.let { check(it.stopCount == 1 && it.releaseCount == 1 && !it.recording) }
            if (continuousSilence) mark("owned_input_continuous_pcm_recognized")
            // Stop rejects late results, independently of the actual EPD/final callback ordering.
            val observationEnd = SystemClock.elapsedRealtime() + 600
            while (SystemClock.elapsedRealtime() < observationEnd) {
                val observation = queue.poll(50, TimeUnit.MILLISECONDS)
                val event = observation?.event
                if (observation != null && event is Event.Lifecycle) recordLifecycle(event.kind, observation.at)
                check(event !is Event.Final && event !is Event.Stopped && event !is Event.Failed) {
                    "OWNED_INPUT_EVENT_AFTER_STOP"
                }
            }
            mark("owned_input_late_results_rejected")
            if (finiteEof) {
                fun observed(event: PcmInputLifecycleEvent): Long {
                    check(lifecycleCounts[event] == 1) { "OWNED_INPUT_EOF_LIFECYCLE_MISSING_OR_REPEATED" }
                    return checkNotNull(lifecycleTimes[event])
                }
                val eof = observed(PcmInputLifecycleEvent.EOF_OBSERVED)
                val captureStop = observed(PcmInputLifecycleEvent.CAPTURE_STOP_FINISHED)
                val completeEnter = observed(PcmInputLifecycleEvent.COMPLETE_AUDIO_SEND_ENTERED)
                val completeReturn = observed(PcmInputLifecycleEvent.COMPLETE_AUDIO_SEND_RETURNED)
                val sdkFinal = observed(PcmInputLifecycleEvent.SDK_FINAL_CALLBACK)
                val sdkEnded = lifecycleTimes[PcmInputLifecycleEvent.SDK_ENDED_CALLBACK]
                    ?.let { observed(PcmInputLifecycleEvent.SDK_ENDED_CALLBACK) }
                check(eof <= captureStop && captureStop <= completeEnter && completeEnter <= completeReturn) { "OWNED_INPUT_EOF_COMPLETION_ORDER" }
                // Millisecond ties cannot prove EOF preceded the actual SDK callback ingress.
                check(eof < sdkFinal && (sdkEnded == null || eof < sdkEnded)) { "OWNED_INPUT_EOF_ORDER_UNPROVEN" }
                val finiteSource = checkNotNull(source)
                check(finiteSource.eofAt > 0 && finiteSource.eofAt <= eof && finiteSource.payloadSampleCount == fixture!!.size / 2)
                mark("owned_input_finite_eof_validated")
                mark("owned_input_functional_pass")
                val firstSpeechOverdue = readyAt > 0 && startedAt - readyAt > 5_000
                val finalOverdue = endedAt > 0 && finalAt - endedAt > 500
                when {
                    firstSpeechOverdue || finalOverdue -> mark("owned_input_product_timing_fail")
                    readyAt <= 0 || startedAt < readyAt || endedAt <= 0 || finalAt < endedAt -> mark("owned_input_product_timing_unverified")
                    else -> mark("owned_input_product_timing_pass")
                }
            }
        } finally {
            source?.let {
                if (finiteEof) {
                    mark("owned_fixture_original_samples_${originalFixtureSamples}")
                    mark("owned_fixture_trimmed_trailing_zero_samples_${trimmedTrailingZeroSamples}")
                }
                mark("owned_fixture_reads_${it.readCount}_samples_${it.sampleCount}")
                mark("owned_fixture_prefix_silence_${prefixSilenceMs}ms")
                // Capture timestamps, never relabel these as SDK readiness, EPD or send ACKs.
                if (it.firstSamplesAt > 0) mark("owned_fixture_first_samples_at_${it.firstSamplesAt - beganAt}ms")
                if (it.firstNonzeroAt > 0) mark("owned_fixture_first_nonzero_at_${it.firstNonzeroAt - beganAt}ms")
                if (it.eofAt > 0) mark("owned_fixture_eof_at_${it.eofAt - beganAt}ms")
                if (it.stopAt > 0) mark("owned_fixture_capture_stop_at_${it.stopAt - beganAt}ms")
                mark("owned_fixture_payload_samples_${it.payloadSampleCount}_of_${fixture!!.size / 2}")
            }
            mark("owned_input_close_requested_at_${SystemClock.elapsedRealtime() - beganAt}ms")
            instrumentation.runOnMainSync {
                mark("owned_input_close_entered")
                adapter?.close()
                mark("owned_input_close_returned")
            }
            fixture?.fill(0)
            // Observe the SDK's known stop/release drain; this delay never establishes stop proof.
            SystemClock.sleep(1_100)
        }
    }

    /** Paces the synthetic PCM as a recorder would, without ever constructing AudioRecord. */
    private class FixtureRecorder(private val pcm: ByteArray, private val continuousSilence: Boolean, prefixSilenceMs: Int) : PcmRecorder {
        private val prefixBytes = prefixSilenceMs * 32
        private var startAt = 0L
        private var offset = 0
        private var active = false
        var stopCount = 0; private set
        var releaseCount = 0; private set
        @Volatile var readCount = 0; private set
        @Volatile var sampleCount = 0; private set
        @Volatile var payloadSampleCount = 0; private set
        @Volatile var firstSamplesAt = 0L; private set
        @Volatile var firstNonzeroAt = 0L; private set
        @Volatile var eofAt = 0L; private set
        @Volatile var stopAt = 0L; private set
        override fun start() { check(startAt == 0L); startAt = SystemClock.elapsedRealtime(); active = true }
        override val recording get() = active
        override fun read(samples: ShortArray): Int {
            check(active)
            readCount++
            val elapsedBytes = (SystemClock.elapsedRealtime() - startAt) * 32
            val due = (if (continuousSilence) elapsedBytes else minOf(pcm.size.toLong() + prefixBytes, elapsedBytes)) / 2 * 2
            val count = minOf(samples.size, ((due - offset) / 2).toInt().coerceAtLeast(0))
            val now = SystemClock.elapsedRealtime()
            if (count > 0 && firstSamplesAt == 0L) firstSamplesAt = now
            repeat(count) { i ->
                val at = offset + i * 2 - prefixBytes
                samples[i] = if (at < 0 || at >= pcm.size) 0 else {
                    payloadSampleCount++
                    ((pcm[at].toInt() and 0xff) or (pcm[at + 1].toInt() shl 8)).toShort()
                }
                if (samples[i].toInt() != 0 && firstNonzeroAt == 0L) firstNonzeroAt = now
            }
            offset += count * 2
            sampleCount += count
            if (endOfInput && eofAt == 0L) eofAt = now
            return count
        }
        override fun stop() { active = false; stopCount++; stopAt = SystemClock.elapsedRealtime() }
        override val endOfInput get() = !continuousSilence && offset == pcm.size + prefixBytes
        override val stopped get() = !active
        override fun release() { releaseCount++ }
    }

    private data class Observation(val event: Event, val at: Long)

    private sealed interface Event {
        data object Connected : Event
        data object Ready : Event
        data object Started : Event
        data object Ended : Event
        data class Final(val nonempty: Boolean, val bounded: Boolean, val fixtureMatch: Boolean) : Event
        data class Stopped(val confirmed: Boolean) : Event
        data class Failed(val code: String) : Event
        data class Lifecycle(val kind: PcmInputLifecycleEvent) : Event
    }
}
