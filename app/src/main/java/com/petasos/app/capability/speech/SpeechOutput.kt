package com.petasos.app.capability.speech

/**
 * UI-independent output boundary. Calls and events belong to the main thread.
 *
 * This verification-stage implementation accepts one utterance per instance. Vendor callbacks
 * have no request identifier, so create a fresh instance for each subsequent utterance.
 * Connecting never starts playback. Close suppresses late events immediately; if a stop was
 * requested, the Pleos 3.3.1 adapter retains the connection for up to 1 s for queued SDK work.
 * Close returning or that delay elapsing is not an acknowledgment of speaker termination.
 * Failed does not prove output has stopped. A subsequent actual vendor stop can deliver Stopped
 * once while the connection is open. Completed is reserved for an uncancelled successful request.
 */
interface SpeechOutput : AutoCloseable {
    fun connect()
    fun speak(text: String)
    fun stop()
    override fun close()
}

sealed interface SpeechOutputEvent {
    data object Connected : SpeechOutputEvent
    data object Ready : SpeechOutputEvent
    data object Started : SpeechOutputEvent
    data object Completed : SpeechOutputEvent
    data object Stopped : SpeechOutputEvent
    data class FocusLost(val kind: SpeechFocusLoss) : SpeechOutputEvent
    data class Failed(val failure: SpeechFailure) : SpeechOutputEvent
}

/** All three pause output; recovery policy belongs to the playback coordinator. */
enum class SpeechFocusLoss { PERMANENT, TRANSIENT, DUCK }

/** Failure metadata intentionally excludes exception messages, audio, and speech text. */
data class SpeechFailure(val code: SpeechFailureCode, val vendorCode: String? = null)

enum class SpeechFailureCode {
    MISSING_SERVICE,
    SERVICE_DISABLED,
    PERMISSION_DENIED,
    INITIALIZATION_FAILED,
    INITIALIZATION_TIMEOUT,
    NOT_CONNECTED,
    ALREADY_USED,
    INVALID_REQUEST,
    AUDIO_FOCUS_DENIED,
    SDK_ERROR,
}
