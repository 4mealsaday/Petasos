package com.petasos.app.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive review metadata; the canonical v0.8 user-record tables remain unchanged. */
@Entity(tableName = "story_region_binding", primaryKeys = ["story_id"],
    indices = [Index(value = ["region_id"]), Index(value = ["region_revision_id"]),
        Index(value = ["region_source_revision_id"]), Index(value = ["association_fact_revision_id"])],
    foreignKeys = [
        ForeignKey(entity = StoryRow::class, parentColumns = ["story_id"], childColumns = ["story_id"]),
        ForeignKey(entity = RegionRow::class, parentColumns = ["region_id"], childColumns = ["region_id"]),
        ForeignKey(entity = RegionRevisionRow::class, parentColumns = ["region_revision_id"], childColumns = ["region_revision_id"]),
        ForeignKey(entity = SourceEvidenceRow::class, parentColumns = ["source_revision_id"], childColumns = ["region_source_revision_id"]),
        ForeignKey(entity = FactRevisionRow::class, parentColumns = ["fact_revision_id"], childColumns = ["association_fact_revision_id"]),
    ])
internal data class StoryRegionBindingRow(
    val story_id: String, val region_id: String, val region_revision_id: String,
    val region_code: String, val dataset_version: String, val region_name: String,
    val region_source_revision_id: String, val association_fact_revision_id: String,
    /** Null explicitly revokes the association without deleting its evidence. */
    val verified_at: Long?, val review_basis: String,
) { override fun toString() = "StoryRegionBindingRow([redacted])" }

internal object RegionalContentSchema {
    const val CONSTRAINT_COUNT = 2
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS story_region_binding (
                story_id TEXT NOT NULL, region_id TEXT NOT NULL, region_revision_id TEXT NOT NULL,
                region_code TEXT NOT NULL, dataset_version TEXT NOT NULL, region_name TEXT NOT NULL,
                region_source_revision_id TEXT NOT NULL, association_fact_revision_id TEXT NOT NULL,
                verified_at INTEGER, review_basis TEXT NOT NULL, PRIMARY KEY(story_id),
                FOREIGN KEY(story_id) REFERENCES story(story_id) ON UPDATE NO ACTION ON DELETE NO ACTION,
                FOREIGN KEY(region_id) REFERENCES region(region_id) ON UPDATE NO ACTION ON DELETE NO ACTION,
                FOREIGN KEY(region_revision_id) REFERENCES region_revision(region_revision_id) ON UPDATE NO ACTION ON DELETE NO ACTION,
                FOREIGN KEY(region_source_revision_id) REFERENCES source_evidence(source_revision_id) ON UPDATE NO ACTION ON DELETE NO ACTION,
                FOREIGN KEY(association_fact_revision_id) REFERENCES fact_revision(fact_revision_id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
            for (column in listOf("region_id", "region_revision_id", "region_source_revision_id", "association_fact_revision_id"))
                db.execSQL("CREATE INDEX IF NOT EXISTS index_story_region_binding_$column ON story_region_binding($column)")
            installConstraints(db)
        }
    }

    fun installConstraints(db: SupportSQLiteDatabase) {
        for (operation in listOf("INSERT", "UPDATE")) db.execSQL("""CREATE TRIGGER IF NOT EXISTS
            constraint_story_region_binding_${operation.lowercase()} BEFORE $operation ON story_region_binding
            WHEN (NEW.verified_at IS NOT NULL AND NEW.verified_at < 0) OR length(trim(NEW.review_basis))=0
              OR length(NEW.region_code)<>5 OR NEW.region_code GLOB '*[^0-9]*'
            BEGIN SELECT RAISE(ABORT, 'invalid regional content review'); END""")
    }
}
