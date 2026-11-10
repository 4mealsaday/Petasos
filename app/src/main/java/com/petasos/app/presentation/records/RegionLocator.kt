package com.petasos.app.presentation.records

/** Display-only: names the public display shape under a recorded point. Never feeds confirmation or planning. */
class RegionLocator(private val shapes: List<DisplayRegionShape>) {
    private class Box(val shape: DisplayRegionShape, val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
        val area get() = (maxLat - minLat) * (maxLon - minLon)
    }

    private val boxes = shapes.map { shape ->
        val points = shape.rings.flatten()
        Box(shape, points.minOf { it.latitude }, points.maxOf { it.latitude }, points.minOf { it.longitude }, points.maxOf { it.longitude })
    }

    /** The smallest containing shape wins, so a district is preferred over its parent city. */
    fun find(point: MapCoordinate): DisplayRegionShape? = boxes
        .filter { point.latitude in it.minLat..it.maxLat && point.longitude in it.minLon..it.maxLon }
        .sortedBy { it.area }
        .firstOrNull { contains(it.shape, point) }?.shape

    /** Display-only: a recorded point that falls in a simplified-boundary gap still names the closest shape. */
    fun findOrNearest(point: MapCoordinate, maxDegrees: Double = 0.03): DisplayRegionShape? {
        find(point)?.let { return it }
        var best: DisplayRegionShape? = null
        var bestDistance = maxDegrees * maxDegrees
        for (box in boxes) {
            if (point.latitude < box.minLat - maxDegrees || point.latitude > box.maxLat + maxDegrees ||
                point.longitude < box.minLon - maxDegrees || point.longitude > box.maxLon + maxDegrees) continue
            for (ring in box.shape.rings) for (vertex in ring) {
                val dy = vertex.latitude - point.latitude
                val dx = vertex.longitude - point.longitude
                val d = dx * dx + dy * dy
                if (d < bestDistance) { bestDistance = d; best = box.shape }
            }
        }
        return best
    }

    private fun contains(shape: DisplayRegionShape, point: MapCoordinate): Boolean {
        var inside = false
        for (ring in shape.rings) {
            var j = ring.size - 1
            for (i in ring.indices) {
                val a = ring[i]; val b = ring[j]
                if ((a.latitude > point.latitude) != (b.latitude > point.latitude) &&
                    point.longitude < (b.longitude - a.longitude) * (point.latitude - a.latitude) / (b.latitude - a.latitude) + a.longitude
                ) inside = !inside
                j = i
            }
        }
        return inside
    }

    companion object {
        /** "서울 중구", "경기 용인시 처인구": province prefix + optional parent city + the shape's own name. */
        fun displayName(shape: DisplayRegionShape): String {
            val parent = when (shape.code) { "41461", "41463", "41465" -> "용인시"; "44131", "44133" -> "천안시"; else -> null }
            val province = PROVINCES[shape.code.take(2)]
            return listOfNotNull(province, parent, shape.name).joinToString(" ")
        }

        private val PROVINCES = mapOf("11" to "서울", "26" to "부산", "27" to "대구", "28" to "인천", "29" to "광주",
            "30" to "대전", "31" to "울산", "36" to "세종", "41" to "경기", "43" to "충북", "44" to "충남", "46" to "전남",
            "47" to "경북", "48" to "경남", "50" to "제주", "51" to "강원", "52" to "전북")
    }
}
