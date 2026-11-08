package com.petasos.app.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [
    TripRow::class,
    SessionRow::class,
    RecordingSegmentRow::class,
    RoutePointRow::class,
    SourceEvidenceRow::class,
    RegionRow::class,
    RegionRevisionRow::class,
    PlaceRow::class,
    PlaceExternalIdRow::class,
    FactRevisionRow::class,
    FactSourceRow::class,
    StoryRow::class,
    StoryVersionRow::class,
    ParagraphRow::class,
    ParagraphFactRow::class,
    ConversationTurnRow::class,
    PendingConversationRow::class,
    ConversationSummaryRow::class,
    PlaybackAttemptRow::class,
    PlaybackCheckpointRow::class,
    CollectionRecordRow::class,
    CollectionProofRow::class,
    PreferenceRow::class,
    GenerationRequestRow::class,
    StoryRegionBindingRow::class,
], version = 2, exportSchema = true)
internal abstract class StorageDatabase : RoomDatabase() {
    companion object {
        /** Additive 1→2 migration preserves every v0.8 row. Unknown upgrades still fail. */
        fun open(context: Context, name: String): StorageDatabase =
            Room.databaseBuilder(context.applicationContext, StorageDatabase::class.java, name)
                .addMigrations(RegionalContentSchema.MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        SchemaConstraints.installVersion1(db)
                        RegionalContentSchema.installConstraints(db)
                    }
                })
                .build()
    }
}
