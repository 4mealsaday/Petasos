package com.petasos.app.presentation.map

import android.view.View
import com.petasos.app.presentation.records.DisplayRegionShape
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
    /** Public region boundaries: drawn as an outline plus a grey cover over regions not collected yet. */
    fun setRegions(regions: List<DisplayRegionShape>)
    /** 0..1 reveal of the journeys (and of the grey covers they uncover); 1 is the finished state. */
    fun setProgress(progress: Float)
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
    /** Regions collected on a journey: never covered. Regions a line touches are uncovered as it reaches them. */
    val uncovered: Set<String> = emptySet(),
    /** Region id -> index (over all [routes] points, in order) where a line first touches it. */
    val revealIndex: Map<String, Int> = emptyMap(),
) {
    val reveal: JourneyReveal by lazy(LazyThreadSafetyMode.NONE) { JourneyReveal(routes, revealIndex, uncovered) }
}

/** Pure arithmetic shared by every renderer, so the Naver layer and the offline map animate identically. */
class JourneyReveal(private val routes: List<MapRoute>, private val revealIndex: Map<String, Int>, private val uncovered: Set<String>) {
    private val offsets = IntArray(routes.size).also { var sum = 0; routes.forEachIndexed { i, r -> it[i] = sum; sum += r.points.size } }
    val total: Int = routes.sumOf { it.points.size }

    private fun global(progress: Float) = if (progress >= 1f) total else (progress.coerceIn(0f, 1f) * total).toInt()

    /** Points of route [index] drawn at [progress]. */
    fun drawn(index: Int, progress: Float): Int = (global(progress) - offsets[index]).coerceIn(0, routes[index].points.size)

    fun isCovered(regionId: String, progress: Float): Boolean {
        if (regionId in uncovered) return false
        val at = revealIndex[regionId] ?: return true
        return at >= global(progress) && progress < 1f
    }

    /** The leading point of the line still being drawn, or null before it starts / once finished. */
    fun head(progress: Float): MapCoordinate? {
        if (progress <= 0f || progress >= 1f) return null
        val g = global(progress)
        for (i in routes.indices.reversed()) {
            if (g > offsets[i] && routes[i].points.isNotEmpty()) return routes[i].points[(g - offsets[i] - 1).coerceIn(0, routes[i].points.size - 1)]
        }
        return null
    }
}

enum class JourneyMapStatus { OFFLINE, LOADING, READY, UNAVAILABLE }

/**
 * A city whose districts are also in the boundary set (수원시 + 장안구…): drawing both would stack two covers on the
 * same ground, and a district would stay grey under its city. Only the districts are drawn.
 */
fun citiesWithDistricts(shapes: List<DisplayRegionShape>): Set<String> {
    val districtCities = shapes.filter { it.name.endsWith("구") && !it.code.endsWith("0") }.map { it.code.take(4) }.toSet()
    return shapes.filter { it.name.endsWith("시") && it.code.endsWith("0") && it.code.take(4) in districtCities }
        .map { it.regionId }.toSet()
}
