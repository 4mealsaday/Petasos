package com.petasos.app.presentation.records

import kotlin.math.cos
import kotlin.math.hypot

/** Thins a recorded line for drawing only: keeps points at least [minMeters] apart plus both ends. */
object RouteSimplifier {
    fun thin(points: List<MapCoordinate>, minMeters: Double = 40.0, maxPoints: Int = 1_200): List<MapCoordinate> {
        if (points.size <= 2) return points
        val kept = ArrayList<MapCoordinate>(minOf(points.size, maxPoints * 2))
        kept += points.first()
        var last = points.first()
        for (index in 1 until points.size - 1) {
            val point = points[index]
            if (meters(last, point) >= minMeters) { kept += point; last = point }
        }
        kept += points.last()
        if (kept.size <= maxPoints) return kept
        val stride = kept.size.toDouble() / maxPoints
        val sampled = ArrayList<MapCoordinate>(maxPoints + 1)
        var cursor = 0.0
        while (cursor < kept.size - 1) { sampled += kept[cursor.toInt()]; cursor += stride }
        sampled += kept.last()
        return sampled
    }

    private fun meters(a: MapCoordinate, b: MapCoordinate): Double {
        val dy = (b.latitude - a.latitude) * 111_320.0
        val dx = (b.longitude - a.longitude) * 111_320.0 * cos(Math.toRadians((a.latitude + b.latitude) / 2))
        return hypot(dx, dy)
    }
}
