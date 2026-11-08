package com.petasos.app.presentation

/** Pure display mapping. Callers must still check live command/permission authority on every action. */
object PlaybackPresentationMapper {
    fun map(input: PlaybackPresentationInput): PlaybackPresentationState {
        val available = input.interactionAvailable && input.vehicleGate == ParkedPresentationGate.PARKED && input.connected
        val preparing = input.preparingStory || input.endingTrip
        val hasContent = input.hasStories || input.hasMusic
        val session = input.session
        val status = when {
            input.connectionFailed -> PlaybackPresentationStatus.CONNECTION_UNAVAILABLE
            !input.connected -> PlaybackPresentationStatus.CONNECTING
            session?.blocked == true -> PlaybackPresentationStatus.BLOCKED
            input.endingTrip -> if (input.tripEndPending) PlaybackPresentationStatus.TRIP_ENDING
                else PlaybackPresentationStatus.TRIP_END_UNCONFIRMED
            preparing -> PlaybackPresentationStatus.PREPARING_STORY
            input.catalogLoaded && !hasContent && (session == null || session.mode == PlaybackPresentationMode.IDLE) ->
                PlaybackPresentationStatus.NO_CONTENT
            else -> when (session?.mode) {
                PlaybackPresentationMode.STORY -> PlaybackPresentationStatus.STORY
                PlaybackPresentationMode.MUSIC -> PlaybackPresentationStatus.MUSIC
                PlaybackPresentationMode.TALK_PENDING -> PlaybackPresentationStatus.TALK_PENDING
                PlaybackPresentationMode.PROMPT -> PlaybackPresentationStatus.PROMPT
                PlaybackPresentationMode.LISTENING -> PlaybackPresentationStatus.LISTENING
                PlaybackPresentationMode.GENERATING -> PlaybackPresentationStatus.GENERATING
                PlaybackPresentationMode.ANSWERING -> PlaybackPresentationStatus.ANSWERING
                PlaybackPresentationMode.RESUMING -> PlaybackPresentationStatus.RESUMING
                PlaybackPresentationMode.PAUSED -> PlaybackPresentationStatus.PAUSED
                PlaybackPresentationMode.IDLE, null -> PlaybackPresentationStatus.IDLE
            }
        }
        return PlaybackPresentationState(
            vehicleGate = input.vehicleGate,
            status = status,
            notice = if (input.liveInputSupported == false && input.notice == PlaybackPresentationNotice.READY)
                PlaybackPresentationNotice.MICROPHONE_VERIFICATION_PENDING else input.notice,
            selectionEnabled = available && !preparing && input.hasStories,
            playEnabled = available && !preparing && hasContent && input.commands.play,
            stopEnabled = available && input.commands.stop,
            nextEnabled = available && !preparing && session != null && input.commands.next,
            previousEnabled = available && !preparing && session != null && input.commands.previous,
            newStoryEnabled = available && input.hasSelectedStory && input.canPrepareStory,
            talkEnabled = available && input.liveInputSupported == true && !input.requestingMicrophone &&
                session?.closed != true && session?.blocked != true && (session == null || session.mode in talkModes),
        )
    }

    private val talkModes = setOf(PlaybackPresentationMode.IDLE, PlaybackPresentationMode.PAUSED,
        PlaybackPresentationMode.STORY, PlaybackPresentationMode.MUSIC, PlaybackPresentationMode.TALK_PENDING,
        PlaybackPresentationMode.PROMPT, PlaybackPresentationMode.LISTENING)
}
