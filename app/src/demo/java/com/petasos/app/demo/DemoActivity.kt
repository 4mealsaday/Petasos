package com.petasos.app.demo

import android.app.Activity
import android.os.Bundle
import android.view.View
import com.petasos.app.R
import com.petasos.app.platform.automotive.CarPolicySource
import com.petasos.app.platform.automotive.VehicleUxPolicy
import com.petasos.app.presentation.ParkedPresentationGate

/** Isolated, parked-only review screen. Intent extras never provide a route, gate or command. */
class DemoActivity : Activity() {
    private lateinit var controller: DemoSessionController
    private lateinit var presentation: DemoPresentation
    private var car: CarPolicySource? = null
    private var subscription = 0L
    private var resumed = false
    private var destroyed = false
    private var ux = VehicleUxPolicy.unavailable()
    internal var latestState: DemoControllerState? = null
        private set
    internal var currentGate = ParkedPresentationGate.UNKNOWN
        private set
    @Volatile internal var closeAcknowledged: Boolean? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_demo)
        presentation = DemoPresentation(findViewById(android.R.id.content))
        presentation.renderGate(ParkedPresentationGate.UNKNOWN)
        controller = DemoSessionController(applicationContext) { state ->
            if (!destroyed) {
                latestState = state
                presentation.render(state, currentGate, resumed)
            }
        }
        listOf(
            R.id.playback_play to DemoControllerCommand.PLAY,
            R.id.playback_stop to DemoControllerCommand.STOP,
            R.id.playback_next to DemoControllerCommand.NEXT,
            R.id.playback_previous to DemoControllerCommand.PREVIOUS,
            R.id.demo_advance_1 to DemoControllerCommand.ADVANCE_1,
            R.id.demo_advance_10 to DemoControllerCommand.ADVANCE_10,
            R.id.demo_advance_30 to DemoControllerCommand.ADVANCE_30,
            R.id.demo_route_anseong to DemoControllerCommand.ROUTE_ANSEONG,
            R.id.demo_route_yuseong to DemoControllerCommand.ROUTE_YUSEONG,
            R.id.demo_route_gap to DemoControllerCommand.OBSERVATION_GAP,
            R.id.demo_reset to DemoControllerCommand.RESET,
        ).forEach { (id, command) ->
            findViewById<View>(id).setOnClickListener {
                // Display state never grants command authority; the worker rechecks this gate.
                if (command == DemoControllerCommand.STOP ||
                    (!destroyed && resumed && ux.permitsParkedPreview)) controller.submit(command)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val epoch = ++subscription
        ux = VehicleUxPolicy.unavailable()
        updateGate()
        car = CarPolicySource(this, { observed ->
            if (!destroyed && epoch == subscription) { ux = observed; updateGate() }
        }, { /* Silent demo has no audio/microphone and never requests power-policy permission. */ })
            .also { it.start() }
    }

    override fun onResume() { super.onResume(); resumed = true; updateGate() }
    override fun onPause() { resumed = false; updateGate(); super.onPause() }
    override fun onStop() {
        ++subscription
        ux = VehicleUxPolicy.unavailable()
        updateGate()
        car?.close()
        car = null
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        resumed = false
        ++subscription
        controller.setAllowed(false)
        car?.close()
        car = null
        controller.close { closeAcknowledged = it }
        super.onDestroy()
    }

    private fun updateGate() {
        currentGate = when {
            !ux.available -> ParkedPresentationGate.UNKNOWN
            !ux.permitsParkedPreview -> ParkedPresentationGate.RESTRICTED
            else -> ParkedPresentationGate.PARKED
        }
        // Hide first; neither a delayed worker result nor failed subscription can reveal content.
        presentation.renderGate(if (resumed) currentGate else ParkedPresentationGate.UNKNOWN)
        controller.setAllowed(!destroyed && resumed && ux.permitsParkedPreview)
        latestState?.let { presentation.render(it, currentGate, resumed) }
    }
}
