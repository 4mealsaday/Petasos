package com.petasos.app.presentation

import android.app.Activity
import android.content.pm.PackageManager
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import com.google.common.util.concurrent.ListenableFuture
import com.petasos.app.R
import com.petasos.app.platform.android.location.LocationAccess
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import com.petasos.app.records.TripResultHeader
import com.petasos.app.records.VehiclePreferences
import com.petasos.app.records.VehicleRecordingSettings

/** Parked presentation only. Queries never create a trip and saved summaries never enter logs. */
class TripControls(private val activity: Activity) {
    private val notice = activity.findViewById<TextView>(R.id.records_notice)
    private val location = activity.findViewById<Button>(R.id.records_location)
    private val permission = activity.findViewById<Button>(R.id.records_permission)
    private val keywords = activity.findViewById<EditText>(R.id.records_keywords)
    private val saveKeywords = activity.findViewById<Button>(R.id.records_save_keywords)
    private val refresh = activity.findViewById<Button>(R.id.records_refresh)
    private val selection = activity.findViewById<Spinner>(R.id.records_trip_selection)
    private val end = activity.findViewById<Button>(R.id.records_end_trip)
    private val result = activity.findViewById<TextView>(R.id.records_result)
    private val formatter = TripResultsFormatter(activity)
    private val renderer = TripControlsRenderer(activity.window.decorView)
    private var host: PetasosSessionHost? = null
    private var available = false
    private var epoch = 0L
    private var tripRead = 0L
    private var listRead = 0L
    private var preferences: VehiclePreferences? = null
    private var recording: VehicleRecordingSettings? = null
    private var trips = emptyList<TripResultHeader>()
    private var updatingSelection = false
    private var saving = false
    private var requestingPermission = false
    private var requestedLocationPermissions = emptyArray<String>()
    private var observedTripId: String? = null
    private var observedEnding = false

    init {
        location.setOnClickListener { saveLocation() }
        permission.setOnClickListener {
            if (available && recording?.locationEnabled == true && !requestingPermission) {
                val requested = LocationAccess.requestPermissions(activity)
                if (requested.isEmpty()) return@setOnClickListener
                requestingPermission = true
                requestedLocationPermissions = requested
                render()
                try {
                    activity.requestPermissions(requested, LOCATION_REQUEST)
                } catch (_: RuntimeException) {
                    requestingPermission = false
                    requestedLocationPermissions = emptyArray()
                    notice.setText(R.string.records_permission_denied)
                    render()
                }
            }
        }
        saveKeywords.setOnClickListener { saveInterests() }
        refresh.setOnClickListener { host?.takeIf { available }?.let(::refreshTrips) }
        end.setOnClickListener { endTrip() }
        selection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (available && !updatingSelection) host?.let { readSelected(it) }
                render()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        render()
    }

    fun update(value: PetasosSessionHost?, allowed: Boolean) {
        val usable = allowed && value != null
        val changed = host !== value || usable != available
        host = value; available = usable
        if (changed) {
            epoch++; tripRead++; listRead++
            preferences = null; recording = null; trips = emptyList(); saving = false
            keywords.setText(""); result.setText(R.string.records_empty)
            updatingSelection = true
            selection.adapter = null
            updatingSelection = false
            observedTripId = value?.snapshot()?.tripId
            observedEnding = value?.isEndingTrip() == true
            if (usable) {
                refreshPreferences(value!!, replaceKeywords = true)
                refreshTrips(value)
                value.locationSettingsChanged() // Recheck platform revocation without granting consent.
            }
        } else if (usable) {
            val trip = value!!.snapshot()?.tripId
            val ending = value.isEndingTrip()
            if (trip != observedTripId || (observedEnding && !ending)) {
                observedTripId = trip; observedEnding = ending
                refreshTrips(value)
            } else observedEnding = ending
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
            notice.setText(when {
                recording?.locationEnabled == false -> R.string.records_location_saved_off
                granted && permissionGranted() -> R.string.records_location_saved_on
                stageGranted && LocationAccess.hasPleosPermission(activity) -> R.string.records_permission_android_needed
                stageGranted -> R.string.records_permission_needed
                else -> R.string.records_permission_denied
            })
            host?.locationSettingsChanged()
        }
        // Do not chain the Android dialog after Pleos permission: the visible button is the next gesture.
        render()
        return true
    }

    private fun refreshPreferences(current: PetasosSessionHost, replaceKeywords: Boolean) {
        observe(current, current.readPreferences(), onFailure = {
            notice.setText(R.string.records_operation_failed)
            // A malformed interest list must never prevent location consent withdrawal.
            observe(current, current.readRecordingSettings(), onFailure = { render() }) { value -> recording = value; render() }
        }) { value ->
            preferences = value; recording = value.recording
            if (replaceKeywords) keywords.setText(value.keywords.joinToString(", "))
            render()
        }
    }

    private fun saveLocation() {
        val current = host ?: return
        val settings = recording ?: return
        val enabled = !settings.locationEnabled
        if (!available || saving || (enabled && current.isEndingTrip())) return
        saving = true; render()
        observe(current, current.setLocationEnabled(enabled, settings.version), onFailure = {
            saving = false
            notice.setText(R.string.records_operation_failed)
            refreshPreferences(current, replaceKeywords = false)
            render()
        }) { stored ->
            saving = false; recording = stored
            notice.setText(when {
                !stored.locationEnabled -> R.string.records_location_saved_off
                !permissionGranted() -> R.string.records_permission_needed
                else -> R.string.records_location_saved_on
            })
            refreshPreferences(current, replaceKeywords = false)
            render()
        }
    }

    private fun saveInterests() {
        val current = host ?: return
        val settings = recording ?: return
        if (!available || saving || current.isEndingTrip()) return
        val text = keywords.text.toString()
        val values = if (text.isBlank()) emptyList() else text.split(',').map(String::trim)
        if (values.size > 20 || values.any { it.isBlank() || it.length > 256 } || values.distinct().size != values.size) {
            notice.setText(R.string.records_keywords_invalid); return
        }
        saving = true; render()
        observe(current, current.setInterestKeywords(values, settings.version), onFailure = {
            saving = false
            notice.setText(R.string.records_operation_failed)
            refreshPreferences(current, replaceKeywords = false)
            render()
        }) { stored ->
            saving = false; preferences = stored; recording = stored.recording
            keywords.setText(stored.keywords.joinToString(", "))
            notice.setText(R.string.records_keywords_saved); render()
        }
    }

    private fun refreshTrips(current: PetasosSessionHost) {
        val serial = ++listRead
        val prior = trips.getOrNull(selection.selectedItemPosition)?.tripId
        observe(current, current.listTrips(), onFailure = { result.setText(R.string.records_operation_failed) }) { values ->
            if (serial != listRead) return@observe
            trips = values
            updatingSelection = true
            selection.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, values.map(formatter::label)).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val preferred = current.snapshot()?.tripId ?: prior
            selection.setSelection(values.indexOfFirst { it.tripId == preferred }.coerceAtLeast(0))
            updatingSelection = false
            readSelected(current)
            render()
        }
    }

    private fun readSelected(current: PetasosSessionHost) {
        val serial = ++tripRead
        val selected = trips.getOrNull(selection.selectedItemPosition)
        if (selected == null) { result.setText(R.string.records_empty); return }
        result.setText(R.string.records_loading)
        observe(current, current.readTrip(selected.tripId), onFailure = {
            if (serial == tripRead) result.setText(R.string.records_operation_failed)
        }) { value ->
            if (serial == tripRead) result.text = value?.let(formatter::format) ?: activity.getString(R.string.records_empty)
        }
    }

    private fun endTrip() {
        val current = host ?: return
        val selected = trips.getOrNull(selection.selectedItemPosition) ?: return
        if (!available || saving || selected.tripId != current.endableTripId() || current.isTripEndPending()) return
        notice.setText(R.string.records_ending_trip)
        observe(current, current.endTrip(selected.tripId), onFailure = {
            notice.setText(R.string.records_end_unconfirmed); render()
        }) {
            notice.setText(R.string.records_ended_trip)
            refreshTrips(current); render()
        }
        render()
    }

    private fun <T> observe(current: PetasosSessionHost, request: ListenableFuture<T>, onFailure: () -> Unit, onValue: (T) -> Unit) {
        val token = epoch
        request.addListener({
            if (!available || host !== current || epoch != token) return@addListener
            try { onValue(request.get()) } catch (_: Exception) { onFailure() }
        }, activity.mainExecutor)
    }

    private fun permissionGranted() = LocationAccess.hasRequiredPermissions(activity)
    private fun render() {
        val current = host
        renderer.render(TripPresentationMapper.map(TripPresentationInput(
            available = available,
            saving = saving,
            locationEnabled = recording?.locationEnabled,
            hasLocationPermission = permissionGranted(),
            hasPleosPermission = LocationAccess.hasPleosPermission(activity),
            endingTrip = current?.isEndingTrip() == true,
            requestingPermission = requestingPermission,
            hasTrips = trips.isNotEmpty(),
            tripEndPending = current?.isTripEndPending() == true,
            selectedTripIsEndable = trips.getOrNull(selection.selectedItemPosition)?.tripId?.let {
                it == current?.endableTripId()
            } == true,
        )))
    }

    companion object { const val LOCATION_REQUEST = 4802 }
}
