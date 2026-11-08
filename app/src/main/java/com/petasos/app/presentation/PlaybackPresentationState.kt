package com.petasos.app.presentation

/** Read-only presentation values, never runtime permission or playback command authority. */
enum class ParkedPresentationGate { UNKNOWN, RESTRICTED, PARKED }
enum class PlaybackPresentationMode { IDLE, STORY, MUSIC, TALK_PENDING, PROMPT, LISTENING, GENERATING, ANSWERING, RESUMING, PAUSED }
enum class PlaybackPresentationStatus {
    CONNECTION_UNAVAILABLE, CONNECTING, BLOCKED, TRIP_ENDING, TRIP_END_UNCONFIRMED,
    PREPARING_STORY, NO_CONTENT, IDLE, STORY, MUSIC, TALK_PENDING, PROMPT, LISTENING,
    GENERATING, ANSWERING, RESUMING, PAUSED,
}
enum class PlaybackPresentationNotice {
    READY, MICROPHONE_VERIFICATION_PENDING, MICROPHONE_PERMISSION_NEEDED, MICROPHONE_PERMISSION_GRANTED,
    MICROPHONE_PERMISSION_DENIED, PREPARING_STORY, STORY_PREPARATION_FAILED, OPERATION_UNAVAILABLE,
}

data class PlaybackPresentationSession(
    val mode: PlaybackPresentationMode,
    val closed: Boolean,
    val blocked: Boolean,
)

data class PlaybackPresentationCommands(
    val play: Boolean = false,
    val stop: Boolean = false,
    val next: Boolean = false,
    val previous: Boolean = false,
)

/** Immutable facts captured by the live adapter, or explicitly supplied by a separate demo. */
data class PlaybackPresentationInput(
    val vehicleGate: ParkedPresentationGate = ParkedPresentationGate.UNKNOWN,
    val interactionAvailable: Boolean = false,
    val connected: Boolean = false,
    val connectionFailed: Boolean = false,
    val session: PlaybackPresentationSession? = null,
    val preparingStory: Boolean = false,
    val endingTrip: Boolean = false,
    val tripEndPending: Boolean = false,
    val catalogLoaded: Boolean = false,
    val hasStories: Boolean = false,
    val hasMusic: Boolean = false,
    val hasSelectedStory: Boolean = false,
    val canPrepareStory: Boolean = false,
    /** Null means no attached Host; false means its live-input capability is unavailable. */
    val liveInputSupported: Boolean? = null,
    val requestingMicrophone: Boolean = false,
    val commands: PlaybackPresentationCommands = PlaybackPresentationCommands(),
    val notice: PlaybackPresentationNotice = PlaybackPresentationNotice.READY,
)

data class PlaybackPresentationState(
    val vehicleGate: ParkedPresentationGate,
    val status: PlaybackPresentationStatus,
    val notice: PlaybackPresentationNotice,
    val selectionEnabled: Boolean,
    val playEnabled: Boolean,
    val stopEnabled: Boolean,
    val nextEnabled: Boolean,
    val previousEnabled: Boolean,
    val newStoryEnabled: Boolean,
    val talkEnabled: Boolean,
)
