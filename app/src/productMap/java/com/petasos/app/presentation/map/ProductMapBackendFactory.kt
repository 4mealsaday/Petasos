package com.petasos.app.presentation.map

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
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
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.Overlay
import com.naver.maps.map.overlay.OverlayImage
import com.naver.maps.map.overlay.PolygonOverlay
import com.naver.maps.map.overlay.PolylineOverlay
import com.petasos.app.BuildConfig
import com.petasos.app.R
import com.petasos.app.presentation.records.DisplayRegionShape
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
    private class RouteLayer(val route: MapRoute, val glow: PolylineOverlay?, val line: PolylineOverlay, val latLngs: List<LatLng>)
    private var routeLayers = emptyList<RouteLayer>()
    private var regions = emptyList<DisplayRegionShape>()
    private val regionOverlays = HashMap<String, List<PolygonOverlay>>()
    private val regionCovered = HashMap<String, Boolean>()
    private val regionLayerOverlays = mutableListOf<Overlay>()
    private var head: Marker? = null
    private var progress = 1f
    private var lastFrameAt = 0L
    private var created = false
    private var started = false
    private var resumed = false
    private var destroyed = false
    private var readySent = false
    private val authFailure = NaverMapSdk.OnAuthFailedListener {
        if (BuildConfig.DEBUG) Log.i("PetasosMap", "fallback reason=auth-failed")
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
                    applyBaseStyle(value)
                    value.addOnLoadListener(loaded)
                    applyPosition()
                    buildRegions()
                    applyScene()
                    if (value.isLoaded) ready()
                } catch (failure: RuntimeException) { logFailure(failure); onFailure() }
                catch (failure: LinkageError) { logFailure(failure); onFailure() }
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
        clearRegions()
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
        routeLayers = emptyList()
        head?.map = null
        head = null
    }

    /** Plain 2D basic map, quieter than the default: no extra layers, smaller place names, a little darker. */
    private fun applyBaseStyle(value: NaverMap) {
        value.mapType = NaverMap.MapType.Basic
        listOf(NaverMap.LAYER_GROUP_BUILDING, NaverMap.LAYER_GROUP_TRANSIT, NaverMap.LAYER_GROUP_BICYCLE,
            NaverMap.LAYER_GROUP_TRAFFIC, NaverMap.LAYER_GROUP_CADASTRAL, NaverMap.LAYER_GROUP_MOUNTAIN)
            .forEach { value.setLayerGroupEnabled(it, false) }
        value.symbolScale = SYMBOL_SCALE
        value.lightness = LIGHTNESS
    }

    override fun setRegions(regions: List<DisplayRegionShape>) {
        if (destroyed) return
        this.regions = regions
        buildRegions()
    }

    private fun clearRegions() {
        regionLayerOverlays.forEach { it.map = null }
        regionLayerOverlays.clear(); regionOverlays.clear(); regionCovered.clear()
    }

    /** One outline polygon per outer ring (rings inside another ring of the same region are its holes). */
    private fun buildRegions() {
        val value = map ?: return
        clearRegions()
        val density = mapView.resources.displayMetrics.density
        val outline = maxOf(1, Math.round(REGION_LINE_DP * density))
        val parents = citiesWithDistricts(regions)
        regions.forEach { shape ->
            if (shape.regionId in parents) return@forEach
            val rings = shape.rings
            val isHole = rings.mapIndexed { i, ring -> rings.indices.any { j -> j != i && contains(rings[j], ring.first()) } }
            val holes = rings.filterIndexed { i, _ -> isHole[i] }.map { r -> r.map { LatLng(it.latitude, it.longitude) } }
            val created = rings.filterIndexed { i, _ -> !isHole[i] }.map { ring ->
                PolygonOverlay(ring.map { LatLng(it.latitude, it.longitude) }).apply {
                    // Holes only matter where they sit inside this outer ring.
                    this.holes = holes.filter { hole -> contains(ring, MapCoordinate(hole[0].latitude, hole[0].longitude)) }
                    color = COVER; outlineColor = REGION_LINE; outlineWidth = outline
                    globalZIndex = Z_COVER; this.map = value
                }
            }
            regionOverlays[shape.regionId] = created
            regionCovered[shape.regionId] = true
            regionLayerOverlays += created
        }
        applyProgress(force = true)
    }

    private fun contains(ring: List<MapCoordinate>, point: MapCoordinate): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]; val b = ring[j]
            if ((a.latitude > point.latitude) != (b.latitude > point.latitude) &&
                point.longitude < (b.longitude - a.longitude) * (point.latitude - a.latitude) / (b.latitude - a.latitude) + a.longitude
            ) inside = !inside
            j = i
        }
        return inside
    }

    override fun setProgress(progress: Float) {
        if (destroyed) return
        this.progress = progress
        applyProgress(force = false)
    }

    /** Covers and the drawn part of each line for [progress]; throttled so a 3 s reveal stays cheap. */
    private fun applyProgress(force: Boolean) {
        if (map == null) return
        val now = android.os.SystemClock.uptimeMillis()
        val finished = progress >= 1f || progress <= 0f
        if (!force && !finished && now - lastFrameAt < FRAME_MS) return
        lastFrameAt = now
        val reveal = scene.reveal
        regionOverlays.forEach { (id, polygons) ->
            val covered = reveal.isCovered(id, progress)
            if (regionCovered[id] != covered) {
                regionCovered[id] = covered
                polygons.forEach { it.color = if (covered) COVER else Color.TRANSPARENT }
            }
        }
        routeLayers.forEachIndexed { index, layer ->
            val count = reveal.drawn(index, progress)
            val visible = count >= 2
            layer.line.isVisible = visible; layer.glow?.isVisible = visible
            if (visible) {
                val part = if (count == layer.latLngs.size) layer.latLngs else layer.latLngs.subList(0, count)
                layer.line.coords = part; layer.glow?.coords = part
            }
        }
        val point = reveal.head(progress)
        val marker = head
        if (marker != null) {
            marker.isVisible = point != null
            if (point != null) marker.position = LatLng(point.latitude, point.longitude)
        }
    }

    private fun applyScene() {
        val value = map ?: return
        clearOverlays()
        val density = mapView.resources.displayMetrics.density
        val ink = mapView.context.getColor(R.color.primary)
        val wash = (0x59 shl 24) or (mapView.context.getColor(R.color.primary_wash) and 0x00FFFFFF)
        scene.highlightRings.forEach { ring ->
            if (ring.size < 3) return@forEach
            overlays += PolygonOverlay(ring.map { LatLng(it.latitude, it.longitude) }).apply {
                color = wash; outlineColor = ink; outlineWidth = (3 * density).toInt(); globalZIndex = Z_HIGHLIGHT; map = value
            }
        }
        // Glow (10dp, 35%) under the line (4dp). Journeys overlap as separate lines, so repeated roads get denser.
        // Dimmed journeys (another one is selected) keep only a faint line.
        val layers = ArrayList<RouteLayer>(scene.routes.size)
        scene.routes.forEach { route ->
            val latLngs = route.points.map { LatLng(it.latitude, it.longitude) }
            fun line(widthDp: Float, color: Int, z: Int) = PolylineOverlay().apply {
                width = Math.round(widthDp * density); this.color = color
                capType = PolylineOverlay.LineCap.Round; joinType = PolylineOverlay.LineJoin.Round
                globalZIndex = z; isVisible = false
                if (latLngs.size >= 2) { coords = latLngs.subList(0, 2); map = value }
            }
            val glow = if (route.emphasized) line(GLOW_DP, ROUTE_GLOW, Z_GLOW).also { overlays += it } else null
            val main = line(LINE_DP, if (route.emphasized) ROUTE else ROUTE_FAINT, Z_LINE).also { overlays += it }
            layers += RouteLayer(route, glow, main, latLngs)
        }
        routeLayers = layers
        head = Marker().apply {
            position = OVERVIEW.target; icon = headIcon(density); anchor = PointF(.5f, .5f); isFlat = false; isForceShowIcon = true
            globalZIndex = Z_HEAD; isVisible = false; map = value
        }
        applyProgress(force = true)
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

    private fun headIcon(density: Float): OverlayImage {
        val size = Math.round(18 * density)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = ROUTE; canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = Color.WHITE; canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2.5f * density, paint)
        return OverlayImage.fromBitmap(bitmap)
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

    /** Debug builds only, and only the exception class: never SDK messages, keys or coordinates. */
    private fun logFailure(failure: Throwable) {
        if (BuildConfig.DEBUG) Log.i("PetasosMap", "fallback reason=exception ${failure.javaClass.simpleName}")
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
        const val SYMBOL_SCALE = 0.85f
        const val LIGHTNESS = -0.2f
        const val FRAME_MS = 50L
        const val GLOW_DP = 10f
        const val LINE_DP = 4f
        const val REGION_LINE_DP = 0.8f
        val COVER = Color.argb(0x73, 0x14, 0x1A, 0x24)       // #141A24 at ~45%
        val REGION_LINE = Color.parseColor("#B4BBCB")
        val ROUTE = Color.parseColor("#0078FF")
        val ROUTE_GLOW = Color.argb(0x59, 0x00, 0x78, 0xFF)  // #0078FF at 35%
        val ROUTE_FAINT = Color.argb(0x59, 0x00, 0x78, 0xFF)
        const val Z_COVER = -300_000
        const val Z_HIGHLIGHT = -299_000
        const val Z_GLOW = -200_000
        const val Z_LINE = -199_000
        const val Z_HEAD = 100_000
    }
}
