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
    private val renderer = PlaybackPresentationRenderer(activity.findViewById<View>(android.R.id.content))
    private val selection = activity.findViewById<Spinner>(R.id.story_selection)
    private val play = activity.findViewById<Button>(R.id.playback_play)
    private val stop = activity.findViewById<Button>(R.id.playback_stop)
    private val next = activity.findViewById<Button>(R.id.playback_next)
    private val previous = activity.findViewById<Button>(R.id.playback_previous)
    private val talk = activity.findViewById<View>(R.id.playback_talk)
    private val newStory = activity.findViewById<Button>(R.id.playback_new_story)
    private val tripControls = TripControls(activity)
    private val musicCredits = MusicCreditsControls(activity)
    private val selectionIntent = StorySelectionIntent()
    private val shell = JourneyShell(activity) { render() }
    private val companion = CompanionControls(activity) { render() }
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
    private var presentationNotice = PlaybackPresentationNotice.READY
    private var requestingMicrophone = false
    private var preparingPlayback = false
    private var playbackCommandGeneration = 0L
    private val uiListener: () -> Unit = { if (started) render() }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { if (started) render() }
    }

    init {
        shell.onCompanionToggle = companion::toggle
        tripControls.onInterestsSaved = shell::interestsSaved
        play.setOnClickListener { visiblePlayback { connected ->
            val selectedId = selectionIntent.storyForPlay(host?.snapshot()?.closed == false)
            if (selectedId != null) {
                val selected = items.firstOrNull { it.mediaId == selectedId } ?: return@visiblePlayback
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
        playbackCommandGeneration++
        host?.cancelVisiblePlaybackPreparation()
        preparingPlayback = false
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

    fun destroy() { shell.dispose() }
    fun onLowMemory() { shell.onLowMemory() }
    fun saveTransientState() { shell.saveTransientState() }

    /** Permission completion is informational only. A new explicit Talk click is always required. */
    fun permissionResult(requestCode: Int, granted: Boolean): Boolean {
        if (tripControls.permissionResult(requestCode, granted)) return true
        if (requestCode != MICROPHONE_REQUEST) return false
        requestingMicrophone = false
        presentationNotice = if (granted) PlaybackPresentationNotice.MICROPHONE_PERMISSION_GRANTED else PlaybackPresentationNotice.MICROPHONE_PERMISSION_DENIED
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
        playbackCommandGeneration++
        preparingPlayback = false
        host?.cancelVisiblePlaybackPreparation()
        presentationNotice = PlaybackPresentationNotice.READY
        try { action(connected) } catch (_: Exception) { presentationNotice = PlaybackPresentationNotice.OPERATION_UNAVAILABLE }
        render()
    }

    private fun visiblePlayback(action: (MediaBrowser) -> Unit) {
        if (!mayInteract() || preparingPlayback) return
        val connected = browser ?: return
        val current = host ?: return
        if (!connected.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) return
        val epoch = generation
        val commandGeneration = ++playbackCommandGeneration
        preparingPlayback = true
        val request = current.prepareVisiblePlayback()
        request.addListener({
            if (generation != epoch || commandGeneration != playbackCommandGeneration ||
                host !== current || browser !== connected) return@addListener
            preparingPlayback = false
            if (!mayInteract()) {
                render(); return@addListener
            }
            try {
                check(request.get())
                check(current.consumeVisiblePlaybackPreparation(request))
                action(connected)
                presentationNotice = PlaybackPresentationNotice.READY
            } catch (_: Exception) {
                presentationNotice = PlaybackPresentationNotice.OPERATION_UNAVAILABLE
            }
            render()
        }, activity.mainExecutor)
        render()
    }

    private fun requestTalk() {
        if (!mayInteract() || requestingMicrophone) return
        val current = host ?: return
        if (!current.supportsLiveInput()) return
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestingMicrophone = true
            presentationNotice = PlaybackPresentationNotice.MICROPHONE_PERMISSION_NEEDED
            render()
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
            return
        }
        try {
            val request = current.talk()
            request.addListener({
                if (!started || host !== current) return@addListener
                try { request.get() } catch (_: Exception) { presentationNotice = PlaybackPresentationNotice.OPERATION_UNAVAILABLE }
                render()
            }, activity.mainExecutor)
        } catch (_: Exception) { presentationNotice = PlaybackPresentationNotice.OPERATION_UNAVAILABLE; render() }
    }

    private fun requestNewStory() {
        if (!mayInteract()) return
        val current = host ?: return
        val selected = items.getOrNull(selection.selectedItemPosition - 1) ?: return
        if (!current.canPrepareStory()) return
        playbackCommandGeneration++
        preparingPlayback = false
        current.cancelVisiblePlaybackPreparation()
        presentationNotice = PlaybackPresentationNotice.PREPARING_STORY
        val request = current.generateAndPlayStory(selected.mediaId)
        request.addListener({
            if (!started || host !== current) return@addListener
            try {
                request.get()
                presentationNotice = PlaybackPresentationNotice.READY
                browser?.let { loadCatalog(it, generation) }
            } catch (_: Exception) { presentationNotice = PlaybackPresentationNotice.STORY_PREPARATION_FAILED }
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
        val gate = if (started && resumed) parkedPresentationGate(policy) else ParkedPresentationGate.UNKNOWN
        renderer.renderGate(gate) // Hide first even if a later Host/control refresh throws.
        val available = mayInteract()
        if (hostVisible != available) { hostVisible = available; host?.setTalkUiVisible(available) }
        val connected = browser
        val current = host
        val snapshot = current?.snapshot()
        shell.update(current, available, ::storyTitle, ::replayStory)
        companion.update(current, available, shell)
        tripControls.update(current, shell.settingsVisible)
        musicCredits.update(current, available && shell.settingsVisible)
        // Capture live dependencies here. The mapper/renderer cannot grant permission or issue commands.
        renderer.render(PlaybackPresentationMapper.map(PlaybackPresentationInput(
            vehicleGate = gate,
            interactionAvailable = available,
            connected = connected != null,
            connectionFailed = connectionFailed,
            session = snapshot?.let { PlaybackPresentationSession(it.mode.presentationMode(), it.closed, it.blockers.isNotEmpty()) },
            preparingStory = current?.isPreparingStory() == true,
            endingTrip = current?.isEndingTrip() == true,
            tripEndPending = current?.isTripEndPending() == true,
            catalogLoaded = loaded,
            hasStories = items.isNotEmpty(),
            hasMusic = current?.hasMusic() == true,
            hasSelectedStory = items.getOrNull(selection.selectedItemPosition - 1) != null,
            canPrepareStory = current?.canPrepareStory() == true,
            liveInputSupported = current?.supportsLiveInput(),
            requestingMicrophone = requestingMicrophone,
            commands = PlaybackPresentationCommands(
                play = connected?.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) == true,
                stop = connected?.isCommandAvailable(Player.COMMAND_STOP) == true,
                next = connected?.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT) == true,
                previous = connected?.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS) == true,
            ),
            notice = presentationNotice,
        )))
        if (preparingPlayback) {
            play.isEnabled = false
            stop.isEnabled = available && connected?.isCommandAvailable(Player.COMMAND_STOP) == true
        }
    }

    private fun storyTitle(versionId: String): String? = items.firstOrNull { it.mediaId == versionId }
        ?.mediaMetadata?.title?.toString()?.take(120)

    private fun replayStory(versionId: String) {
        val selected = items.firstOrNull { it.mediaId == versionId } ?: return
        visiblePlayback { connected ->
            if (!connected.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM)) return@visiblePlayback
            connected.setMediaItem(selected)
            connected.prepare()
            connected.play()
            selectionIntent.playRequested()
        }
    }

    private fun PlaybackMode.presentationMode(): PlaybackPresentationMode = when (this) {
        PlaybackMode.IDLE -> PlaybackPresentationMode.IDLE
        PlaybackMode.STORY -> PlaybackPresentationMode.STORY
        PlaybackMode.MUSIC -> PlaybackPresentationMode.MUSIC
        PlaybackMode.TALK_PENDING -> PlaybackPresentationMode.TALK_PENDING
        PlaybackMode.PROMPT -> PlaybackPresentationMode.PROMPT
        PlaybackMode.LISTENING -> PlaybackPresentationMode.LISTENING
        PlaybackMode.GENERATING -> PlaybackPresentationMode.GENERATING
        PlaybackMode.ANSWERING -> PlaybackPresentationMode.ANSWERING
        PlaybackMode.RESUMING -> PlaybackPresentationMode.RESUMING
        PlaybackMode.PAUSED -> PlaybackPresentationMode.PAUSED
    }

    companion object { const val MICROPHONE_REQUEST = 4801 }
}

/** Separately testable view gate; neither service connection nor absence of flags implies parked. */
object ParkedPreviewVisibility {
    fun apply(content: View, message: TextView, policy: VehicleUxPolicy) {
        PlaybackPresentationRenderer.renderGate(content, message, parkedPresentationGate(policy))
    }
}

private fun parkedPresentationGate(policy: VehicleUxPolicy): ParkedPresentationGate = when {
    policy.permitsParkedPreview -> ParkedPresentationGate.PARKED
    policy.available -> ParkedPresentationGate.RESTRICTED
    else -> ParkedPresentationGate.UNKNOWN
}
