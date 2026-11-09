package com.petasos.app.storage

import android.app.Instrumentation
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.generation.StrictJson
import com.petasos.app.presentation.records.RecordsGeometryCatalog
import java.util.UUID

/** Disposable real schema with synthetic samples only. No product history, actual position or output. */
object RecordsAtlasProjectionProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "records-atlas-${UUID.randomUUID()}.db"
        var now = 100L
        try {
            SyntheticStorageFixture.create(context, name, wallTime = { now }).use { store ->
                SyntheticStorageFixture.install(store)
                val sql = store.database.openHelper.writableDatabase
                fun exec(statement: String, vararg values: Any?) = sql.execSQL(statement, values)
                fun number(statement: String): Long = sql.query(statement).use { check(it.moveToFirst()); it.getLong(0) }
                val projection = RoomRecordsAtlasStore(store.database)
                val initialChanges = number("SELECT total_changes()")
                check(projection.readAtlas().let { it.trips.isEmpty() && it.paths.isEmpty() && it.collectedRegions.isEmpty() && it.totalTrips == 0L })
                check(projection.readRegion("absent").let { !it.collected && it.stories.isEmpty() && it.trips.isEmpty() })
                check(projection.readTrip("absent").let { it.results == null && it.paths.isEmpty() })
                check(number("SELECT total_changes()") == initialChanges)
                mark("records_atlas_empty_schema_reads_do_not_create_trip_collection_or_location")

                exec("INSERT INTO region(region_id,name) VALUES ('atlas-region','SYNTHETIC ATLAS REGION')")
                exec("INSERT INTO region(region_id,name) VALUES ('atlas-pending','SYNTHETIC PENDING REGION')")
                exec("""INSERT INTO region_revision(region_revision_id,region_id,code,dataset_version,name,source_revision_id,policy_json,verified_at)
                    VALUES ('atlas-region-r1','atlas-region','synthetic','synthetic','SYNTHETIC ATLAS REGION','test-source-r1','{}',1)""")
                exec("UPDATE story SET region_id='atlas-region' WHERE story_id='test-story'")
                store.openSession("atlas-trip-a", "atlas-session-a")
                exec("""INSERT INTO recording_segment(segment_id,trip_id,session_id,started_at,status)
                    VALUES ('atlas-segment-a','atlas-trip-a','atlas-session-a',100,'active')""")
                fun point(sequence: Int, time: Long, quality: String = "valid", revision: Int = 0,
                          clock: String = "atlas-clock", provider: String = "synthetic", providerEpoch: String? = null,
                          segment: String = "atlas-segment-a", id: String = "atlas-point-$sequence") {
                    exec("""INSERT INTO route_point(point_id,segment_id,sequence,provider,provider_epoch,source_event_id,
                        source_time_unit,observed_at,received_at,received_elapsed_ms,clock_scope_id,latitude,longitude,
                        raw_coordinates_json,quality_status,route_revision) VALUES (?,?,?,?,?,?,'epoch_ms',?,?,?,?,?,?,'{}',?,?)""",
                        id, segment, sequence, provider, providerEpoch, if (providerEpoch == null) null else id,
                        time, time, time, clock, 37.0 + sequence / 10_000.0, 127.0, quality, revision)
                }
                point(0, 1000); point(1, 2000); point(2, 3000, quality = "invalid"); point(3, 4000)
                point(4, 15_001); point(5, 16_001); point(6, 17_001, revision = 1)
                point(7, 18_001, revision = 1, clock = "other-clock")
                point(8, 19_001, revision = 1, clock = "other-clock", provider = "other-provider")
                point(9, 20_001, revision = 1, clock = "other-clock", provider = "other-provider", providerEpoch = "new-epoch")
                point(11, 21_001, revision = 1, clock = "other-clock", provider = "other-provider", providerEpoch = "new-epoch")
                fun complete(id: String, session: String, paragraph: String) {
                    exec("""INSERT INTO playback_attempt(attempt_id,session_id,kind,paragraph_id,state,started_at,completed_at,clock_scope_id)
                        VALUES (?,?,'story',?,'completed',100,101,'synthetic-clock')""", id, session, paragraph)
                }
                complete("atlas-a-p1", "atlas-session-a", "test-p1")
                check(projection.readRegion("atlas-region").stories.isEmpty())
                check(projection.readAtlas().collectedRegions.isEmpty())
                mark("records_atlas_partial_story_and_valid_position_alone_do_not_create_collection_credit")

                exec("""INSERT INTO collection_record(collection_id,collection_type,region_id,rule_version,earned_at,created_at)
                    VALUES ('atlas-region-card','region','atlas-region','synthetic-rule',999000,999001)""")
                exec("""INSERT INTO collection_proof(collection_id,proof_key,point_id,region_revision_id)
                    VALUES ('atlas-region-card','atlas-region','atlas-point-0','atlas-region-r1')""")
                // A row without durable proof must not colour a region, even with a later earnedAt.
                exec("""INSERT INTO collection_record(collection_id,collection_type,region_id,rule_version,earned_at,created_at)
                    VALUES ('atlas-unproved-card','region','atlas-pending','synthetic-rule',999999,999999)""")
                exec("UPDATE recording_segment SET status='closed',ended_at=30000,end_reason='user_end' WHERE segment_id='atlas-segment-a'")
                exec("UPDATE session SET status='closed',closed_at=30000 WHERE session_id='atlas-session-a'")
                exec("UPDATE trip SET status='closed',ended_at=30000,end_reason='user_end' WHERE trip_id='atlas-trip-a'")
                now = 40000
                store.openSession("atlas-trip-b", "atlas-session-b")
                exec("""INSERT INTO recording_segment(segment_id,trip_id,session_id,started_at,status)
                    VALUES ('atlas-segment-b','atlas-trip-b','atlas-session-b',40000,'active')""")
                point(0, 45000, segment = "atlas-segment-b", id = "atlas-b-point-0")
                complete("atlas-b-p2", "atlas-session-b", "test-p2")
                exec("""INSERT INTO collection_record(collection_id,collection_type,story_id,story_version_id,rule_version,earned_at,created_at)
                    VALUES ('atlas-story-card','story','test-story','test-story-v1','synthetic-rule',50000,50001)""")
                exec("INSERT INTO collection_proof(collection_id,proof_key,attempt_id) VALUES ('atlas-story-card','test-p1','atlas-a-p1')")
                exec("INSERT INTO collection_proof(collection_id,proof_key,attempt_id) VALUES ('atlas-story-card','test-p2','atlas-b-p2')")

                val before = number("SELECT total_changes()")
                val atlas = projection.readAtlas()
                check(atlas.totalTrips == 2L && atlas.trips.map { it.tripId } == listOf("atlas-trip-b", "atlas-trip-a"))
                check(atlas.collectedRegions.single().regionId == "atlas-region" && !atlas.routeSamplesTruncated)
                check(atlas.paths.size == 9 && atlas.paths.sumOf { it.points.size } == 11)
                val a = projection.readTrip("atlas-trip-a")
                check(a.paths.map { it.points.size } == listOf(2, 1, 2, 1, 1, 1, 1, 1))
                check(a.paths.all { it.tripId == "atlas-trip-a" })
                val b = projection.readTrip("atlas-trip-b")
                check(b.paths.single().points.size == 1 && b.results?.header?.tripId == "atlas-trip-b")
                check(projection.readAtlas(1).let { it.totalTrips == 2L && it.tripOffset == 1 && it.trips.single().tripId == "atlas-trip-a" })
                val region = projection.readRegion("atlas-region")
                check(region.collected && region.stories.single().versionId == "test-story-v1")
                check(region.trips.single().tripId == "atlas-trip-a") // Proof, not earnedAt or story playback in B.
                check(!projection.readRegion("atlas-pending").collected)
                check(number("SELECT total_changes()") == before)
                mark("records_atlas_schema_paths_split_on_invalid_gap_sequence_route_clock_provider_and_trip")
                mark("records_atlas_proof_only_region_earned_and_complete_story_nested_with_trip_pagination")

                exec("UPDATE source_evidence SET allowed_uses_json=?", StrictJson.stringify(SyntheticStorageFixture.allowedUses(false, false, false)))
                exec("UPDATE region_revision SET verified_at=NULL WHERE region_revision_id='atlas-region-r1'")
                val revokedChanges = number("SELECT total_changes()")
                val revoked = projection.readRegion("atlas-region")
                check(revoked.collected && revoked.stories.single().versionId == "test-story-v1")
                val historical = projection.readTrip("atlas-trip-a")
                check(listOf(atlas, region, a, b, revoked, historical, atlas.paths.first(), atlas.paths.first().points.first())
                    .all { it.toString().endsWith("([redacted])") })
                check(number("SELECT total_changes()") == revokedChanges)
                check(number("SELECT count(*) FROM collection_record") == 3L)
                check(sql.query(SimpleSQLiteQuery("SELECT region_id FROM collection_record WHERE collection_type='region'"))
                    .use { it.count } == 2)
                mark("records_atlas_read_only_revoked_historical_ids_remain_without_titles_or_raw_conversation")
            }
            val shapes = RecordsGeometryCatalog.load(context)
            check(shapes.size == 261 && shapes.none { it.code in setOf("30140", "36110", "46110") })
            check(shapes.all { region -> region.rings.isNotEmpty() && region.rings.all { it.size >= 4 && it.first() == it.last() } })
            mark("records_atlas_packaged_261_public_display_shapes_exclude_three_quarantined_geometries")
        } finally { SyntheticStorageFixture.cleanup(context, name) }
    }
}
