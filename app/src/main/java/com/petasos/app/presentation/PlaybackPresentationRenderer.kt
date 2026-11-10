package com.petasos.app.presentation

import android.view.View
import android.widget.TextView
import com.petasos.app.R

/** Writes only the vehicle-gate visibility on the supplied view tree. No listeners, Host, or commands. */
class PlaybackPresentationRenderer(root: View) {
    private val content = root.findViewById<View>(R.id.preview_content)
    private val vehicleNotice = root.findViewById<TextView>(R.id.vehicle_policy_notice)

    /** Revoke visibility before adapter work that could fail or synchronously change state. */
    fun renderGate(gate: ParkedPresentationGate) { renderGate(content, vehicleNotice, gate) }

    companion object {
        /** Shared with the existing direct vehicle-gate diagnostic; no platform state is queried. */
        internal fun renderGate(content: View, message: TextView, gate: ParkedPresentationGate) {
            content.visibility = if (gate == ParkedPresentationGate.PARKED) View.VISIBLE else View.GONE
            message.visibility = if (gate == ParkedPresentationGate.PARKED) View.GONE else View.VISIBLE
            message.setText(if (gate == ParkedPresentationGate.UNKNOWN) R.string.vehicle_policy_waiting
                else R.string.vehicle_policy_restricted)
        }
    }
}
