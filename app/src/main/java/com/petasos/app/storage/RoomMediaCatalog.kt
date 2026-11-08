package com.petasos.app.storage

import com.petasos.app.runtime.RuntimeContentUnavailable

data class InstalledStoryItem(val versionId: String, val title: String)

/** General reviewed titles only; no conversation, transcript, file path, token or external URI. */
class RoomMediaCatalog(private val store: RoomRuntimePersistence) {
    fun items(): List<InstalledStoryItem> {
        val candidates = mutableListOf<InstalledStoryItem>()
        store.database.openHelper.readableDatabase.query("""SELECT v.story_version_id,s.title FROM story s JOIN story_version v ON v.story_id=s.story_id
            WHERE v.status='published' AND v.version=(SELECT max(n.version) FROM story_version n WHERE n.story_id=s.story_id AND n.status='published')
            ORDER BY s.title,s.story_id LIMIT 100""").use { rows ->
            while (rows.moveToNext()) candidates += InstalledStoryItem(rows.getString(0), rows.getString(1))
        }
        return candidates.filter {
            try { store.prepareStory(it.versionId); true }
            catch (_: RuntimeContentUnavailable) { false }
        }
    }
}
