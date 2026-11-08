package com.petasos.app.storage

import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentRegionBinding

/** Test APK only. Durable synthetic association; the production authority reader still validates it. */
internal object RegionalBindingFixture {
    fun bindExistingStory(database: StorageDatabase, storyId: String, regionRevisionId: String) {
        database.runInTransaction {
            val sql = database.openHelper.writableDatabase
            val binding = sql.query(SimpleSQLiteQuery(
                "SELECT region_id,code,dataset_version,name,source_revision_id FROM region_revision WHERE region_revision_id=?",
                arrayOf(regionRevisionId),
            )).use {
                check(it.moveToFirst())
                ContentRegionBinding(storyId, it.getString(0), regionRevisionId, it.getString(1),
                    it.getString(2), it.getString(3), it.getString(4), "synthetic-association-$storyId-r1", 1,
                    "Synthetic test-only reviewed association; no actual region or tourism claim.")
            }
            sql.execSQL("INSERT INTO fact_revision(fact_revision_id,fact_id,version,statement,kind,verified_at) VALUES (?,?,1,?,'verified_fact',1)",
                arrayOf(binding.associationFactRevisionId, "synthetic-association-$storyId",
                    "SYNTHETIC TEST ONLY: $storyId belongs to ${binding.regionId}."))
            sql.execSQL("INSERT INTO fact_source(fact_revision_id,source_revision_id) VALUES (?,'test-source-r1')",
                arrayOf(binding.associationFactRevisionId))
            sql.execSQL("UPDATE story SET region_id=? WHERE story_id=?", arrayOf(binding.regionId, storyId))
            RoomRegionalContentBindings(database, { 100_000 }).install(binding)
        }
    }
}
