package com.petasos.app.platform.pleos.speech

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.petasos.app.capability.speech.SpeechFailure
import com.petasos.app.capability.speech.SpeechFailureCode
import com.petasos.app.capability.speech.SpeechFocusLoss
import com.petasos.app.capability.speech.SpeechOutput
import com.petasos.app.capability.speech.SpeechOutputEvent
import com.petasos.app.platform.android.audio.AndroidOutputAudioFocus
import java.util.Locale

/** A single-utterance, on-device Pleos connection. No speech content is logged or stored. */
class PleosSpeechOutput(
    context: Context,
    private val onEvent: (SpeechOutputEvent) -> Unit,
) : SpeechOutput {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AndroidOutputAudioFocus(appContext, attributes, pauseWhenDucked = true) { change ->
        if (!closed || retainingFocus) {
            when (change) {
                AndroidOutputAudioFocus.Change.GAINED -> onEvent(SpeechOutputEvent.FocusGained)
                AndroidOutputAudioFocus.Change.LOST -> {
                    if (!closed) stop()
                    onEvent(SpeechOutputEvent.FocusLost(SpeechFocusLoss.PERMANENT))
                }
                AndroidOutputAudioFocus.Change.TRANSIENT, AndroidOutputAudioFocus.Change.DUCK -> {
                    if (!closed && requestUsed && !terminal && !canceled) {
                        // Stop is submitted before the runtime observes the focus interruption.
                        stop()
                        onEvent(SpeechOutputEvent.FocusLost(SpeechFocusLoss.TRANSIENT))
                    }
                }
            }
        }
    }
    private var retainingFocus = false
    private var sdk: TextToSpeech? = null
    private var connectionAttempted = false
    private var connecting = false
    private var connected = false
    private var requestUsed = false
    private var canceled = false
    private val callbacks = SpeechOutputCallbacks()
    private val terminal: Boolean get() = callbacks.terminal
    private var closed = false
    private val cleanup = SpeechRequestCleanup()

    private val timeout = Runnable {
        if (connecting && !closed) {
            connecting = false
            releaseSdk()
            fail(SpeechFailureCode.INITIALIZATION_TIMEOUT)
        }
    }

    private val events = object : EventListener {
        override fun onReady() = dispatch {
            if (requestUsed && !canceled && !terminal) onEvent(SpeechOutputEvent.Ready)
        }

        override fun onStart() = dispatch {
            if (requestUsed && !canceled && !terminal) onEvent(SpeechOutputEvent.Started)
        }

        override fun onDone() = dispatch {
            if (requestUsed && callbacks.acceptCompleted(canceled)) {
                cleanup.confirmOutputIdle()
                focus.outputFinished()
                onEvent(SpeechOutputEvent.Completed)
            }
        }

        override fun onStop() = dispatch {
            if (requestUsed) {
                cleanup.confirmOutputIdle()
                focus.outputFinished()
                if (callbacks.acceptStopped()) {
                    onEvent(SpeechOutputEvent.Stopped)
                }
            }
        }

        override fun onError(errorType: ErrorType) = dispatch {
            if (requestUsed && callbacks.acceptFailure()) {
                requestStop(reportFailure = false)
                fail(errorCode(errorType), errorType.name)
            }
        }

        override fun onUpdatedRms(rms: Double) = Unit
    }

    override fun connect() {
        requireSpeechMainThread()
        if (closed || connecting || connected) return
        if (connectionAttempted) {
            fail(SpeechFailureCode.ALREADY_USED)
            return
        }
        connectionAttempted = true
        val environmentFailure = speechEnvironmentFailure(appContext, "pleos.car.permission.TTS_SERVICE")
        if (environmentFailure != null) {
            fail(environmentFailure)
            return
        }
        connecting = true
        main.postDelayed(timeout, INITIALIZATION_TIMEOUT_MS)
        try {
            val connection = TextToSpeech(appContext, Mode.ON_DEVICE)
            sdk = connection
            connection.initialize(object : OnInitializeListener {
                override fun onSuccess() = dispatch {
                    if (connecting) {
                        main.removeCallbacks(timeout)
                        connecting = false
                        try {
                            connection.addEventListener(events)
                            connection.setLocale(Locale.KOREA)
                            connection.setAudioAttributes(attributes)
                            connected = true
                            onEvent(SpeechOutputEvent.Connected)
                        } catch (_: SecurityException) {
                            releaseSdk()
                            fail(SpeechFailureCode.PERMISSION_DENIED)
                        } catch (_: RuntimeException) {
                            releaseSdk()
                            fail(SpeechFailureCode.INITIALIZATION_FAILED)
                        }
                    }
                }

                override fun onFailure(errorType: ErrorType) = dispatch {
                    if (connecting) {
                        main.removeCallbacks(timeout)
                        connecting = false
                        releaseSdk()
                        fail(errorCode(errorType, initializing = true), errorType.name)
                    }
                }
            })
        } catch (_: SecurityException) {
            initializationFailed(SpeechFailureCode.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            initializationFailed(SpeechFailureCode.INITIALIZATION_FAILED)
        }
    }

    override fun speak(text: String) {
        requireSpeechMainThread()
        if (closed) return
        if (!connected) {
            fail(SpeechFailureCode.NOT_CONNECTED)
            return
        }
        if (requestUsed) {
            fail(SpeechFailureCode.ALREADY_USED)
            return
        }
        if (text.isBlank()) {
            fail(SpeechFailureCode.INVALID_REQUEST)
            return
        }
        requestUsed = true
        try {
            if (!focus.acquire()) {
                callbacks.acceptFailure()
                fail(SpeechFailureCode.AUDIO_FOCUS_DENIED)
                return
            }
            cleanup.markRequestIssued()
            sdk?.speak(text, true)
        } catch (_: SecurityException) {
            callbacks.acceptFailure()
            requestStop(reportFailure = false)
            fail(SpeechFailureCode.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            callbacks.acceptFailure()
            requestStop(reportFailure = false)
            fail(SpeechFailureCode.SDK_ERROR)
        }
    }

    override fun stop() {
        requireSpeechMainThread()
        if (closed || !requestUsed || canceled) return
        requestStop(reportFailure = true)
    }

    private fun requestStop(reportFailure: Boolean) {
        canceled = true
        if (!cleanup.beginStop(SystemClock.elapsedRealtime())) return
        try {
            sdk?.stop()
        } catch (_: SecurityException) {
            callbacks.acceptFailure()
            if (reportFailure) fail(SpeechFailureCode.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            callbacks.acceptFailure()
            if (reportFailure) fail(SpeechFailureCode.SDK_ERROR)
        }
        // The SDK queues stop. Keep the focus registration until onStop or bounded close cleanup.
    }

    override fun close() {
        retainingFocus = false
        if (closed) focus.close() else closeConnection()
    }

    /** Called only after physical stop; the old SDK connection is never reused to resume. */
    fun closeRetainingFocus() {
        retainingFocus = true
        closeConnection()
    }

    fun cancelFocusRecovery() { retainingFocus = false; focus.close() }

    private fun closeConnection() {
        requireSpeechMainThread()
        if (closed) return
        closed = true
        connecting = false
        connected = false
        main.removeCallbacks(timeout)
        if (requestUsed && !canceled) {
            requestStop(reportFailure = false)
        }
        // Like STT, ON_DEVICE TTS 3.3.1 queues stop but synchronously unbinds on release.
        // Avoid a queued stop rebinding after release. This delay is not a vendor stop guarantee.
        val remaining = cleanup.releaseDelayMillis(SystemClock.elapsedRealtime())
        if (remaining > 0) main.postDelayed({ releaseSdk() }, remaining) else releaseSdk()
    }

    private fun initializationFailed(code: SpeechFailureCode) {
        connecting = false
        main.removeCallbacks(timeout)
        releaseSdk()
        fail(code)
    }

    private fun releaseSdk() {
        if (retainingFocus) focus.outputFinished() else focus.close()
        val connection = sdk ?: return
        sdk = null
        connected = false
        try { connection.removeEventListener(events) } catch (_: RuntimeException) { /* Best effort. */ }
        try { connection.release() } catch (_: RuntimeException) { /* Best effort. */ }
    }

    private fun fail(code: SpeechFailureCode, vendorCode: String? = null) {
        if (!closed) onEvent(SpeechOutputEvent.Failed(SpeechFailure(code, vendorCode)))
    }

    private fun dispatch(action: () -> Unit) {
        main.post { if (!closed) action() }
    }

    private fun errorCode(error: ErrorType, initializing: Boolean = false): SpeechFailureCode =
        if (error == ErrorType.PERMISSION_DENIED) SpeechFailureCode.PERMISSION_DENIED
        else if (initializing) SpeechFailureCode.INITIALIZATION_FAILED
        else SpeechFailureCode.SDK_ERROR
}
