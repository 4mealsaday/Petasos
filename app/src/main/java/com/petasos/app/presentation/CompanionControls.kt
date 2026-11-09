package com.petasos.app.presentation

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import com.petasos.app.R
import com.petasos.app.capability.navigation.NavigationScreenCommand
import com.petasos.app.platform.android.location.LocationAccess
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import com.petasos.app.platform.pleos.navigation.PleosCompanionDisplaySupport
import com.petasos.app.platform.pleos.navigation.PleosNavigationScreens

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
    private var starting = false
    private var stopping = false
    private var requestingPermission = false
    private var notice: String? = null

    fun update(value: PetasosSessionHost?, allowed: Boolean, shell: JourneyShell) {
        if (host !== value) {
            generation++
            host = value
            stopping = false
            starting = false
        }
        available = allowed
        shell.updateCompanionState(value?.isCompanionActive() == true, stopping || starting, notice)
    }

    /** Main-thread Activity permission callback passthrough; see [com.petasos.app.MainActivity]. */
    fun permissionResult(requestCode: Int, granted: Boolean): Boolean {
        if (requestCode != PERMISSION_REQUEST) return false
        requestingPermission = false
        notice = activity.getString(if (granted) R.string.companion_permission_needed else R.string.companion_permission_denied)
        onChanged()
        return true
    }

    fun toggle() {
        val current = host ?: return
        if (!available || stopping || starting || requestingPermission) return
        if (current.isCompanionActive()) {
            stopCompanion(current)
            return
        }
        startCompanion(current)
    }

    private fun startCompanion(current: PetasosSessionHost) {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermission(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        val missingLocation = LocationAccess.requestPermissions(activity)
        if (missingLocation.isNotEmpty()) {
            requestPermission(missingLocation)
            return
        }
        // No public, verified navigation-surface adapter exists on the current Pleos image; the
        // app-drawn overlay (TYPE_APPLICATION_OVERLAY) is the simultaneous-display surface instead.
        val support = PleosCompanionDisplaySupport.inspect(activity)
        if (!support.navigationSurfaceAvailable) {
            notice = activity.getString(if (!support.overlaySettingsAvailable)
                R.string.companion_display_unavailable else R.string.companion_navigation_surface_unavailable)
            onChanged()
            return
        }
        val epoch = ++generation
        starting = true
        notice = null
        onChanged()
        val request = current.prepareVisibleCompanion()
        request.addListener({
            if (host !== current || generation != epoch) return@addListener
            starting = false
            try {
                check(request.get())
                check(current.consumeVisibleCompanionPreparation(request))
                requestNavigationForeground()
            } catch (_: Exception) {
                notice = activity.getString(R.string.companion_start_unavailable)
            }
            onChanged()
        }, activity.mainExecutor)
    }

    private fun stopCompanion(current: PetasosSessionHost) {
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

    private fun requestPermission(permissions: Array<String>) {
        requestingPermission = true
        notice = null
        onChanged()
        try { activity.requestPermissions(permissions, PERMISSION_REQUEST) }
        catch (_: Exception) { requestingPermission = false; onChanged() }
    }

    /** One-shot: the companion ON gesture already owns this click, no ongoing UI state to publish. */
    private fun requestNavigationForeground() {
        if (activity.checkSelfPermission(PleosNavigationScreens.PERMISSION) != PackageManager.PERMISSION_GRANTED) return
        val gateway = try { PleosNavigationScreens(activity.applicationContext) } catch (_: Exception) { return }
        try {
            gateway.connect(
                onReady = { try { gateway.send(NavigationScreenCommand.Foreground) } finally { gateway.close() } },
                onFailed = { gateway.close() },
            )
        } catch (_: Exception) { gateway.close() }
    }

    companion object { const val PERMISSION_REQUEST = 4803 }
}
