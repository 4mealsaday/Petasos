package com.petasos.app.platform.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.net.toUri
import com.petasos.app.BuildConfig
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.petasos.app.platform.android.audio.AndroidOutputAudioFocus
import com.petasos.app.playback.OperationToken
import com.petasos.app.runtime.*

/** A single, local, non-repeating track. ExoPlayer is never the system session's command target. */
@androidx.annotation.OptIn(UnstableApi::class)
class Media3RuntimeMusic(context: Context) : RuntimeMusic {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private class Slot(val token: OperationToken, val initialPositionMs: Long, val callback: (RuntimeMusicEvent) -> Unit) {
        var player: ExoPlayer? = null
        var focus: AndroidOutputAudioFocus? = null
        var recoveringFocus = false
        var preparationResolved = false
        var started = false
        var stopping = false
        var releaseFailed = false
        var ended = false
        var failed = false
        /** Paused for a window announcement (CONTENT_PIPELINE.md 6장); focus is released meanwhile. */
        var held = false
        var playingSinceMs = 0L
        var playedMs = 0L
        var demoEnd: Runnable? = null
    }
    private class Quiescent(val token: OperationToken, val positionMs: Long, val callback: (RuntimeMusicEvent) -> Unit)
    private var current: Slot? = null
    private var recoveringSlot: Slot? = null
    private var lastQuiescent: Quiescent? = null
    private var closed = false

    override fun startOutput(attempt: OperationToken, source: RuntimeMusicSource, positionMs: Long, callback: (RuntimeMusicEvent) -> Unit) {
        startPreparedOutput(attempt, positionMs, { prepared ->
            prepared(RuntimeMusicPermit(source) { source.isCurrent() })
        }, callback)
    }

    override fun startPreparedOutput(
        attempt: OperationToken,
        positionMs: Long,
        prepare: ((RuntimeMusicPermit?) -> Unit) -> Unit,
        callback: (RuntimeMusicEvent) -> Unit,
    ) {
        main.post {
            if (closed || current != null || positionMs < 0) {
                callback(RuntimeMusicEvent.Failed)
                callback(RuntimeMusicEvent.Stopped(positionMs.coerceAtLeast(0))) // This attempt never created a player.
                return@post
            }
            recoveringSlot?.focus?.close()
            recoveringSlot = null
            val slot = Slot(attempt, positionMs, callback)
            current = slot
            lastQuiescent = null
            try {
                prepare { permit ->
                    main.post prepared@ {
                        if (current !== slot || closed || slot.stopping || slot.preparationResolved) return@prepared
                        slot.preparationResolved = true
                        if (permit == null || !permit.isCurrent()) {
                            stopSlot(slot) // No player exists, so this attempt is independently silent.
                            return@prepared
                        }
                        startPlayer(slot, permit)
                    }
                }
            } catch (_: Exception) {
                if (current === slot && slot.player == null) {
                    callback(RuntimeMusicEvent.Failed)
                    stopSlot(slot)
                }
            }
        }
    }

    private fun startPlayer(slot: Slot, permit: RuntimeMusicPermit) {
        val source = permit.source
        if (source.uri.toUri().scheme != "file") {
            slot.callback(RuntimeMusicEvent.Failed)
            stopSlot(slot)
            return
        }
        fun permitted() = current === slot && !closed && !slot.stopping && !slot.failed && permit.isCurrent()
        if (!permitted()) { stopSlot(slot); return }
        try {
            val player = ExoPlayer.Builder(context).build()
            slot.player = player
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.shuffleModeEnabled = false
            // This owner retains the same request during a stop/recreate resume. ExoPlayer must
            // not independently resume its old player before the Coordinator validates recovery.
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            val focus = createFocus(slot, player)
            slot.focus = focus
            player.setHandleAudioBecomingNoisy(true)
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (current !== slot || slot.stopping || !isPlaying) return
                    if (!slot.started) { slot.started = true; slot.callback(RuntimeMusicEvent.Started) }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (current !== slot || slot.stopping || slot.ended || playbackState != Player.STATE_ENDED) return
                    slot.ended = true
                    val position = player.currentPosition.coerceAtLeast(0)
                    // Leave ListenerSet's callback flush first. Its recursive sendEvent does not
                    // synchronously deliver release-timeout errors from inside another callback.
                    main.post {
                        if (current === slot && !slot.stopping && release(slot, position)) slot.callback(RuntimeMusicEvent.Completed(position))
                    }
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (current !== slot || slot.stopping || playWhenReady) return
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) slot.callback(RuntimeMusicEvent.FocusLost)
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (slot.stopping && error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT) slot.releaseFailed = true
                    if (current === slot && !slot.stopping) { slot.failed = true; slot.callback(RuntimeMusicEvent.Failed) }
                }
            })
            player.setMediaItem(MediaItem.Builder().setMediaId(source.assetId).setUri(source.uri).build(), slot.initialPositionMs)
            // Stop ingress can happen on the worker while this main-thread configuration runs.
            // These guards read only atomic/volatile authority, never a file, hash or database.
            if (!permitted()) { stopSlot(slot); return }
            player.prepare()
            if (!permitted()) { stopSlot(slot); return }
            if (holdOnStart) {
                // DEMO: the story -> music hand-off line speaks first. The line is requested from the
                // transition observer, which runs BEFORE this StartOutput effect, so holdForAnnouncement()
                // would find no slot yet and the first seconds of the track would play under the line.
                // Nothing is played and no focus is taken until holdForAnnouncement(false) releases it,
                // which also keeps the track's focus request out of the announcement's own.
                holdOnStart = false
                slot.held = true
                focus.close()
                slot.focus = null
                return
            }
            if (!focus.acquire()) {
                slot.callback(RuntimeMusicEvent.FocusLost)
                stopSlot(slot)
                return
            }
            if (!permitted()) { stopSlot(slot); return }
            slot.playingSinceMs = SystemClock.elapsedRealtime()
            player.play()
            tick(slot)
            // DEMO: treat DEMO_MUSIC_DURATION_MS of playback as a complete play-through, driving the
            // exact same completion path a real STATE_ENDED would (not a seek -- ExoPlayer clamps
            // seekTo(Long.MAX_VALUE) to a position short of the real end and keeps playing from
            // there instead of reaching STATE_ENDED, so a seek-based trick never actually completes).
            scheduleDemoEnd(slot, player)
        } catch (_: Exception) {
            slot.callback(RuntimeMusicEvent.Failed)
            stopSlot(slot)
        }
    }

    /**
     * DEMO: hold the NEXT track to start, before its player exists, for an announcement that is
     * requested at the mode transition. Cleared by the matching holdForAnnouncement(false).
     */
    private var holdOnStart = false
    fun holdNextStartForAnnouncement() { main.post { holdOnStart = true } }

    private fun createFocus(slot: Slot, player: ExoPlayer): AndroidOutputAudioFocus {
        return AndroidOutputAudioFocus(context, android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build(), pauseWhenDucked = false) { change ->
            if (!closed && (current === slot || recoveringSlot === slot)) when (change) {
                AndroidOutputAudioFocus.Change.DUCK -> if (current === slot && !slot.stopping) player.volume = 0.2f
                AndroidOutputAudioFocus.Change.GAINED -> {
                    if (slot.recoveringFocus) {
                        slot.recoveringFocus = false
                        slot.callback(RuntimeMusicEvent.FocusRegained)
                        if (recoveringSlot === slot) { slot.focus?.close(); recoveringSlot = null }
                    } else if (current === slot && !slot.stopping) player.volume = 1f
                }
                AndroidOutputAudioFocus.Change.TRANSIENT -> {
                    slot.recoveringFocus = true
                    if (current === slot && !slot.stopping) player.pause()
                    slot.callback(RuntimeMusicEvent.FocusTransient)
                }
                AndroidOutputAudioFocus.Change.LOST -> {
                    slot.recoveringFocus = false
                    if (current === slot && !slot.stopping) player.pause()
                    slot.callback(RuntimeMusicEvent.FocusLost)
                }
            }
        }
    }

    /**
     * Window announcement during music: pause and release focus (so the speech's own focus request never reaches
     * the Coordinator as a music focus loss), then take focus again and continue from the same position.
     * The DEMO play-through timer only counts time actually played.
     */
    fun holdForAnnouncement(hold: Boolean) {
        main.post {
            if (!hold) holdOnStart = false // Releasing also cancels a hold queued for a track that never started.
            val slot = current ?: return@post
            val player = slot.player ?: return@post
            if (slot.stopping || closed || slot.held == hold) return@post
            if (hold) {
                slot.held = true
                if (player.isPlaying) slot.playedMs += SystemClock.elapsedRealtime() - slot.playingSinceMs
                slot.demoEnd?.let(main::removeCallbacks)
                player.pause()
                slot.focus?.close()
                slot.focus = null
            } else {
                slot.held = false
                val focus = createFocus(slot, player)
                slot.focus = focus
                if (!focus.acquire()) { slot.callback(RuntimeMusicEvent.FocusLost); return@post }
                slot.playingSinceMs = SystemClock.elapsedRealtime()
                player.play()
                scheduleDemoEnd(slot, player)
            }
        }
    }

    private fun scheduleDemoEnd(slot: Slot, player: ExoPlayer) {
        if (!BuildConfig.DEMO) return
        val end = Runnable {
            if (current === slot && !slot.stopping && !slot.ended && !slot.held) {
                slot.ended = true
                val position = player.currentPosition.coerceAtLeast(0)
                if (release(slot, position)) slot.callback(RuntimeMusicEvent.Completed(position))
            }
        }
        slot.demoEnd?.let(main::removeCallbacks)
        slot.demoEnd = end
        main.postDelayed(end, (DEMO_MUSIC_DURATION_MS - slot.playedMs).coerceAtLeast(0))
    }

    private fun tick(slot: Slot) {
        main.postDelayed({
            if (current === slot && !slot.stopping) {
                slot.player?.let { slot.callback(RuntimeMusicEvent.Position(it.currentPosition.coerceAtLeast(0))) }
                tick(slot)
            }
        }, 1_000)
    }

    override fun stopOutput(attempt: OperationToken) {
        main.post {
            val slot = current?.takeIf { it.token == attempt }
            if (slot != null) stopSlot(slot)
            else if (current == null) lastQuiescent?.takeIf { it.token == attempt }?.let {
                // A Stop ingress may invalidate COMPLETED while the player is already released.
                // This acknowledgment refers to that exact proven-quiescent operation.
                it.callback(RuntimeMusicEvent.Stopped(it.positionMs))
            }
        }
    }

    private fun stopSlot(slot: Slot) {
        if (slot.stopping) return
        val player = slot.player
        if (player == null) {
            slot.stopping = true
            if (current === slot) current = null
            slot.focus?.outputFinished()
            if (slot.recoveringFocus) recoveringSlot = slot
            lastQuiescent = Quiescent(slot.token, slot.initialPositionMs, slot.callback)
            slot.callback(RuntimeMusicEvent.Stopped(slot.initialPositionMs))
            return
        }
        val position = player.currentPosition.coerceAtLeast(0)
        if (release(slot, position)) {
            slot.callback(RuntimeMusicEvent.Stopped(position))
        }
    }

    /** ExoPlayer release synchronously waits for playback-thread teardown; a timeout is a failure. */
    private fun release(slot: Slot, position: Long): Boolean {
        slot.stopping = true
        return try {
            checkNotNull(slot.player).release()
            if (slot.releaseFailed) { slot.focus?.close(); slot.callback(RuntimeMusicEvent.Failed); false }
            else {
                if (current === slot) current = null
                slot.focus?.outputFinished()
                if (slot.recoveringFocus) recoveringSlot = slot
                lastQuiescent = Quiescent(slot.token, position, slot.callback)
                true
            }
        } catch (_: Exception) { slot.releaseFailed = true; slot.focus?.close(); slot.callback(RuntimeMusicEvent.Failed); false }
    }

    override fun cancelFocusRecovery() {
        main.post {
            current?.takeIf { it.recoveringFocus }?.let { it.recoveringFocus = false; it.focus?.close() }
            recoveringSlot?.focus?.close()
            recoveringSlot = null
        }
    }

    override fun close() {
        main.post {
            closed = true
            recoveringSlot?.focus?.close(); recoveringSlot = null
            current?.let { it.recoveringFocus = false; stopSlot(it); it.focus?.close() }
        }
    }

    companion object {
        /**
         * DEMO: a track counts as completely played after this long (also what the planner assumes
         * for the gap). Raising it to fill a whole region squeezes the next story out of its window
         * (`--video` paces story regions at 45 s), and withdrawing the offer to limit the count
         * stalls story scheduling outright — the planner derives the next story boundary from the
         * offered duration. Change the story length or the pacing instead.
         */
        const val DEMO_MUSIC_DURATION_MS = 35_000L
    }
}
