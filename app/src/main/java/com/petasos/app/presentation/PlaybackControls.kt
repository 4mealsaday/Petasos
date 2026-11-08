package com.petasos.app.presentation

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.petasos.app.R
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import com.petasos.app.platform.automotive.CarPolicySource
import com.petasos.app.platform.automotive.VehicleUxPolicy
import com.petasos.app.platform.media.PetasosMediaService
import com.petasos.app.playback.PlaybackMode

/** Presentation binding only: MediaBrowser commands, parked visibility, and explicit microphone consent. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackControls(private val activity: Activity) {
    private val content = activity.findViewById<View>(R.id.preview_content)
    private val vehicleNotice = activity.findViewById<TextView>(R.id.vehicle_policy_notice)
    private val status = activity.findViewById<TextView>(R.id.playback_status)
    private val notice = activity.findViewById<TextView>(R.id.playback_notice)
    private val selection = activity.findViewById<Spinner>(R.id.story_selection)
    private val play = activity.findViewById<Button>(R.id.playback_play)
    private val stop = activity.findViewById<Button>(R.id.playback_stop)
    private val next = activity.findViewById<Button>(R.id.playback_next)
    private val previous = activity.findViewById<Button>(R.id.playback_previous)
    private val talk = activity.findViewById<Button>(R.id.playback_talk)
    private val newStory = activity.findViewById<Button>(R.id.playback_new_story)
    private val tripControls = TripControls(activity)
    private val musicCredits = MusicCreditsControls(activity)
    private val selectionIntent = StorySelectionIntent()
    private var updatingSelection = false
    private var source: CarPolicySource? = null
    private var policy = VehicleUxPolicy.unavailable()
    private var future: ListenableFuture<MediaBrowser>? = null
    private var browser: MediaBrowser? = null
    private var host: PetasosSessionHost? = null
    private var hostVisible = false
    private var started = false
    private var resumed = false
    private var generation = 0L
    private var items = emptyList<MediaItem>()
    private var loaded = false
    private var connectionFailed = false
    private var noticeResource = R.string.ready_description
    private var requestingMicrophone = false
    private val uiListener: () -> Unit = { if (started) render() }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { if (started) render() }
    }

    init {
        play.setOnClickListener { mediaCommand(Player.COMMAND_PLAY_PAUSE) { connected ->
            val selectedId = selectionIntent.storyForPlay(host?.snapshot()?.closed == false)
            if (selectedId != null) {
                val selected = items.firstOrNull { it.mediaId == selectedId } ?: return@mediaCommand
                connected.setMediaItem(selected)
            }
            connected.prepare()
            connected.play()
            selectionIntent.playRequested()
        } }
        stop.setOnClickListener { mediaCommand(Player.COMMAND_STOP) { it.stop() } }
        next.setOnClickListener { mediaCommand(Player.COMMAND_SEEK_TO_NEXT) { it.seekToNext() } }
        previous.setOnClickListener { mediaCommand(Player.COMMAND_SEEK_TO_PREVIOUS) { it.seekToPrevious() } }
        talk.setOnClickListener { requestTalk() }
        newStory.setOnClickListener { requestNewStory() }
        selection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!updatingSelection) {
                    selectionIntent.select(items.getOrNull(position - 1)?.mediaId)
                    render()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        render()
    }

    fun start() {
        if (started) return
        started = true
        val epoch = ++generation
        policy = VehicleUxPolicy.unavailable()
        loaded = false; connectionFailed = false
        render() // The XML also starts hidden: a car-service read is required before any preview is visible.
        source = CarPolicySource(activity, onUx = { value ->
            if (started && generation == epoch) { policy = value; render() }
        }, onPower = {}).also { it.start() }
        try {
            val connecting = MediaBrowser.Builder(activity,
                SessionToken(activity, ComponentName(activity, PetasosMediaService::class.java)))
                .setListener(object : MediaBrowser.Listener {
                    override fun onDisconnected(controller: MediaController) {
                        if (started && generation == epoch) {
                            browser = null; connectionFailed = true; attachHost(null); render()
                        }
                    }
                }).buildAsync()
            future = connecting
            connecting.addListener({
                if (started && generation == epoch) {
                    try {
                        val connected = connecting.get()
                        browser = connected
                        connected.addListener(playerListener)
                        attachHost(PetasosMediaService.currentHost)
                        loadCatalog(connected, epoch)
                    } catch (_: Exception) { connectionFailed = true }
                    render()
                }
            }, activity.mainExecutor)
        } catch (_: Exception) { connectionFailed = true; render() }
    }

    fun setResumed(value: Boolean) { resumed = value; render() }

    fun stop() {
        if (!started) return
        started = false; resumed = false; generation++
        policy = VehicleUxPolicy.unavailable()
        attachHost(null) // Cancels an input interaction before releasing the Activity's controller.
        source?.close(); source = null
        browser?.removeListener(playerListener)
        browser = null
        future?.let(MediaController::releaseFuture)
        future = null
        items = emptyList(); loaded = false
        selectionIntent.replaceCatalogSelection(null)
        render()
    }

    /** Permission completion is informational only. A new explicit Talk click is always required. */
    fun permissionResult(requestCode: Int, granted: Boolean): Boolean {
        if (tripControls.permissionResult(requestCode, granted)) return true
        if (requestCode != MICROPHONE_REQUEST) return false
        requestingMicrophone = false
        noticeResource = if (granted) R.string.microphone_permission_granted else R.string.microphone_permission_denied
        render()
        return true
    }

    private fun loadCatalog(connected: MediaBrowser, epoch: Long) {
        val request = connected.getChildren(PetasosMediaService.ROOT_ID, 0, 100, null)
        request.addListener({
            if (!started || generation != epoch || browser !== connected) return@addListener
            try {
                val result = request.get()
                val entries = result.value ?: throw IllegalStateException("CATALOG_UNAVAILABLE")
                items = entries.filter { it.mediaId.isNotBlank() && it.localConfiguration == null && it.mediaMetadata.isPlayable == true }
                loaded = true
                val labels = listOf(activity.getString(R.string.story_selection_automatic)) +
                    items.map { it.mediaMetadata.title?.toString()?.take(120) ?: activity.getString(R.string.app_name) }
                val current = connected.currentMediaItem?.mediaId
                val index = items.indexOfFirst { it.mediaId == current }.takeIf { it >= 0 }?.plus(1) ?: 0
                updatingSelection = true
                try {
                    selectionIntent.replaceCatalogSelection(items.getOrNull(index - 1)?.mediaId)
                    selection.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels).apply {
                        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                    selection.setSelection(index)
                } finally { updatingSelection = false }
            } catch (_: Exception) { connectionFailed = true }
            render()
        }, activity.mainExecutor)
    }

    private fun mediaCommand(command: Int, action: (MediaBrowser) -> Unit) {
        if (!mayInteract()) return
        val connected = browser ?: return
        if (!connected.isCommandAvailable(command)) return
        noticeResource = R.string.ready_description
        try { action(connected) } catch (_: Exception) { noticeResource = R.string.playback_operation_unavailable }
        render()
    }

    private fun requestTalk() {
        if (!mayInteract() || requestingMicrophone) return
        val current = host ?: return
        if (!current.supportsLiveInput()) return
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestingMicrophone = true
            noticeResource = R.string.microphone_permission_needed
            render()
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
            return
        }
        try {
            val request = current.talk()
            request.addListener({
                if (!started || host !== current) return@addListener
                try { request.get() } catch (_: Exception) { noticeResource = R.string.playback_operation_unavailable }
                render()
            }, activity.mainExecutor)
        } catch (_: Exception) { noticeResource = R.string.playback_operation_unavailable; render() }
    }

    private fun requestNewStory() {
        if (!mayInteract()) return
        val current = host ?: return
        val selected = items.getOrNull(selection.selectedItemPosition - 1) ?: return
        if (!current.canPrepareStory()) return
        noticeResource = R.string.playback_preparing_story_description
        val request = current.generateAndPlayStory(selected.mediaId)
        request.addListener({
            if (!started || host !== current) return@addListener
            try {
                request.get()
                noticeResource = R.string.ready_description
                browser?.let { loadCatalog(it, generation) }
            } catch (_: Exception) { noticeResource = R.string.playback_story_preparation_failed }
            render()
        }, activity.mainExecutor)
        render()
    }

    private fun attachHost(value: PetasosSessionHost?) {
        if (value === host) return
        host?.setLocationUiVisible(false)
        host?.setTalkUiVisible(false)
        host?.removeUiListener(uiListener)
        host = value; hostVisible = false
        // Location while-in-use follows Activity visibility, independent of parked controls or focus.
        value?.setLocationUiVisible(started)
        musicCredits.update(value, false)
        value?.addUiListener(uiListener)
    }

    private fun mayInteract() = started && resumed && policy.permitsParkedPreview && browser != null

    private fun render() {
        ParkedPreviewVisibility.apply(content, vehicleNotice, policy)
        val available = mayInteract()
        if (hostVisible != available) { hostVisible = available; host?.setTalkUiVisible(available) }
        val connected = browser
        val snapshot = host?.snapshot()
        val preparing = host?.isPreparingStory() == true || host?.isEndingTrip() == true
        tripControls.update(host, available)
        musicCredits.update(host, available)
        val hasContent = items.isNotEmpty() || host?.hasMusic() == true
        selection.isEnabled = available && !preparing && items.isNotEmpty()
        play.isEnabled = available && !preparing && hasContent && connected?.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) == true
        stop.isEnabled = available && connected?.isCommandAvailable(Player.COMMAND_STOP) == true
        next.isEnabled = available && !preparing && snapshot != null && connected?.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT) == true
        previous.isEnabled = available && !preparing && snapshot != null && connected?.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS) == true
        newStory.isEnabled = available && items.getOrNull(selection.selectedItemPosition - 1) != null && host?.canPrepareStory() == true
        talk.isEnabled = available && host?.supportsLiveInput() == true && !requestingMicrophone && snapshot?.closed != true && snapshot?.blockers?.isNotEmpty() != true &&
            (snapshot == null || snapshot.mode in setOf(PlaybackMode.IDLE, PlaybackMode.PAUSED, PlaybackMode.STORY, PlaybackMode.MUSIC,
                PlaybackMode.TALK_PENDING, PlaybackMode.PROMPT, PlaybackMode.LISTENING))
        status.setText(when {
            connectionFailed -> R.string.playback_unavailable
            connected == null -> R.string.ready_title
            snapshot?.blockers?.isNotEmpty() == true -> R.string.playback_blocked
            host?.isEndingTrip() == true -> if (host?.isTripEndPending() == true) R.string.records_ending_trip else R.string.records_end_unconfirmed
            preparing -> R.string.playback_preparing_story
            loaded && !hasContent && (snapshot == null || snapshot.mode == PlaybackMode.IDLE) -> R.string.playback_no_content
            else -> when (snapshot?.mode) {
                PlaybackMode.STORY -> R.string.playback_story
                PlaybackMode.MUSIC -> R.string.playback_music
                PlaybackMode.TALK_PENDING -> R.string.playback_talk_pending
                PlaybackMode.PROMPT -> R.string.playback_prompt
                PlaybackMode.LISTENING -> R.string.playback_listening
                PlaybackMode.GENERATING -> R.string.playback_generating
                PlaybackMode.ANSWERING -> R.string.playback_answering
                PlaybackMode.RESUMING -> R.string.playback_resuming
                PlaybackMode.PAUSED -> R.string.playback_paused
                else -> R.string.playback_idle
            }
        })
        notice.setText(if (host?.supportsLiveInput() == false && noticeResource == R.string.ready_description)
            R.string.microphone_verification_pending else noticeResource)
    }

    companion object { const val MICROPHONE_REQUEST = 4801 }
}

/** Separately testable view gate; neither service connection nor absence of flags implies parked. */
object ParkedPreviewVisibility {
    fun apply(content: View, message: TextView, policy: VehicleUxPolicy) {
        content.visibility = if (policy.permitsParkedPreview) View.VISIBLE else View.GONE
        message.visibility = if (policy.permitsParkedPreview) View.GONE else View.VISIBLE
        message.setText(if (policy.available) R.string.vehicle_policy_restricted else R.string.vehicle_policy_waiting)
    }
}
