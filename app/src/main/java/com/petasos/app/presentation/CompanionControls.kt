package com.petasos.app.presentation

import android.app.Activity
import com.petasos.app.R
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import com.petasos.app.platform.pleos.navigation.PleosCompanionDisplaySupport

/**
 * The home switch represents the entire companion, never just Media3 play/pause.
 * Native Navigation screen switching cannot satisfy the required simultaneous display.
 */
class CompanionControls(
    private val activity: Activity,
    private val onChanged: () -> Unit,
) {
    private var host: PetasosSessionHost? = null
    private var available = false
    private var generation = 0L
    private var stopping = false
    private var notice: String? = null

    fun update(value: PetasosSessionHost?, allowed: Boolean, shell: JourneyShell) {
        if (host !== value) {
            generation++
            host = value
            stopping = false
        }
        available = allowed
        shell.updateCompanionState(value?.isCompanionActive() == true, stopping, notice)
    }

    fun toggle() {
        val current = host ?: return
        if (!available || stopping) return
        if (!current.isCompanionActive()) {
            // No public, verified navigation-surface adapter exists on the current Pleos image.
            // Do not launch Navigation alone, start audio/location, request unrelated permissions,
            // or persist an ON flag as a substitute for the requested simultaneous experience.
            val support = PleosCompanionDisplaySupport.inspect(activity)
            notice = activity.getString(if (!support.overlayPermissionGranted && !support.overlaySettingsAvailable)
                R.string.companion_display_unavailable else R.string.companion_navigation_surface_unavailable)
            onChanged()
            return
        }
        val epoch = ++generation
        stopping = true
        notice = null
        val completion = current.disableCompanion()
        onChanged()
        completion.addListener({
            if (host !== current || generation != epoch) return@addListener
            stopping = false
            notice = try { completion.get(); null }
            catch (_: Exception) { activity.getString(R.string.companion_stop_unavailable) }
            onChanged()
        }, activity.mainExecutor)
    }
}
