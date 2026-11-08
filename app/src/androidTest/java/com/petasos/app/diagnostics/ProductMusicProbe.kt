package com.petasos.app.diagnostics

import android.app.Instrumentation
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
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
import com.petasos.app.platform.media.PetasosMediaService
import com.petasos.app.playback.PlaybackMode
import com.petasos.app.playback.AdapterPhase
import com.petasos.app.playback.PlaybackBlocker
import com.petasos.app.storage.StorageDatabase
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Product public story -> reviewed bundled music, actual Media3, standard Stop/resume and natural end.
 * No fixture installation, microphone, generation request, location opt-in, or production-data cleanup.
 * The normal listening records remain in petasos.db after Stop.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object ProductMusicProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val deadline = SystemClock.elapsedRealtime() + 165_000
        val workDeadline = deadline - 5_000 // Reserve time for the standard Stop and controller cleanup.
        val main = Handler(Looper.getMainLooper())
        val active = AtomicBoolean(true)
        val power = AtomicReference(VehiclePowerPolicy.unavailable())
        var policy: CarPolicySource? = null
        var connection: ListenableFuture<MediaBrowser>? = null
        var browser: MediaBrowser? = null
        var host: PetasosSessionHost? = null
        var playIssued = false
        var failure: String? = null

        fun remaining(until: Long): Long = (until - SystemClock.elapsedRealtime()).also {
            if (it <= 0) throw ProbeFailure("APP_MUSIC_DEADLINE_EXCEEDED")
        }
        fun <T> await(future: ListenableFuture<T>, code: String, limitMs: Long = 10_000): T {
            return try { future.get(minOf(remaining(workDeadline), limitMs), TimeUnit.MILLISECONDS) }
            catch (error: ProbeFailure) { throw error }
            catch (_: Exception) { throw ProbeFailure(code) }
        }
        fun <T> onMain(code: String, action: () -> T): T {
            val result = SettableFuture.create<T>()
            main.post {
                if (!active.get()) { result.setException(ProbeFailure("APP_MUSIC_CANCELLED")); return@post }
                try { result.set(action()) } catch (_: Exception) { result.setException(ProbeFailure(code)) }
            }
            return await(result, code)
        }
        fun waitUntil(code: String, limitMs: Long, predicate: () -> Boolean) {
            val until = minOf(workDeadline, SystemClock.elapsedRealtime() + limitMs)
            while (!predicate()) {
                if (SystemClock.elapsedRealtime() >= until) throw ProbeFailure(code)
                SystemClock.sleep(minOf(50, remaining(until)))
            }
        }
        if (!context.getDatabasePath("petasos.db").isFile) throw ProbeFailure("APP_MUSIC_INSTALLED_DATABASE_REQUIRED")
        val database = StorageDatabase.open(context, "petasos.db")
        try {
            val sql = database.openHelper.readableDatabase
            fun count(statement: String, vararg args: Any?): Long = sql.query(SimpleSQLiteQuery(statement, args)).use {
                if (!it.moveToFirst()) throw ProbeFailure("APP_MUSIC_STORAGE_READ_FAILED")
                it.getLong(0)
            }
            fun requireLocationOff() {
                if (count("SELECT count(*) FROM preference WHERE scope_id='vehicle' AND location_enabled=1") != 0L) {
                    throw ProbeFailure("APP_MUSIC_LOCATION_RECORDING_ENABLED")
                }
            }
            fun baseline() = listOf("trip", "session", "recording_segment", "playback_attempt").map {
                count("SELECT count(*) FROM $it")
            }
            requireLocationOff()
            onMain("APP_MUSIC_EXISTING_RUNTIME") {
                check(PetasosMediaService.currentHost?.snapshot() == null)
            }
            val before = baseline()
            val previousSessions = buildSet {
                sql.query("SELECT session_id FROM session").use { while (it.moveToNext()) add(it.getString(0)) }
            }
            onMain("APP_MUSIC_POWER_CONNECT_FAILED") {
                policy = CarPolicySource(context, onUx = {}, onPower = power::set).also { it.start() }
            }
            waitUntil("APP_MUSIC_POWER_UNAVAILABLE", 10_000) { power.get().available }
            if (power.get().audioBlocked) throw ProbeFailure("APP_MUSIC_POWER_AUDIO_BLOCKED")
            mark("app_music_actual_power_available_and_audio_allowed")

            connection = onMain("APP_MUSIC_BROWSER_CONNECT_FAILED") {
                MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PetasosMediaService::class.java))).buildAsync()
            }
            browser = await(connection!!, "APP_MUSIC_BROWSER_UNAVAILABLE")
            host = onMain("APP_MUSIC_HOST_UNAVAILABLE") { checkNotNull(PetasosMediaService.currentHost) }
            val live = host!!
            val credits = await(live.musicCredits(), "APP_MUSIC_CREDITS_UNAVAILABLE")
            check(credits.contains("Prelude No. 1") && credits.contains("Chris Zabriskie") &&
                credits.contains("https://creativecommons.org/licenses/by/4.0/") &&
                credits.contains("No changes were made."))
            mark("app_music_bundled_attribution_license_and_unchanged_source_available_without_runtime")
            val connected = browser!!
            val children = onMain("APP_MUSIC_CATALOG_REQUEST_FAILED") {
                connected.getChildren(PetasosMediaService.ROOT_ID, 0, 100, null)
            }
            val entries = await(children, "APP_MUSIC_CATALOG_UNAVAILABLE").value
                ?: throw ProbeFailure("APP_MUSIC_CATALOG_UNAVAILABLE")
            val selected: MediaItem = entries.firstOrNull { item ->
                item.localConfiguration == null && item.mediaMetadata.isPlayable == true && count("""
                    SELECT count(*) FROM paragraph p JOIN paragraph_fact pf ON pf.paragraph_id=p.paragraph_id
                    JOIN fact_source fs ON fs.fact_revision_id=pf.fact_revision_id
                    JOIN source_evidence s ON s.source_revision_id=fs.source_revision_id
                    WHERE p.story_version_id=? AND s.review_status='verified' AND s.provider NOT LIKE 'synthetic%'
                    AND s.source_url LIKE 'https://%' AND s.source_url NOT LIKE 'https://example.invalid/%'
                """.trimIndent(), item.mediaId) > 0
            } ?: throw ProbeFailure("APP_MUSIC_REVIEWED_PUBLIC_STORY_REQUIRED")
            onMain("APP_MUSIC_PREPARE_UNAVAILABLE") {
                check(connected.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM))
                check(connected.isCommandAvailable(Player.COMMAND_PREPARE))
                check(connected.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
                check(connected.isCommandAvailable(Player.COMMAND_STOP))
                connected.setMediaItem(selected)
                connected.prepare()
            }
            waitUntil("APP_MUSIC_SELECTION_NOT_APPLIED", 5_000) { live.selectedVersionId() == selected.mediaId }
            onMain("APP_MUSIC_PREPARE_STARTED_RUNTIME") { check(live.snapshot() == null) }
            if (baseline() != before) throw ProbeFailure("APP_MUSIC_BROWSE_OR_PREPARE_CREATED_RECORDS")
            mark("app_music_product_browse_and_prepare_keep_runtime_uncreated")

            requireLocationOff()
            if (!power.get().available) throw ProbeFailure("APP_MUSIC_POWER_UNAVAILABLE")
            if (power.get().audioBlocked) throw ProbeFailure("APP_MUSIC_POWER_AUDIO_BLOCKED")
            playIssued = true
            onMain("APP_MUSIC_PLAY_COMMAND_FAILED") { connected.play() }
            waitUntil("APP_MUSIC_SESSION_NOT_CREATED", 8_000) { live.snapshot() != null }
            val sessionId = checkNotNull(live.snapshot()).sessionId
            if (sessionId in previousSessions) throw ProbeFailure("APP_MUSIC_REUSED_OLD_SESSION")
            waitUntil("APP_MUSIC_SESSION_NOT_COMMITTED", 5_000) {
                count("SELECT count(*) FROM session WHERE session_id=? AND status='active'", sessionId) == 1L
            }
            waitUntil("APP_MUSIC_HOST_POWER_NOT_READY", 8_000) {
                val snapshot = checkNotNull(live.snapshot())
                snapshot.playIntent && PlaybackBlocker.POWER_AUDIO !in snapshot.blockers
            }
            fun checkRuntime() {
                val snapshot = live.snapshot() ?: throw ProbeFailure("APP_MUSIC_RUNTIME_LOST")
                if (snapshot.sessionId != sessionId || snapshot.closed) throw ProbeFailure("APP_MUSIC_SESSION_CHANGED")
                if (!power.get().available || PlaybackBlocker.POWER_AUDIO in snapshot.blockers) throw ProbeFailure("APP_MUSIC_POWER_UNAVAILABLE")
                if (power.get().audioBlocked) throw ProbeFailure("APP_MUSIC_POWER_AUDIO_BLOCKED")
                if (snapshot.blockers.isNotEmpty()) throw ProbeFailure("APP_MUSIC_RUNTIME_BLOCKED")
                if (snapshot.outputPhase == AdapterPhase.UNAVAILABLE) throw ProbeFailure("APP_MUSIC_TTS_UNAVAILABLE")
            }
            val assetId = "music-chris-zabriskie-prelude-no-1"
            fun musicCount(state: String) = count("SELECT count(*) FROM playback_attempt WHERE session_id=? AND kind='music' AND asset_id=? AND state=?", sessionId, assetId, state)
            fun startedMusic() = count("SELECT count(*) FROM playback_attempt WHERE session_id=? AND kind='music' AND asset_id=? AND started_at IS NOT NULL", sessionId, assetId)
            fun completedCount() = count("SELECT count(*) FROM playback_attempt WHERE session_id=? AND kind='story' AND state='completed'", sessionId)
            fun quiescent() = live.snapshot()?.let { snapshot ->
                !snapshot.playIntent && !snapshot.interactionIntent && snapshot.outputPhase == AdapterPhase.READY &&
                    snapshot.pendingPlaybackCommits == 0 &&
                    count("SELECT count(*) FROM playback_attempt WHERE session_id=? AND state IN ('prepared','playing')", sessionId) == 0L
            } == true
            waitUntil("APP_MUSIC_STORY_DID_NOT_HAND_OFF_TO_MUSIC", 65_000) {
                checkRuntime(); startedMusic() == 1L && live.snapshot()?.mode == PlaybackMode.MUSIC
            }
            check(completedCount() > 0)
            val requiredParagraphs = count("SELECT count(*) FROM paragraph WHERE story_version_id=?", selected.mediaId)
            val completedParagraphs = count("""SELECT count(DISTINCT a.paragraph_id) FROM playback_attempt a
                JOIN paragraph p ON p.paragraph_id=a.paragraph_id WHERE a.session_id=? AND a.kind='story'
                AND a.state='completed' AND p.story_version_id=?""", sessionId, selected.mediaId)
            if (requiredParagraphs == 0L || completedParagraphs != requiredParagraphs || live.snapshot()?.checkpoint?.hasNext == true) {
                throw ProbeFailure("APP_MUSIC_STORY_NOT_FULLY_COMPLETED")
            }
            check(count("""SELECT count(*) FROM playback_attempt m WHERE m.session_id=? AND m.kind='music'
                AND m.started_at IS NOT NULL AND EXISTS (SELECT 1 FROM playback_attempt s WHERE s.session_id=m.session_id
                AND s.kind='story' AND s.state='completed' AND s.completed_at<=m.started_at)""", sessionId) == 1L)
            waitUntil("APP_MUSIC_METADATA_INCORRECT", 3_000) {
                onMain("APP_MUSIC_METADATA_UNAVAILABLE") {
                    connected.currentMediaItem?.mediaId == "music:$assetId" &&
                        connected.mediaMetadata.title?.toString() == "Prelude No. 1" &&
                        connected.mediaMetadata.artist?.toString() == "Chris Zabriskie"
                }
            }
            mark("app_music_completed_public_story_then_actual_licensed_media3_started_with_artist_metadata")
            waitUntil("APP_MUSIC_POSITION_NOT_ADVANCING", 8_000) {
                checkRuntime(); (live.snapshot()?.musicCheckpoint?.positionMs ?: 0) >= 4_000
            }
            onMain("APP_MUSIC_FIRST_STOP_FAILED") { connected.stop() }
            waitUntil("APP_MUSIC_FIRST_STOP_NOT_COMMITTED", 8_000) { quiescent() }
            val stoppedPosition = checkNotNull(live.snapshot()?.musicCheckpoint).positionMs
            check(stoppedPosition in 4_000 until 68_545)
            check(musicCount("interrupted") == 1L && musicCount("completed") == 0L)
            val attemptsBeforeWait = startedMusic()
            SystemClock.sleep(700)
            check(quiescent() && startedMusic() == attemptsBeforeWait)
            mark("app_music_standard_stop_persists_real_position_and_prevents_queued_autoplay")
            val resumeRequestedAt = SystemClock.elapsedRealtime()
            onMain("APP_MUSIC_RESUME_FAILED") { connected.play() }
            waitUntil("APP_MUSIC_RESUME_RESTARTED_STORY_OR_FAILED", 8_000) {
                checkRuntime(); startedMusic() == 2L && live.snapshot()?.mode == PlaybackMode.MUSIC
            }
            // The retained checkpoint alone is not evidence of a successful player seek.
            // Observe the first changed position from the new adapter attempt; a restart at zero
            // must fail immediately rather than eventually catching up to the saved position.
            waitUntil("APP_MUSIC_RESUMED_POSITION_CALLBACK_MISSING", 4_000) {
                checkRuntime(); live.snapshot()?.musicCheckpoint?.positionMs != stoppedPosition
            }
            val resumedPosition = checkNotNull(live.snapshot()?.musicCheckpoint).positionMs
            val elapsedSinceResume = SystemClock.elapsedRealtime() - resumeRequestedAt
            if (resumedPosition < stoppedPosition || resumedPosition > stoppedPosition + elapsedSinceResume + 1_500) {
                throw ProbeFailure("APP_MUSIC_ACTUAL_RESUME_POSITION_INCORRECT")
            }
            mark("app_music_play_without_reselection_resumes_music_checkpoint_in_new_attempt")
            waitUntil("APP_MUSIC_NATURAL_COMPLETION_NOT_COMMITTED", 76_000) {
                checkRuntime(); musicCount("completed") == 1L
            }
            waitUntil("APP_MUSIC_NEXT_AUTOMATIC_OUTPUT_NOT_STARTED", 10_000) {
                checkRuntime()
                count("SELECT count(*) FROM playback_attempt WHERE session_id=? AND state='playing' AND started_at IS NOT NULL", sessionId) > 0
            }
            check(count("SELECT count(*) FROM playback_attempt WHERE session_id=? AND kind='music' AND state='completed' AND position_ms>=68000", sessionId) == 1L)
            mark("app_music_only_natural_end_commits_completion_then_allows_next_automatic_output")

            onMain("APP_MUSIC_STOP_COMMAND_FAILED") { connected.stop() }
            waitUntil("APP_MUSIC_STOP_NOT_QUIESCENT", remaining(workDeadline)) {
                val snapshot = live.snapshot()
                snapshot != null && snapshot.sessionId == sessionId && !snapshot.playIntent && !snapshot.interactionIntent &&
                    snapshot.outputPhase == AdapterPhase.READY && snapshot.pendingPlaybackCommits == 0 &&
                    count("SELECT count(*) FROM playback_attempt WHERE session_id=? AND state IN ('prepared','playing')", sessionId) == 0L
            }
            if (count("SELECT count(*) FROM playback_checkpoint WHERE session_id=? AND play_intent=0", sessionId) != 1L) {
                throw ProbeFailure("APP_MUSIC_STOP_INTENT_NOT_PERSISTED")
            }
            if (count("SELECT count(*) FROM recording_segment WHERE session_id=?", sessionId) != 0L ||
                count("SELECT count(*) FROM conversation_turn WHERE session_id=?", sessionId) != 0L ||
                count("SELECT count(*) FROM generation_request WHERE session_id=?", sessionId) != 0L) {
                throw ProbeFailure("APP_MUSIC_UNEXPECTED_LOCATION_OR_CONVERSATION")
            }
            if (completedCount() == 0L) throw ProbeFailure("APP_MUSIC_COMPLETION_RECORD_NOT_RETAINED")
            mark("app_music_standard_stop_persists_false_intent_without_location_input_or_generation")
        } catch (error: ProbeFailure) { failure = error.code }
        catch (_: Exception) { failure = "APP_MUSIC_OPERATION_FAILED" }
        finally {
            active.set(false) // Late main-thread work must not start playback after a timeout.
            val cleaned = SettableFuture.create<Void?>()
            main.post {
                try {
                    if (playIssued) {
                        val controller = browser
                        if (controller != null) controller.stop() else host?.stop()
                    }
                    policy?.close()
                    connection?.let(MediaController::releaseFuture)
                    cleaned.set(null)
                } catch (_: Exception) { cleaned.setException(ProbeFailure("APP_MUSIC_CLEANUP_FAILED")) }
            }
            try { cleaned.get((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS) }
            catch (_: Exception) { if (failure == null) failure = "APP_MUSIC_CLEANUP_FAILED" }
            database.close()
        }
        failure?.let { mark(it.lowercase()); throw ProbeFailure(it) }
    }

    private class ProbeFailure(val code: String) : IllegalStateException(code)
}
