package com.petasos.app.platform.media

import android.content.Context
import android.os.Handler
import android.os.Looper
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
            val focus = AndroidOutputAudioFocus(context, android.media.AudioAttributes.Builder()
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
            if (!focus.acquire()) {
                slot.callback(RuntimeMusicEvent.FocusLost)
                stopSlot(slot)
                return
            }
            if (!permitted()) { stopSlot(slot); return }
            player.play()
            tick(slot)
            // DEMO: treat DEMO_MUSIC_DURATION_MS of playback as a complete play-through, driving the
            // exact same completion path a real STATE_ENDED would (not a seek -- ExoPlayer clamps
            // seekTo(Long.MAX_VALUE) to a position short of the real end and keeps playing from
            // there instead of reaching STATE_ENDED, so a seek-based trick never actually completes).
            if (BuildConfig.DEMO) main.postDelayed({
                if (current === slot && !slot.stopping && !slot.ended) {
                    slot.ended = true
                    val position = player.currentPosition.coerceAtLeast(0)
                    if (release(slot, position)) slot.callback(RuntimeMusicEvent.Completed(position))
                }
            }, DEMO_MUSIC_DURATION_MS)
        } catch (_: Exception) {
            slot.callback(RuntimeMusicEvent.Failed)
            stopSlot(slot)
        }
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

    private companion object {
        const val DEMO_MUSIC_DURATION_MS = 15_000L
    }
}
