package com.petasos.app.presentation

import android.app.Activity
import android.widget.TextView
import com.petasos.app.R
import com.petasos.app.platform.android.runtime.PetasosSessionHost

/** Parked, read-only music credits for the data-sources page. The host owns all catalog/file work. */
class SourcesControls(private val activity: Activity) {
    private val music = activity.findViewById<TextView>(R.id.sources_music)
    private var host: PetasosSessionHost? = null
    private var visible = false
    private var epoch = 0L
    private var loaded = false

    fun update(value: PetasosSessionHost?, show: Boolean) {
        val usable = show && value != null
        if (host !== value || visible != usable) {
            epoch++
            loaded = false
            if (!usable) music.setText(R.string.sources_music_loading)
        }
        host = value
        visible = usable
        if (usable && !loaded) load(value!!)
    }

    private fun load(current: PetasosSessionHost) {
        loaded = true
        val serial = ++epoch
        try {
            val request = current.musicCredits()
            request.addListener({
                if (!visible || epoch != serial || host !== current || activity.isFinishing || activity.isDestroyed) return@addListener
                music.text = try { request.get().takeIf { it.isNotBlank() } ?: activity.getString(R.string.sources_music_empty) }
                catch (_: Exception) { activity.getString(R.string.sources_music_unavailable) }
            }, activity.mainExecutor)
        } catch (_: Exception) {
            music.setText(R.string.sources_music_unavailable)
        }
    }
}
