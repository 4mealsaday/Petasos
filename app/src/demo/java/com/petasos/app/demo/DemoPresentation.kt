package com.petasos.app.demo

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import com.petasos.app.R
import com.petasos.app.presentation.ParkedPresentationGate
import com.petasos.app.presentation.PlaybackPresentationCommands
import com.petasos.app.presentation.PlaybackPresentationInput
import com.petasos.app.presentation.PlaybackPresentationMapper
import com.petasos.app.presentation.PlaybackPresentationMode
import com.petasos.app.presentation.PlaybackPresentationRenderer
import com.petasos.app.presentation.PlaybackPresentationSession
import com.petasos.app.presentation.records.DisplayRegionShape
import com.petasos.app.presentation.records.RecordsGeometryCatalog
import com.petasos.app.presentation.records.RegionalMapView
import java.util.concurrent.Executors

/** Only current public state crosses this UI boundary. Future planner candidates stay internal. */
internal class DemoPresentation(private val root: View) {
    private val playback = PlaybackPresentationRenderer(root)
    private val map = RegionalMapView(root.context)
    private val main = Handler(Looper.getMainLooper())
    private val mapWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PetasosDemoMap").apply { isDaemon = true }
    }
    private var geometry = emptyList<DisplayRegionShape>()
    private var currentRegionId: String? = null
    private var closed = false
    private var talkNotice = false
    var seeking = false

    init {
        (root.findViewById<FrameLayout>(R.id.demo_map_container).parent as View).clipToOutline = true
        map.contentDescription = string(R.string.demo_map_description)
        root.findViewById<FrameLayout>(R.id.demo_map_container).addView(map,
            FrameLayout.LayoutParams(-1, -1))
        val app = root.context.applicationContext
        mapWorker.execute {
            val loaded = try { RecordsGeometryCatalog.load(app) } catch (_: Exception) { emptyList() }
            main.post {
                if (!closed) {
                    geometry = loaded
                    renderMap()
                    if (loaded.isEmpty()) map.contentDescription = string(R.string.demo_map_unavailable)
                }
            }
        }
    }

    fun close() { closed = true; mapWorker.shutdownNow(); main.removeCallbacksAndMessages(null) }
    fun renderGate(gate: ParkedPresentationGate) = playback.renderGate(gate)
    fun dismissTalkNotice() { talkNotice = false }
    fun showTalkUnavailable() { talkNotice = true; text(R.id.playback_notice, string(R.string.demo_talk_unavailable)) }

    fun previewSeek(seconds: Int) {
        text(R.id.demo_seek_label, string(R.string.demo_timeline_value, seconds / 60, seconds % 60))
    }

    fun render(value: DemoControllerState, gate: ParkedPresentationGate, resumed: Boolean) {
        val snapshot = value.snapshot
        val visibleGate = if (resumed) gate else ParkedPresentationGate.UNKNOWN
        val ready = snapshot != null && !snapshot.closed && snapshot.failure == null && value.failure == null
        val enabled = ready && !value.busy && !seeking
        val elapsedMs = ((snapshot?.elapsedMs ?: DemoFixtureStore.START_MS) - DemoFixtureStore.START_MS)
            .coerceIn(0, DemoReplayTimeline.DURATION_MS)
        playback.render(PlaybackPresentationMapper.map(PlaybackPresentationInput(
            vehicleGate = visibleGate, interactionAvailable = resumed,
            connected = snapshot != null, connectionFailed = value.failure != null,
            session = snapshot?.let { PlaybackPresentationSession(
                PlaybackPresentationMode.valueOf(it.mode.name), it.closed, it.failure != null) },
            catalogLoaded = snapshot != null, hasStories = snapshot != null, hasMusic = snapshot != null,
            commands = PlaybackPresentationCommands(
                play = enabled && snapshot?.playIntent != true && elapsedMs < DemoReplayTimeline.DURATION_MS,
                stop = ready, next = enabled, previous = false),
        )))
        val controlsEnabled = enabled && visibleGate == ParkedPresentationGate.PARKED
        listOf(R.id.demo_route_anseong, R.id.demo_route_yuseong, R.id.demo_route_gap,
            R.id.playback_talk).forEach { root.findViewById<View>(it).isEnabled = controlsEnabled }
        root.findViewById<SeekBar>(R.id.demo_timeline).apply {
            isEnabled = (controlsEnabled || seeking) && visibleGate == ParkedPresentationGate.PARKED
            if (!seeking) { progress = (elapsedMs / 1_000).toInt(); previewSeek(progress) }
        }
        root.findViewById<View>(R.id.demo_reset).isEnabled = !value.busy && !seeking && snapshot != null &&
            visibleGate == ParkedPresentationGate.PARKED
        root.findViewById<View>(R.id.demo_route_anseong).isSelected = snapshot?.route == DemoRouteChoice.ANSEONG
        root.findViewById<View>(R.id.demo_route_yuseong).isSelected = snapshot?.route == DemoRouteChoice.YUSEONG
        root.findViewById<View>(R.id.demo_route_gap).isSelected = snapshot?.route == DemoRouteChoice.OBSERVATION_GAP
        text(R.id.demo_story_title, when {
            snapshot?.musicPositionMs != null -> string(R.string.demo_music_title)
            snapshot?.activeStoryTitle != null -> snapshot.activeStoryTitle
            else -> string(R.string.demo_idle_title)
        })
        text(R.id.demo_caption, snapshot?.caption ?: if (snapshot?.musicPositionMs != null)
            string(R.string.demo_caption_gap) else string(R.string.demo_caption_ready))
        text(R.id.demo_region, snapshot?.currentRegionName?.let { string(R.string.demo_region_value, it) }
            ?: string(R.string.demo_region_waiting))
        if (currentRegionId != snapshot?.currentRegionId) {
            currentRegionId = snapshot?.currentRegionId
            renderMap()
        }
        val seconds = elapsedMs / 1_000
        text(R.id.demo_clock, string(R.string.demo_clock_value, seconds / 60, seconds % 60))
        text(R.id.playback_notice, when {
            value.failure != null || snapshot?.failure != null || value.resetFailed -> string(R.string.demo_operation_failed)
            seeking -> string(R.string.demo_working)
            talkNotice -> string(R.string.demo_talk_unavailable)
            elapsedMs == DemoReplayTimeline.DURATION_MS -> string(R.string.demo_end_notice)
            snapshot?.caption != null -> string(R.string.demo_caption_source)
            else -> string(R.string.ready_description)
        })
    }

    private fun renderMap() {
        map.submit(geometry, highlightedRegionIds = setOfNotNull(currentRegionId),
            selectedRegionId = currentRegionId, focusOnPaths = currentRegionId != null)
    }

    private fun text(id: Int, value: String) { root.findViewById<TextView>(id).text = value }
    private fun string(id: Int, vararg args: Any): String = root.context.getString(id, *args)
}
