package com.petasos.app.planning

import android.content.Context

/** Settings "창밖 안내" (CONTENT_PIPELINE.md 6장). Default on. A per-device convenience, not trip data. */
object WindowGuideSetting {
    private const val FILE = "petasos_settings"
    private const val KEY = "window_guide_enabled"

    fun enabled(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, true)
    } catch (_: Exception) { true }

    fun set(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }
}
