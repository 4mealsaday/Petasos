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

/** Diagnostic timestamps only; no audio or recognition payload can enter this observer. */
internal enum class PcmInputLifecycleEvent {
    REQUEST_ENTERED, EOF_OBSERVED, CAPTURE_STOP_FINISHED,
    COMPLETE_AUDIO_SEND_ENTERED, COMPLETE_AUDIO_SEND_RETURNED,
    SDK_ENDED_CALLBACK, SDK_FINAL_CALLBACK, STOP_ENTERED,
}

/**
 * One explicit microphone request, captured by an app-owned AudioRecord and streamed through
 * Pleos's documented ON_DEVICE PCM API. It never invokes SpeechToText.request().
 *
 * [onCaptureStopped] acknowledges only the owned recorder's verified stop/release. Ended stops
 * capture without cancelling the final-result grace period. Explicit stop cancels text and then
 * delivers retained stop evidence. No audio files, transcript logs, or automatic retries exist.
 */
class PleosPcmSpeechInput internal constructor(
    context: Context,
    private val recorderFactory: () -> PcmRecorder,
    private val onCaptureStopped: (Boolean) -> Unit,
    private val onEvent: (SpeechInputEvent) -> Unit,
    /** May run on an SDK callback thread. Defaults to no observation in the product. */
    private val onLifecycle: (PcmInputLifecycleEvent, Long) -> Unit = { _, _ -> },
) : SpeechInput {
    constructor(
        context: Context,
        onCaptureStopped: (Boolean) -> Unit,
        onEvent: (SpeechInputEvent) -> Unit,
    ) : this(context, { AndroidPcmRecorder(context.applicationContext) }, onCaptureStopped, onEvent)

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var sdk: SpeechToText? = null
    private var connectionAttempted = false
    private var connecting = false
    private var connected = false
    private var requested = false
    private var canceled = false
    private var closed = false
    private var failed = false
    private var started = false
    private var ended = false
    private var finalDelivered = false
    private var capture: PcmCaptureSession? = null
    private var captureEnded = false
    private var captureStopProof = false
    private var stopDelivered = false
    private var audioSent = false
    private var sendCompleted = false
    private var captureStartedAt = 0L
    private var schedule: PcmPollSchedule? = null
    private val cleanup = SpeechRequestCleanup()

    private val initializationTimeout = Runnable {
        if (connecting && !closed) {
            connecting = false
            releaseSdk()
            fail(SpeechFailureCode.INITIALIZATION_TIMEOUT)
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (closed || canceled || captureEnded || failed) return
            if (SystemClock.elapsedRealtime() - captureStartedAt >= CAPTURE_HARD_LIMIT_MS) {
                fail(SpeechFailureCode.INVALID_REQUEST)
                finishCapture()
                return
            }
            try {
                capture?.readChunk()?.let { bytes ->
                    // The SDK may retain this fresh buffer internally. Never reuse/mutate it after
                    // sendAudio; the app retains no reference, queues no chunks and writes no audio.
                    audioSent = true
                    cleanup.markRequestIssued()
                    checkNotNull(sdk).sendAudio(bytes)
                }
                // Finite PCM ends independently of recognition callbacks. Waiting for an SDK
                // result to announce EOF creates a cycle on engines that flush only on complete.
                if (capture?.endOfInput == true) {
                    observe(PcmInputLifecycleEvent.EOF_OBSERVED)
                    finishCapture()
                }
                if (!captureEnded && !closed && !canceled && !failed) {
                    main.postDelayed(this, checkNotNull(schedule).delayAfterRead(SystemClock.elapsedRealtime()))
                }
            } catch (_: SecurityException) {
                fail(SpeechFailureCode.PERMISSION_DENIED)
                finishCapture()
            } catch (_: RuntimeException) {
                fail(SpeechFailureCode.SDK_ERROR)
                finishCapture()
            }
        }
    }

    private val listener = object : ResultListener {
        // The external PCM API deliberately has no Ready event. Our Ready means AudioRecord
        // startRecording returned and this recorder reports RECORDSTATE_RECORDING.
        override fun onReady() = Unit
        override fun onStartedRecognition() = dispatch {
            if (acceptResults() && !started && !ended) {
                started = true
                onEvent(SpeechInputEvent.Started)
            }
        }
        override fun onUpdated(stt: String, completed: Boolean) {
            if (completed) observe(PcmInputLifecycleEvent.SDK_FINAL_CALLBACK)
            dispatch {
                if (acceptResults() && !finalDelivered) {
                    if (completed) {
                        finalDelivered = true
                        finishCapture()
                    }
                    if (!failed) onEvent(SpeechInputEvent.Transcript(stt, completed))
                }
            }
        }
        override fun onEndedRecognition() {
            observe(PcmInputLifecycleEvent.SDK_ENDED_CALLBACK)
            dispatch {
                if (acceptResults() && !ended) {
                    ended = true
                    onEvent(SpeechInputEvent.Ended)
                    finishCapture()
                }
            }
        }
        override fun onError(errorType: ErrorType) = dispatch {
            if (requested && !canceled && !failed) {
                fail(if (errorType == ErrorType.PERMISSION_DENIED) SpeechFailureCode.PERMISSION_DENIED else SpeechFailureCode.SDK_ERROR)
                finishCapture()
            }
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    override fun connect() {
        requireSpeechMainThread()
        if (closed || connecting || connected) return
        if (connectionAttempted) { fail(SpeechFailureCode.ALREADY_USED); return }
        connectionAttempted = true
        speechEnvironmentFailure(context, "pleos.car.permission.STT_SERVICE")?.let { fail(it); return }
        connecting = true
        main.postDelayed(initializationTimeout, INITIALIZATION_TIMEOUT_MS)
        try {
            val connection = SpeechToText(context, Mode.ON_DEVICE)
            sdk = connection
            connection.initialize(object : OnInitializeListener {
                override fun onSuccess() = dispatch {
                    if (connecting) {
                        main.removeCallbacks(initializationTimeout)
                        connecting = false
                        try {
                            connection.addListener(listener)
                            connection.setLocale(Locale.KOREA)
                            connected = true
                            onEvent(SpeechInputEvent.Connected)
                        } catch (_: SecurityException) { initializationFailed(SpeechFailureCode.PERMISSION_DENIED) }
                        catch (_: RuntimeException) { initializationFailed(SpeechFailureCode.INITIALIZATION_FAILED) }
                    }
                }
                override fun onFailure(errorType: ErrorType) = dispatch {
                    if (connecting) initializationFailed(
                        if (errorType == ErrorType.PERMISSION_DENIED) SpeechFailureCode.PERMISSION_DENIED
                        else SpeechFailureCode.INITIALIZATION_FAILED,
                    )
                }
            })
        } catch (_: SecurityException) { initializationFailed(SpeechFailureCode.PERMISSION_DENIED) }
        catch (_: RuntimeException) { initializationFailed(SpeechFailureCode.INITIALIZATION_FAILED) }
    }

    override fun request() {
        requireSpeechMainThread()
        observe(PcmInputLifecycleEvent.REQUEST_ENTERED)
        if (closed || canceled) return
        if (!connected) { fail(SpeechFailureCode.NOT_CONNECTED); return }
        if (requested) { fail(SpeechFailureCode.ALREADY_USED); return }
        requested = true
        val session = PcmCaptureSession(recorderFactory)
        capture = session
        try {
            session.start()
            captureStartedAt = SystemClock.elapsedRealtime()
            schedule = PcmPollSchedule(captureStartedAt)
            onEvent(SpeechInputEvent.Ready)
            main.postDelayed(poll, checkNotNull(schedule).firstDelayMs())
        } catch (_: SecurityException) {
            fail(SpeechFailureCode.PERMISSION_DENIED)
            finishCapture()
        } catch (_: RuntimeException) {
            fail(SpeechFailureCode.SDK_ERROR)
            finishCapture()
        }
    }

    override fun stop() {
        requireSpeechMainThread()
        observe(PcmInputLifecycleEvent.STOP_ENTERED)
        if (closed || canceled) return
        canceled = true
        finishCapture()
        stopRecognition()
        if (!stopDelivered) {
            stopDelivered = true
            onCaptureStopped(captureStopProof)
        }
    }

    /** Stops the physical recorder, preserving permission for a final STT result until stop/close. */
    private fun finishCapture() {
        if (captureEnded) return
        captureEnded = true
        main.removeCallbacks(poll)
        schedule = null
        captureStopProof = capture?.stop() ?: true // No recorder was ever requested.
        observe(PcmInputLifecycleEvent.CAPTURE_STOP_FINISHED)
        capture = null
        if (audioSent && !sendCompleted) {
            sendCompleted = true
            try {
                sdk?.let { connection ->
                    observe(PcmInputLifecycleEvent.COMPLETE_AUDIO_SEND_ENTERED)
                    connection.completeAudioSend()
                    // Synchronous method return only, never a recognizer flush or physical-stop ACK.
                    observe(PcmInputLifecycleEvent.COMPLETE_AUDIO_SEND_RETURNED)
                }
            }
            catch (_: RuntimeException) { fail(SpeechFailureCode.SDK_ERROR) }
        }
    }

    private fun stopRecognition() {
        if (cleanup.beginStop(SystemClock.elapsedRealtime())) {
            try { sdk?.stop() } catch (_: RuntimeException) { fail(SpeechFailureCode.SDK_ERROR) }
        }
    }

    override fun close() {
        requireSpeechMainThread()
        if (closed) return
        closed = true
        canceled = true
        connecting = false
        connected = false
        main.removeCallbacks(initializationTimeout)
        finishCapture()
        stopRecognition()
        // SDK coroutine drain only, not the capture-stop proof above.
        val delay = cleanup.releaseDelayMillis(SystemClock.elapsedRealtime())
        if (delay > 0) main.postDelayed({ releaseSdk() }, delay) else releaseSdk()
    }

    private fun acceptResults() = requested && !canceled && !failed && !closed
    private fun observe(event: PcmInputLifecycleEvent) {
        try { onLifecycle(event, SystemClock.elapsedRealtime()) }
        catch (_: Exception) { /* Optional diagnostic observation must never alter capture semantics. */ }
    }
    private fun dispatch(action: () -> Unit) { main.post { if (!closed) action() } }
    private fun fail(code: SpeechFailureCode) {
        if (!closed && !failed) {
            failed = true
            onEvent(SpeechInputEvent.Failed(SpeechFailure(code)))
        }
    }
    private fun initializationFailed(code: SpeechFailureCode) {
        connecting = false
        main.removeCallbacks(initializationTimeout)
        releaseSdk()
        fail(code)
    }
    private fun releaseSdk() {
        val connection = sdk ?: return
        sdk = null
        connected = false
        try { connection.removeListener(listener) } catch (_: RuntimeException) { /* Continue cleanup. */ }
        try { connection.release() } catch (_: RuntimeException) { /* No capture acknowledgment. */ }
    }

    private companion object {
        // Emergency local bound; Coordinator enforces the v0.8 5 s first-speech / 60 s speech gates.
        const val CAPTURE_HARD_LIMIT_MS = 65_000L
    }
}
