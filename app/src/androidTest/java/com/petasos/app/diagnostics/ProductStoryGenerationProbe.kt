package com.petasos.app.diagnostics

import android.app.Instrumentation
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.petasos.app.playback.PlaybackSnapshot
import com.petasos.app.storage.RoomContentInstaller
import com.petasos.app.storage.StorageDatabase
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real product host -> reviewed public seed -> configured API -> TX-05 -> TTS start -> Stop.
 * Retains generated public content and normal records. Never opens input or opts into location.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object ProductStoryGenerationProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val deadline = SystemClock.elapsedRealtime() + 90_000
        val workDeadline = deadline - 5_000
        val main = Handler(Looper.getMainLooper())
        val active = AtomicBoolean(true)
        val power = AtomicReference(VehiclePowerPolicy.unavailable())
        val ux = AtomicReference(VehicleUxPolicy.unavailable())
        var policy: CarPolicySource? = null
        var connection: ListenableFuture<MediaBrowser>? = null
        var host: PetasosSessionHost? = null
        var preparationIssued = false
        var failure: String? = null

        fun remaining(until: Long): Long = (until - SystemClock.elapsedRealtime()).also {
            if (it <= 0) throw ProbeFailure("APP_STORY_GENERATION_DEADLINE_EXCEEDED")
        }
        fun <T> await(future: ListenableFuture<T>, code: String, limitMs: Long = 10_000): T = try {
            future.get(minOf(remaining(workDeadline), limitMs), TimeUnit.MILLISECONDS)
        } catch (error: ProbeFailure) { throw error }
        catch (_: Exception) { throw ProbeFailure(code) }
        fun <T> onMain(code: String, action: () -> T): T {
            val result = SettableFuture.create<T>()
            main.post {
                if (!active.get()) { result.setException(ProbeFailure("APP_STORY_GENERATION_CANCELLED")); return@post }
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
        fun requirePolicy() {
            if (!ux.get().permitsParkedPreview) throw ProbeFailure("APP_STORY_GENERATION_PARKED_UX_REQUIRED")
            if (power.get().audioBlocked) throw ProbeFailure("APP_STORY_GENERATION_AUDIO_POLICY_UNAVAILABLE")
        }
        fun quiescent(state: PlaybackSnapshot?): Boolean = state == null ||
            (!state.closed && !state.playIntent && !state.interactionIntent && state.outputPhase == AdapterPhase.READY &&
                state.inputPhase !in setOf(AdapterPhase.ACTIVE, AdapterPhase.STOPPING) && state.pendingPlaybackCommits == 0)

        if (!context.getDatabasePath("petasos.db").isFile) throw ProbeFailure("APP_STORY_GENERATION_INSTALLED_DATABASE_REQUIRED")
        val database = StorageDatabase.open(context, "petasos.db")
        try {
            val sql = database.openHelper.readableDatabase
            fun count(statement: String, vararg args: Any?): Long = sql.query(SimpleSQLiteQuery(statement, args)).use {
                if (!it.moveToFirst()) throw ProbeFailure("APP_STORY_GENERATION_STORAGE_READ_FAILED")
                it.getLong(0)
            }
            fun text(statement: String, vararg args: Any?): String = sql.query(SimpleSQLiteQuery(statement, args)).use {
                if (!it.moveToFirst()) throw ProbeFailure("APP_STORY_GENERATION_STORAGE_READ_FAILED")
                it.getString(0)
            }
            fun requireLocationOff() {
                if (count("SELECT count(*) FROM preference WHERE scope_id='vehicle' AND location_enabled=1") != 0L)
                    throw ProbeFailure("APP_STORY_GENERATION_LOCATION_RECORDING_ENABLED")
            }
            requireLocationOff()
            onMain("APP_STORY_GENERATION_POLICY_CONNECT_FAILED") {
                policy = CarPolicySource(context, onUx = { updated ->
                    ux.set(updated)
                    if (!updated.permitsParkedPreview) host?.setTalkUiVisible(false)
                }, onPower = power::set).also { it.start() }
            }
            waitUntil("APP_STORY_GENERATION_POLICY_UNAVAILABLE", 10_000) { ux.get().available && power.get().available }
            requirePolicy()
            mark("app_story_generation_actual_parked_ux_and_audio_policy_confirmed")

            connection = onMain("APP_STORY_GENERATION_BROWSER_CONNECT_FAILED") {
                MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PetasosMediaService::class.java))).buildAsync()
            }
            val browser = await(connection!!, "APP_STORY_GENERATION_BROWSER_UNAVAILABLE")
            host = onMain("APP_STORY_GENERATION_HOST_UNAVAILABLE") { checkNotNull(PetasosMediaService.currentHost) }
            val live = host!!
            onMain("APP_STORY_GENERATION_EXISTING_PLAYBACK") {
                check(quiescent(live.snapshot()))
                check(!live.supportsLiveInput())
                live.setTalkUiVisible(true)
            }
            val children = onMain("APP_STORY_GENERATION_CATALOG_REQUEST_FAILED") {
                browser.getChildren(PetasosMediaService.ROOT_ID, 0, 100, null)
            }
            val entries = await(children, "APP_STORY_GENERATION_CATALOG_UNAVAILABLE").value
                ?: throw ProbeFailure("APP_STORY_GENERATION_CATALOG_UNAVAILABLE")
            val sourceJoin = """FROM paragraph p JOIN paragraph_fact pf ON pf.paragraph_id=p.paragraph_id
                JOIN fact_source fs ON fs.fact_revision_id=pf.fact_revision_id
                JOIN source_evidence s ON s.source_revision_id=fs.source_revision_id WHERE p.story_version_id=?"""
            val seed = entries.firstOrNull { item ->
                item.localConfiguration == null && item.mediaMetadata.isPlayable == true &&
                    count("SELECT count(*) $sourceJoin", item.mediaId) > 0 &&
                    count("""SELECT count(*) $sourceJoin AND (s.review_status<>'verified' OR s.provider LIKE 'synthetic%'
                        OR s.source_url NOT LIKE 'https://%' OR s.source_url LIKE 'https://example.invalid/%')""", item.mediaId) == 0L
            } ?: throw ProbeFailure("APP_STORY_GENERATION_REVIEWED_PUBLIC_SEED_REQUIRED")
            val seedStoryId = text("SELECT story_id FROM story_version WHERE story_version_id=?", seed.mediaId)
            val seedHash = text("SELECT content_hash FROM story_version WHERE story_version_id=?", seed.mediaId)
            val maxVersion = count("SELECT max(version) FROM story_version WHERE story_id=?", seedStoryId)
            val beforeVersions = count("SELECT count(*) FROM story_version")
            val beforeRequests = count("SELECT count(*) FROM generation_request WHERE task='story'")
            val beforeStarted = count("SELECT count(*) FROM playback_attempt WHERE started_at IS NOT NULL")
            val beforePublicRows = listOf("story", "source_evidence", "fact_revision").map { count("SELECT count(*) FROM $it") }
            waitUntil("APP_STORY_GENERATION_HOST_NOT_READY", 8_000) { requirePolicy(); live.canPrepareStory() }

            requireLocationOff()
            preparationIssued = true
            val cancelled = onMain("APP_STORY_GENERATION_IMMEDIATE_STOP_FAILED") {
                requirePolicy()
                val pending = live.generateAndPlayStory(seed.mediaId)
                live.stop() // Same main action: revoke the job before any later UI/network continuation.
                pending
            }
            try {
                cancelled.get(minOf(remaining(workDeadline), 5_000), TimeUnit.MILLISECONDS)
                throw ProbeFailure("APP_STORY_GENERATION_CANCELLED_JOB_SUCCEEDED")
            } catch (error: ProbeFailure) { throw error }
            catch (_: ExecutionException) { /* Expected sanitized failed future, never print its cause. */ }
            catch (_: Exception) { throw ProbeFailure("APP_STORY_GENERATION_CANCEL_FUTURE_UNAVAILABLE") }
            waitUntil("APP_STORY_GENERATION_CANCEL_NOT_QUIESCENT", 8_000) {
                !live.isPreparingStory() && quiescent(live.snapshot()) &&
                    count("SELECT count(*) FROM playback_attempt WHERE state IN ('prepared','playing')") == 0L
            }
            if (count("SELECT count(*) FROM story_version") != beforeVersions ||
                count("SELECT count(*) FROM generation_request WHERE task='story'") != beforeRequests ||
                count("SELECT count(*) FROM playback_attempt WHERE started_at IS NOT NULL") != beforeStarted)
                throw ProbeFailure("APP_STORY_GENERATION_CANCEL_CREATED_OUTPUT_OR_VERSION")
            mark("app_story_generation_same_action_stop_fails_future_clears_busy_and_prevents_publication_or_output")

            waitUntil("APP_STORY_GENERATION_RETRY_NOT_READY", 8_000) { requirePolicy(); live.canPrepareStory() }
            requireLocationOff()
            val generated = onMain("APP_STORY_GENERATION_REQUEST_FAILED") {
                requirePolicy()
                live.generateAndPlayStory(seed.mediaId)
            }
            // Host setup/queueing is included here; the actual GenerationClient deadline stays 15s.
            await(generated, "APP_STORY_GENERATION_API_OR_INSTALL_FAILED", 25_000)
            if (live.isPreparingStory()) throw ProbeFailure("APP_STORY_GENERATION_BUSY_NOT_CLEARED")
            requirePolicy()
            if (count("SELECT count(*) FROM story_version") != beforeVersions + 1 ||
                count("SELECT count(*) FROM generation_request WHERE task='story'") != beforeRequests + 1)
                throw ProbeFailure("APP_STORY_GENERATION_PUBLICATION_COUNT_MISMATCH")
            val newVersion = text("SELECT story_version_id FROM story_version WHERE story_id=? AND version=? AND status='published'", seedStoryId, maxVersion + 1)
            if (newVersion == seed.mediaId || text("SELECT content_hash FROM story_version WHERE story_version_id=?", seed.mediaId) != seedHash ||
                listOf("story", "source_evidence", "fact_revision").map { count("SELECT count(*) FROM $it") } != beforePublicRows)
                throw ProbeFailure("APP_STORY_GENERATION_CHANGED_REVIEWED_SEED_OR_EVIDENCE")
            RoomContentInstaller(database, System::currentTimeMillis, ownsDatabase = false).use {
                if (it.publishedStory(newVersion).storyId != seedStoryId) throw ProbeFailure("APP_STORY_GENERATION_INVALID_PUBLISHED_HASH")
            }
            waitUntil("APP_STORY_GENERATION_CHECKPOINT_NOT_SELECTED", 8_000) {
                requirePolicy()
                live.snapshot()?.checkpoint?.story?.versionId == newVersion
            }
            val sessionId = live.snapshot()?.sessionId ?: throw ProbeFailure("APP_STORY_GENERATION_RUNTIME_LOST")
            if (count("""SELECT count(*) FROM generation_request WHERE session_id=? AND task='story' AND status='succeeded'
                AND finished_at IS NOT NULL AND model_id IS NOT NULL AND provider IS NOT NULL AND prompt_version IS NOT NULL""", sessionId) < 1)
                throw ProbeFailure("APP_STORY_GENERATION_SUCCESS_METADATA_MISSING")
            mark("app_story_generation_actual_api_publication_and_host_checkpoint_verified")
            fun startedCount() = count("""SELECT count(*) FROM playback_attempt a JOIN paragraph p ON p.paragraph_id=a.paragraph_id
                WHERE a.session_id=? AND a.kind='story' AND p.story_version_id=?
                AND a.started_at IS NOT NULL AND a.started_elapsed_ms IS NOT NULL""", sessionId, newVersion)
            waitUntil("APP_STORY_GENERATION_TTS_START_NOT_COMMITTED", 15_000) {
                requirePolicy()
                val state = live.snapshot() ?: throw ProbeFailure("APP_STORY_GENERATION_RUNTIME_LOST")
                if (state.sessionId != sessionId || state.closed || state.blockers.isNotEmpty() || state.outputPhase == AdapterPhase.UNAVAILABLE)
                    throw ProbeFailure("APP_STORY_GENERATION_RUNTIME_BLOCKED")
                startedCount() > 0
            }
            mark("app_story_generation_generated_public_story_actual_tts_start_committed")
            onMain("APP_STORY_GENERATION_STOP_FAILED") { live.stop() }
            waitUntil("APP_STORY_GENERATION_STOP_NOT_QUIESCENT", 8_000) {
                !live.isPreparingStory() && quiescent(live.snapshot()) &&
                    count("SELECT count(*) FROM playback_attempt WHERE session_id=? AND state IN ('prepared','playing')", sessionId) == 0L
            }
            val stoppedStarts = count("SELECT count(*) FROM playback_attempt WHERE session_id=? AND started_at IS NOT NULL", sessionId)
            val quietUntil = minOf(workDeadline, SystemClock.elapsedRealtime() + 1_000)
            while (SystemClock.elapsedRealtime() < quietUntil) {
                if (!quiescent(live.snapshot()) ||
                    count("SELECT count(*) FROM playback_attempt WHERE session_id=? AND started_at IS NOT NULL", sessionId) != stoppedStarts)
                    throw ProbeFailure("APP_STORY_GENERATION_STARTED_AFTER_STOP")
                val delay = (quietUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                if (delay > 0) SystemClock.sleep(minOf(50, delay))
            }
            if (count("SELECT count(*) FROM playback_checkpoint WHERE session_id=? AND play_intent=0", sessionId) != 1L ||
                count("SELECT count(*) FROM conversation_turn WHERE session_id=?", sessionId) != 0L ||
                count("SELECT count(*) FROM recording_segment WHERE session_id=?", sessionId) != 0L)
                throw ProbeFailure("APP_STORY_GENERATION_UNEXPECTED_INPUT_LOCATION_OR_STOP_INTENT")
            requireLocationOff()
            mark("app_story_generation_stop_persists_false_intent_and_no_new_start_or_microphone_location_record")
        } catch (error: ProbeFailure) { failure = error.code }
        catch (_: Exception) { failure = "APP_STORY_GENERATION_OPERATION_FAILED" }
        finally {
            active.set(false)
            val cleaned = SettableFuture.create<Void?>()
            main.post {
                try {
                    if (preparationIssued) host?.stop()
                    host?.setTalkUiVisible(false)
                    policy?.close()
                    connection?.let(MediaController::releaseFuture)
                    cleaned.set(null)
                } catch (_: Exception) { cleaned.setException(ProbeFailure("APP_STORY_GENERATION_CLEANUP_FAILED")) }
            }
            try { cleaned.get((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS) }
            catch (_: Exception) { if (failure == null) failure = "APP_STORY_GENERATION_CLEANUP_FAILED" }
            database.close()
        }
        failure?.let { mark(it.lowercase()); throw ProbeFailure(it) }
    }

    private class ProbeFailure(val code: String) : IllegalStateException(code)
}
