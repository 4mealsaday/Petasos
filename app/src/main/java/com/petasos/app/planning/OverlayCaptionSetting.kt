package com.petasos.app.planning

import android.content.Context

/**
 * Settings "내비 위 자막". Default on.
 *
 * The talk button stays either way; only the caption card is hidden, so the navigation map below
 * is not covered while recording. A per-device convenience, not trip data.
 */
object OverlayCaptionSetting {
    private const val FILE = "petasos_settings"
    private const val KEY = "overlay_caption_enabled"

    fun enabled(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, true)
    } catch (_: Exception) { true }

    fun set(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }
}
