package com.petasos.app.presentation.map

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.petasos.app.R
import com.petasos.app.presentation.records.DisplayRegionShape
import com.petasos.app.presentation.records.MapCoordinate
import com.petasos.app.presentation.records.RegionalMapView

/**
 * Native map surface, with offline public boundaries when a product-only SDK cannot load.
 * The owner forwards visible-screen lifecycle calls and only already-authorized fresh positions.
 * No backend is created by inflation: a detached renderer/probe cannot initiate SDK networking.
 */
class ProductJourneyMap @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val fallback = RegionalMapView(context)
    private var backend: JourneyMapBackend? = null
    private var started = false
    private var resumed = false
    private var destroyed = false
    private var generation = 0L
    private var pendingPosition: MapCoordinate? = null
    private var timeout: Runnable? = null
    var status: JourneyMapStatus = JourneyMapStatus.OFFLINE
        private set
    var onStatusChanged: ((JourneyMapStatus) -> Unit)? = null
        set(value) { field = value; value?.invoke(status) }
    val statusTextRes: Int get() = when (status) {
        JourneyMapStatus.OFFLINE -> R.string.journey_map_offline
        JourneyMapStatus.LOADING -> R.string.journey_map_loading
        JourneyMapStatus.READY -> if (pendingPosition == null) R.string.journey_map_position_pending else R.string.journey_map_current_position
        JourneyMapStatus.UNAVAILABLE -> R.string.journey_map_unavailable
    }

    init {
        isSaveEnabled = false
        setSaveFromParentEnabled(false)
        addView(fallback, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun submitGeometry(geometry: List<DisplayRegionShape>) {
        fallback.submit(geometry, focusOnPaths = true)
    }

    /** Null and invalid positions clear the old marker; display state is never saved in a Bundle. */
    fun setPosition(point: MapCoordinate?) {
        if (destroyed) return
        pendingPosition = point?.takeIf { started && resumed && it.latitude.isFinite() && it.longitude.isFinite() &&
            it.latitude in -80.0..80.0 && it.longitude in -180.0..180.0 }
        fallback.setCurrentPosition(pendingPosition)
        safely { backend?.setPosition(pendingPosition) }
        onStatusChanged?.invoke(status)
    }

    fun onStart() {
        if (destroyed || started) return
        started = true
        val token = ++generation
        safely {
            val layer = ProductMapBackendFactory.create(context,
                onReady = { post { if (started && generation == token) mapReady() } },
                onFailure = { post { if (started && generation == token) fail() } },
            )
            if (layer == null) { setStatus(JourneyMapStatus.OFFLINE); return@safely }
            backend = layer
            // Keep the live view drawing beneath fallback until the SDK confirms its first load.
            addView(layer.view, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            setStatus(JourneyMapStatus.LOADING)
            layer.onCreate()
            layer.onStart()
            layer.setPosition(pendingPosition)
            timeout = Runnable { if (started && generation == token && status == JourneyMapStatus.LOADING) fail() }
                .also { postDelayed(it, MAP_LOAD_TIMEOUT_MS) }
        }
    }

    fun onResume() {
        if (destroyed || resumed) return
        onStart()
        resumed = true
        safely { backend?.onResume() }
    }

    fun onPause() {
        if (destroyed) return
        val wasResumed = resumed
        resumed = false
        setPosition(null)
        if (!wasResumed) return
        safely { backend?.onPause() }
    }

    fun onStop() {
        if (destroyed || !started) return
        onPause()
        started = false
        generation++
        releaseBackend()
        setStatus(JourneyMapStatus.OFFLINE)
    }

    fun onDestroy() {
        if (destroyed) return
        onStop()
        destroyed = true
        onStatusChanged = null
        pendingPosition = null
        fallback.setCurrentPosition(null)
    }

    fun onLowMemory() = safely { backend?.onLowMemory() }

    /** The backend observes the callback but intentionally discards its private camera Bundle. */
    fun saveTransientState() = safely { backend?.onSaveInstanceState() }

    /** An explicit UI retry can reopen a failed network map; no timed/background retries. */
    fun retry() {
        if (destroyed || !started) return
        val wasResumed = resumed
        onStop(); onStart()
        if (wasResumed) onResume()
    }

    private fun mapReady() {
        timeout?.let(::removeCallbacks); timeout = null
        fallback.visibility = View.GONE
        setStatus(JourneyMapStatus.READY)
    }

    private fun fail() {
        generation++
        releaseBackend()
        setStatus(JourneyMapStatus.UNAVAILABLE)
    }

    private fun releaseBackend() {
        timeout?.let(::removeCallbacks); timeout = null
        val layer = backend
        backend = null
        if (layer != null) {
            // Teardown attempts remain independent when a failed native map rejects one callback.
            runCatching { layer.setPosition(null) }
            runCatching { layer.onPause() }
            runCatching { layer.onStop() }
            runCatching { layer.onDestroy() }
            removeView(layer.view)
        }
        fallback.visibility = View.VISIBLE
    }

    private fun setStatus(value: JourneyMapStatus) { status = value; onStatusChanged?.invoke(value) }
    private inline fun safely(action: () -> Unit) {
        try { action() } catch (_: RuntimeException) { fail() } catch (_: LinkageError) { fail() }
    }

    companion object { private const val MAP_LOAD_TIMEOUT_MS = 20_000L }
}
