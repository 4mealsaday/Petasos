package com.petasos.app.diagnostics

import android.app.Instrumentation
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import com.petasos.app.R
import com.petasos.app.presentation.TripControlsRenderer
import com.petasos.app.presentation.TripPresentationInput
import com.petasos.app.presentation.TripPresentationMapper

/** This detached probe body does not create an Activity/Host or read storage/settings or request permissions. */
internal object TripPresentationRendererProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        instrumentation.runOnMainSync {
            val themed = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Petasos)
            val root = LayoutInflater.from(themed).inflate(R.layout.activity_main, FrameLayout(themed), true)
            check(!root.isAttachedToWindow)
            val renderer = TripControlsRenderer(root)
            val location = root.findViewById<Button>(R.id.records_location)
            val permission = root.findViewById<Button>(R.id.records_permission)
            val keywords = root.findViewById<EditText>(R.id.records_keywords)
            val save = root.findViewById<Button>(R.id.records_save_keywords)
            val refresh = root.findViewById<Button>(R.id.records_refresh)
            val selection = root.findViewById<Spinner>(R.id.records_trip_selection)
            val end = root.findViewById<Button>(R.id.records_end_trip)
            val notice = root.findViewById<TextView>(R.id.records_notice)
            val result = root.findViewById<TextView>(R.id.records_result)
            val adapter = ArrayAdapter(themed, android.R.layout.simple_spinner_item, listOf("synthetic-first", "synthetic-second"))
            selection.adapter = adapter
            selection.setSelection(1)
            keywords.setText("synthetic unsaved input")
            notice.text = "synthetic asynchronous notice"
            result.text = "synthetic existing result"
            var locationClicks = 0
            var permissionClicks = 0
            // Test-owned sentinels only. These never invoke the real controls or an Android dialog.
            location.setOnClickListener { locationClicks++ }
            permission.setOnClickListener { permissionClicks++ }

            val ready = TripPresentationInput(available = true, saving = false, locationEnabled = true,
                hasLocationPermission = false, hasPleosPermission = false, endingTrip = false,
                requestingPermission = false, hasTrips = true, tripEndPending = false, selectedTripIsEndable = true)
            fun render(input: TripPresentationInput) {
                renderer.render(TripPresentationMapper.map(input))
                check(!root.isAttachedToWindow)
                check(selection.adapter === adapter && selection.selectedItemPosition == 1)
                check(keywords.text.toString() == "synthetic unsaved input")
                check(notice.text.toString() == "synthetic asynchronous notice" && result.text.toString() == "synthetic existing result")
                check(locationClicks == 0 && permissionClicks == 0)
            }
            fun text(button: Button, resource: Int) { check(button.text.toString() == themed.getString(resource)) }
            fun allDisabled() {
                check(listOf<View>(location, permission, keywords, save, refresh, selection, end).none { it.isEnabled })
            }

            render(ready.copy(available = false))
            allDisabled()
            check(permission.visibility == View.GONE)

            render(ready.copy(locationEnabled = false))
            check(location.isEnabled && keywords.isEnabled && save.isEnabled && refresh.isEnabled && selection.isEnabled && end.isEnabled)
            text(location, R.string.records_location_enable)
            check(permission.visibility == View.GONE)
            // A hidden permission button need not be disabled; preserve the existing display contract.

            render(ready)
            text(location, R.string.records_location_disable)
            check(permission.visibility == View.VISIBLE && permission.isEnabled)
            text(permission, R.string.records_permission)
            render(ready.copy(hasPleosPermission = true))
            check(permission.visibility == View.VISIBLE && permission.isEnabled)
            text(permission, R.string.records_permission_android)
            render(ready.copy(hasPleosPermission = true, hasLocationPermission = true))
            check(permission.visibility == View.GONE)
            render(ready.copy(hasPleosPermission = true, requestingPermission = true))
            check(permission.visibility == View.VISIBLE && !permission.isEnabled)

            render(ready.copy(endingTrip = true))
            check(location.isEnabled && !keywords.isEnabled && !save.isEnabled && !permission.isEnabled)
            text(location, R.string.records_location_disable)
            render(ready.copy(endingTrip = true, locationEnabled = false))
            check(!location.isEnabled)
            text(location, R.string.records_location_enable)
            render(ready.copy(saving = true))
            check(!location.isEnabled && !keywords.isEnabled && !save.isEnabled && !permission.isEnabled && !end.isEnabled)
            check(refresh.isEnabled && selection.isEnabled)
            render(ready.copy(locationEnabled = null))
            check(!location.isEnabled && !keywords.isEnabled && !save.isEnabled && !permission.isEnabled)
            check(refresh.isEnabled && permission.visibility == View.GONE)
            render(ready.copy(tripEndPending = true))
            check(!end.isEnabled)
            render(ready.copy(hasTrips = false, selectedTripIsEndable = false))
            check(!selection.isEnabled && !end.isEnabled)

            render(ready)
            render(ready.copy(available = false))
            allDisabled()
            check(permission.visibility == View.GONE)
            render(ready)
            // Rendering has not replaced adapter-owned listeners. Only local counters run here.
            check(location.performClick() && permission.performClick())
            check(locationClicks == 1 && permissionClicks == 1)
            check(!root.isAttachedToWindow)
        }
        mark("trip_renderer_detached_synthetic_visibility_and_permission_stage_buttons_preserved")
        mark("trip_renderer_ending_trip_allows_consent_withdrawal_and_preserves_unsaved_input_and_listeners")
    }
}
