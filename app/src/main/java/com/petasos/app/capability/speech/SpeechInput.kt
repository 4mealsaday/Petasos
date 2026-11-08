package com.petasos.app.capability.speech

/**
 * UI-independent input boundary. Calls and events belong to the main thread.
 *
 * Connect does not open the microphone; request does. Each instance accepts one request only,
 * because the vendor callbacks have no request identifier. Create a fresh instance to retry.
 * Started is delivered once per request. Ended describes the vendor speech-segment end; it is not
 * a stop acknowledgment and need not follow a no-speech cancellation. A final Transcript may
 * arrive after Ended until close. Explicit stop cancels transcription delivery.
 * Close immediately rejects work/drops callbacks; platform resource release may finish later.
 * Pleos 3.3.1 cleanup drains queued SDK stop work for up to 1 s before release; this is not a
 * microphone acknowledgment. The legacy SDK-owned microphone adapter exposes no such proof.
 * The app-owned PCM adapter separately reports its recorder's stop/state/release result. That
 * proof is independent of recognition Ended and must not be inferred from close's return.
 */
interface SpeechInput : AutoCloseable {
    fun connect()
    fun request()
    fun stop()
    override fun close()
}

sealed interface SpeechInputEvent {
    data object Connected : SpeechInputEvent
    data object Ready : SpeechInputEvent
    data object Started : SpeechInputEvent
    data object Ended : SpeechInputEvent
    data class Failed(val failure: SpeechFailure) : SpeechInputEvent

    class Transcript(val text: String, val isFinal: Boolean) : SpeechInputEvent {
        // Event inspection must not accidentally write the user's speech to a diagnostic log.
        override fun toString(): String = "Transcript(isFinal=$isFinal, characterCount=${text.length})"
    }
}
