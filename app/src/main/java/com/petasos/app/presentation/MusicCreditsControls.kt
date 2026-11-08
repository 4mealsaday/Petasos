package com.petasos.app.presentation

import android.app.Activity
import android.app.AlertDialog
import android.widget.Button
import com.petasos.app.R
import com.petasos.app.platform.android.runtime.PetasosSessionHost

/** Parked, read-only credits. The host owns all catalog/file work on its worker queue. */
class MusicCreditsControls(private val activity: Activity) {
    private val button = activity.findViewById<Button>(R.id.music_credits)
    private var host: PetasosSessionHost? = null
    private var available = false
    private var epoch = 0L
    private var dialog: AlertDialog? = null

    init { button.setOnClickListener { show() }; render() }

    fun update(value: PetasosSessionHost?, allowed: Boolean) {
        val usable = allowed && value != null
        if (host !== value || available != usable) {
            epoch++
            dismiss()
        }
        host = value
        available = usable
        render()
    }

    private fun show() {
        val current = host ?: return
        if (!available || dialog != null || activity.isFinishing || activity.isDestroyed) return
        val serial = ++epoch
        val shown = AlertDialog.Builder(activity)
            .setTitle(R.string.music_credits_title)
            .setMessage(R.string.music_credits_loading)
            .setPositiveButton(R.string.music_credits_close, null)
            .create()
        dialog = shown
        shown.setOnDismissListener {
            if (dialog === shown) { dialog = null; epoch++; render() }
        }
        shown.show()
        render()
        try {
            val request = current.musicCredits()
            request.addListener({
                if (!available || epoch != serial || host !== current || dialog !== shown ||
                    activity.isFinishing || activity.isDestroyed) return@addListener
                val body = try { request.get().takeIf { it.isNotBlank() } ?: activity.getString(R.string.music_credits_empty) }
                catch (_: Exception) { activity.getString(R.string.music_credits_unavailable) }
                shown.setMessage(body)
            }, activity.mainExecutor)
        } catch (_: Exception) {
            if (available && epoch == serial && dialog === shown) shown.setMessage(activity.getString(R.string.music_credits_unavailable))
        }
    }

    private fun dismiss() {
        val previous = dialog
        dialog = null
        previous?.dismiss()
    }

    private fun render() { button.isEnabled = available && dialog == null }
}
