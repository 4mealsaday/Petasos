package com.petasos.app.presentation

import android.app.Activity
import android.content.pm.PackageManager
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.google.common.util.concurrent.ListenableFuture
import com.petasos.app.BuildConfig
import com.petasos.app.R
import com.petasos.app.planning.WindowGuideSetting
import com.petasos.app.platform.android.location.LocationAccess
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import com.petasos.app.presentation.interests.InterestGridView
import com.petasos.app.presentation.interests.InterestSelectionDraft
import com.petasos.app.records.VehiclePreferences
import com.petasos.app.records.VehicleRecordingSettings

/**
 * Settings bindings: the location-recording switch and the interest chips. Queries never create a
 * trip. Owners (the Host) decide consent; this class only reads, shows and sends explicit taps.
 */
class TripControls(private val activity: Activity) {
    /** Called after interests were stored (true) or when storing failed (false). */
    var onInterestsSaved: ((Boolean) -> Unit)? = null
    private val locationSwitch = activity.findViewById<ToggleView>(R.id.settings_location_switch)
    private val windowSwitch = activity.findViewById<ToggleView>(R.id.settings_window_switch)
    private val permissionRow = activity.findViewById<View>(R.id.settings_location_permission_row)
    private val permission = activity.findViewById<Button>(R.id.settings_location_permission)
    private val notice = activity.findViewById<TextView>(R.id.settings_notice)
    private val grids = listOf(R.id.settings_interest_grid, R.id.onboarding_interest_grid)
        .mapNotNull { activity.findViewById<InterestGridView>(it) }
    private val saveSettings = activity.findViewById<Button>(R.id.interests_save)
    private val saveOnboarding = activity.findViewById<Button>(R.id.onboarding_start)
    private var host: PetasosSessionHost? = null
    private var available = false
    private var epoch = 0L
    private var preferences: VehiclePreferences? = null
    private var recording: VehicleRecordingSettings? = null
    private var saving = false
    private var requestingPermission = false
    private var requestedLocationPermissions = emptyArray<String>()
    private var interestDraft = InterestSelectionDraft.empty()

    init {
        grids.forEach { grid ->
            grid.onChoice = { choice ->
                val current = host
                if (available && recording != null && !saving && current?.isEndingTrip() == false) {
                    interestDraft = interestDraft.toggle(choice)
                }
                renderInterests()
            }
        }
        locationSwitch.setOnClickListener { saveLocation() }
        // 창밖 안내 (CONTENT_PIPELINE.md 6장): a device setting, read by the host when a landmark is due.
        windowSwitch.checked = WindowGuideSetting.enabled(activity)
        windowSwitch.setOnClickListener {
            windowSwitch.checked = !windowSwitch.checked
            WindowGuideSetting.set(activity, windowSwitch.checked)
        }
        permission.setOnClickListener { requestPermission() }
        saveSettings.setOnClickListener { saveInterests() }
        saveOnboarding.setOnClickListener { saveInterests() }
        render()
    }

    /** Whether the stored preference (or the DEMO default for a never-set one) allows recording. */
    private fun locationOn() = recording?.let { it.locationEnabled || (BuildConfig.DEMO && it.version == null) } == true

    fun update(value: PetasosSessionHost?, allowed: Boolean) {
        val usable = allowed && value != null
        val changed = host !== value || usable != available
        host = value; available = usable
        if (changed) {
            epoch++
            preferences = null; recording = null; saving = false
            bindInterests(emptyList())
            if (usable) {
                refreshPreferences(value!!)
                value.locationSettingsChanged() // Recheck platform revocation without granting consent.
            }
        }
        render()
    }

    fun permissionResult(requestCode: Int, granted: Boolean): Boolean {
        if (requestCode != LOCATION_REQUEST) return false
        requestingPermission = false
        val stageGranted = requestedLocationPermissions.isNotEmpty() && requestedLocationPermissions.all {
            activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        requestedLocationPermissions = emptyArray()
        if (available) {
            if (!(granted && permissionGranted()) && !stageGranted) showNotice(R.string.settings_permission_denied)
            host?.locationSettingsChanged()
        }
        // Do not chain the Android dialog after the Pleos one: the visible button is the next gesture.
        render()
        return true
    }

    private fun requestPermission() {
        if (!available || !locationOn() || requestingPermission) return
        val requested = LocationAccess.requestPermissions(activity)
        if (requested.isEmpty()) return
        requestingPermission = true
        requestedLocationPermissions = requested
        render()
        try {
            activity.requestPermissions(requested, LOCATION_REQUEST)
        } catch (_: RuntimeException) {
            requestingPermission = false
            requestedLocationPermissions = emptyArray()
            showNotice(R.string.settings_permission_denied)
            render()
        }
    }

    private fun refreshPreferences(current: PetasosSessionHost) {
        observe(current, current.readPreferences(), onFailure = {
            // A malformed interest list must never prevent location consent withdrawal.
            observe(current, current.readRecordingSettings(), onFailure = { render() }) { value -> recording = value; render() }
        }) { value ->
            preferences = value; recording = value.recording
            bindInterests(value.keywords)
            render()
        }
    }

    /**
     * First run: "계속하기" on the screen that explains location use is the explicit consent gesture.
     * Without it, saving interests would write an explicit "off" row and withdraw the DEMO default.
     */
    fun ensureLocationConsent() {
        // The DEMO default shows "on" for a never-set row, but only a stored row survives saving interests.
        if (!available || saving || recording?.locationEnabled != false) return
        setLocation(true)
    }

    private fun saveLocation() = setLocation(!locationOn())

    private fun setLocation(enabled: Boolean) {
        val current = host ?: run { render(); return }
        val settings = recording ?: run { render(); return }
        if (!available || saving || (enabled && current.isEndingTrip())) { render(); return }
        saving = true; render()
        observe(current, current.setLocationEnabled(enabled, settings.version), onFailure = {
            saving = false
            showNotice(R.string.settings_operation_failed)
            refreshPreferences(current)
            render()
        }) { stored ->
            saving = false; recording = stored
            refreshPreferences(current)
            render()
        }
    }

    private fun saveInterests() {
        val current = host ?: return
        val settings = recording ?: run { onInterestsSaved?.invoke(false); return }
        if (!available || saving || current.isEndingTrip()) return
        val values = interestDraft.valuesForSave() ?: run { onInterestsSaved?.invoke(false); return }
        saving = true; render()
        observe(current, current.setInterestKeywords(values, settings.version), onFailure = {
            saving = false
            refreshPreferences(current)
            render()
            onInterestsSaved?.invoke(false)
        }) { stored ->
            saving = false; preferences = stored; recording = stored.recording
            bindInterests(stored.keywords)
            render()
            onInterestsSaved?.invoke(true)
        }
    }

    private fun <T> observe(current: PetasosSessionHost, request: ListenableFuture<T>, onFailure: () -> Unit, onValue: (T) -> Unit) {
        val token = epoch
        request.addListener({
            if (!available || host !== current || epoch != token) return@addListener
            try { onValue(request.get()) } catch (_: Exception) { onFailure() }
        }, activity.mainExecutor)
    }

    private fun permissionGranted() = LocationAccess.hasRequiredPermissions(activity)

    private fun bindInterests(values: List<String>) {
        interestDraft = try { InterestSelectionDraft.fromStored(values) } catch (_: IllegalArgumentException) { InterestSelectionDraft.empty() }
        renderInterests()
    }

    private fun renderInterests() { grids.forEach { it.render(interestDraft.selectedTags) } }

    private fun showNotice(resource: Int) {
        notice.setText(resource)
        notice.setTextColor(activity.getColor(R.color.negative))
        notice.visibility = View.VISIBLE
    }

    private fun render() {
        val current = host
        val state = TripPresentationMapper.map(TripPresentationInput(
            available = available,
            saving = saving,
            locationEnabled = recording?.locationEnabled,
            hasLocationPermission = permissionGranted(),
            hasPleosPermission = LocationAccess.hasPleosPermission(activity),
            endingTrip = current?.isEndingTrip() == true,
            requestingPermission = requestingPermission,
            hasTrips = false,
            tripEndPending = current?.isTripEndPending() == true,
            selectedTripIsEndable = false,
        ))
        locationSwitch.checked = locationOn()
        locationSwitch.isEnabled = state.locationToggleEnabled
        val needsPermission = available && locationOn() && !permissionGranted()
        permissionRow.visibility = if (needsPermission) View.VISIBLE else View.GONE
        permission.setText(if (state.permissionStage == LocationPermissionStage.ANDROID)
            R.string.settings_location_permission_android else R.string.settings_location_permission)
        permission.isEnabled = state.permissionEnabled
        grids.forEach { it.isEnabled = state.interestsEnabled }
        saveSettings.isEnabled = state.interestsEnabled
        saveOnboarding.isEnabled = state.interestsEnabled
        if (notice.visibility == View.VISIBLE && notice.currentTextColor == activity.getColor(R.color.negative) && !needsPermission && available) {
            // A stale permission error disappears once the platform grants it.
            if (permissionGranted()) notice.visibility = View.GONE
        }
    }

    companion object { const val LOCATION_REQUEST = 4802 }
}
