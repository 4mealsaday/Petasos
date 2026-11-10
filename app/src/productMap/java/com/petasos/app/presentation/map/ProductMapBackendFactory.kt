package com.petasos.app.presentation.map

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.LocationTrackingMode
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap
import com.naver.maps.map.NaverMapOptions
import com.naver.maps.map.NaverMapSdk
import com.naver.maps.map.overlay.Overlay
import com.naver.maps.map.overlay.PolygonOverlay
import com.naver.maps.map.overlay.PolylineOverlay
import com.petasos.app.R
import com.petasos.app.presentation.records.MapCoordinate

/** Compiled only for debug/privacy/release. The demo provides a separate no-network factory. */
internal object ProductMapBackendFactory {
    fun create(context: Context, onReady: () -> Unit, onFailure: () -> Unit): JourneyMapBackend? {
        if (context.getString(R.string.petasos_naver_map_key_id).isBlank()) return null
        return NaverJourneyMapBackend(context, onReady, onFailure)
    }
}

/** Official MapView lifecycle; neither a FusedLocationSource nor SDK location tracking is installed. */
private class NaverJourneyMapBackend(
    context: Context,
    private val onReady: () -> Unit,
    private val onFailure: () -> Unit,
) : JourneyMapBackend {
    private val sdk = NaverMapSdk.getInstance(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private var map: NaverMap? = null
    private var position: MapCoordinate? = null
    private var scene = MapScene()
    private val overlays = mutableListOf<Overlay>()
    private var created = false
    private var started = false
    private var resumed = false
    private var destroyed = false
    private var readySent = false
    private val authFailure = NaverMapSdk.OnAuthFailedListener {
        // Do not stringify SDK exceptions, credentials or coordinates, and suppress its default toast.
        handler.post { if (!destroyed) onFailure() }
    }
    private val loaded = NaverMap.OnLoadListener { ready() }
    private val mapView: MapView
    override val view: View get() = mapView

    init {
        sdk.onAuthFailedListener = authFailure
        try {
            val margin = (16 * context.resources.displayMetrics.density).toInt()
            mapView = MapView(context, NaverMapOptions()
                .camera(OVERVIEW)
                .useTextureView(true)
                .fpsLimit(30)
                .locationButtonEnabled(false)
                .zoomControlEnabled(false)
                .compassEnabled(false)
                .scaleBarEnabled(true)
                .indoorLevelPickerEnabled(false)
                .tiltGesturesEnabled(false)
                .rotateGesturesEnabled(false)
                .logoGravity(Gravity.BOTTOM or Gravity.END)
                .logoMargin(margin, margin, margin, margin)
            ).apply {
                isSaveEnabled = false
                setSaveFromParentEnabled(false)
            }
        } catch (failure: RuntimeException) {
            releaseAuthListener(); throw failure
        } catch (failure: LinkageError) {
            releaseAuthListener(); throw failure
        }
    }

    override fun onCreate() {
        if (created || destroyed) return
        created = true
        mapView.onCreate(null)
        mapView.getMapAsync { value ->
            if (!destroyed) {
                try {
                    map = value
                    value.locationSource = null
                    value.locationTrackingMode = LocationTrackingMode.None
                    value.locationOverlay.isVisible = false
                    value.addOnLoadListener(loaded)
                    applyPosition()
                    applyScene()
                    if (value.isLoaded) ready()
                } catch (_: RuntimeException) { onFailure() }
                catch (_: LinkageError) { onFailure() }
            }
        }
    }

    override fun onStart() {
        if (!created || started || destroyed) return
        started = true
        mapView.onStart()
    }

    override fun onResume() {
        if (!started || resumed || destroyed) return
        resumed = true
        mapView.onResume()
    }

    override fun onPause() {
        if (!resumed || destroyed) return
        resumed = false
        mapView.onPause()
    }

    override fun onStop() {
        if (!started || destroyed) return
        onPause()
        started = false
        mapView.onStop()
    }

    override fun onDestroy() {
        if (destroyed) return
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        position = null
        clearOverlays()
        releaseAuthListener()
        map?.removeOnLoadListener(loaded)
        map = null
        if (created) mapView.onDestroy()
    }

    override fun onLowMemory() { if (created && !destroyed) mapView.onLowMemory() }

    override fun onSaveInstanceState() {
        if (created && !destroyed) {
            // Satisfy the SDK callback without putting its camera/location state in Activity state.
            val transientState = Bundle()
            mapView.onSaveInstanceState(transientState)
            transientState.clear()
        }
    }

    override fun setPosition(point: MapCoordinate?) {
        if (destroyed) return
        if (position == point && map != null) return
        position = point
        applyPosition()
    }

    override fun setScene(scene: MapScene) {
        if (destroyed) return
        this.scene = scene
        applyScene()
    }

    private fun clearOverlays() {
        overlays.forEach { it.map = null }
        overlays.clear()
    }

    private fun applyScene() {
        val value = map ?: return
        clearOverlays()
        val density = mapView.resources.displayMetrics.density
        val ink = mapView.context.getColor(R.color.primary)
        val faint = mapView.context.getColor(R.color.primary_line)
        val wash = (0x59 shl 24) or (mapView.context.getColor(R.color.primary_wash) and 0x00FFFFFF)
        scene.highlightRings.forEach { ring ->
            if (ring.size < 3) return@forEach
            overlays += PolygonOverlay(ring.map { LatLng(it.latitude, it.longitude) }).apply {
                color = wash; outlineColor = ink; outlineWidth = (3 * density).toInt(); globalZIndex = 100; map = value
            }
        }
        // Emphasized lines go last (above) so a selected journey is never hidden by the faint ones.
        scene.routes.sortedBy { it.emphasized }.forEach { route ->
            if (route.points.size < 2) return@forEach
            overlays += PolylineOverlay(route.points.map { LatLng(it.latitude, it.longitude) }).apply {
                color = if (route.emphasized) ink else faint
                width = (6 * density).toInt()
                capType = PolylineOverlay.LineCap.Round
                joinType = PolylineOverlay.LineJoin.Round
                globalZIndex = if (route.emphasized) 300 else 200
                map = value
            }
        }
        val focus = scene.focus.map { LatLng(it.latitude, it.longitude) }
        if (focus.isNotEmpty()) {
            val bounds = LatLngBounds.fromOrNull(focus)
            val pad = (48 * density).toInt()
            try {
                if (bounds == null || focus.distinct().size < 2) value.moveCamera(CameraUpdate.scrollAndZoomTo(focus.first(), 14.0))
                else value.moveCamera(CameraUpdate.fitBounds(bounds, pad))
            } catch (_: RuntimeException) { /* A degenerate box keeps the current view. */ }
        }
    }

    private fun applyPosition() {
        val value = map ?: return
        val point = position
        value.locationOverlay.isVisible = point != null
        if (point == null) {
            value.locationOverlay.position = OVERVIEW.target
            value.moveCamera(CameraUpdate.toCameraPosition(OVERVIEW))
        } else {
            val coordinate = LatLng(point.latitude, point.longitude)
            value.locationOverlay.position = coordinate
            value.moveCamera(CameraUpdate.scrollAndZoomTo(coordinate, 15.0))
        }
    }

    private fun ready() {
        if (!destroyed && !readySent) { readySent = true; onReady() }
    }

    private fun releaseAuthListener() {
        if (sdk.onAuthFailedListener === authFailure) sdk.onAuthFailedListener = SILENT_AUTH_FAILURE
    }

    private companion object {
        // A neutral viewport is display context only. It is never presented as a measured position.
        val OVERVIEW = CameraPosition(LatLng(36.2, 127.8), 6.0)
        val SILENT_AUTH_FAILURE = NaverMapSdk.OnAuthFailedListener { }
    }
}
