package com.petasos.app.storage

import com.petasos.app.generation.StrictJson
import com.petasos.app.planning.WindowLandmark

/** Reads the installed pack's window landmarks (written by RoomContentInstaller.installLandmarks). */
internal class RoomPackLandmarks(private val database: StorageDatabase) {
    fun all(): List<WindowLandmark> = try {
        database.openHelper.readableDatabase.query(
            "SELECT place_id,latitude,longitude,landmark_policy_json FROM place WHERE landmark_policy_json IS NOT NULL ORDER BY place_id",
        ).use { row ->
            buildList {
                while (row.moveToNext()) {
                    if (row.isNull(1) || row.isNull(2)) continue
                    val value = try { StrictJson.parse(row.getString(3).toByteArray(Charsets.UTF_8)) as? Map<*, *> } catch (_: Exception) { null }
                    if (value == null || value["format"] != FORMAT) continue // e.g. a leftover pre-pack policy row
                    val visibility = when (value["visibility"]) { "large" -> WindowLandmark.Visibility.LARGE; "small" -> WindowLandmark.Visibility.SMALL; else -> null }
                    val name = value["name"] as? String
                    val intro = value["intro"] as? String
                    if (visibility == null || name.isNullOrBlank() || intro.isNullOrBlank()) continue
                    add(WindowLandmark(row.getString(0), name, row.getDouble(1), row.getDouble(2), visibility, intro))
                }
            }
        }
    } catch (_: Exception) { emptyList() }

    companion object { const val FORMAT = "pack-landmark-v1" }
}
