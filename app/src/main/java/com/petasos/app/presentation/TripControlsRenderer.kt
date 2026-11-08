package com.petasos.app.presentation

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import com.petasos.app.R

/** Binds values to the existing layout. No host, storage, permission request or command listener. */
class TripControlsRenderer(root: View) {
    private val location = root.findViewById<Button>(R.id.records_location)
    private val permission = root.findViewById<Button>(R.id.records_permission)
    private val keywords = root.findViewById<EditText>(R.id.records_keywords)
    private val saveKeywords = root.findViewById<Button>(R.id.records_save_keywords)
    private val refresh = root.findViewById<Button>(R.id.records_refresh)
    private val selection = root.findViewById<Spinner>(R.id.records_trip_selection)
    private val end = root.findViewById<Button>(R.id.records_end_trip)

    fun render(state: TripPresentationState) {
        location.isEnabled = state.locationToggleEnabled
        location.setText(if (state.locationRecordingEnabled) R.string.records_location_disable else R.string.records_location_enable)
        permission.visibility = if (state.permissionVisible) View.VISIBLE else View.GONE
        permission.setText(if (state.permissionStage == LocationPermissionStage.ANDROID)
            R.string.records_permission_android else R.string.records_permission)
        permission.isEnabled = state.permissionEnabled
        keywords.isEnabled = state.interestsEnabled
        saveKeywords.isEnabled = state.interestsEnabled
        refresh.isEnabled = state.refreshEnabled
        selection.isEnabled = state.tripSelectionEnabled
        end.isEnabled = state.endTripEnabled
    }
}
