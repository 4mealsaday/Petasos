package com.petasos.app.presentation

import android.view.View
import android.widget.TextView
import com.petasos.app.R

/** Writes only presentation properties on the supplied view tree. No listeners, Host, or commands. */
class PlaybackPresentationRenderer(root: View) {
    private val content = root.findViewById<View>(R.id.preview_content)
    private val vehicleNotice = root.findViewById<TextView>(R.id.vehicle_policy_notice)
    private val status = root.findViewById<TextView>(R.id.playback_status)
    private val notice = root.findViewById<TextView>(R.id.playback_notice)
    private val selection = root.findViewById<View>(R.id.story_selection)
    private val play = root.findViewById<View>(R.id.playback_play)
    private val stop = root.findViewById<View>(R.id.playback_stop)
    private val next = root.findViewById<View>(R.id.playback_next)
    private val previous = root.findViewById<View>(R.id.playback_previous)
    private val newStory = root.findViewById<View>(R.id.playback_new_story)
    private val talk = root.findViewById<View>(R.id.playback_talk)

    /** Revoke visibility before adapter work that could fail or synchronously change state. */
    fun renderGate(gate: ParkedPresentationGate) { renderGate(content, vehicleNotice, gate) }

    fun render(state: PlaybackPresentationState) {
        renderGate(state.vehicleGate)
        selection.isEnabled = state.selectionEnabled
        play.isEnabled = state.playEnabled
        stop.isEnabled = state.stopEnabled
        next.isEnabled = state.nextEnabled
        previous.isEnabled = state.previousEnabled
        newStory.isEnabled = state.newStoryEnabled
        talk.isEnabled = state.talkEnabled
        status.setText(statusResource(state.status))
        notice.setText(noticeResource(state.notice))
    }

    companion object {
        /** Shared with the existing direct vehicle-gate diagnostic; no platform state is queried. */
        internal fun renderGate(content: View, message: TextView, gate: ParkedPresentationGate) {
            content.visibility = if (gate == ParkedPresentationGate.PARKED) View.VISIBLE else View.GONE
            message.visibility = if (gate == ParkedPresentationGate.PARKED) View.GONE else View.VISIBLE
            message.setText(if (gate == ParkedPresentationGate.UNKNOWN) R.string.vehicle_policy_waiting
                else R.string.vehicle_policy_restricted)
        }

        private fun statusResource(status: PlaybackPresentationStatus): Int = when (status) {
            PlaybackPresentationStatus.CONNECTION_UNAVAILABLE -> R.string.playback_unavailable
            PlaybackPresentationStatus.CONNECTING -> R.string.ready_title
            PlaybackPresentationStatus.BLOCKED -> R.string.playback_blocked
            PlaybackPresentationStatus.TRIP_ENDING -> R.string.records_ending_trip
            PlaybackPresentationStatus.TRIP_END_UNCONFIRMED -> R.string.records_end_unconfirmed
            PlaybackPresentationStatus.PREPARING_STORY -> R.string.playback_preparing_story
            PlaybackPresentationStatus.NO_CONTENT -> R.string.playback_no_content
            PlaybackPresentationStatus.IDLE -> R.string.playback_idle
            PlaybackPresentationStatus.STORY -> R.string.playback_story
            PlaybackPresentationStatus.MUSIC -> R.string.playback_music
            PlaybackPresentationStatus.TALK_PENDING -> R.string.playback_talk_pending
            PlaybackPresentationStatus.PROMPT -> R.string.playback_prompt
            PlaybackPresentationStatus.LISTENING -> R.string.playback_listening
            PlaybackPresentationStatus.GENERATING -> R.string.playback_generating
            PlaybackPresentationStatus.ANSWERING -> R.string.playback_answering
            PlaybackPresentationStatus.RESUMING -> R.string.playback_resuming
            PlaybackPresentationStatus.PAUSED -> R.string.playback_paused
        }

        private fun noticeResource(notice: PlaybackPresentationNotice): Int = when (notice) {
            PlaybackPresentationNotice.READY -> R.string.ready_description
            PlaybackPresentationNotice.MICROPHONE_VERIFICATION_PENDING -> R.string.microphone_verification_pending
            PlaybackPresentationNotice.MICROPHONE_PERMISSION_NEEDED -> R.string.microphone_permission_needed
            PlaybackPresentationNotice.MICROPHONE_PERMISSION_GRANTED -> R.string.microphone_permission_granted
            PlaybackPresentationNotice.MICROPHONE_PERMISSION_DENIED -> R.string.microphone_permission_denied
            PlaybackPresentationNotice.PREPARING_STORY -> R.string.playback_preparing_story_description
            PlaybackPresentationNotice.STORY_PREPARATION_FAILED -> R.string.playback_story_preparation_failed
            PlaybackPresentationNotice.OPERATION_UNAVAILABLE -> R.string.playback_operation_unavailable
        }
    }
}
