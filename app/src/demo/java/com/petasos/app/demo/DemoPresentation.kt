package com.petasos.app.demo

import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.petasos.app.R
import com.petasos.app.presentation.ParkedPresentationGate
import com.petasos.app.presentation.PlaybackPresentationCommands
import com.petasos.app.presentation.PlaybackPresentationInput
import com.petasos.app.presentation.PlaybackPresentationMapper
import com.petasos.app.presentation.PlaybackPresentationMode
import com.petasos.app.presentation.PlaybackPresentationRenderer
import com.petasos.app.presentation.PlaybackPresentationSession

/** Read-only view binding; strings/styles/layout remain exclusively in demo resources. */
internal class DemoPresentation(private val root: View) {
    private val playback = PlaybackPresentationRenderer(root)
    fun renderGate(gate: ParkedPresentationGate) = playback.renderGate(gate)

    fun render(value: DemoControllerState, gate: ParkedPresentationGate, resumed: Boolean) {
        val snapshot = value.snapshot
        val visibleGate = if (resumed) gate else ParkedPresentationGate.UNKNOWN
        val ready = snapshot != null && !snapshot.closed && snapshot.failure == null && value.failure == null
        val enabled = ready && !value.busy
        playback.render(PlaybackPresentationMapper.map(PlaybackPresentationInput(
            vehicleGate = visibleGate,
            interactionAvailable = resumed,
            connected = snapshot != null,
            connectionFailed = value.failure != null,
            session = snapshot?.let { PlaybackPresentationSession(
                PlaybackPresentationMode.valueOf(it.mode.name), it.closed, it.failure != null) },
            catalogLoaded = snapshot != null,
            hasStories = snapshot != null,
            hasMusic = snapshot != null,
            commands = PlaybackPresentationCommands(play = enabled && snapshot?.playIntent != true,
                stop = ready, next = enabled, previous = enabled),
        )))
        val controlsEnabled = enabled && visibleGate == ParkedPresentationGate.PARKED
        listOf(R.id.demo_advance_1, R.id.demo_advance_10, R.id.demo_advance_30,
            R.id.demo_route_anseong, R.id.demo_route_yuseong, R.id.demo_route_gap).forEach {
            root.findViewById<View>(it).isEnabled = controlsEnabled
        }
        root.findViewById<View>(R.id.demo_reset).isEnabled = !value.busy && snapshot != null &&
            visibleGate == ParkedPresentationGate.PARKED
        root.findViewById<View>(R.id.demo_route_anseong).isSelected = snapshot?.route == DemoRouteChoice.ANSEONG
        root.findViewById<View>(R.id.demo_route_yuseong).isSelected = snapshot?.route == DemoRouteChoice.YUSEONG
        root.findViewById<View>(R.id.demo_route_gap).isSelected = snapshot?.route == DemoRouteChoice.OBSERVATION_GAP
        text(R.id.demo_story_title, when {
            snapshot?.musicPositionMs != null -> string(R.string.demo_music_title)
            snapshot?.activeStoryTitle != null -> snapshot.activeStoryTitle
            else -> string(R.string.demo_idle_title)
        })
        text(R.id.demo_region, snapshot?.currentRegionName?.let { string(R.string.demo_region_value, it) }
            ?: string(R.string.demo_region_waiting))
        text(R.id.demo_next_title, when {
            snapshot?.offeredStoryTitle != null -> string(R.string.demo_next_value,
                snapshot.targetRegionName ?: "", snapshot.offeredStoryTitle)
            snapshot?.targetRegionName != null -> string(R.string.demo_target_value, snapshot.targetRegionName)
            else -> string(R.string.demo_next_waiting)
        })
        val elapsed = ((snapshot?.elapsedMs ?: DemoFixtureStore.START_MS) - DemoFixtureStore.START_MS) / 1_000L
        text(R.id.demo_clock, string(R.string.demo_clock_value, elapsed / 60, elapsed % 60))
        text(R.id.demo_completed, string(R.string.demo_completed_value,
            snapshot?.completedStoryCount ?: 0L, snapshot?.completedMusicCount ?: 0L))
        root.findViewById<ProgressBar>(R.id.demo_music_progress).apply {
            visibility = if (snapshot?.musicPositionMs != null) View.VISIBLE else View.GONE
            progress = (snapshot?.musicPositionMs ?: 0L).coerceIn(0, 10_000).toInt()
        }
        if (value.failure != null || snapshot?.failure != null || value.resetFailed) {
            text(R.id.playback_notice, string(R.string.demo_operation_failed))
        } else if (value.busy) text(R.id.playback_notice, string(R.string.demo_working))
    }

    private fun text(id: Int, value: String) { root.findViewById<TextView>(id).text = value }
    private fun string(id: Int, vararg args: Any): String = root.context.getString(id, *args)
}
