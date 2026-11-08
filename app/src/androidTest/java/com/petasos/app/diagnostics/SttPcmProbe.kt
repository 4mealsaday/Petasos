package com.petasos.app.diagnostics

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.app.Instrumentation
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic PCM transport/recognition check; this never requests the microphone. */
object SttPcmProbe {
    class Failure(val code: String) : RuntimeException()

    private const val FIXTURE = "stt_fixture.pcm"
    private const val EXPECTED = "지금 지나가는 지역의 이야기를 알려 주세요."
    private const val CHUNK_BYTES = 3_200 // 100 ms of mono, 16 kHz, signed LE16 PCM.
    private const val MAX_FIXTURE_BYTES = 16_000 * 2 * 15
    private val separators = Regex("[\\p{P}\\p{Z}\\s]+")

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val startedAt = SystemClock.elapsedRealtime()
        val queue = LinkedBlockingQueue<Observed>()
        val closed = AtomicBoolean(false)
        var sdk: SpeechToText? = null
        var listenerAdded = false
        var audioSent = false
        var readyCount = 0
        var startedCount = 0
        var endedCount = 0
        var finalCount = 0
        var matchingFinal = false
        var primaryFailure: Failure? = null
        val expected = normalize(EXPECTED)

        fun offer(event: Event) {
            if (!closed.get()) {
                queue.offer(Observed(event, SystemClock.elapsedRealtime() - startedAt))
            }
        }

        fun onMain(code: String, action: () -> Unit) {
            try {
                instrumentation.runOnMainSync(action)
            } catch (_: Exception) {
                fail(code)
            }
        }

        fun record(observed: Observed) {
            when (val event = observed.event) {
                Event.Initialized -> fail("stt_pcm_duplicate_initialization")
                Event.Ready -> {
                    readyCount++
                    mark("stt_pcm_ready_at_${observed.elapsedMs}ms")
                }
                Event.Started -> {
                    startedCount++
                    mark("stt_pcm_started_at_${observed.elapsedMs}ms")
                }
                Event.Ended -> {
                    endedCount++
                    mark("stt_pcm_ended_at_${observed.elapsedMs}ms")
                }
                is Event.Final -> {
                    finalCount++
                    mark("stt_pcm_final_at_${observed.elapsedMs}ms")
                    if (!event.nonempty) fail("stt_pcm_empty_final")
                    if (!event.matches) fail("stt_pcm_unexpected_final")
                    matchingFinal = true
                    mark("stt_pcm_fixture_matched")
                }
                is Event.Failure -> fail(event.code)
            }
        }

        fun drain() {
            while (true) record(queue.poll() ?: return)
        }

        val listener = object : ResultListener {
            override fun onReady() = offer(Event.Ready)
            override fun onStartedRecognition() = offer(Event.Started)
            override fun onEndedRecognition() = offer(Event.Ended)
            override fun onUpdated(stt: String, completed: Boolean) {
                // Only booleans leave this callback. Neither text nor audio is logged.
                if (completed) offer(Event.Final(stt.isNotBlank(), normalize(stt) == expected))
            }
            override fun onError(errorType: ErrorType) =
                offer(Event.Failure("stt_pcm_sdk_${errorType.name.lowercase(Locale.ROOT)}"))
            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
            override fun onUpdatedRms(rms: Double) = Unit
        }

        try {
            val pcm = try {
                instrumentation.context.assets.open(FIXTURE).use { stream ->
                    // Cap reads as well as the total test duration, even if the fixture changes.
                    val buffer = ByteArray(MAX_FIXTURE_BYTES + 1)
                    var size = 0
                    while (size < buffer.size) {
                        val count = stream.read(buffer, size, buffer.size - size)
                        if (count < 0) break
                        size += count
                    }
                    if (size == 0 || size > MAX_FIXTURE_BYTES || size % 2 != 0) {
                        fail("stt_pcm_invalid_fixture")
                    }
                    buffer.copyOf(size)
                }
            } catch (failure: Failure) {
                throw failure
            } catch (_: Exception) {
                fail("stt_pcm_fixture_read_failed")
            }

            onMain("stt_pcm_initialize_call_failed") {
                val connection = SpeechToText(instrumentation.targetContext, Mode.ON_DEVICE)
                sdk = connection
                connection.initialize(object : OnInitializeListener {
                    override fun onSuccess() = offer(Event.Initialized)
                    override fun onFailure(errorType: ErrorType) = offer(
                        Event.Failure("stt_pcm_initialize_${errorType.name.lowercase(Locale.ROOT)}"),
                    )
                })
            }
            val initialized = queue.poll(10, TimeUnit.SECONDS)
                ?: fail("stt_pcm_initialization_timeout")
            when (val event = initialized.event) {
                Event.Initialized -> mark("stt_pcm_connected_at_${initialized.elapsedMs}ms")
                is Event.Failure -> fail(event.code)
                else -> fail("stt_pcm_callback_before_initialization")
            }
            val connection = sdk ?: fail("stt_pcm_missing_connection")
            onMain("stt_pcm_listener_setup_failed") {
                // Set the flag first so cleanup attempts removal if addListener partially succeeds.
                listenerAdded = true
                connection.addListener(listener)
                connection.setLocale(Locale.KOREA)
            }

            // sendAudio starts direct-input recognition; request() would select microphone input.
            val audioStartedAt = SystemClock.elapsedRealtime()
            mark("stt_pcm_audio_send_started")
            var offset = 0
            while (offset < pcm.size) {
                drain()
                val end = minOf(offset + CHUNK_BYTES, pcm.size)
                val chunk = pcm.copyOfRange(offset, end)
                audioSent = true
                onMain("stt_pcm_send_failed") { connection.sendAudio(chunk) }
                offset = end
                // Pace by absolute sample time so SDK call time does not accumulate drift.
                val dueAt = audioStartedAt + offset * 1_000L / 32_000
                val delay = dueAt - SystemClock.elapsedRealtime()
                if (delay > 0) SystemClock.sleep(delay)
            }
            onMain("stt_pcm_complete_send_failed") { connection.completeAudioSend() }
            mark("stt_pcm_audio_send_completed")

            val resultDeadline = SystemClock.elapsedRealtime() + 15_000
            while (!matchingFinal || endedCount == 0) {
                val remaining = resultDeadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                record(queue.poll(remaining, TimeUnit.MILLISECONDS) ?: break)
            }
            drain()
            if (!matchingFinal) fail("stt_pcm_final_timeout")
            if (startedCount == 0) fail("stt_pcm_started_missing")
            if (endedCount == 0) {
                mark("stt_pcm_matching_final_without_ended")
                fail("stt_pcm_ended_timeout_after_matching_final")
            }
            mark("stt_pcm_recognition_verified")
        } catch (failure: Exception) {
            // The public failure surface contains only our codes, never vendor payloads.
            primaryFailure = if (failure is Failure) {
                failure
            } else {
                Failure("stt_pcm_probe_failed")
            }
        } finally {
            closed.set(true)
            var teardownFailed = false
            val connection = sdk
            if (connection != null) {
                fun clean(name: String, action: () -> Unit) {
                    try {
                        instrumentation.runOnMainSync(action)
                    } catch (_: Exception) {
                        teardownFailed = true
                        mark("stt_pcm_teardown_${name}_failed")
                    }
                }
                if (audioSent && endedCount == 0) {
                    clean("stop") { connection.stop() }
                    // stop is dispatched asynchronously by SDK 3.3.1. Observe separately from release.
                    SystemClock.sleep(1_000)
                }
                if (listenerAdded) clean("remove_listener") { connection.removeListener(listener) }
                clean("release") { connection.release() }
            }
            mark("stt_pcm_callback_counts_ready_${readyCount}_started_${startedCount}_ended_${endedCount}_final_$finalCount")
            mark("stt_pcm_elapsed_${SystemClock.elapsedRealtime() - startedAt}ms")
            if (teardownFailed && primaryFailure == null) {
                primaryFailure = Failure("stt_pcm_teardown_failed")
            }
        }
        primaryFailure?.let { throw it }
    }

    private fun normalize(text: String): String = text.replace(separators, "")
    private fun fail(code: String): Nothing = throw Failure(code)
    private data class Observed(val event: Event, val elapsedMs: Long)
    private sealed interface Event {
        data object Initialized : Event
        data object Ready : Event
        data object Started : Event
        data object Ended : Event
        data class Final(val nonempty: Boolean, val matches: Boolean) : Event
        data class Failure(val code: String) : Event
    }
}
