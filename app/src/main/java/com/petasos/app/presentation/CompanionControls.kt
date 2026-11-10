package com.petasos.app.presentation

import android.Manifest
import com.petasos.app.BuildConfig
import android.app.Activity
import android.content.pm.PackageManager
import com.petasos.app.R
import com.petasos.app.capability.navigation.NavigationScreenCommand
import com.petasos.app.platform.android.location.LocationAccess
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import com.petasos.app.platform.pleos.navigation.PleosCompanionDisplaySupport
import com.petasos.app.platform.pleos.navigation.PleosNavigationScreens
import com.petasos.app.platform.pleos.navigation.PleosRouteSearch

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
    private var continueAfterPermission = false
    private var notice: String? = null
    private var readinessItem: JourneyShell.ReadyItem? = null

    fun update(value: PetasosSessionHost?, allowed: Boolean, shell: JourneyShell) {
        if (host !== value) {
            generation++
            host = value
            stopping = false
            starting = false
        }
        available = allowed
        val resumeStart = continueAfterPermission && allowed && value != null && !requestingPermission
        if (resumeStart) {
            continueAfterPermission = false
            if (!value!!.isCompanionActive() && !starting && !stopping) { startCompanion(value); return }
        }
        shell.updateCompanionState(value?.isCompanionActive() == true, stopping || starting, notice)
    }

    /** Main-thread Activity permission callback passthrough; see [com.petasos.app.MainActivity]. */
    fun permissionResult(requestCode: Int, granted: Boolean): Boolean {
        if (requestCode == READINESS_REQUEST) {
            requestingPermission = false
            onChanged()
            // Location is two prompts (Pleos, then Android). A new request made inside this callback is dropped,
            // so the second one follows on the next main-loop turn.
            if (granted && readinessItem == JourneyShell.ReadyItem.LOCATION && LocationAccess.requestPermissions(activity).isNotEmpty())
                activity.window.decorView.post { fixReadiness(JourneyShell.ReadyItem.LOCATION) }
            return true
        }
        if (requestCode != PERMISSION_REQUEST) return false
        requestingPermission = false
        if (granted) {
            // The ON gesture already asked for this. The callback lands before onResume, so the
            // same start continues on the next update that sees the screen interactive again.
            notice = null
            continueAfterPermission = true
            onChanged()
            return true
        }
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
                requestDemoDestinationRoute()
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
        // DEMO: OFF means "arrived" -- end the trip so 나의 기록 shows this run as its own finished
        // trip (and its conversation summary) and the next ON starts fresh. endTrip already revokes
        // the companion and stops every source; it must run while the parked UI still counts as
        // visible, i.e. before a plain disable drops that visibility.
        val tripId = if (BuildConfig.DEMO) current.endableTripId() else null
        if (tripId != null) {
            val ending = current.endTrip(tripId)
            onChanged()
            ending.addListener({
                if (host !== current || generation != epoch) return@addListener
                try {
                    ending.get()
                    if (BuildConfig.DEBUG) android.util.Log.i("PetasosTalk", "trip ended on OFF")
                    stopping = false
                    onChanged()
                } catch (error: Exception) {
                    if (BuildConfig.DEBUG) android.util.Log.w("PetasosTalk", "trip end on OFF failed", error)
                    disable(current, epoch)
                }
            }, activity.mainExecutor)
            return
        }
        disable(current, epoch)
    }

    private fun disable(current: PetasosSessionHost, epoch: Long) {
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

    private fun requestPermission(permissions: Array<String>, code: Int = PERMISSION_REQUEST) {
        requestingPermission = true
        notice = null
        onChanged()
        try { activity.requestPermissions(permissions, code) }
        catch (_: Exception) { requestingPermission = false; onChanged() }
    }

    /** Home readiness "설정 필요": one permission prompt, or the overlay settings screen / a guide line. */
    fun fixReadiness(item: JourneyShell.ReadyItem) {
        if (starting || stopping || requestingPermission) return
        readinessItem = item
        when (item) {
            JourneyShell.ReadyItem.LOCATION -> LocationAccess.requestPermissions(activity).takeIf { it.isNotEmpty() }
                ?.let { requestPermission(it, READINESS_REQUEST) }
            JourneyShell.ReadyItem.MIC -> requestPermission(arrayOf(Manifest.permission.RECORD_AUDIO), READINESS_REQUEST)
            JourneyShell.ReadyItem.OVERLAY -> {
                val opened = PleosCompanionDisplaySupport.inspect(activity).overlaySettingsAvailable && try {
                    activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${activity.packageName}"))); true
                } catch (_: Exception) { false }
                notice = if (opened) null else activity.getString(R.string.home_ready_overlay_guide)
                onChanged()
            }
        }
    }

    /** Home's "내비로 돌아가기": the same navigation-foreground request the ON gesture makes. */
    fun returnToNavigation() { if (!starting && !stopping) requestNavigationForeground() }

    /** One-shot: the companion ON gesture already owns this click, no ongoing UI state to publish. */
    private fun requestNavigationForeground() {
        if (BuildConfig.DEMO) {
            // The SDK Foreground request is dropped ("navi not ready") when Navigation's UI is not
            // up yet; for the demo also bring Navigation's own activity to the front directly.
            try {
                activity.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .setClassName(DEMO_NAVIGATION_PACKAGE, "$DEMO_NAVIGATION_PACKAGE.MainActivity")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { /* Fall back to the SDK request below. */ }
        }
        if (activity.checkSelfPermission(PleosNavigationScreens.PERMISSION) != PackageManager.PERMISSION_GRANTED) return
        val gateway = try { PleosNavigationScreens(activity.applicationContext) } catch (_: Exception) { return }
        try {
            gateway.connect(
                onReady = { try { gateway.send(NavigationScreenCommand.Foreground) } finally { gateway.close() } },
                onFailed = { gateway.close() },
            )
        } catch (_: Exception) { gateway.close() }
    }

    /** Fire-and-forget: a routing failure must never block or delay companion start. */
    private fun requestDemoDestinationRoute() {
        try {
            PleosRouteSearch.request(
                activity.applicationContext,
                poiName = "대전엑스포과학공원",
                latitude = 36.3765,
                longitude = 127.3866,
            )
        } catch (_: Exception) { /* Logged inside PleosRouteSearch; never surfaced here. */ }
    }

    companion object {
        const val PERMISSION_REQUEST = 4803
        const val READINESS_REQUEST = 4804
        private const val DEMO_NAVIGATION_PACKAGE = "ai.umos.maps.android.navigation.app"
    }
}
