package com.petasos.app.diagnostics

import android.app.Instrumentation
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.sqlite.db.SimpleSQLiteQuery
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import com.petasos.app.platform.automotive.CarPolicySource
import com.petasos.app.platform.automotive.VehiclePowerPolicy
import com.petasos.app.platform.automotive.VehicleUxPolicy
import com.petasos.app.platform.media.PetasosMediaService
import com.petasos.app.playback.AdapterPhase
import com.petasos.app.records.TripRecordStatus
import com.petasos.app.records.TripResultsSnapshot
import com.petasos.app.storage.ClosedTrip
import com.petasos.app.storage.StorageDatabase
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Explicit public listening and trip-end test through the real product service. The resulting
 * history is retained. No microphone, location opt-in, model request, fixture install or DB cleanup.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object ProductTripLifecycleProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val deadline = SystemClock.elapsedRealtime() + 80_000
        val workDeadline = deadline - 15_000
        val main = Handler(Looper.getMainLooper())
        val active = AtomicBoolean(true)
        val ux = AtomicReference(VehicleUxPolicy.unavailable())
        val power = AtomicReference(VehiclePowerPolicy.unavailable())
        var policy: CarPolicySource? = null
        var connection: ListenableFuture<MediaBrowser>? = null
        var browser: MediaBrowser? = null
        val host = AtomicReference<PetasosSessionHost?>(null)
        val cleanupTrip = AtomicReference<String?>(null)
        var playbackIssued = false
        var failure: String? = null

        fun remaining(until: Long): Long = (until - SystemClock.elapsedRealtime()).also {
            if (it <= 0) throw ProbeFailure("APP_TRIP_DEADLINE_EXCEEDED")
        }
        fun <T> await(future: ListenableFuture<T>, code: String, limitMs: Long = 10_000): T = try {
            future.get(minOf(remaining(workDeadline), limitMs), TimeUnit.MILLISECONDS)
        } catch (error: ProbeFailure) { throw error }
        catch (_: Exception) { throw ProbeFailure(code) }
        fun <T> onMain(code: String, action: () -> T): T {
            val result = SettableFuture.create<T>()
            main.post {
                if (!active.get()) { result.setException(ProbeFailure("APP_TRIP_CANCELLED")); return@post }
                try { result.set(action()) } catch (_: Exception) { result.setException(ProbeFailure(code)) }
            }
            return await(result, code)
        }
        fun requirePolicy() {
            if (!ux.get().available) throw ProbeFailure("APP_TRIP_UX_UNAVAILABLE")
            if (!ux.get().permitsParkedPreview) throw ProbeFailure("APP_TRIP_PARKED_UI_UNAVAILABLE")
            if (!power.get().available) throw ProbeFailure("APP_TRIP_POWER_UNAVAILABLE")
            if (power.get().audioBlocked) throw ProbeFailure("APP_TRIP_AUDIO_BLOCKED")
        }
        fun waitUntil(code: String, limitMs: Long, predicate: () -> Boolean) {
            val until = minOf(workDeadline, SystemClock.elapsedRealtime() + limitMs)
            while (!predicate()) {
                if (SystemClock.elapsedRealtime() >= until) throw ProbeFailure(code)
                SystemClock.sleep(minOf(50, remaining(until)))
            }
        }

        if (!context.getDatabasePath("petasos.db").isFile) throw ProbeFailure("APP_TRIP_INSTALLED_DATABASE_REQUIRED")
        val database = StorageDatabase.open(context, "petasos.db")
        try {
            val sql = database.openHelper.readableDatabase
            fun count(statement: String, vararg args: Any?): Long = sql.query(SimpleSQLiteQuery(statement, args)).use {
                if (!it.moveToFirst()) throw ProbeFailure("APP_TRIP_STORAGE_READ_FAILED")
                it.getLong(0)
            }
            fun baseline() = listOf("trip", "session", "recording_segment", "route_point", "playback_attempt",
                "conversation_turn", "generation_request", "preference").map { count("SELECT count(*) FROM $it") }
            fun preferences(): List<String?> = sql.query("SELECT keywords_json,voice_json,story_frequency_json,location_enabled,version,updated_at FROM preference WHERE scope_id='vehicle'").use {
                if (!it.moveToFirst()) emptyList() else (0..5).map { index -> if (it.isNull(index)) null else it.getString(index) }
            }
            fun requireLocationOff() {
                if (count("SELECT count(*) FROM preference WHERE scope_id='vehicle' AND location_enabled=1") != 0L)
                    throw ProbeFailure("APP_TRIP_LOCATION_RECORDING_ENABLED")
            }
            requireLocationOff()
            if (count("SELECT count(*) FROM conversation_turn WHERE summary_state IN ('pending','running','retry_wait')") != 0L)
                throw ProbeFailure("APP_TRIP_PENDING_BACKGROUND_SUMMARY")
            onMain("APP_TRIP_EXISTING_RUNTIME") { check(PetasosMediaService.currentHost?.snapshot() == null) }
            val before = baseline()
            val preferenceBefore = preferences()
            val existingSessions = buildSet {
                sql.query("SELECT session_id FROM session").use { while (it.moveToNext()) add(it.getString(0)) }
            }
            onMain("APP_TRIP_POLICY_CONNECT_FAILED") {
                policy = CarPolicySource(context, onUx = { value ->
                    ux.set(value)
                    host.get()?.setTalkUiVisible(value.permitsParkedPreview)
                }, onPower = power::set).also { it.start() }
            }
            waitUntil("APP_TRIP_POLICY_UNAVAILABLE", 10_000) { ux.get().available && power.get().available }
            requirePolicy()
            mark("app_trip_actual_parked_ux_and_audio_power_policy_available")

            connection = onMain("APP_TRIP_BROWSER_CONNECT_FAILED") {
                MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PetasosMediaService::class.java))).buildAsync()
            }
            browser = await(connection!!, "APP_TRIP_BROWSER_UNAVAILABLE")
            val connected = browser!!
            val live = onMain("APP_TRIP_HOST_UNAVAILABLE") {
                requirePolicy()
                checkNotNull(PetasosMediaService.currentHost).also { host.set(it); it.setTalkUiVisible(true) }
            }
            val settings = await(live.readPreferences(), "APP_TRIP_SETTINGS_UNAVAILABLE")
            if (settings.locationEnabled) throw ProbeFailure("APP_TRIP_LOCATION_RECORDING_ENABLED")
            val oldTrips = await(live.listTrips(), "APP_TRIP_RESULTS_UNAVAILABLE")
            oldTrips.firstOrNull()?.let { listed ->
                val detail = await(live.readTrip(listed.tripId), "APP_TRIP_RESULTS_UNAVAILABLE")
                if (detail?.header != listed) throw ProbeFailure("APP_TRIP_RESULT_HEADER_MISMATCH")
            }
            val entries = await(onMain("APP_TRIP_CATALOG_REQUEST_FAILED") {
                connected.getChildren(PetasosMediaService.ROOT_ID, 0, 100, null)
            }, "APP_TRIP_CATALOG_UNAVAILABLE").value ?: throw ProbeFailure("APP_TRIP_CATALOG_UNAVAILABLE")
            val selected = entries.firstOrNull { item ->
                item.localConfiguration == null && item.mediaMetadata.isPlayable == true && count("""
                    SELECT count(*) FROM paragraph p JOIN paragraph_fact pf ON pf.paragraph_id=p.paragraph_id
                    JOIN fact_source fs ON fs.fact_revision_id=pf.fact_revision_id
                    JOIN source_evidence s ON s.source_revision_id=fs.source_revision_id
                    WHERE p.story_version_id=? AND s.review_status='verified' AND s.provider NOT LIKE 'synthetic%'
                    AND s.source_url LIKE 'https://%' AND s.source_url NOT LIKE 'https://example.invalid/%'
                """.trimIndent(), item.mediaId) > 0
            } ?: throw ProbeFailure("APP_TRIP_REVIEWED_PUBLIC_STORY_REQUIRED")
            onMain("APP_TRIP_PREPARE_UNAVAILABLE") {
                requirePolicy()
                check(connected.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM) && connected.isCommandAvailable(Player.COMMAND_PREPARE) &&
                    connected.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) && connected.isCommandAvailable(Player.COMMAND_STOP))
                connected.setMediaItem(selected)
                connected.prepare()
            }
            waitUntil("APP_TRIP_SELECTION_NOT_APPLIED", 5_000) { live.selectedVersionId() == selected.mediaId }
            onMain("APP_TRIP_READ_CREATED_RUNTIME") { check(live.snapshot() == null && !live.isEndingTrip()) }
            if (baseline() != before || preferences() != preferenceBefore) throw ProbeFailure("APP_TRIP_READ_MUTATED_RECORDS")
            mark("app_trip_browse_prepare_settings_and_results_do_not_create_session_or_change_preferences")

            fun checkRuntime(sessionId: String) {
                requirePolicy()
                val snapshot = live.snapshot() ?: throw ProbeFailure("APP_TRIP_RUNTIME_LOST")
                if (snapshot.sessionId != sessionId || snapshot.closed) throw ProbeFailure("APP_TRIP_SESSION_CHANGED")
                if (snapshot.blockers.isNotEmpty()) throw ProbeFailure("APP_TRIP_RUNTIME_BLOCKED")
                if (snapshot.outputPhase == AdapterPhase.UNAVAILABLE) throw ProbeFailure("APP_TRIP_TTS_UNAVAILABLE")
            }
            fun startedCount(sessionId: String) = count("""SELECT count(*) FROM playback_attempt a JOIN paragraph p ON p.paragraph_id=a.paragraph_id
                WHERE a.session_id=? AND a.kind='story' AND p.story_version_id=? AND a.started_at IS NOT NULL AND a.started_elapsed_ms IS NOT NULL""",
                sessionId, selected.mediaId)
            fun musicStartedCount(sessionId: String) = count("""SELECT count(*) FROM playback_attempt
                WHERE session_id=? AND kind='music' AND started_at IS NOT NULL AND started_elapsed_ms IS NOT NULL""", sessionId)
            fun noUnexpectedInput(sessionId: String) {
                if (count("SELECT count(*) FROM recording_segment WHERE session_id=?", sessionId) != 0L ||
                    count("SELECT count(*) FROM conversation_turn WHERE session_id=?", sessionId) != 0L ||
                    count("SELECT count(*) FROM generation_request WHERE session_id=?", sessionId) != 0L)
                    throw ProbeFailure("APP_TRIP_UNEXPECTED_LOCATION_OR_CONVERSATION")
            }
            fun assertResults(tripId: String, status: TripRecordStatus): TripResultsSnapshot {
                val result = await(live.readTrip(tripId), "APP_TRIP_RESULTS_UNAVAILABLE") ?: throw ProbeFailure("APP_TRIP_RESULTS_MISSING")
                if (result.header.tripId != tripId || result.header.status != status ||
                    result.header.revision != count("SELECT revision FROM trip WHERE trip_id=?", tripId) ||
                    result.sessions.active != count("SELECT count(*) FROM session WHERE trip_id=? AND status='active'", tripId) ||
                    result.sessions.interrupted != count("SELECT count(*) FROM session WHERE trip_id=? AND status='interrupted'", tripId) ||
                    result.sessions.closed != count("SELECT count(*) FROM session WHERE trip_id=? AND status='closed'", tripId) ||
                    result.playback.total != count("SELECT count(*) FROM playback_attempt a JOIN session s ON s.session_id=a.session_id WHERE s.trip_id=?", tripId))
                    throw ProbeFailure("APP_TRIP_RESULTS_COUNTS_MISMATCH")
                return result
            }
            fun assertClosed(tripId: String, sessionId: String, closed: ClosedTrip) {
                if (closed.tripId != tripId || count("SELECT count(*) FROM trip WHERE trip_id=? AND status='closed' AND end_reason='user_end' AND ended_at=? AND revision=?",
                        tripId, closed.endedAt, closed.revision) != 1L ||
                    count("SELECT count(*) FROM session WHERE session_id=? AND trip_id=? AND status='closed' AND closed_at IS NOT NULL", sessionId, tripId) != 1L ||
                    count("SELECT count(*) FROM session WHERE trip_id=? AND status='active'", tripId) != 0L ||
                    count("SELECT count(*) FROM recording_segment WHERE trip_id=? AND status='active'", tripId) != 0L ||
                    count("SELECT count(*) FROM playback_attempt a JOIN session s ON s.session_id=a.session_id WHERE s.trip_id=? AND a.state IN ('prepared','playing')", tripId) != 0L ||
                    count("SELECT count(*) FROM playback_checkpoint WHERE trip_id=? AND play_intent<>0", tripId) != 0L)
                    throw ProbeFailure("APP_TRIP_END_NOT_DURABLE")
                onMain("APP_TRIP_END_HOST_NOT_RELEASED") { check(live.snapshot() == null && !live.isEndingTrip()) }
                val result = assertResults(tripId, TripRecordStatus.CLOSED)
                if (result.header.endedAt != closed.endedAt || result.header.endReasonCode != "user_end") throw ProbeFailure("APP_TRIP_END_RESULT_MISMATCH")
                noUnexpectedInput(sessionId)
            }

            requireLocationOff()
            playbackIssued = true
            onMain("APP_TRIP_FIRST_PLAY_FAILED") { requirePolicy(); connected.play() }
            waitUntil("APP_TRIP_FIRST_SESSION_MISSING", 8_000) { live.snapshot() != null }
            val first = live.snapshot() ?: throw ProbeFailure("APP_TRIP_FIRST_SESSION_MISSING")
            if (first.sessionId in existingSessions) throw ProbeFailure("APP_TRIP_OLD_SESSION_REUSED")
            cleanupTrip.set(first.tripId)
            waitUntil("APP_TRIP_FIRST_HOST_POLICY_NOT_READY", 8_000) {
                requirePolicy()
                live.snapshot()?.let { it.sessionId == first.sessionId && it.playIntent && it.blockers.isEmpty() } == true
            }
            waitUntil("APP_TRIP_FIRST_TTS_NOT_STARTED", 15_000) { checkRuntime(first.sessionId); startedCount(first.sessionId) > 0 }
            mark("app_trip_first_product_session_has_actual_public_tts_started")

            val endAndRacedPlay = onMain("APP_TRIP_END_REQUEST_FAILED") {
                requirePolicy()
                val ending = live.endTrip(first.tripId)
                check(live.isEndingTrip())
                // Same-process ingress is deterministic; the normal controller Play path uses this port.
                ending to live.play()
            }
            val firstClosed = await(endAndRacedPlay.first, "APP_TRIP_END_UNCONFIRMED", 12_000)
            // Rejection and completed no-op are both valid. A new runtime is never valid here.
            try { endAndRacedPlay.second.get(minOf(remaining(workDeadline), 5_000), TimeUnit.MILLISECONDS) }
            catch (_: java.util.concurrent.ExecutionException) { }
            await(live.readPreferences(), "APP_TRIP_END_QUEUE_BARRIER_FAILED")
            assertClosed(first.tripId, first.sessionId, firstClosed)
            cleanupTrip.set(null)
            if (count("SELECT count(*) FROM session") != before[1] + 1) throw ProbeFailure("APP_TRIP_END_RACED_PLAY_CREATED_SESSION")
            mark("app_trip_end_awaits_actual_stop_commit_closes_session_and_rejects_play_during_end")

            requireLocationOff()
            onMain("APP_TRIP_SECOND_PLAY_FAILED") { requirePolicy(); connected.play() }
            waitUntil("APP_TRIP_SECOND_SESSION_MISSING", 8_000) { live.snapshot() != null }
            val second = live.snapshot() ?: throw ProbeFailure("APP_TRIP_SECOND_SESSION_MISSING")
            if (second.tripId == first.tripId || second.sessionId == first.sessionId || second.sessionId in existingSessions)
                throw ProbeFailure("APP_TRIP_MANUAL_PLAY_REUSED_ENDED_TRIP")
            cleanupTrip.set(second.tripId)
            waitUntil("APP_TRIP_SECOND_HOST_POLICY_NOT_READY", 8_000) {
                requirePolicy()
                live.snapshot()?.let { it.sessionId == second.sessionId && it.playIntent && it.blockers.isEmpty() } == true
            }
            waitUntil("APP_TRIP_SECOND_AUTOMATIC_MUSIC_NOT_STARTED", 15_000) {
                checkRuntime(second.sessionId); musicStartedCount(second.sessionId) > 0
            }
            if (startedCount(second.sessionId) != 0L) throw ProbeFailure("APP_TRIP_RETAINED_STORY_RESELECTED")
            if (PetasosMediaService.currentHost !== live) throw ProbeFailure("APP_TRIP_HOST_WAS_REPLACED")
            mark("app_trip_next_manual_browser_play_starts_new_trip_with_automatic_music_not_retained_story")

            onMain("APP_TRIP_STOP_FAILED") { connected.stop() }
            waitUntil("APP_TRIP_STOP_NOT_QUIESCENT", 8_000) {
                requirePolicy()
                val snapshot = live.snapshot()
                snapshot != null && snapshot.sessionId == second.sessionId && !snapshot.closed && !snapshot.playIntent && !snapshot.interactionIntent &&
                    snapshot.outputPhase == AdapterPhase.READY && snapshot.pendingPlaybackCommits == 0 &&
                    count("SELECT count(*) FROM playback_attempt WHERE session_id=? AND state IN ('prepared','playing')", second.sessionId) == 0L
            }
            if (count("SELECT count(*) FROM trip WHERE trip_id=? AND status='active' AND ended_at IS NULL", second.tripId) != 1L ||
                count("SELECT count(*) FROM session WHERE session_id=? AND status='active' AND closed_at IS NULL", second.sessionId) != 1L ||
                count("SELECT count(*) FROM playback_checkpoint WHERE session_id=? AND play_intent=0", second.sessionId) != 1L)
                throw ProbeFailure("APP_TRIP_MEDIA_STOP_ENDED_TRIP")
            assertResults(second.tripId, TripRecordStatus.ACTIVE)
            noUnexpectedInput(second.sessionId)
            mark("app_trip_standard_media_stop_keeps_trip_and_session_open_with_false_intent")

            val finalEnd = onMain("APP_TRIP_FINAL_END_REQUEST_FAILED") { requirePolicy(); live.endTrip(second.tripId) }
            val secondClosed = await(finalEnd, "APP_TRIP_FINAL_END_UNCONFIRMED", 12_000)
            assertClosed(second.tripId, second.sessionId, secondClosed)
            cleanupTrip.set(null)
            if (preferences() != preferenceBefore || startedCount(first.sessionId) == 0L || musicStartedCount(second.sessionId) == 0L)
                throw ProbeFailure("APP_TRIP_EXISTING_SETTINGS_OR_NEW_HISTORY_CHANGED")
            mark("app_trip_final_explicit_end_preserves_listening_records_and_unchanged_preferences")
        } catch (error: ProbeFailure) { failure = error.code }
        catch (_: Exception) { failure = "APP_TRIP_OPERATION_FAILED" }
        finally {
            active.set(false)
            val cleaned = SettableFuture.create<Void?>()
            main.post {
                try {
                    val live = host.get()
                    if (playbackIssued) {
                        browser?.stop() ?: live?.stop()
                        // Do not mark an unconfirmed close successful or invent an end when UI policy is lost.
                        val tripId = cleanupTrip.get()
                        if (live != null && tripId != null && tripId == live.endableTripId() && ux.get().permitsParkedPreview) {
                            val ending = live.endTrip(tripId)
                            ending.addListener({
                                try { ending.get(); cleaned.set(null) }
                                catch (_: Exception) { cleaned.setException(ProbeFailure("APP_TRIP_CLEANUP_FAILED")) }
                            }, { task -> task.run() })
                        } else cleaned.set(null)
                    } else cleaned.set(null)
                } catch (_: Exception) { cleaned.setException(ProbeFailure("APP_TRIP_CLEANUP_FAILED")) }
            }
            try { cleaned.get((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS) }
            catch (_: Exception) { if (failure == null) failure = "APP_TRIP_CLEANUP_FAILED" }
            val released = SettableFuture.create<Void?>()
            main.post {
                try {
                    host.get()?.setTalkUiVisible(false)
                    policy?.close()
                    connection?.let(MediaController::releaseFuture)
                    released.set(null)
                } catch (_: Exception) { released.setException(ProbeFailure("APP_TRIP_CLEANUP_FAILED")) }
            }
            try { released.get((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS) }
            catch (_: Exception) { if (failure == null) failure = "APP_TRIP_CLEANUP_FAILED" }
            database.close()
        }
        failure?.let { mark(it.lowercase()); throw ProbeFailure(it) }
    }

    private class ProbeFailure(val code: String) : IllegalStateException(code)
}
