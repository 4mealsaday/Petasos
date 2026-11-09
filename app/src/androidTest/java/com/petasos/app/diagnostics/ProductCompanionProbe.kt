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
import android.widget.Spinner
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.sqlite.db.SimpleSQLiteQuery
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.petasos.app.MainActivity
import com.petasos.app.R
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.platform.android.content.BundledStoryCatalog
import com.petasos.app.platform.android.location.LocationAccess
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import com.petasos.app.platform.media.CompanionLocationService
import com.petasos.app.platform.media.PetasosMediaService
import com.petasos.app.platform.pleos.navigation.PleosNavigationScreens
import com.petasos.app.playback.AdapterPhase
import com.petasos.app.storage.StorageDatabase
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real UI Play -> foreground companion -> native Navigation handoff, with no test-owned binding kept alive. */
@androidx.annotation.OptIn(UnstableApi::class)
object ProductCompanionProbe {
    class Failure(val outcome: String, val code: String) : IllegalStateException(code)

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        if (!com.petasos.app.platform.pleos.navigation.PleosCompanionDisplaySupport.inspect(context).navigationSurfaceAvailable)
            throw Failure("BLOCKED", "app_companion_dev36_navigation_overlay_host_unavailable_dev35_probe_retained")
        if (!LocationAccess.hasRequiredPermissions(context)) throw Failure("BLOCKED", "app_companion_existing_location_permissions_required")
        if (context.checkSelfPermission(PleosNavigationScreens.PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw Failure("BLOCKED", "app_companion_navigation_screen_permission_required")
        if (!context.getDatabasePath("petasos.db").isFile) throw Failure("BLOCKED", "app_companion_saved_consent_required")
        val database = StorageDatabase.open(context, "petasos.db")
        val sql = database.openHelper.readableDatabase
        val deadline = SystemClock.elapsedRealtime() + 85_000
        val workDeadline = deadline - 15_000
        val main = Handler(Looper.getMainLooper())
        val active = AtomicBoolean(true)
        val activity = AtomicReference<MainActivity?>(null)
        val visible = AtomicBoolean(false)
        val stoppedAt = AtomicLong(-1)
        val application = context.applicationContext as Application
        var registered = false
        var connection: ListenableFuture<MediaBrowser>? = null
        var host: PetasosSessionHost? = null
        var playIssued = false
        var launched = false
        var failure: Failure? = null
        var preferenceBaseline: Long? = null
        var generationBaseline: Long? = null
        var conversationBaseline: Long? = null
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(value: Activity, state: Bundle?) { if (value is MainActivity) activity.set(value) }
            override fun onActivityStarted(value: Activity) { if (value is MainActivity) { activity.set(value); visible.set(true) } }
            override fun onActivityStopped(value: Activity) {
                if (value is MainActivity && activity.get() === value) {
                    stoppedAt.set(SystemClock.elapsedRealtime()); visible.set(false)
                }
            }
            override fun onActivityDestroyed(value: Activity) { if (value is MainActivity) activity.compareAndSet(value, null) }
            override fun onActivityResumed(value: Activity) = Unit
            override fun onActivityPaused(value: Activity) = Unit
            override fun onActivitySaveInstanceState(value: Activity, state: Bundle) = Unit
        }
        fun count(statement: String, vararg args: Any?): Long = sql.query(SimpleSQLiteQuery(statement, args)).use {
            if (!it.moveToFirst()) throw Failure("FAIL", "app_companion_storage_read_failed")
            it.getLong(0)
        }
        fun requireConsent() {
            if (!LocationAccess.hasRequiredPermissions(context) || count("SELECT count(*) FROM preference WHERE scope_id='vehicle' AND location_enabled=1") != 1L)
                throw Failure("BLOCKED", "app_companion_saved_consent_or_permission_changed")
        }
        fun <T> await(future: ListenableFuture<T>, code: String, cleanup: Boolean = false): T = try {
            val left = (if (cleanup) deadline else workDeadline) - SystemClock.elapsedRealtime()
            if (left <= 0) throw Failure("FAIL", "app_companion_deadline_exceeded")
            future.get(minOf(left, 8_000), TimeUnit.MILLISECONDS)
        } catch (error: Failure) { throw error }
        catch (_: Exception) { throw Failure("FAIL", code) }
        fun <T> onMain(code: String, cleanup: Boolean = false, action: () -> T): T {
            val result = SettableFuture.create<T>()
            main.post {
                if (!cleanup && !active.get()) { result.setException(Failure("FAIL", "app_companion_cancelled")); return@post }
                try { result.set(action()) } catch (_: Exception) { result.setException(Failure("FAIL", code)) }
            }
            return await(result, code, cleanup)
        }
        fun waitFor(limitMs: Long, cleanup: Boolean = false, condition: () -> Boolean): Boolean {
            val until = minOf(if (cleanup) deadline else workDeadline, SystemClock.elapsedRealtime() + limitMs)
            while (!condition()) {
                if (SystemClock.elapsedRealtime() >= until) return false
                SystemClock.sleep(50)
            }
            return true
        }
        fun click(id: Int) = onMain("app_companion_ui_command_failed") {
            val screen = checkNotNull(activity.get())
            check(screen.findViewById<View>(R.id.preview_content).isShown)
            val control = screen.findViewById<View>(id)
            check(control.isShown && control.isEnabled && control.performClick())
        }
        fun launch(cleanup: Boolean = false) {
            onMain("app_companion_activity_launch_failed", cleanup) {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                launched = true
            }
            if (!waitFor(8_000, cleanup) { visible.get() && activity.get() != null })
                throw Failure("FAIL", "app_companion_activity_not_visible")
        }
        fun stopped(live: PetasosSessionHost): Boolean = live.snapshot()?.let {
            !it.closed && !it.playIntent && !it.interactionIntent && it.outputPhase == AdapterPhase.READY && it.pendingPlaybackCommits == 0
        } == true
        fun liveUpdates(session: String, afterRow: Long, afterElapsed: Long): Long = count("""
            SELECT count(*) FROM route_point p JOIN recording_segment g ON g.segment_id=p.segment_id
            WHERE g.session_id=? AND p.rowid>? AND p.received_elapsed_ms>=? AND p.quality_status='valid'
              AND p.provider LIKE 'android-location-manager:%'
              AND json_extract(p.raw_coordinates_json,'$.readingKind')='UPDATE'
              AND json_extract(p.provider_confidence_json,'$.androidLocation.elapsedRealtimeNanos')>=?
        """.trimIndent(), session, afterRow, afterElapsed, afterElapsed * 1_000_000L)

        try {
            requireConsent()
            if (count("SELECT count(*) FROM conversation_turn WHERE summary_state IN ('pending','running','retry_wait')") != 0L ||
                count("SELECT count(*) FROM generation_request WHERE status IN ('pending','running')") != 0L)
                throw Failure("BLOCKED", "app_companion_pending_model_work")
            val preferenceVersion = count("SELECT version FROM preference WHERE scope_id='vehicle'")
            val generationCount = count("SELECT count(*) FROM generation_request")
            val conversationCount = count("SELECT count(*) FROM conversation_turn")
            preferenceBaseline = preferenceVersion; generationBaseline = generationCount; conversationBaseline = conversationCount
            onMain("app_companion_lifecycle_registration_failed") { application.registerActivityLifecycleCallbacks(lifecycle); registered = true }
            launch()
            if (!waitFor(10_000) { onMain("app_companion_gate_unavailable") {
                    activity.get()?.findViewById<View>(R.id.journey_nav_listen)?.let { it.isShown && it.isEnabled } == true
                } }) throw Failure("BLOCKED", "app_companion_actual_parked_ui_unavailable")
            connection = onMain("app_companion_browser_connect_failed") {
                MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PetasosMediaService::class.java))).buildAsync()
            }
            val browser = await(checkNotNull(connection), "app_companion_browser_unavailable")
            val live = onMain("app_companion_host_unavailable") { checkNotNull(PetasosMediaService.currentHost) }
            host = live
            if (live.isCompanionActive() || live.isPreparingStory() || live.snapshot()?.let { it.playIntent || it.interactionIntent || it.pendingPlaybackCommits != 0 || (!it.closed && it.outputPhase != AdapterPhase.READY) } == true)
                throw Failure("BLOCKED", "app_companion_existing_user_work_active")
            val entries = await(onMain("app_companion_catalog_request_failed") {
                browser.getChildren(PetasosMediaService.ROOT_ID, 0, 100, null)
            }, "app_companion_catalog_unavailable").value?.filter { it.mediaMetadata.isPlayable == true && it.localConfiguration == null }
                ?: throw Failure("BLOCKED", "app_companion_catalog_unavailable")
            val bytes = context.assets.open(BundledStoryCatalog.ASSET).use { it.readBytes() }
            if (ContentBundleCodec.sha256(bytes) != BundledStoryCatalog.SHA256) throw Failure("FAIL", "app_companion_bundled_asset_hash_mismatch")
            val bundledIds = ContentBundleCodec.decode(bytes, System.currentTimeMillis()).versions.map { it.storyVersionId }.toSet()
            click(R.id.journey_nav_settings)
            if (!onMain("app_companion_selection_unavailable") { checkNotNull(activity.get()).findViewById<View>(R.id.story_selection).isShown })
                click(R.id.journey_playback_options)
            if (!waitFor(5_000) { onMain("app_companion_selection_unavailable") {
                    checkNotNull(activity.get()).findViewById<Spinner>(R.id.story_selection).let { it.isEnabled && it.count == entries.size + 1 }
                } }) throw Failure("BLOCKED", "app_companion_public_catalog_not_ready")
            onMain("app_companion_public_selection_failed") {
                val spinner = checkNotNull(activity.get()).findViewById<Spinner>(R.id.story_selection)
                val index = entries.indices.firstOrNull { entries[it].mediaId in bundledIds && spinner.selectedItemPosition != it + 1 }
                    ?: throw Failure("BLOCKED", "app_companion_public_story_required")
                spinner.setSelection(index + 1)
            }
            instrumentation.waitForIdleSync()
            click(R.id.journey_nav_listen)
            requireConsent()
            playIssued = true
            onMain("app_companion_pending_play_stop_controls_failed") {
                val screen = checkNotNull(activity.get())
                val play = screen.findViewById<View>(R.id.playback_play)
                val stop = screen.findViewById<View>(R.id.playback_stop)
                check(play.isEnabled && play.isShown && stop.isEnabled && stop.isShown)
                // One main-loop action: Stop must cancel preparation before its late acknowledgment.
                check(play.performClick())
                check(stop.isEnabled && stop.performClick())
            }
            await(live.readRecordingSettings(), "app_companion_cancel_worker_barrier_failed")
            val cancellationUntil = SystemClock.elapsedRealtime() + 6_000L
            if (cancellationUntil >= workDeadline) throw Failure("FAIL", "app_companion_deadline_exceeded")
            while (SystemClock.elapsedRealtime() < cancellationUntil) {
                if (live.isCompanionActive() || live.snapshot()?.let { it.playIntent || it.interactionIntent } == true)
                    throw Failure("FAIL", "app_companion_cancelled_play_started_late")
                SystemClock.sleep(50)
            }
            mark("app_companion_actual_same_event_play_stop_cancelled_pending_start_no_late_play_or_foreground_lease")
            if (!waitFor(3_000) { onMain("app_companion_play_control_unavailable") {
                    checkNotNull(activity.get()).findViewById<View>(R.id.playback_play).isEnabled
                } }) throw Failure("FAIL", "app_companion_play_not_reenabled_after_cancel")
            click(R.id.playback_play)
            if (!waitFor(10_000) { live.isCompanionActive() && live.snapshot()?.playIntent == true })
                throw Failure("FAIL", "app_companion_explicit_ui_play_not_acknowledged")
            await(onMain("app_companion_media_stop_failed") { live.stop() }, "app_companion_media_stop_failed")
            if (!waitFor(5_000) { stopped(live) }) throw Failure("FAIL", "app_companion_media_not_stopped")
            val session = live.snapshot()?.sessionId ?: throw Failure("FAIL", "app_companion_session_missing")
            mark("app_companion_actual_ui_play_foreground_session_acknowledged_media_stopped")
            if (!waitFor(15_000) { requireConsent(); liveUpdates(session, 0, 0) > 0 })
                throw Failure("BLOCKED", "app_companion_no_valid_visible_live_update")
            onMain("app_companion_test_browser_release_failed") { connection?.let(MediaController::releaseFuture); connection = null }
            mark("app_companion_test_owned_browser_released_before_navigation_handoff")
            val rowBoundary = count("SELECT COALESCE(MAX(rowid),0) FROM route_point")
            val beforeStop = stoppedAt.get()
            click(R.id.journey_open_navigation)
            if (!waitFor(12_000) { stoppedAt.get() > beforeStop && !visible.get() }) {
                val requested = onMain("app_companion_navigation_status_unavailable") {
                    activity.get()?.findViewById<TextView>(R.id.journey_navigation_notice)?.text == context.getString(R.string.journey_navigation_requested)
                }
                if (requested) {
                    mark("app_companion_native_screen_requested_activity_remained_visible_no_hidden_claim")
                    throw Failure("BLOCKED", "app_companion_visible_or_multiwindow_hidden_case_deferred")
                }
                throw Failure("BLOCKED", "app_companion_native_screen_handoff_unconfirmed")
            }
            val hiddenAt = stoppedAt.get()
            mark("app_companion_native_screen_request_actual_activity_onstop_observed")
            if (!waitFor(20_000) {
                    requireConsent()
                    if (visible.get() || PetasosMediaService.currentHost !== live || live.snapshot()?.sessionId != session || !live.isCompanionActive())
                        throw Failure("FAIL", "app_companion_hidden_session_not_retained")
                    liveUpdates(session, rowBoundary, hiddenAt) > 0
                }) throw Failure("BLOCKED", "app_companion_no_new_valid_hidden_update")
            if (!stopped(live)) throw Failure("FAIL", "app_companion_hidden_started_media_or_input")
            if (count("SELECT version FROM preference WHERE scope_id='vehicle'") != preferenceVersion)
                throw Failure("BLOCKED", "app_companion_preferences_changed")
            if (count("SELECT count(*) FROM generation_request") != generationCount || count("SELECT count(*) FROM conversation_turn") != conversationCount)
                throw Failure("FAIL", "app_companion_unexpected_model_or_conversation")
            mark("app_companion_same_host_session_new_post_stop_measured_non_cached_update_committed")
            mark("app_companion_consent_retained_no_model_or_microphone_request")
        } catch (error: Failure) { failure = error }
        catch (_: Exception) { failure = Failure("FAIL", "app_companion_operation_failed") }
        finally {
            active.set(false)
            failure?.let { mark(it.code) }
            var cleanupFailure: Failure? = null
            fun cleanup(code: String, action: () -> Unit) {
                try { action() } catch (error: Exception) {
                    val found = error as? Failure ?: Failure("FAIL", code)
                    mark(found.code)
                    if (cleanupFailure == null) cleanupFailure = found
                }
            }
            val live = host
            if (playIssued && live != null) {
                cleanup("app_companion_cleanup_stop_failed") {
                    await(onMain("app_companion_cleanup_stop_failed", true) { live.stop() }, "app_companion_cleanup_stop_failed", true)
                }
                // A failed media Stop must never skip releasing our foreground location lease.
                cleanup("app_companion_cleanup_companion_stop_failed") {
                    onMain("app_companion_cleanup_companion_stop_failed", true) { CompanionLocationService.stop(context, live) }
                    if (!waitFor(5_000, true) { !live.isCompanionActive() }) throw Failure("FAIL", "app_companion_cleanup_foreground_lease_active")
                    mark("app_companion_cleanup_foreground_lease_released_without_ending_trip")
                }
            }
            cleanup("app_companion_cleanup_browser_release_failed") {
                onMain("app_companion_cleanup_browser_release_failed", true) { connection?.let(MediaController::releaseFuture); connection = null }
            }
            cleanup("app_companion_cleanup_activity_restore_failed") {
                if (launched) {
                    launch(cleanup = true)
                    mark("app_companion_activity_restored_history_retained_no_clear_data")
                }
            }
            cleanup("app_companion_cleanup_observer_failed") {
                onMain("app_companion_cleanup_observer_failed", true) { if (registered) application.unregisterActivityLifecycleCallbacks(lifecycle) }
            }
            cleanup("app_companion_cleanup_scope_check_failed") {
                if (preferenceBaseline != null && count("SELECT version FROM preference WHERE scope_id='vehicle'") != preferenceBaseline)
                    throw Failure("BLOCKED", "app_companion_preferences_changed")
                if (generationBaseline != null && (count("SELECT count(*) FROM generation_request") != generationBaseline ||
                    count("SELECT count(*) FROM conversation_turn") != conversationBaseline))
                    throw Failure("FAIL", "app_companion_unexpected_model_or_conversation")
                if (generationBaseline != null) mark("app_companion_final_model_conversation_and_preference_counts_unchanged")
            }
            failure = cleanupFailure ?: failure
            database.close()
        }
        failure?.let { throw it }
    }
}
