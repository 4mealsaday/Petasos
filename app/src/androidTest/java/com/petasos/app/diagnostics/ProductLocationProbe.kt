package com.petasos.app.diagnostics

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.sqlite.db.SimpleSQLiteQuery
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.petasos.app.MainActivity
import com.petasos.app.R
import com.petasos.app.platform.android.location.AndroidLocationSource
import com.petasos.app.platform.android.location.LocationAccess
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import com.petasos.app.platform.media.PetasosMediaService
import com.petasos.app.playback.AdapterPhase
import com.petasos.app.storage.StorageDatabase
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Existing user opt-in only. Uses the product Activity/Host and retains ordinary trip history.
 * Reads aggregate sample counts/quality, never coordinate values, source payloads or conversation text.
 * A closed segment and stable counts prove storage quiescence, not a vendor unregister acknowledgment.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object ProductLocationProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        // These guards precede Activity creation and every source subscription caused by the probe.
        if (!LocationAccess.hasRequiredPermissions(context)) throw Failure("BLOCKED", "app_location_permissions_required")
        if (!context.getDatabasePath("petasos.db").isFile) throw Failure("BLOCKED", "app_location_saved_consent_required")
        val database = StorageDatabase.open(context, "petasos.db")
        val deadline = SystemClock.elapsedRealtime() + 85_000
        val workDeadline = deadline - 10_000
        val main = Handler(Looper.getMainLooper())
        val active = AtomicBoolean(true)
        val activity = AtomicReference<MainActivity?>(null)
        val destroyedActivity = AtomicReference<MainActivity?>(null)
        val visible = AtomicBoolean(false)
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val application = context.applicationContext as Application
        var registered = false
        var connection: ListenableFuture<MediaBrowser>? = null
        var host: PetasosSessionHost? = null
        var sessionId: String? = null
        var playbackIssued = false
        var failure: Failure? = null
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(value: Activity, state: Bundle?) { if (value is MainActivity) activity.set(value) }
            override fun onActivityStarted(value: Activity) {
                if (value is MainActivity) { activity.set(value); visible.set(true); starts.incrementAndGet() }
            }
            override fun onActivityStopped(value: Activity) {
                if (value is MainActivity) { if (activity.get() === value) visible.set(false); stops.incrementAndGet() }
            }
            override fun onActivityDestroyed(value: Activity) {
                if (value is MainActivity) {
                    destroyedActivity.set(value)
                    if (activity.compareAndSet(value, null)) visible.set(false)
                }
            }
            override fun onActivityResumed(value: Activity) = Unit
            override fun onActivityPaused(value: Activity) = Unit
            override fun onActivitySaveInstanceState(value: Activity, state: Bundle) = Unit
        }

        fun remaining(until: Long): Long = (until - SystemClock.elapsedRealtime()).also {
            if (it <= 0) throw Failure("FAIL", "app_location_deadline_exceeded")
        }
        fun <T> await(future: ListenableFuture<T>, code: String, until: Long = workDeadline, limitMs: Long = 8_000): T = try {
            future.get(minOf(remaining(until), limitMs), TimeUnit.MILLISECONDS)
        } catch (error: Failure) { throw error }
        catch (_: Exception) { throw Failure("FAIL", code) }
        fun <T> onMain(code: String, cleanup: Boolean = false, action: () -> T): T {
            val result = SettableFuture.create<T>()
            main.post {
                if (!cleanup && !active.get()) { result.setException(Failure("FAIL", "app_location_cancelled")); return@post }
                try { result.set(action()) } catch (_: Exception) { result.setException(Failure("FAIL", code)) }
            }
            return await(result, code, if (cleanup) deadline else workDeadline)
        }
        val sql = database.openHelper.readableDatabase
        fun count(statement: String, vararg args: Any?): Long = sql.query(SimpleSQLiteQuery(statement, args)).use {
            if (!it.moveToFirst()) throw Failure("FAIL", "app_location_storage_read_failed")
            it.getLong(0)
        }
        fun requireConsent() {
            if (!LocationAccess.hasRequiredPermissions(context)) throw Failure("BLOCKED", "app_location_permissions_changed")
            if (count("SELECT count(*) FROM preference WHERE scope_id='vehicle' AND location_enabled=1") != 1L)
                throw Failure("BLOCKED", "app_location_saved_consent_required")
        }
        fun awaitCondition(limitMs: Long, until: Long = workDeadline, condition: () -> Boolean): Boolean {
            val end = minOf(until, SystemClock.elapsedRealtime() + limitMs)
            while (!condition()) {
                val delay = end - SystemClock.elapsedRealtime()
                if (delay <= 0) return false
                SystemClock.sleep(minOf(100, delay))
            }
            return true
        }
        fun requireCondition(code: String, limitMs: Long, condition: () -> Boolean) {
            if (!awaitCondition(limitMs, condition = condition)) throw Failure("FAIL", code)
        }
        fun points(session: String): Long = count("""
            SELECT count(*) FROM route_point p JOIN recording_segment g ON g.segment_id=p.segment_id WHERE g.session_id=?
        """.trimIndent(), session)
        fun updates(session: String, afterSegmentRow: Long = 0): Long = count("""
            SELECT count(*) FROM route_point p JOIN recording_segment g ON g.segment_id=p.segment_id
            WHERE g.session_id=? AND g.rowid>? AND json_extract(p.raw_coordinates_json,'$.readingKind')='UPDATE'
        """.trimIndent(), session, afterSegmentRow)
        fun liveValid(session: String, afterSegmentRow: Long = 0): Long = count("""
            SELECT count(*) FROM route_point p JOIN recording_segment g ON g.segment_id=p.segment_id
            WHERE g.session_id=? AND g.rowid>? AND p.quality_status='valid'
            AND p.provider LIKE 'android-location-manager:%' AND json_extract(p.raw_coordinates_json,'$.readingKind')='UPDATE'
        """.trimIndent(), session, afterSegmentRow)
        fun activeSegments(session: String): Long = count("SELECT count(*) FROM recording_segment WHERE session_id=? AND status='active'", session)
        fun reportCounts(phase: String, session: String) {
            mark("app_location_${phase}_points_${points(session)}")
            mark("app_location_${phase}_updates_${updates(session)}")
            for (quality in listOf("valid", "stale", "invalid", "unknown")) {
                val value = count("""
                    SELECT count(*) FROM route_point p JOIN recording_segment g ON g.segment_id=p.segment_id
                    WHERE g.session_id=? AND p.quality_status=?
                """.trimIndent(), session, quality)
                mark("app_location_${phase}_${quality}_$value")
            }
        }
        fun launch() {
            requireConsent()
            val before = starts.get()
            onMain("app_location_activity_launch_failed") {
                context.startActivity(Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                })
            }
            requireCondition("app_location_activity_not_started", 8_000) { starts.get() > before && visible.get() }
            // This uses the real UI's parked + browser + settings gate, not a forged Host visibility flag.
            if (!awaitCondition(10_000) {
                    requireConsent()
                    onMain("app_location_activity_state_unavailable") {
                        activity.get()?.findViewById<View>(R.id.records_location)?.isEnabled == true
                    }
                }) throw Failure("BLOCKED", "app_location_parked_ui_unavailable")
        }

        try {
            requireConsent()
            val preferenceVersion = count("SELECT version FROM preference WHERE scope_id='vehicle'")
            val generationCount = count("SELECT count(*) FROM generation_request")
            val conversationCount = count("SELECT count(*) FROM conversation_turn")
            if (count("SELECT count(*) FROM conversation_turn WHERE summary_state IN ('pending','running','retry_wait')") != 0L)
                throw Failure("BLOCKED", "app_location_pending_background_summary")
            val capabilities = AndroidLocationSource.inspect(context)
            mark("app_location_existing_fine_permission_${capabilities.finePermissionGranted}")
            mark("app_location_existing_coarse_permission_${capabilities.coarsePermissionGranted}")
            mark("app_location_provider_${capabilities.preferredProvider() ?: "unavailable"}")
            mark("app_location_existing_saved_consent_and_permissions_verified")
            onMain("app_location_lifecycle_observer_failed") { application.registerActivityLifecycleCallbacks(lifecycle); registered = true }
            launch()
            connection = onMain("app_location_browser_connect_failed") {
                MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PetasosMediaService::class.java))).buildAsync()
            }
            await(connection!!, "app_location_browser_unavailable") // Keep the same Host bound while its Activity is in the background.
            val live = onMain("app_location_host_unavailable") { checkNotNull(PetasosMediaService.currentHost) }
            host = live
            if (live.snapshot() == null) {
                val selected = await(live.browse(), "app_location_catalog_unavailable").firstOrNull { item ->
                    count("""
                        SELECT count(*) FROM paragraph p JOIN paragraph_fact pf ON pf.paragraph_id=p.paragraph_id
                        JOIN fact_source fs ON fs.fact_revision_id=pf.fact_revision_id
                        JOIN source_evidence s ON s.source_revision_id=fs.source_revision_id
                        WHERE p.story_version_id=? AND s.review_status='verified' AND s.provider NOT LIKE 'synthetic%'
                        AND s.source_url LIKE 'https://%' AND s.source_url NOT LIKE 'https://example.invalid/%'
                    """.trimIndent(), item.versionId) > 0
                } ?: throw Failure("BLOCKED", "app_location_public_story_required")
                await(onMain("app_location_select_failed") { live.select(selected.versionId) }, "app_location_select_failed")
                requireConsent()
                playbackIssued = true
                await(onMain("app_location_play_failed") { live.play() }, "app_location_play_failed")
                requireCondition("app_location_session_missing", 8_000) { live.snapshot()?.closed == false }
            }
            playbackIssued = true
            await(onMain("app_location_media_stop_failed") { live.stop() }, "app_location_media_stop_failed")
            requireCondition("app_location_media_not_stopped", 8_000) {
                live.snapshot()?.let { !it.closed && !it.playIntent && !it.interactionIntent &&
                    it.outputPhase == AdapterPhase.READY && it.pendingPlaybackCommits == 0 } == true
            }
            val session = live.snapshot()?.sessionId ?: throw Failure("FAIL", "app_location_session_missing")
            sessionId = session
            requireCondition("app_location_session_not_committed", 5_000) {
                count("SELECT count(*) FROM session WHERE session_id=? AND status='active'", session) == 1L
            }
            mark("app_location_actual_activity_visible_media_stopped_session_open")
            val firstValid = awaitCondition(30_000) { requireConsent(); liveValid(session) > 0 }
            val firstLive = updates(session) > 0
            reportCounts("visible", session)
            mark(if (firstLive) "app_location_actual_update_committed" else "app_location_no_live_location_before_hide")

            val stoppedBefore = stops.get()
            var closedForVisibility = false
            onMain("app_location_home_failed") {
                checkNotNull(activity.get()).startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            val homeStopped = awaitCondition(3_000) { stops.get() > stoppedBefore && !visible.get() }
            if (homeStopped) {
                mark("app_location_home_requested_actual_onstop_observed")
            } else {
                // Some automotive launchers expose a Home panel without hiding the app. Move the
                // real task behind it; callbacks and storage checks still come from the framework.
                mark("app_location_home_did_not_stop_activity_task_background_requested")
                val moved = onMain("app_location_task_background_failed") {
                    if (stops.get() > stoppedBefore && !visible.get()) false
                    else checkNotNull(activity.get()).moveTaskToBack(true)
                }
                val backgroundStopped = awaitCondition(if (moved) 8_000 else 0) {
                    stops.get() > stoppedBefore && !visible.get()
                }
                if (backgroundStopped) {
                    mark(if (moved) "app_location_task_background_requested_actual_onstop_observed"
                        else "app_location_delayed_home_actual_onstop_observed")
                } else {
                    // A task-panel shell may keep the Activity visible even after accepting the move.
                    // Closing/reopening is a separate lifecycle proof, never background acceptance.
                    mark("app_location_task_remained_visible_activity_close_reopen_fallback")
                    val closing = onMain("app_location_activity_close_failed") {
                        checkNotNull(activity.get()).also { it.finish() }
                    }
                    requireCondition("app_location_activity_close_not_observed", 5_000) {
                        stops.get() > stoppedBefore && !visible.get() && destroyedActivity.get() === closing
                    }
                    closedForVisibility = true
                    mark("app_location_activity_close_actual_onstop_and_destroy_observed")
                }
            }
            await(live.readRecordingSettings(), "app_location_hidden_queue_barrier_failed")
            requireCondition("app_location_hidden_segment_active", 5_000) { activeSegments(session) == 0L }
            val hiddenPoints = points(session)
            val segmentBoundary = count("SELECT COALESCE(MAX(rowid),0) FROM recording_segment WHERE session_id=?", session)
            val hiddenUntil = SystemClock.elapsedRealtime() + 2_500
            if (hiddenUntil > workDeadline) throw Failure("FAIL", "app_location_deadline_exceeded")
            while (SystemClock.elapsedRealtime() < hiddenUntil) {
                requireConsent()
                if (visible.get() || points(session) != hiddenPoints || activeSegments(session) != 0L)
                    throw Failure("FAIL", "app_location_hidden_recording_continued")
                SystemClock.sleep(100)
            }
            mark("app_location_actual_onstop_segments_closed_and_counts_stable")

            launch()
            if (PetasosMediaService.currentHost !== live || live.snapshot()?.sessionId != session)
                throw Failure("FAIL", "app_location_return_replaced_open_session")
            val returnedValid = awaitCondition(15_000) { requireConsent(); liveValid(session, segmentBoundary) > 0 }
            val returnedLive = updates(session, segmentBoundary) > 0
            reportCounts("returned", session)
            mark(if (returnedLive) "app_location_return_new_segment_actual_update_committed" else "app_location_no_live_location_after_return")
            if (live.snapshot()?.playIntent != false || live.snapshot()?.interactionIntent != false)
                throw Failure("FAIL", "app_location_return_started_output_or_input")
            requireConsent()
            if (count("SELECT version FROM preference WHERE scope_id='vehicle'") != preferenceVersion)
                throw Failure("BLOCKED", "app_location_preference_changed_during_probe")
            if (count("SELECT count(*) FROM generation_request") != generationCount ||
                count("SELECT count(*) FROM conversation_turn") != conversationCount)
                throw Failure("FAIL", "app_location_unexpected_model_or_conversation")
            mark("app_location_consent_permissions_preserved_no_model_or_microphone_requested")
            if (!firstLive || !returnedLive) throw Failure("BLOCKED", "app_location_no_live_location")
            if (!firstValid || !returnedValid)
                throw Failure("BLOCKED", "app_location_live_quality_not_valid")
            mark(if (closedForVisibility) "app_location_live_valid_updates_and_activity_close_reopen_storage_pass"
                else "app_location_live_valid_updates_and_foreground_storage_lifecycle_pass")
        } catch (error: Failure) { failure = error }
        catch (_: Exception) { failure = Failure("FAIL", "app_location_operation_failed") }
        finally {
            active.set(false)
            try {
                val live = host
                val stopping = onMain("app_location_cleanup_failed", cleanup = true) {
                    val future = if (playbackIssued) live?.stop() else null
                    activity.get()?.finish()
                    future
                }
                if (stopping != null) await(stopping, "app_location_cleanup_stop_failed", deadline)
                if (!awaitCondition(5_000, deadline) { !visible.get() }) throw Failure("FAIL", "app_location_cleanup_activity_visible")
                if (live != null) await(live.readRecordingSettings(), "app_location_cleanup_queue_barrier_failed", deadline)
                val session = sessionId
                if (session != null && activeSegments(session) != 0L) throw Failure("FAIL", "app_location_cleanup_segment_active")
                if (starts.get() > 0) mark("app_location_finished_activity_storage_closed_history_and_consent_retained")
            } catch (_: Exception) { failure = Failure("FAIL", "app_location_cleanup_failed") }
            try {
                onMain("app_location_cleanup_release_failed", cleanup = true) {
                    if (registered) application.unregisterActivityLifecycleCallbacks(lifecycle)
                    connection?.let(MediaController::releaseFuture)
                }
            } catch (_: Exception) { failure = Failure("FAIL", "app_location_cleanup_release_failed") }
            database.close()
        }
        failure?.let { mark(it.code); throw it }
    }

    class Failure(val outcome: String, val code: String) : IllegalStateException(code)
}
