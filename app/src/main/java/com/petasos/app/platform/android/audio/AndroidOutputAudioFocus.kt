package com.petasos.app.platform.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** One output's registration, retained while a transiently interrupted output awaits real GAIN. */
class AndroidOutputAudioFocus(
    context: Context,
    attributes: AudioAttributes,
    private val pauseWhenDucked: Boolean,
    private val onChange: (Change) -> Unit,
) : AutoCloseable {
    enum class Change { TRANSIENT, DUCK, LOST, GAINED }
    private val manager = requireNotNull(context.getSystemService(AudioManager::class.java))
    private var registered = false
    private var closed = false
    private var ducked = false
    var awaitingGain = false
        private set
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(pauseWhenDucked)
        .setOnAudioFocusChangeListener({ change ->
            if (registered && !closed) when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    awaitingGain = true; onChange(Change.TRANSIENT)
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    if (pauseWhenDucked) { awaitingGain = true; onChange(Change.TRANSIENT) }
                    else { ducked = true; onChange(Change.DUCK) }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    awaitingGain = false; ducked = false
                    onChange(Change.LOST)
                    close()
                }
                AudioManager.AUDIOFOCUS_GAIN -> if (awaitingGain || ducked) {
                    awaitingGain = false; ducked = false; onChange(Change.GAINED)
                }
            }
        }, Handler(Looper.getMainLooper()))
        .build()

    fun acquire(): Boolean {
        if (closed || registered) return false
        registered = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return registered
    }

    /** Physical output completion is separate from the focus registration needed for GAIN. */
    fun outputFinished() { if (!awaitingGain) close() }

    override fun close() {
        if (closed) return
        closed = true; awaitingGain = false; ducked = false
        if (registered) {
            registered = false
            try { manager.abandonAudioFocusRequest(request) } catch (_: RuntimeException) { }
        }
    }
}
