package com.petasos.app.presentation

import android.content.Context
import android.content.res.Configuration

/**
 * DESIGN.md is laid out for an app area about 1330dp wide. A wider area (the Pleos emulator reports
 * 1707dp at 160dpi) gets its density raised so every dp/sp keeps the same proportion to the screen.
 * Never scales down.
 */
object DesignScale {
    private const val DESIGN_WIDTH_DP = 1330f
    private const val MAX_SCALE = 1.5f

    fun wrap(base: Context, areaWidthDp: Int): Context {
        val scale = (areaWidthDp / DESIGN_WIDTH_DP).coerceIn(1f, MAX_SCALE)
        if (scale == 1f) return base
        val config = Configuration(base.resources.configuration)
        config.densityDpi = (config.densityDpi * scale).toInt()
        return base.createConfigurationContext(config)
    }
}
