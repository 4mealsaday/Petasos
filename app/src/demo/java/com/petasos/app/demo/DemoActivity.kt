package com.petasos.app.demo

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.SeekBar
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
    private val main = Handler(Looper.getMainLooper())
    private val realtime = DemoRealtimeClock()
    private var pendingSeekMs: Long? = null
    private var seekInFlight = false
    private val tick = object : Runnable {
        override fun run() {
            if (!realtime.running || destroyed || !resumed || !ux.permitsParkedPreview) return
            val delta = realtime.pending(SystemClock.elapsedRealtime())
            if (delta >= 1_000 && controller.advanceBy(delta)) realtime.accepted(delta)
            if (realtime.running) main.postDelayed(this, 100)
        }
    }
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
                if (state.failure != null || state.snapshot?.failure != null ||
                    (state.snapshot?.elapsedMs ?: 0) >= DemoFixtureStore.START_MS + DemoReplayTimeline.DURATION_MS) {
                    stopClock()
                }
                if (!state.busy && seekInFlight) {
                    seekInFlight = false
                    presentation.seeking = false
                }
                presentation.render(state, currentGate, resumed)
                if (!state.busy) dispatchPendingSeek()
            }
        }
        listOf(
            R.id.playback_play to DemoControllerCommand.PLAY,
            R.id.playback_stop to DemoControllerCommand.STOP,
            R.id.playback_next to DemoControllerCommand.NEXT,
            R.id.playback_previous to DemoControllerCommand.PREVIOUS,
            R.id.demo_route_anseong to DemoControllerCommand.ROUTE_ANSEONG,
            R.id.demo_route_yuseong to DemoControllerCommand.ROUTE_YUSEONG,
            R.id.demo_route_gap to DemoControllerCommand.OBSERVATION_GAP,
            R.id.demo_reset to DemoControllerCommand.RESET,
        ).forEach { (id, command) ->
            findViewById<View>(id).setOnClickListener {
                // Display state never grants command authority; the worker rechecks this gate.
                if (command == DemoControllerCommand.STOP ||
                    (!destroyed && resumed && ux.permitsParkedPreview)) {
                    presentation.dismissTalkNotice()
                    if (command == DemoControllerCommand.STOP || command == DemoControllerCommand.RESET) stopClock()
                    if (controller.submit(command) && command == DemoControllerCommand.PLAY) {
                        realtime.start(SystemClock.elapsedRealtime())
                        main.removeCallbacks(tick)
                        main.postDelayed(tick, 100)
                    }
                }
            }
        }
        findViewById<View>(R.id.playback_talk).setOnClickListener {
            if (!destroyed && resumed && ux.permitsParkedPreview) presentation.showTalkUnavailable()
        }
        findViewById<SeekBar>(R.id.demo_timeline).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                stopClock()
                presentation.seeking = true
                presentation.dismissTalkNotice()
            }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) presentation.previewSeek(progress)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) { seekTo(seekBar.progress * 1_000L) }
        })
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
        stopClock()
        presentation.close()
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
        if (destroyed || !resumed || !ux.permitsParkedPreview) {
            stopClock()
            pendingSeekMs = null
            presentation.seeking = false
        }
        controller.setAllowed(!destroyed && resumed && ux.permitsParkedPreview)
        latestState?.let { presentation.render(it, currentGate, resumed) }
    }

    /** Package-local instrumentation uses the same seek path as the public SeekBar listener. */
    internal fun seekTo(positionMs: Long) {
        stopClock()
        if (destroyed || !resumed || !ux.permitsParkedPreview || positionMs !in 0..DemoReplayTimeline.DURATION_MS) {
            presentation.seeking = false
            return
        }
        presentation.seeking = true
        pendingSeekMs = positionMs
        dispatchPendingSeek()
    }

    private fun dispatchPendingSeek() {
        val target = pendingSeekMs ?: return
        if (latestState?.busy != false || seekInFlight) return
        pendingSeekMs = null
        seekInFlight = true
        if (!controller.seekTo(target)) {
            seekInFlight = false
            presentation.seeking = false
            latestState?.let { presentation.render(it, currentGate, resumed) }
        }
    }

    private fun stopClock() { realtime.stop(); main.removeCallbacks(tick) }
}
