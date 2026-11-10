package com.petasos.app.presentation

import android.app.Activity
import android.content.ComponentName
import android.view.View
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
    private val tripControls = TripControls(activity)
    private val sources = SourcesControls(activity)
    private val selectionIntent = StorySelectionIntent()
    private val shell = JourneyShell(activity) { render() }
    private val companion = CompanionControls(activity) { render() }
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
    private var preparingPlayback = false
    private var playbackCommandGeneration = 0L
    private val uiListener: () -> Unit = { if (started) render() }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { if (started) render() }
    }

    init {
        shell.onCompanionToggle = companion::toggle
        shell.onReturnToNavigation = companion::returnToNavigation
        shell.onOnboardingContinue = tripControls::ensureLocationConsent
        tripControls.onInterestsSaved = shell::interestsSaved
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

    /** Permission completion is informational only. A new explicit gesture is always required. */
    fun permissionResult(requestCode: Int, granted: Boolean): Boolean {
        if (tripControls.permissionResult(requestCode, granted)) return true
        if (companion.permissionResult(requestCode, granted)) return true
        return false
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
                selectionIntent.replaceCatalogSelection(
                    items.firstOrNull { it.mediaId == connected.currentMediaItem?.mediaId }?.mediaId)
            } catch (_: Exception) { connectionFailed = true }
            render()
        }, activity.mainExecutor)
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
            } catch (_: Exception) { /* The row simply stays replayable; nothing is surfaced as a dev notice. */ }
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
        sources.update(value, false)
        value?.addUiListener(uiListener)
    }

    private fun mayInteract() = started && resumed && policy.permitsParkedPreview && browser != null

    private fun render() {
        val gate = if (started && resumed) parkedPresentationGate(policy) else ParkedPresentationGate.UNKNOWN
        renderer.renderGate(gate) // Hide first even if a later Host/control refresh throws.
        val available = mayInteract()
        if (hostVisible != available) { hostVisible = available; host?.setTalkUiVisible(available) }
        val current = host
        shell.update(current, available, ::storyTitle, ::replayStory)
        companion.update(current, available, shell)
        tripControls.update(current, available)
        sources.update(current, available && shell.sourcesVisible)
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

    @Suppress("unused")
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
