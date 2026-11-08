package com.petasos.app.storage

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.petasos.app.generation.StrictJson
import com.petasos.app.location.*
import com.petasos.app.playback.PreparedStory
import com.petasos.app.planning.CandidateReason
import java.util.UUID
import java.util.concurrent.Callable

/** LOC-01/02 and COL-01. No schema extension, coordinate logging, or inferred production boundary. */
class RoomLocationStore internal constructor(
    private val database: StorageDatabase,
    private val boundary: RegionBoundaryLookup,
    private val wallTime: () -> Long,
    private val ownsDatabase: Boolean,
) : LocationRecordingStore, AutoCloseable {
    constructor(context: Context, boundary: RegionBoundaryLookup = RegionBoundaryLookup.NONE, databaseName: String = "petasos.db") : this(
        StorageDatabase.open(context, databaseName), boundary, System::currentTimeMillis, true,
    )

    private val sql: SupportSQLiteDatabase get() = database.openHelper.writableDatabase
    private val regionPolicies = RoomRegionPolicyStore(database, wallTime)

    /** Missing preference is not consent. Browsing/Media Play must not write this setting implicitly. */
    fun isExplicitlyEnabled(): Boolean = transaction {
        number("SELECT count(*) FROM preference WHERE scope_id='vehicle' AND location_enabled=1") == 1L
    }

    override fun openSegment(tripId: String, sessionId: String, segmentId: String) = transaction {
        requireSafe(tripId.isNotBlank() && sessionId.isNotBlank() && segmentId.isNotBlank())
        requireSafe(number("SELECT count(*) FROM session s JOIN trip t ON t.trip_id=s.trip_id WHERE s.session_id=? AND s.trip_id=? AND s.status='active' AND t.status='active'", sessionId, tripId) == 1L)
        exec("INSERT INTO recording_segment(segment_id,trip_id,session_id,started_at,status) VALUES (?,?,?,?,'active')", segmentId, tripId, sessionId, wallTime())
    }

    override fun append(segmentId: String, points: List<RecordedLocation>): LocationCommit = transaction {
        requireSafe(points.size in 1..LocationRecorder.MAX_BATCH_POINTS)
        val session = query("SELECT g.trip_id,s.route_revision FROM recording_segment g JOIN session s ON s.session_id=g.session_id WHERE g.segment_id=? AND g.status='active' AND s.status='active'", segmentId).use {
            requireSafe(it.moveToFirst()); it.getString(0) to it.getLong(1)
        }
        var sequence = number("SELECT COALESCE(MAX(sequence)+1,0) FROM route_point WHERE segment_id=?", segmentId)
        var inserted = 0
        var duplicate = 0
        var currentRegion: ConfirmedRegion? = null
        val acquired = mutableSetOf<String>()
        points.forEach { point ->
            requireSafe(point.routeRevision in 0..session.second && point.receivedAtElapsedMs >= 0 && point.receivedAtEpochMs >= 0 && point.clockScopeId.isNotBlank())
            requireSafe(point.observedAtEpochMs == null || point.observedAtEpochMs >= 0)
            requireSafe((point.providerEpoch == null) == (point.sourceEventId == null))
            requireSafe(point.quality != LocationQuality.VALID || (point.observedAtEpochMs != null && point.observedAtEpochMs <= point.receivedAtEpochMs &&
                point.latitude != null && point.longitude != null && point.accuracyMeters != null && point.adapterVersion != null && point.sourceTimeUnit != "unknown"))
            val values = values(segmentId, sequence, point)
            if (samePointAlreadyStored(point.pointId, values)) return@forEach
            if (point.sourceEventId != null && number("SELECT count(*) FROM route_point WHERE provider=? AND provider_epoch=? AND source_event_id=?",
                    point.provider, point.providerEpoch, point.sourceEventId) > 0) {
                duplicate++; return@forEach
            }
            exec("INSERT INTO route_point(${values.keys.joinToString(",")}) VALUES (${values.keys.joinToString(",") { "?" }})", *values.values.toTypedArray())
            inserted++; sequence++
            point.observedAtEpochMs?.takeIf { point.quality != LocationQuality.INVALID && it <= point.receivedAtEpochMs }?.let { observed ->
                exec("UPDATE recording_segment SET last_observed_at=CASE WHEN last_observed_at IS NULL OR last_observed_at<? THEN ? ELSE last_observed_at END WHERE segment_id=?", observed, observed, segmentId)
                exec("UPDATE trip SET last_observed_at=CASE WHEN last_observed_at IS NULL OR last_observed_at<? THEN ? ELSE last_observed_at END WHERE trip_id=?", observed, observed, session.first)
            }
            currentRegion = confirmAndCollect(segmentId, sequence - 1, point, acquired)
        }
        LocationCommit(inserted, duplicate, acquired.toSet(), currentRegion?.regionId, currentRegion)
    }

    override fun finishSegment(segmentId: String, reason: String, interrupted: Boolean) = transaction {
        requireSafe(reason in setOf("receipt_gap", "permission_denied", "no_location", "location_disconnected", "unsupported", "suspend", "explicit_stop"))
        if (interrupted) exec("UPDATE recording_segment SET status='interrupted',interruption_detected_at=?,end_reason=? WHERE segment_id=? AND status='active'", wallTime(), reason, segmentId)
        else exec("UPDATE recording_segment SET status='closed',ended_at=?,end_reason=? WHERE segment_id=? AND status='active'", wallTime(), reason, segmentId)
    }

    /** Metadata lookup only; this does not prove current presence or authorize automatic playback. */
    internal fun currentRegionStories(regionId: String): List<PreparedStory> =
        RoomAutomaticStoryStore(database, wallTime, ownsDatabase = false,
            hierarchy = (boundary as? HierarchicalRegionBoundaryLookup)?.hierarchy ?: ReviewedRegionHierarchy.NONE).candidates(regionId)
            .filter { it.reason == CandidateReason.CURRENT_REGION }.map { it.story }

    /** Worker/network evidence read. The SDK/main thread must use the separately captured atomic permit. */
    fun isConfirmedRegionCurrent(confirmation: ConfirmedRegion): Boolean = transaction {
        val current = regionPolicies.reviewedPolicy(confirmation.regionRevisionId)
        current != null && current.regionId == confirmation.regionId &&
            current.regionRevisionId == confirmation.regionRevisionId && policySelectionKey(current.eligibilityKey) == confirmation.policySourceKey &&
            boundary.isSelectionCurrent(confirmation.regionRevisionId)
    }

    private fun confirmAndCollect(segment: String, sequence: Long, point: RecordedLocation, acquired: MutableSet<String>): ConfirmedRegion? {
        if (point.quality != LocationQuality.VALID || point.latitude == null || point.longitude == null) return null
        val matches = try { boundary.containing(point.latitude, point.longitude) } catch (_: Exception) { return null }
        val match = matches.singleOrNull() ?: return null
        val region = regionPolicies.reviewedPolicy(match.regionRevisionId) ?: return null
        if (match.datasetVersion != region.policy.datasetVersion || match.coordinateReferenceSystem != region.policy.coordinateReferenceSystem) return null
        val recent = mutableListOf<RegionPoint>()
        query("SELECT * FROM route_point WHERE segment_id=? AND sequence<=? ORDER BY sequence DESC LIMIT ?", segment, sequence, region.policy.consecutiveSamples).use {
            while (it.moveToNext()) recent += RegionPoint(it.long("sequence"), readPoint(it))
        }
        recent.reverse()
        if (!RegionConfirmation.confirmed(region.regionRevisionId, region.policy, recent, boundary)) return null
        if (number("SELECT count(*) FROM collection_record WHERE collection_type='region' AND region_id=?", region.regionId) == 0L) {
            val collection = UUID.randomUUID().toString()
            exec("INSERT INTO collection_record(collection_id,collection_type,region_id,rule_version,earned_at,created_at) VALUES (?,'region',?,?,?,?)",
                collection, region.regionId, if (boundary.selectionKey == null) "region-confirmation-v1" else "region-confirmation-hierarchy-v1", point.observedAtEpochMs, wallTime())
            recent.forEach { proof ->
                exec("INSERT INTO collection_proof(collection_id,proof_key,point_id,region_revision_id) VALUES (?,?,?,?)", collection,
                    "point:${proof.location.pointId}", proof.location.pointId, region.regionRevisionId)
            }
            acquired += region.regionId
        }
        return ConfirmedRegion(region.regionId, region.regionRevisionId, policySelectionKey(region.eligibilityKey), point.pointId)
    }

    private fun policySelectionKey(policyKey: String): String = boundary.selectionKey?.let {
        com.petasos.app.content.ContentBundleCodec.sha256(StrictJson.encode(listOf(policyKey, it)))
    } ?: policyKey

    private fun values(segment: String, sequence: Long, p: RecordedLocation): Map<String, Any?> = linkedMapOf(
        "point_id" to p.pointId, "segment_id" to segment, "sequence" to sequence, "provider" to p.provider,
        "provider_epoch" to p.providerEpoch, "source_event_id" to p.sourceEventId, "raw_timestamp" to p.rawTimestamp,
        "source_time_unit" to p.sourceTimeUnit, "observed_at" to p.observedAtEpochMs, "received_at" to p.receivedAtEpochMs,
        "received_elapsed_ms" to p.receivedAtElapsedMs, "clock_scope_id" to p.clockScopeId, "latitude" to p.latitude,
        "longitude" to p.longitude, "raw_coordinates_json" to p.rawCoordinatesJson, "accuracy_meters" to p.accuracyMeters,
        "provider_confidence_json" to p.providerConfidenceJson, "quality_status" to p.quality.name.lowercase(), "route_revision" to p.routeRevision,
    )

    private fun samePointAlreadyStored(pointId: String, values: Map<String, Any?>): Boolean = query("SELECT * FROM route_point WHERE point_id=?", pointId).use { row ->
        if (!row.moveToFirst()) return@use false
        values.filterKeys { it != "sequence" }.forEach { (column, value) ->
            val index = row.getColumnIndexOrThrow(column)
            requireSafe(when (value) {
                null -> row.isNull(index)
                is String -> row.getString(index) == value
                is Long -> !row.isNull(index) && row.getLong(index) == value
                is Double -> !row.isNull(index) && row.getDouble(index) == value
                else -> false
            })
        }
        true
    }

    private fun readPoint(c: Cursor): RecordedLocation {
        val confidence = c.text("provider_confidence_json") ?: "{}"
        val metadata = try { StrictJson.parse(confidence.toByteArray()) as? Map<*, *> } catch (_: Exception) { null }
        val adapter = metadata?.get("adapterVersion") as? String
        val measuredAge = metadata?.let { AndroidLocationMeaningAdapter.restoredAgeMs(it, c.long("received_elapsed_ms")) }
        val storedQuality = LocationQuality.valueOf(c.text("quality_status")!!.uppercase())
        // Historical region confirmation reads these rows without requesting a new live deadline.
        // A corrupt Android evidence envelope must therefore lose VALID quality at this boundary.
        val quality = if (adapter == AndroidLocationMeaningAdapter.VERSION && measuredAge == null)
            LocationQuality.UNKNOWN else storedQuality
        return RecordedLocation(c.text("point_id")!!, c.text("provider")!!, c.text("provider_epoch"), c.text("source_event_id"),
            c.text("raw_timestamp") ?: "", c.text("source_time_unit")!!, c.nullLong("observed_at"), c.long("received_at"),
            c.long("received_elapsed_ms"), c.text("clock_scope_id")!!, c.nullDouble("latitude"), c.nullDouble("longitude"),
            c.text("raw_coordinates_json")!!, c.nullDouble("accuracy_meters"), confidence,
            quality, adapter, c.long("route_revision"), measuredAge)
    }

    private fun Cursor.text(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.nullLong(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.nullDouble(column: String): Double? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getDouble(it) }
    private fun query(statement: String, vararg args: Any?): Cursor = sql.query(SimpleSQLiteQuery(statement, args))
    private fun number(statement: String, vararg args: Any?): Long = query(statement, *args).use { if (it.moveToFirst()) it.getLong(0) else 0 }
    private fun exec(statement: String, vararg args: Any?) { sql.execSQL(statement, args) }
    private fun requireSafe(condition: Boolean) { if (!condition) throw StorageUnavailable("LOCATION_STATE_UNAVAILABLE") }
    private fun <T> transaction(action: () -> T): T = try { database.runInTransaction(Callable { action() }) }
        catch (failure: StorageUnavailable) { throw failure }
        catch (_: Exception) { throw StorageUnavailable("LOCATION_STORAGE_UNAVAILABLE") }
    override fun close() { if (ownsDatabase) database.close() }
}
