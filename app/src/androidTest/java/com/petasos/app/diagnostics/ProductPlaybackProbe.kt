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
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.content.ContentVersion
import com.petasos.app.generation.StrictJson
import com.petasos.app.platform.android.content.BundledStoryCatalog
import com.petasos.app.platform.android.location.BundledRegionCatalog
import com.petasos.app.platform.android.location.BundledRegionPolicyCatalog
import com.petasos.app.location.ReviewedRegionPolicyCodec
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import com.petasos.app.platform.automotive.CarPolicySource
import com.petasos.app.platform.automotive.VehiclePowerPolicy
import com.petasos.app.platform.media.PetasosMediaService
import com.petasos.app.playback.AdapterPhase
import com.petasos.app.playback.PlaybackBlocker
import com.petasos.app.storage.StorageDatabase
import com.petasos.app.storage.RoomRegionPolicyStore
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Explicit real listening test: product MediaBrowser -> service/host -> installed story -> TTS -> Room.
 * No fixture installation, microphone, generation request, location opt-in, or production-data cleanup.
 * The normal listening records remain in petasos.db after Stop.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object ProductPlaybackProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit, bundledStoryIndex: Int? = null) {
        if (bundledStoryIndex != null && bundledStoryIndex !in 0..9) throw ProbeFailure("BUNDLED_PLAYBACK_INDEX_INVALID")
        val context = instrumentation.targetContext
        val deadline = SystemClock.elapsedRealtime() + 70_000
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
            if (it <= 0) throw ProbeFailure("APP_PLAYBACK_DEADLINE_EXCEEDED")
        }
        fun <T> await(future: ListenableFuture<T>, code: String, limitMs: Long = 10_000): T {
            return try { future.get(minOf(remaining(workDeadline), limitMs), TimeUnit.MILLISECONDS) }
            catch (error: ProbeFailure) { throw error }
            catch (_: Exception) { throw ProbeFailure(code) }
        }
        fun <T> onMain(code: String, action: () -> T): T {
            val result = SettableFuture.create<T>()
            main.post {
                if (!active.get()) { result.setException(ProbeFailure("APP_PLAYBACK_CANCELLED")); return@post }
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
        if (!context.getDatabasePath("petasos.db").isFile) throw ProbeFailure("APP_PLAYBACK_INSTALLED_DATABASE_REQUIRED")
        val database = StorageDatabase.open(context, "petasos.db")
        try {
            val bundledVersion: ContentVersion? = bundledStoryIndex?.let { index ->
                val bytes = context.assets.open(BundledStoryCatalog.ASSET).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        if (size == 0 || output.size() + size > StrictJson.MAX_BYTES)
                            throw ProbeFailure("BUNDLED_PLAYBACK_ASSET_INVALID")
                        output.write(buffer, 0, size)
                    }
                    output.toByteArray()
                }
                if (ContentBundleCodec.sha256(bytes) != BundledStoryCatalog.SHA256)
                    throw ProbeFailure("BUNDLED_PLAYBACK_ASSET_HASH_MISMATCH")
                val bundle = ContentBundleCodec.decode(bytes, System.currentTimeMillis())
                if (bundle.stories.size != 10 || bundle.versions.size != 10)
                    throw ProbeFailure("BUNDLED_PLAYBACK_ASSET_INVENTORY_INVALID")
                val storyId = bundle.stories.sortedBy { it.storyId }[index].storyId
                val version = bundle.versions.singleOrNull { it.storyId == storyId }
                    ?: throw ProbeFailure("BUNDLED_PLAYBACK_ASSET_VERSION_AMBIGUOUS")
                mark("bundled_playback_index_$index")
                mark("bundled_playback_public_story_${version.storyId}")
                mark("bundled_playback_public_version_${version.storyVersionId}")
                mark("bundled_playback_asset_sha256_${BundledStoryCatalog.SHA256}")
                version
            }
            val sql = database.openHelper.readableDatabase
            fun count(statement: String, vararg args: Any?): Long = sql.query(SimpleSQLiteQuery(statement, args)).use {
                if (!it.moveToFirst()) throw ProbeFailure("APP_PLAYBACK_STORAGE_READ_FAILED")
                it.getLong(0)
            }
            fun locationPreference(): List<String?> = sql.query(
                "SELECT location_enabled,version,updated_at FROM preference WHERE scope_id='vehicle'"
            ).use { rows ->
                if (!rows.moveToFirst()) emptyList() else (0 until rows.columnCount).map {
                    if (rows.isNull(it)) null else rows.getString(it)
                }
            }
            val savedLocationPreference = locationPreference()
            fun requireLocationPreferenceUnchanged() {
                if (locationPreference() != savedLocationPreference)
                    throw ProbeFailure("APP_PLAYBACK_LOCATION_PREFERENCE_CHANGED")
            }
            fun baseline() = listOf("trip", "session", "recording_segment", "playback_attempt").map {
                count("SELECT count(*) FROM $it")
            }
            // This probe never launches an Activity. Foreground location eligibility remains false
            // even when the user previously opted in; preserve that preference rather than toggling it.
            onMain("APP_PLAYBACK_EXISTING_RUNTIME") {
                check(PetasosMediaService.currentHost?.snapshot() == null)
            }
            val before = baseline()
            val previousSessions = buildSet {
                sql.query("SELECT session_id FROM session").use { while (it.moveToNext()) add(it.getString(0)) }
            }
            onMain("APP_PLAYBACK_POWER_CONNECT_FAILED") {
                policy = CarPolicySource(context, onUx = {}, onPower = power::set).also { it.start() }
            }
            waitUntil("APP_PLAYBACK_POWER_UNAVAILABLE", 10_000) { power.get().available }
            if (power.get().audioBlocked) throw ProbeFailure("APP_PLAYBACK_POWER_AUDIO_BLOCKED")
            mark("app_playback_actual_power_available_and_audio_allowed")

            connection = onMain("APP_PLAYBACK_BROWSER_CONNECT_FAILED") {
                MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PetasosMediaService::class.java))).buildAsync()
            }
            browser = await(connection!!, "APP_PLAYBACK_BROWSER_UNAVAILABLE")
            host = onMain("APP_PLAYBACK_HOST_UNAVAILABLE") { checkNotNull(PetasosMediaService.currentHost) }
            val live = host!!
            val connected = browser!!
            val children = onMain("APP_PLAYBACK_CATALOG_REQUEST_FAILED") {
                connected.getChildren(PetasosMediaService.ROOT_ID, 0, 100, null)
            }
            val entries = await(children, "APP_PLAYBACK_CATALOG_UNAVAILABLE").value
                ?: throw ProbeFailure("APP_PLAYBACK_CATALOG_UNAVAILABLE")
            // Observe the real Host bootstrap; never install/repair reviews from this probe.
            val regionManifest = BundledRegionCatalog(context).readManifest()
            val regionReviews = BundledRegionPolicyCatalog(context).readBundle().reviews
            val regionPolicies = RoomRegionPolicyStore(database, System::currentTimeMillis)
            for (review in regionReviews) {
                val entry = regionManifest.regions.single { it.regionRevisionId == review.regionRevisionId }
                val expected = ReviewedRegionPolicyCodec.storedPolicyJson(regionManifest, entry, review)
                if (count("SELECT count(*) FROM region_revision WHERE region_revision_id=? AND verified_at=? AND policy_json=?",
                        review.regionRevisionId, review.verifiedAt, expected) != 1L ||
                    regionPolicies.reviewedPolicy(review.regionRevisionId)?.policy != review.policy)
                    throw ProbeFailure("APP_PLAYBACK_PRODUCT_REGION_POLICY_NOT_CURRENT")
            }
            mark("app_playback_product_current_packaged_region_policies_${regionReviews.size}")
            // Read only: the real Host must have installed the exact two packaged landmark reviews.
            // Prior completed stories may have no automatic candidates and must retain their history.
            val landmarkPolicyCount = try {
                com.petasos.app.storage.BundledLandmarkPolicyProbe.requireCurrentPackagedPolicies(context, database)
            } catch (_: Exception) { throw ProbeFailure("APP_PLAYBACK_PRODUCT_LANDMARK_POLICY_NOT_CURRENT") }
            mark("app_playback_product_current_packaged_landmark_policies_$landmarkPolicyCount")
            val selected: MediaItem = entries.firstOrNull { item ->
                (bundledVersion == null || item.mediaId == bundledVersion.storyVersionId) &&
                item.localConfiguration == null && item.mediaMetadata.isPlayable == true && count("""
                    SELECT count(*) FROM paragraph p JOIN paragraph_fact pf ON pf.paragraph_id=p.paragraph_id
                    JOIN fact_source fs ON fs.fact_revision_id=pf.fact_revision_id
                    JOIN source_evidence s ON s.source_revision_id=fs.source_revision_id
                    WHERE p.story_version_id=? AND s.review_status='verified' AND s.provider NOT LIKE 'synthetic%'
                    AND s.source_url LIKE 'https://%' AND s.source_url NOT LIKE 'https://example.invalid/%'
                """.trimIndent(), item.mediaId) > 0
            } ?: throw ProbeFailure("APP_PLAYBACK_REVIEWED_PUBLIC_STORY_REQUIRED")
            if (bundledVersion != null) {
                if (count("SELECT count(*) FROM story_version WHERE story_version_id=? AND story_id=? AND content_hash=? AND status='published'",
                        bundledVersion.storyVersionId, bundledVersion.storyId, bundledVersion.contentHash) != 1L)
                    throw ProbeFailure("BUNDLED_PLAYBACK_INSTALLED_VERSION_MISMATCH")
                val requiredIds = sql.query(SimpleSQLiteQuery("SELECT paragraph_id FROM paragraph WHERE story_version_id=? AND required=1 ORDER BY ordinal",
                    arrayOf(bundledVersion.storyVersionId))).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
                if (requiredIds != bundledVersion.paragraphs.filter { it.required }.sortedBy { it.ordinal }.map { it.paragraphId })
                    throw ProbeFailure("BUNDLED_PLAYBACK_REQUIRED_PARAGRAPHS_MISMATCH")
            }
            onMain("APP_PLAYBACK_PREPARE_UNAVAILABLE") {
                check(connected.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM))
                check(connected.isCommandAvailable(Player.COMMAND_PREPARE))
                check(connected.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
                check(connected.isCommandAvailable(Player.COMMAND_STOP))
                connected.setMediaItem(selected)
                connected.prepare()
            }
            waitUntil("APP_PLAYBACK_SELECTION_NOT_APPLIED", 5_000) { live.selectedVersionId() == selected.mediaId }
            onMain("APP_PLAYBACK_PREPARE_STARTED_RUNTIME") { check(live.snapshot() == null) }
            if (baseline() != before) throw ProbeFailure("APP_PLAYBACK_BROWSE_OR_PREPARE_CREATED_RECORDS")
            mark("app_playback_product_browse_and_prepare_keep_runtime_uncreated")

            requireLocationPreferenceUnchanged()
            if (!power.get().available) throw ProbeFailure("APP_PLAYBACK_POWER_UNAVAILABLE")
            if (power.get().audioBlocked) throw ProbeFailure("APP_PLAYBACK_POWER_AUDIO_BLOCKED")
            playIssued = true
            onMain("APP_PLAYBACK_PLAY_COMMAND_FAILED") { connected.play() }
            waitUntil("APP_PLAYBACK_SESSION_NOT_CREATED", 8_000) { live.snapshot() != null }
            val sessionId = checkNotNull(live.snapshot()).sessionId
            if (sessionId in previousSessions) throw ProbeFailure("APP_PLAYBACK_REUSED_OLD_SESSION")
            waitUntil("APP_PLAYBACK_SESSION_NOT_COMMITTED", 5_000) {
                count("SELECT count(*) FROM session WHERE session_id=? AND status='active'", sessionId) == 1L
            }
            waitUntil("APP_PLAYBACK_HOST_POWER_NOT_READY", 8_000) {
                val snapshot = checkNotNull(live.snapshot())
                snapshot.playIntent && PlaybackBlocker.POWER_AUDIO !in snapshot.blockers
            }
            fun checkRuntime() {
                val snapshot = live.snapshot() ?: throw ProbeFailure("APP_PLAYBACK_RUNTIME_LOST")
                if (snapshot.sessionId != sessionId || snapshot.closed) throw ProbeFailure("APP_PLAYBACK_SESSION_CHANGED")
                if (!power.get().available || PlaybackBlocker.POWER_AUDIO in snapshot.blockers) throw ProbeFailure("APP_PLAYBACK_POWER_UNAVAILABLE")
                if (power.get().audioBlocked) throw ProbeFailure("APP_PLAYBACK_POWER_AUDIO_BLOCKED")
                if (snapshot.blockers.isNotEmpty()) throw ProbeFailure("APP_PLAYBACK_RUNTIME_BLOCKED")
                if (snapshot.outputPhase == AdapterPhase.UNAVAILABLE) throw ProbeFailure("APP_PLAYBACK_TTS_UNAVAILABLE")
            }
            fun startedCount() = count("""SELECT count(*) FROM playback_attempt a JOIN paragraph p ON p.paragraph_id=a.paragraph_id
                WHERE a.session_id=? AND a.kind='story' AND p.story_version_id=?
                  AND a.started_at IS NOT NULL AND a.started_elapsed_ms IS NOT NULL""", sessionId, selected.mediaId)
            fun completedCount() = count("""SELECT count(*) FROM playback_attempt a JOIN paragraph p ON p.paragraph_id=a.paragraph_id
                WHERE a.session_id=? AND a.kind='story' AND p.story_version_id=? AND a.state='completed'
                  AND a.started_at IS NOT NULL AND a.started_elapsed_ms IS NOT NULL AND a.completed_at IS NOT NULL""", sessionId, selected.mediaId)
            fun allRequiredCompleted(): Boolean = bundledVersion?.paragraphs?.filter { it.required }?.all { paragraph ->
                count("""SELECT count(*) FROM playback_attempt WHERE session_id=? AND kind='story' AND paragraph_id=?
                    AND state='completed' AND started_at IS NOT NULL AND completed_at IS NOT NULL
                    AND started_elapsed_ms IS NOT NULL AND ended_elapsed_ms>started_elapsed_ms
                    AND interrupted_at IS NULL AND length(clock_scope_id)>0""", sessionId, paragraph.paragraphId) == 1L
            } ?: (completedCount() > 0)
            waitUntil("APP_PLAYBACK_TTS_STARTED_NOT_COMMITTED", 15_000) { checkRuntime(); startedCount() > 0 }
            mark("app_playback_product_actual_tts_started_in_new_session")
            waitUntil("APP_PLAYBACK_TTS_COMPLETION_NOT_COMMITTED", (remaining(workDeadline) - 8_000).coerceAtLeast(1)) {
                checkRuntime(); allRequiredCompleted()
            }
            mark("app_playback_product_actual_tts_completed_committed_to_petasos_db")

            onMain("APP_PLAYBACK_STOP_COMMAND_FAILED") { connected.stop() }
            waitUntil("APP_PLAYBACK_STOP_NOT_QUIESCENT", remaining(workDeadline)) {
                val snapshot = live.snapshot()
                snapshot != null && snapshot.sessionId == sessionId && !snapshot.playIntent && !snapshot.interactionIntent &&
                    snapshot.outputPhase == AdapterPhase.READY && snapshot.pendingPlaybackCommits == 0 &&
                    count("SELECT count(*) FROM playback_attempt WHERE session_id=? AND state IN ('prepared','playing')", sessionId) == 0L
            }
            if (count("SELECT count(*) FROM playback_checkpoint WHERE session_id=? AND play_intent=0", sessionId) != 1L) {
                throw ProbeFailure("APP_PLAYBACK_STOP_INTENT_NOT_PERSISTED")
            }
            if (count("SELECT count(*) FROM recording_segment WHERE session_id=?", sessionId) != 0L ||
                count("SELECT count(*) FROM conversation_turn WHERE session_id=?", sessionId) != 0L ||
                count("SELECT count(*) FROM generation_request WHERE session_id=?", sessionId) != 0L) {
                throw ProbeFailure("APP_PLAYBACK_UNEXPECTED_LOCATION_OR_CONVERSATION")
            }
            if (completedCount() == 0L) throw ProbeFailure("APP_PLAYBACK_COMPLETION_RECORD_NOT_RETAINED")
            if (bundledVersion != null) {
                if (!allRequiredCompleted()) throw ProbeFailure("BUNDLED_PLAYBACK_REQUIRED_COMPLETION_NOT_RETAINED")
                val required = bundledVersion.paragraphs.filter { it.required }.sortedBy { it.ordinal }
                val scopes = mutableSetOf<String>()
                var durationSum = 0L
                for (paragraph in required) {
                    sql.query(SimpleSQLiteQuery("""SELECT started_elapsed_ms,ended_elapsed_ms,clock_scope_id FROM playback_attempt
                        WHERE session_id=? AND kind='story' AND paragraph_id=? AND state='completed'""",
                        arrayOf(sessionId, paragraph.paragraphId))).use { rows ->
                        if (!rows.moveToFirst() || rows.isNull(0) || rows.isNull(1) || rows.isNull(2))
                            throw ProbeFailure("BUNDLED_PLAYBACK_MONOTONIC_COMPLETION_MISSING")
                        val started = rows.getLong(0)
                        val ended = rows.getLong(1)
                        if (started < 0 || ended <= started || rows.getString(2).isBlank())
                            throw ProbeFailure("BUNDLED_PLAYBACK_MONOTONIC_COMPLETION_INVALID")
                        scopes += rows.getString(2)
                        val duration = ended - started
                        durationSum = Math.addExact(durationSum, duration)
                        if (rows.moveToNext()) throw ProbeFailure("BUNDLED_PLAYBACK_DUPLICATE_COMPLETION")
                        mark("bundled_playback_required_ordinal_${paragraph.ordinal}_duration_ms_$duration")
                    }
                }
                if (scopes.size != 1) throw ProbeFailure("BUNDLED_PLAYBACK_CLOCK_SCOPE_CHANGED")
                mark("bundled_playback_required_paragraphs_${required.size}")
                mark("bundled_playback_required_duration_total_ms_$durationSum")
                mark("bundled_playback_all_required_actual_tts_callbacks_and_Room_monotonic_completion_verified")
            }
            requireLocationPreferenceUnchanged()
            mark("app_playback_standard_stop_persists_false_intent_without_location_input_or_generation")
            mark("app_playback_existing_location_preference_preserved_no_activity_launched")
        } catch (error: ProbeFailure) { failure = error.code }
        catch (_: Exception) { failure = "APP_PLAYBACK_OPERATION_FAILED" }
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
                } catch (_: Exception) { cleaned.setException(ProbeFailure("APP_PLAYBACK_CLEANUP_FAILED")) }
            }
            try { cleaned.get((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS) }
            catch (_: Exception) { if (failure == null) failure = "APP_PLAYBACK_CLEANUP_FAILED" }
            database.close()
        }
        failure?.let { mark(it.lowercase()); throw ProbeFailure(it) }
    }

    private class ProbeFailure(val code: String) : IllegalStateException(code)
}
