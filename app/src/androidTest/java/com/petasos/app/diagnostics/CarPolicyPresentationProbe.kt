package com.petasos.app.diagnostics

import android.app.Instrumentation
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.petasos.app.R
import com.petasos.app.platform.automotive.CarPolicySource
import com.petasos.app.platform.automotive.VehicleUxPolicy
import com.petasos.app.presentation.ParkedPreviewVisibility
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual Car UX read/close plus deterministic view gating. No car-state changes, playback, or microphone. */
object CarPolicyPresentationProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        instrumentation.runOnMainSync {
            val themed = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Petasos)
            val root = LayoutInflater.from(themed).inflate(R.layout.activity_main, android.widget.FrameLayout(themed), true)
            val content = root.findViewById<View>(R.id.preview_content)
            val message = root.findViewById<TextView>(R.id.vehicle_policy_notice)
            check(content.visibility == View.GONE && message.visibility == View.VISIBLE)
            ParkedPreviewVisibility.apply(content, message, VehicleUxPolicy.unavailable())
            check(content.visibility == View.GONE && message.visibility == View.VISIBLE)
            val parked = VehicleUxPolicy(true, false, 0, 120, 10, 3)
            ParkedPreviewVisibility.apply(content, message, parked)
            check(content.visibility == View.VISIBLE && message.visibility == View.GONE)
            ParkedPreviewVisibility.apply(content, message, parked.copy(activeRestrictions = 1))
            check(content.visibility == View.GONE && message.visibility == View.VISIBLE)
            ParkedPreviewVisibility.apply(content, message, parked.copy(requiresDistractionOptimization = true))
            check(content.visibility == View.GONE)
            ParkedPreviewVisibility.apply(content, message, VehicleUxPolicy.unavailable())
            check(content.visibility == View.GONE)
        }
        mark("presentation_initial_unknown_restrictions_and_disconnect_hide_entire_preview")
        mark("presentation_only_current_unrestricted_parked_policy_exposes_controls")

        val known = CountDownLatch(1)
        val latest = AtomicReference(VehicleUxPolicy.unavailable())
        lateinit var source: CarPolicySource
        try {
            instrumentation.runOnMainSync {
                source = CarPolicySource(instrumentation.targetContext, onUx = { value ->
                    latest.set(value)
                    if (value.available) known.countDown()
                }, onPower = {})
                source.start()
            }
            if (known.await(10, TimeUnit.SECONDS)) {
                mark(if (latest.get().permitsParkedPreview) "car_ux_actual_current_policy_allows_parked_preview"
                    else "car_ux_actual_current_policy_requires_hidden_preview")
            } else mark("car_ux_unavailable_keeps_preview_hidden")
        } finally { instrumentation.runOnMainSync { source.close() } }
        check(!latest.get().available && !latest.get().permitsParkedPreview)
        mark("car_ux_close_revokes_preview_permission")
    }
}
