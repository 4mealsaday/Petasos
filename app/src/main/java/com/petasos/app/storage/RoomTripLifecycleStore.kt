package com.petasos.app.storage

import androidx.sqlite.db.SimpleSQLiteQuery
import java.util.concurrent.Callable

/** Observed user end only; this does not claim that an interrupted historical segment ended now. */
class ClosedTrip internal constructor(val tripId: String, val endedAt: Long, val revision: Long) {
    override fun toString() = "ClosedTrip([redacted])"
}

/** Explicit trip end is distinct from media Stop and from a resumable session close. */
class RoomTripLifecycleStore internal constructor(
    private val database: StorageDatabase,
    private val wallTime: () -> Long,
) {
    private val sql get() = database.openHelper.writableDatabase

    /**
     * Live callers first await Runtime.close(true). Recovered callers supply the recovery candidate's
     * latest session and revision after TX-04, without inventing a new session or a stop ACK.
     */
    fun endTrip(tripId: String, expectedSessionId: String, expectedTripRevision: Long? = null): ClosedTrip = transaction {
        requireSafe(tripId.isNotBlank() && expectedSessionId.isNotBlank() && expectedTripRevision?.let { it >= 1 } != false)
        val trip = sql.query(SimpleSQLiteQuery("SELECT status,started_at,ended_at,end_reason,revision FROM trip WHERE trip_id=?", arrayOf<Any?>(tripId))).use {
            requireSafe(it.moveToFirst())
            Trip(it.getString(0), it.getLong(1), if (it.isNull(2)) null else it.getLong(2),
                if (it.isNull(3)) null else it.getString(3), it.getLong(4))
        }
        sql.query(SimpleSQLiteQuery("SELECT session_id,status,closed_at FROM session WHERE trip_id=? ORDER BY rowid DESC LIMIT 1", arrayOf<Any?>(tripId))).use {
            requireSafe(it.moveToFirst() && it.getString(0) == expectedSessionId)
            val status = it.getString(1)
            requireSafe(status in setOf("closed", "interrupted") && (status != "closed" || !it.isNull(2)))
        }
        requireSafe(number("SELECT count(*) FROM session WHERE trip_id=? AND status='active'", tripId) == 0L)
        requireSafe(number("SELECT count(*) FROM recording_segment WHERE trip_id=? AND status='active'", tripId) == 0L)
        requireSafe(number("SELECT count(*) FROM playback_attempt a JOIN session s ON s.session_id=a.session_id WHERE s.trip_id=? AND a.state IN ('prepared','playing')", tripId) == 0L)
        requireSafe(number("SELECT count(*) FROM conversation_turn t JOIN session s ON s.session_id=t.session_id WHERE s.trip_id=? AND (t.lifecycle='open' OR t.frozen<>1)", tripId) == 0L)
        if (trip.status == "closed") {
            requireSafe(trip.endedAt != null && trip.endReason == "user_end")
            return@transaction ClosedTrip(tripId, trip.endedAt!!, trip.revision)
        }
        requireSafe(trip.status in setOf("active", "interrupted") && trip.endedAt == null &&
            (expectedTripRevision == null || trip.revision == expectedTripRevision) && trip.revision < Long.MAX_VALUE)
        val now = wallTime()
        requireSafe(now >= 0 && now >= trip.startedAt)
        requireSafe(number("SELECT count(*) FROM session WHERE trip_id=? AND (started_at>? OR closed_at>?)", tripId, now, now) == 0L)
        requireSafe(number("SELECT count(*) FROM recording_segment WHERE trip_id=? AND (started_at>? OR ended_at>?)", tripId, now, now) == 0L)
        sql.execSQL("UPDATE generation_request SET status='cancelled',finished_at=?,error_code='TRIP_ENDED' WHERE task<>'summary' AND status IN ('pending','running') AND session_id IN (SELECT session_id FROM session WHERE trip_id=?)", arrayOf<Any?>(now, tripId))
        sql.execSQL("UPDATE playback_checkpoint SET play_intent=0 WHERE trip_id=?", arrayOf<Any?>(tripId))
        sql.execSQL("UPDATE trip SET status='closed',ended_at=?,end_reason='user_end',revision=revision+1 WHERE trip_id=?", arrayOf<Any?>(now, tripId))
        ClosedTrip(tripId, now, trip.revision + 1)
    }

    private class Trip(val status: String, val startedAt: Long, val endedAt: Long?, val endReason: String?, val revision: Long)
    private fun requireSafe(condition: Boolean) { if (!condition) throw StorageUnavailable("TRIP_END_STATE_UNAVAILABLE") }
    private fun number(statement: String, vararg args: Any?): Long = sql.query(SimpleSQLiteQuery(statement, args)).use {
        if (it.moveToFirst()) it.getLong(0) else 0
    }
    private fun <T> transaction(action: () -> T): T = try { database.runInTransaction(Callable { action() }) }
        catch (failure: StorageUnavailable) { throw failure }
        catch (_: Exception) { throw StorageUnavailable("TRIP_END_STORAGE_UNAVAILABLE") }
}
