package com.petasos.app.presentation.records

import android.content.Context
import org.json.JSONObject

/** Public, simplified shapes for drawing only. Never feed these into confirmation or planning. */
data class DisplayRegionShape(val regionId: String, val name: String, val code: String, val rings: List<List<MapCoordinate>>)

object RecordsGeometryCatalog {
    @Volatile private var cached: List<DisplayRegionShape>? = null

    /** Call on a worker. Static public geometry is shared, user history is never cached here. */
    @Synchronized fun load(context: Context): List<DisplayRegionShape> {
        cached?.let { return it }
        val root = context.assets.open("records-map/regions-display-v1.json").bufferedReader().use { JSONObject(it.readText()) }
        check(root.getBoolean("displayOnly") && root.getInt("formatVersion") == 1)
        val entries = root.getJSONArray("regions")
        check(entries.length() in 1..512)
        val values = (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            val rings = entry.getJSONArray("rings")
            val coordinates = (0 until rings.length()).map { ring ->
                val points = rings.getJSONArray(ring)
                (0 until points.length()).map { point ->
                    val pair = points.getJSONArray(point)
                    MapCoordinate(pair.getDouble(1), pair.getDouble(0)).also {
                        check(it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in 30.0..45.0 && it.longitude in 120.0..135.0)
                    }
                }.also { check(it.size >= 4 && it.first() == it.last()) }
            }
            check(coordinates.isNotEmpty())
            DisplayRegionShape(entry.getString("regionId"), entry.getString("name"), entry.getString("code"), coordinates)
        }
        check(values.map { it.regionId }.distinct().size == values.size)
        cached = values
        return values
    }
}
