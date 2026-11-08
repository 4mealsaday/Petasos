package com.petasos.app.diagnostics

import android.app.Instrumentation
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import com.petasos.app.R
import com.petasos.app.presentation.ParkedPresentationGate
import com.petasos.app.presentation.PlaybackPresentationCommands
import com.petasos.app.presentation.PlaybackPresentationInput
import com.petasos.app.presentation.PlaybackPresentationMapper
import com.petasos.app.presentation.PlaybackPresentationMode
import com.petasos.app.presentation.PlaybackPresentationNotice
import com.petasos.app.presentation.PlaybackPresentationRenderer
import com.petasos.app.presentation.PlaybackPresentationSession

/**
 * Pure-input mapper plus real Android widgets in an unattached, disposable layout. This is separate
 * from MainActivity and the actual CarUx observation. This probe body does not create a Host or
 * access services, storage, permissions, media, microphone, location or vehicle controls.
 * Normal target Application startup still runs and may perform product database recovery.
 */
internal object PresentationRendererProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        instrumentation.runOnMainSync {
            val themed = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Petasos)
            val root = LayoutInflater.from(themed).inflate(R.layout.activity_main, FrameLayout(themed), true)
            val content = root.findViewById<View>(R.id.preview_content)
            val vehicleNotice = root.findViewById<TextView>(R.id.vehicle_policy_notice)
            val status = root.findViewById<TextView>(R.id.playback_status)
            val notice = root.findViewById<TextView>(R.id.playback_notice)
            val selection = root.findViewById<Spinner>(R.id.story_selection)
            val play = root.findViewById<Button>(R.id.playback_play)
            val stop = root.findViewById<Button>(R.id.playback_stop)
            val next = root.findViewById<Button>(R.id.playback_next)
            val previous = root.findViewById<Button>(R.id.playback_previous)
            val newStory = root.findViewById<Button>(R.id.playback_new_story)
            val talk = root.findViewById<Button>(R.id.playback_talk)
            val controls = listOf<View>(selection, play, stop, next, previous, newStory, talk)
            check(!root.isAttachedToWindow && content.visibility == View.GONE && vehicleNotice.visibility == View.VISIBLE)
            val renderer = PlaybackPresentationRenderer(root)
            val adapter = ArrayAdapter(themed, android.R.layout.simple_spinner_item, listOf("synthetic-auto", "synthetic-story"))
            selection.adapter = adapter
            selection.setSelection(1)
            var clicks = 0
            // A local sentinel proves the renderer does not replace command-adapter listeners.
            play.setOnClickListener { clicks++ }
            fun render(input: PlaybackPresentationInput) {
                renderer.render(PlaybackPresentationMapper.map(input))
                check(!root.isAttachedToWindow)
                check(selection.adapter === adapter && selection.selectedItemPosition == 1 && clicks == 0)
            }
            fun text(view: TextView, resource: Int) { check(view.text.toString() == themed.getString(resource)) }
            fun disabled() { check(controls.none { it.isEnabled }) }
            val playingSession = PlaybackPresentationSession(PlaybackPresentationMode.STORY, closed = false, blocked = false)
            val ready = PlaybackPresentationInput(vehicleGate = ParkedPresentationGate.PARKED,
                interactionAvailable = true, connected = true,
                session = playingSession,
                catalogLoaded = true, hasStories = true, hasSelectedStory = true, canPrepareStory = true,
                liveInputSupported = true, commands = PlaybackPresentationCommands(play = true, stop = true, next = true, previous = true))

            render(PlaybackPresentationInput())
            disabled()
            check(content.visibility == View.GONE && vehicleNotice.visibility == View.VISIBLE)
            text(vehicleNotice, R.string.vehicle_policy_waiting)
            text(status, R.string.ready_title)
            render(ready)
            check(content.visibility == View.VISIBLE && vehicleNotice.visibility == View.GONE)
            check(controls.all { it.isEnabled })
            text(status, R.string.playback_story)
            text(notice, R.string.ready_description)

            // Hidden/unknown policy must beat otherwise permissive, stale display inputs.
            for (gate in listOf(ParkedPresentationGate.RESTRICTED, ParkedPresentationGate.UNKNOWN)) {
                render(ready.copy(vehicleGate = gate))
                disabled()
                check(content.visibility == View.GONE && vehicleNotice.visibility == View.VISIBLE)
                text(vehicleNotice, if (gate == ParkedPresentationGate.UNKNOWN)
                    R.string.vehicle_policy_waiting else R.string.vehicle_policy_restricted)
            }
            render(ready.copy(interactionAvailable = false))
            disabled()
            check(content.visibility == View.VISIBLE && vehicleNotice.visibility == View.GONE)
            render(ready.copy(connected = false, connectionFailed = true))
            disabled()
            text(status, R.string.playback_unavailable)

            render(ready.copy(commands = PlaybackPresentationCommands()))
            check(!play.isEnabled && !stop.isEnabled && !next.isEnabled && !previous.isEnabled)
            check(selection.isEnabled && newStory.isEnabled && talk.isEnabled)
            render(ready.copy(preparingStory = true, canPrepareStory = false))
            check(!selection.isEnabled && !play.isEnabled && !next.isEnabled && !previous.isEnabled && !newStory.isEnabled)
            check(stop.isEnabled)
            text(status, R.string.playback_preparing_story)
            render(ready.copy(endingTrip = true, tripEndPending = true, canPrepareStory = false))
            text(status, R.string.records_ending_trip)
            check(!play.isEnabled && stop.isEnabled)
            render(ready.copy(endingTrip = true, tripEndPending = false, canPrepareStory = false))
            text(status, R.string.records_end_unconfirmed)

            render(ready.copy(session = playingSession.copy(blocked = true)))
            check(!talk.isEnabled)
            text(status, R.string.playback_blocked)
            render(ready.copy(session = playingSession.copy(closed = true)))
            check(!talk.isEnabled)
            render(ready.copy(session = playingSession.copy(mode = PlaybackPresentationMode.ANSWERING)))
            check(!talk.isEnabled)
            text(status, R.string.playback_answering)
            render(ready.copy(requestingMicrophone = true, notice = PlaybackPresentationNotice.MICROPHONE_PERMISSION_NEEDED))
            check(!talk.isEnabled)
            text(notice, R.string.microphone_permission_needed)
            render(ready.copy(liveInputSupported = false))
            check(!talk.isEnabled)
            text(notice, R.string.microphone_verification_pending)
            render(ready.copy(liveInputSupported = false, notice = PlaybackPresentationNotice.MICROPHONE_PERMISSION_DENIED))
            text(notice, R.string.microphone_permission_denied)

            render(ready.copy(session = null, hasStories = false, hasMusic = true, hasSelectedStory = false, canPrepareStory = false))
            check(play.isEnabled && !selection.isEnabled && !next.isEnabled && !previous.isEnabled && !newStory.isEnabled)
            render(ready.copy(session = null, hasStories = false, hasMusic = false, hasSelectedStory = false, canPrepareStory = false))
            check(!play.isEnabled && !selection.isEnabled)
            text(status, R.string.playback_no_content)
            render(ready)
            check(controls.all { it.isEnabled })
            check(play.performClick() && clicks == 1) // Only the test-owned counter, no command bridge.
            check(!root.isAttachedToWindow)
        }
        mark("presentation_renderer_detached_synthetic_unknown_and_driving_gates_disable_all_playback_controls")
        mark("presentation_renderer_command_preparing_talk_status_and_notice_mapping_preserved_without_host")
        mark("presentation_renderer_preserves_catalog_selection_and_adapter_owned_click_listener")
        TripPresentationRendererProbe.run(instrumentation, mark)
    }
}
