package com.petasos.app.platform.pleos.speech

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.petasos.app.capability.speech.SpeechFailure
import com.petasos.app.capability.speech.SpeechFailureCode
import com.petasos.app.capability.speech.SpeechInput
import com.petasos.app.capability.speech.SpeechInputEvent
import java.util.Locale

/** A single-request, on-device Pleos connection. It never persists audio or logs speech text. */
class PleosSpeechInput(
    context: Context,
    private val onEvent: (SpeechInputEvent) -> Unit,
) : SpeechInput {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var sdk: SpeechToText? = null
    private var connectionAttempted = false
    private var connecting = false
    private var connected = false
    private var requestUsed = false
    private var canceled = false
    private var finalDelivered = false
    private var startedDelivered = false
    private var ended = false
    private var failed = false
    private var closed = false
    private val cleanup = SpeechRequestCleanup()

    private val timeout = Runnable {
        if (connecting && !closed) {
            connecting = false
            releaseSdk()
            fail(SpeechFailureCode.INITIALIZATION_TIMEOUT)
        }
    }

    private val events = object : ResultListener {
        override fun onReady() = dispatch {
            if (requestUsed && !canceled && !ended && !failed) onEvent(SpeechInputEvent.Ready)
        }

        override fun onStartedRecognition() = dispatch {
            // SDK 3.3.1 repeats EPD_ON while speech continues. Expose one request start.
            if (requestUsed && !canceled && !ended && !failed && !startedDelivered) {
                startedDelivered = true
                onEvent(SpeechInputEvent.Started)
            }
        }

        override fun onUpdated(stt: String, completed: Boolean) = dispatch {
            // Ended is deliberately not a gate: SDK final-result/ended ordering is under test.
            if (requestUsed && !canceled && !finalDelivered && !failed) {
                if (completed) finalDelivered = true
                onEvent(SpeechInputEvent.Transcript(stt, completed))
            }
        }

        override fun onEndedRecognition() = dispatch {
            if (requestUsed && !ended) {
                ended = true
                onEvent(SpeechInputEvent.Ended)
            }
        }

        override fun onError(errorType: ErrorType) = dispatch {
            if (requestUsed && !failed) {
                failed = true
                fail(errorCode(errorType), errorType.name)
            }
        }

        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
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
        val environmentFailure = speechEnvironmentFailure(appContext, "pleos.car.permission.STT_SERVICE")
        if (environmentFailure != null) {
            fail(environmentFailure)
            return
        }
        connecting = true
        main.postDelayed(timeout, INITIALIZATION_TIMEOUT_MS)
        try {
            val connection = SpeechToText(appContext, Mode.ON_DEVICE)
            sdk = connection
            connection.initialize(object : OnInitializeListener {
                override fun onSuccess() = dispatch {
                    if (connecting) {
                        main.removeCallbacks(timeout)
                        connecting = false
                        try {
                            connection.addListener(events)
                            connection.setLocale(Locale.KOREA)
                            connected = true
                            onEvent(SpeechInputEvent.Connected)
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

    override fun request() {
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
        requestUsed = true
        cleanup.markRequestIssued()
        try {
            sdk?.request()
        } catch (_: SecurityException) {
            failed = true
            fail(SpeechFailureCode.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            failed = true
            fail(SpeechFailureCode.SDK_ERROR)
        }
    }

    override fun stop() {
        requireSpeechMainThread()
        if (closed || !requestUsed || canceled) return
        canceled = true
        // EPD_OFF does not acknowledge microphone termination. Explicit cancellation must also
        // reject a final transcript arriving after that event.
        if (!cleanup.beginStop(SystemClock.elapsedRealtime())) return
        try {
            sdk?.stop()
        } catch (_: SecurityException) {
            failed = true
            fail(SpeechFailureCode.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            failed = true
            fail(SpeechFailureCode.SDK_ERROR)
        }
    }

    override fun close() {
        requireSpeechMainThread()
        if (closed) return
        closed = true
        connecting = false
        connected = false
        main.removeCallbacks(timeout)
        if (requestUsed && !canceled) {
            canceled = true
            if (cleanup.beginStop(SystemClock.elapsedRealtime())) {
                try { sdk?.stop() } catch (_: RuntimeException) { /* Continue releasing resources. */ }
            }
        }
        // SDK 3.3.1 enqueues stop internally, but release unbinds synchronously. Immediate release
        // lets that queued stop rebind the service. Retain the SDK for a bounded drain interval.
        // This is a verified workaround, not a vendor acknowledgment of microphone termination.
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
        val connection = sdk ?: return
        sdk = null
        connected = false
        try { connection.removeListener(events) } catch (_: RuntimeException) { /* Best effort. */ }
        try { connection.release() } catch (_: RuntimeException) { /* Best effort. */ }
    }

    private fun fail(code: SpeechFailureCode, vendorCode: String? = null) {
        if (!closed) onEvent(SpeechInputEvent.Failed(SpeechFailure(code, vendorCode)))
    }

    private fun dispatch(action: () -> Unit) {
        main.post { if (!closed) action() }
    }

    private fun errorCode(error: ErrorType, initializing: Boolean = false): SpeechFailureCode =
        if (error == ErrorType.PERMISSION_DENIED) SpeechFailureCode.PERMISSION_DENIED
        else if (initializing) SpeechFailureCode.INITIALIZATION_FAILED
        else SpeechFailureCode.SDK_ERROR

}
