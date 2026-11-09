package com.petasos.app.diagnostics

import android.app.Instrumentation
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.petasos.app.platform.android.audio.AndroidOutputAudioFocus
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Synthetic focus requests with real AudioManager callbacks. No player, TTS, microphone or route. */
object AudioFocusRecoveryProbe {
    class Failure(val outcome: String, val code: String) : RuntimeException(code)

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val manager = requireNotNull(context.getSystemService(AudioManager::class.java))
        val events = LinkedBlockingQueue<AndroidOutputAudioFocus.Change>()
        var owner: AndroidOutputAudioFocus? = null
        var competitor: AudioFocusRequest? = null
        fun <T : Any> onMain(action: () -> T): T {
            var result: T? = null
            var error: Throwable? = null
            instrumentation.runOnMainSync { try { result = action() } catch (failure: Throwable) { error = failure } }
            error?.let { throw it }
            return checkNotNull(result)
        }
        fun newOwner() {
            events.clear()
            onMain {
                owner = AndroidOutputAudioFocus(context, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    pauseWhenDucked = true, onChange = { events.offer(it); Unit })
                if (owner?.acquire() != true) throw Failure("BLOCKED", "focus_probe_initial_request_denied")
                Unit
            }
        }
        fun compete(permanent: Boolean) {
            onMain {
                val request = AudioFocusRequest.Builder(if (permanent) AudioManager.AUDIOFOCUS_GAIN
                    else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(if (permanent) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener({ }, Handler(Looper.getMainLooper())).build()
                competitor = request
                if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    throw Failure("BLOCKED", "focus_probe_competing_request_denied")
                Unit
            }
        }
        fun abandonCompetitor() {
            onMain {
                competitor?.let(manager::abandonAudioFocusRequest)
                competitor = null
                Unit
            }
        }
        fun expect(expected: AndroidOutputAudioFocus.Change, code: String) {
            val actual = events.poll(5, TimeUnit.SECONDS) ?: throw Failure("BLOCKED", code)
            if (actual != expected) throw Failure("FAIL", "focus_probe_unexpected_platform_change")
        }
        fun expectNoGain(code: String) {
            if (events.poll(1_200, TimeUnit.MILLISECONDS) != null) throw Failure("FAIL", code)
        }
        try {
            newOwner()
            compete(permanent = false)
            expect(AndroidOutputAudioFocus.Change.TRANSIENT, "focus_probe_navigation_transient_not_observed")
            onMain {
                check(owner?.awaitingGain == true)
                owner?.outputFinished() // Simulates independently confirmed output teardown only.
                check(owner?.awaitingGain == true)
                Unit
            }
            abandonCompetitor()
            expect(AndroidOutputAudioFocus.Change.GAINED, "focus_probe_actual_regain_not_observed")
            onMain { owner?.close(); owner = null; Unit }
            mark("focus_actual_transient_and_gain_from_synthetic_navigation_request")
            mark("focus_registration_retained_after_confirmed_output_boundary")

            newOwner()
            compete(permanent = false)
            expect(AndroidOutputAudioFocus.Change.TRANSIENT, "focus_probe_second_transient_not_observed")
            onMain { owner?.outputFinished(); owner?.close(); owner = null; Unit }
            abandonCompetitor()
            expectNoGain("focus_probe_gain_after_registration_canceled")
            mark("focus_canceled_registration_does_not_deliver_regain")

            newOwner()
            compete(permanent = true)
            expect(AndroidOutputAudioFocus.Change.LOST, "focus_probe_permanent_loss_not_observed")
            abandonCompetitor()
            expectNoGain("focus_probe_permanent_loss_delivered_regain")
            mark("focus_actual_permanent_loss_has_no_automatic_regain")
        } finally {
            onMain {
                owner?.close()
                competitor?.let(manager::abandonAudioFocusRequest)
                Unit
            }
        }
    }
}
