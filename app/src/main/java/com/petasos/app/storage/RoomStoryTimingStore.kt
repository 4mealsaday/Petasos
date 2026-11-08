package com.petasos.app.storage

import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.playback.PreparedStory

/** Immutable, installed duration estimates; these are not completion or arrival evidence. */
class RoomStoryTimingStore internal constructor(private val persistence: RoomRuntimePersistence) {
    fun paragraphDurations(story: PreparedStory): List<Long>? = try {
        if (persistence.prepareStory(story.versionId) != story) null else {
            val durations = mutableMapOf<String, Long>()
            persistence.database.openHelper.readableDatabase.query(SimpleSQLiteQuery(
                "SELECT paragraph_id,estimated_duration_ms FROM paragraph WHERE story_version_id=?",
                arrayOf(story.versionId),
            )).use { rows -> while (rows.moveToNext()) durations[rows.getString(0)] = rows.getLong(1) }
            story.paragraphIds.map { durations[it] ?: 0 }.takeIf { values ->
                values.all { it in 1..3_600_000L } && values.sum() <= 3_600_000L
            }
        }
    } catch (_: Exception) { null }
}
