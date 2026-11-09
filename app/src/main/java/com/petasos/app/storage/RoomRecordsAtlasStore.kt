package com.petasos.app.storage

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.presentation.records.*
import com.petasos.app.records.TripResultHeader
import com.petasos.app.records.TripRecordStatus
import java.util.concurrent.Callable

/** Read-only worker projection. Does not create cards, grant policy, load raw conversation or recover trips. */
class RoomRecordsAtlasStore internal constructor(private val database: StorageDatabase) {
    private val sql get() = database.openHelper.readableDatabase

    fun readAtlas(tripOffset: Int = 0): RecordsAtlasSnapshot {
        require(tripOffset >= 0)
        return transaction {
            val collected = mutableListOf<RecordsRegionProof>()
            query("""SELECT c.region_id,c.earned_at FROM collection_record c WHERE c.collection_type='region'
                AND EXISTS ($REGION_PROOF) ORDER BY c.earned_at DESC,c.region_id""").use {
                while (it.moveToNext()) collected += RecordsRegionProof(it.getString(0), it.getLong(1))
            }
            val trips = RoomTripResultsStore(database).listTrips(TRIP_PAGE_SIZE, tripOffset)
            val count = query("SELECT count(*) FROM trip").use { it.moveToFirst(); it.getLong(0) }
            val routes = readPaths(null)
            RecordsAtlasSnapshot(collected, trips, count, tripOffset, routes.first, routes.second)
        }
    }

    fun readRegion(regionId: String): RecordsRegionSnapshot {
        require(regionId.length in 1..256)
        return transaction {
            val collected = query("""SELECT 1 FROM collection_record c WHERE c.collection_type='region'
                AND c.region_id=? AND EXISTS ($REGION_PROOF) LIMIT 1""", regionId).use { it.moveToFirst() }
            // A child district may contain stories associated with its reviewed parent city. This is
            // a display association only: it does not create or imply a parent-city passage award.
            val storyRegion = DISPLAY_PARENT[regionId] ?: regionId
            val stories = mutableListOf<RecordsStory>()
            query("""SELECT c.story_version_id,c.earned_at FROM collection_record c JOIN story st ON st.story_id=c.story_id
                WHERE c.collection_type='story' AND st.region_id IN (?,?) AND EXISTS (
                    SELECT 1 FROM collection_proof cp JOIN playback_attempt a ON a.attempt_id=cp.attempt_id
                    JOIN paragraph p ON p.paragraph_id=a.paragraph_id WHERE cp.collection_id=c.collection_id
                    AND cp.point_id IS NULL AND a.kind='story' AND a.state='completed'
                    AND p.story_version_id=c.story_version_id AND cp.proof_key=p.paragraph_id)
                ORDER BY c.earned_at DESC,c.collection_id LIMIT 51""", regionId, storyRegion).use {
                while (it.moveToNext()) stories += RecordsStory(it.getString(0), it.getLong(1))
            }
            val trips = mutableListOf<TripResultHeader>()
            query("""SELECT DISTINCT t.trip_id,t.status,t.started_at,t.ended_at,t.last_observed_at,
                t.interruption_detected_at,t.end_reason,t.revision FROM trip t
                JOIN recording_segment g ON g.trip_id=t.trip_id JOIN route_point p ON p.segment_id=g.segment_id
                JOIN collection_proof cp ON cp.point_id=p.point_id JOIN collection_record c ON c.collection_id=cp.collection_id
                JOIN region_revision rr ON rr.region_revision_id=cp.region_revision_id
                WHERE c.collection_type='region' AND c.region_id=? AND cp.attempt_id IS NULL
                AND p.quality_status='valid' AND rr.region_id=c.region_id
                ORDER BY t.started_at DESC,t.trip_id DESC LIMIT 21""", regionId).use {
                while (it.moveToNext()) trips += header(it)
            }
            RecordsRegionSnapshot(regionId, collected, stories.take(50), trips.take(20), stories.size > 50, trips.size > 20)
        }
    }

    fun readTrip(tripId: String): RecordsTripSnapshot {
        require(tripId.length in 1..256)
        return transaction {
            val results = RoomTripResultsStore(database).readTrip(tripId)
            val routes = readPaths(tripId)
            RecordsTripSnapshot(results, routes.first, routes.second)
        }
    }

    private fun readPaths(tripId: String?): Pair<List<RecordedPath>, Boolean> {
        val samples = mutableListOf<RecordedMapSample>()
        // Keep invalid rows in the sample stream so a missing fix cannot become a connecting line.
        val filter = if (tripId == null) "" else "WHERE g.trip_id=?"
        val parameters = if (tripId == null) emptyArray() else arrayOf<Any?>(tripId)
        query("""SELECT g.trip_id,p.segment_id,p.sequence,p.clock_scope_id,p.received_elapsed_ms,
            p.observed_at,p.route_revision,p.quality_status,p.latitude,p.longitude,p.provider,p.provider_epoch
            FROM route_point p JOIN recording_segment g ON g.segment_id=p.segment_id $filter
            ORDER BY g.started_at DESC,g.segment_id DESC,p.sequence DESC LIMIT ${MAX_ROUTE_SAMPLES + 1}""", *parameters).use {
            while (it.moveToNext()) samples += RecordedMapSample(it.getString(0), it.getString(1), it.getLong(2),
                it.getString(3), it.getLong(4), it.longOrNull(5), it.getLong(6), it.getString(7) == "valid",
                if (it.isNull(8) || it.isNull(9)) null else MapCoordinate(it.getDouble(8), it.getDouble(9)),
                it.getString(10), if (it.isNull(11)) null else it.getString(11))
        }
        val truncated = samples.size > MAX_ROUTE_SAMPLES
        return RecordedPathMapper.map(samples.take(MAX_ROUTE_SAMPLES).asReversed()) to truncated
    }

    private fun header(row: Cursor) = TripResultHeader(row.getString(0), when (row.getString(1)) {
        "active" -> TripRecordStatus.ACTIVE; "interrupted" -> TripRecordStatus.INTERRUPTED
        "closed" -> TripRecordStatus.CLOSED; else -> error("Invalid saved trip state")
    }, row.getLong(2), row.longOrNull(3), row.longOrNull(4), row.longOrNull(5),
        if (row.isNull(6)) null else row.getString(6), row.getLong(7))
    private fun Cursor.longOrNull(index: Int) = if (isNull(index)) null else getLong(index)
    private fun query(statement: String, vararg values: Any?): Cursor = sql.query(SimpleSQLiteQuery(statement, values))
    private fun <T> transaction(block: () -> T): T = database.runInTransaction(Callable(block))

    companion object {
        const val TRIP_PAGE_SIZE = 20
        const val MAX_ROUTE_SAMPLES = 30_000
        private const val REGION_PROOF = """SELECT 1 FROM collection_proof cp JOIN route_point p ON p.point_id=cp.point_id
            JOIN region_revision rr ON rr.region_revision_id=cp.region_revision_id WHERE cp.collection_id=c.collection_id
            AND cp.attempt_id IS NULL AND p.quality_status='valid' AND rr.region_id=c.region_id"""
        // Exact five relationships pinned by assets/regions/reviewed-hierarchy-v1.json.
        private val DISPLAY_PARENT = mapOf("kr-sigungu-41461" to "kr-sigungu-41460", "kr-sigungu-41463" to "kr-sigungu-41460",
            "kr-sigungu-41465" to "kr-sigungu-41460", "kr-sigungu-44131" to "kr-sigungu-44130", "kr-sigungu-44133" to "kr-sigungu-44130")
    }
}
