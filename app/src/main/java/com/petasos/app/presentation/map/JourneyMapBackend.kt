package com.petasos.app.presentation.map

import android.view.View
import com.petasos.app.presentation.records.MapCoordinate

/** Only a display adapter: it cannot collect locations, authorize stories, or select a route. */
internal interface JourneyMapBackend {
    val view: View
    fun onCreate()
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onDestroy()
    fun onLowMemory()
    fun onSaveInstanceState()
    fun setPosition(point: MapCoordinate?)
    /** Display-only: recorded routes, the highlighted region outline, and where the camera should look. */
    fun setScene(scene: MapScene)
}

/** A recorded stretch of one journey. [emphasized] decides line colour; points are already thinned. */
data class MapRoute(val key: String, val points: List<MapCoordinate>, val emphasized: Boolean)

data class MapScene(
    val routes: List<MapRoute> = emptyList(),
    /** Outer rings of the selected region, drawn as a soft fill with an outline. */
    val highlightRings: List<List<MapCoordinate>> = emptyList(),
    /** The camera fits these points; empty keeps the current view. */
    val focus: List<MapCoordinate> = emptyList(),
    /** Same region as [highlightRings]; the offline fallback map needs the id to colour and zoom to it. */
    val highlightRegionId: String? = null,
)

enum class JourneyMapStatus { OFFLINE, LOADING, READY, UNAVAILABLE }
