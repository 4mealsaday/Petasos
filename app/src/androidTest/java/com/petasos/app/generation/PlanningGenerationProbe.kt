package com.petasos.app.generation

import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.playback.OperationToken
import com.petasos.app.runtime.RuntimeCancellation
import com.petasos.app.storage.RoomContentInstaller
import com.petasos.app.storage.RoomStoryGenerationStore
import com.petasos.app.storage.StorageDatabase
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit actual configured HTTPS/model probe using an operator-supplied reviewed public bundle.
 * Only a disposable Room DB receives the generated version. No host, microphone, audio, location,
 * playback credit or production DB is opened. Bodies, source text and credentials are never logged.
 */
object PlanningGenerationProbe {
    private const val RESUME_CONTEXT = "개인 대화가 없는 개발 연결 시험입니다. 제공된 공개 이야기 후보에서 이어 들을 위치를 선택합니다."

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(!File(context.cacheDir, "development-generation-token").exists())
        val configurations = DeviceGenerationConfigStore(context)
        val configuration = configurations.load() ?: throw GenerationException(GenerationFailureKind.CONFIGURATION)
        check(URI(configuration.serverOrigin).host == "localhost")
        val incoming = File(context.cacheDir, "content-bundle.json")
        val databaseName = "planning-generation-live-${UUID.randomUUID()}.db"
        val active = AtomicBoolean(true)
        var database: StorageDatabase? = null
        var client: GenerationClient? = null
        try {
            check(incoming.isFile && incoming.canonicalPath == incoming.absolutePath) { "PUBLIC_BUNDLE_REQUIRED" }
            val bytes = readBundle(incoming)
            val bundle = ContentBundleCodec.decode(bytes, System.currentTimeMillis())
            check(bundle.sources.all { it.reviewStatus == "verified" && it.allowedUses.allowGeneration &&
                !it.provider.startsWith("synthetic", ignoreCase = true) &&
                URI(it.sourceUrl).scheme == "https" && URI(it.sourceUrl).host != "example.invalid" }) { "REVIEWED_PUBLIC_BUNDLE_REQUIRED" }
            val seedVersion = bundle.versions.firstOrNull() ?: error("PUBLIC_STORY_REQUIRED")
            val db = StorageDatabase.open(context, databaseName)
            database = db
            val installer = RoomContentInstaller(db, System::currentTimeMillis, ownsDatabase = false)
            check(installer.install(bytes).newVersions == bundle.versions.size)
            bytes.fill(0)
            val seed = installer.publishedStory(seedVersion.storyVersionId)
            val sql = db.openHelper.writableDatabase
            fun count(statement: String, vararg args: Any?): Long = sql.query(SimpleSQLiteQuery(statement, args)).use {
                check(it.moveToFirst()); it.getLong(0)
            }
            val tripId = UUID.randomUUID().toString()
            val sessionId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            // Test-owned identity and empty active session only; no product lifecycle is invoked.
            db.runInTransaction(Callable {
                sql.execSQL("INSERT INTO trip(trip_id,started_at,status) VALUES (?,?,'active')", arrayOf<Any?>(tripId, now))
                sql.execSQL("INSERT INTO session(session_id,trip_id,started_at,status) VALUES (?,?,?,'active')", arrayOf<Any?>(sessionId, tripId, now))
            })
            val immutableTables = listOf("story", "source_evidence", "fact_revision", "fact_source", "place", "region")
            val originalCounts = immutableTables.map { count("SELECT count(*) FROM $it") }
            val beforeVersionCount = count("SELECT count(*) FROM story_version")
            val storage = RoomStoryGenerationStore(db, System::currentTimeMillis)
            val request = OperationToken(sessionId, 0, UUID.randomUUID().toString())
            val prepared = checkNotNull(storage.prepare(request, seed.versionId, emptyList(), isRuntimeCurrent = active::get))
            check(prepared.context.interestTags.isEmpty())
            mark("generation_planning_live_reviewed_public_seed_in_disposable_room_no_private_context")

            val api = GenerationClient(configurations, configurations)
            client = api
            val story = success(await<ValidatedGenerationStory>(25_000) { api.submitStory(request, prepared.context, it) }, "story", mark)
            check(story.storyId == seed.storyId && story.storyVersionId != seed.versionId)
            check(story.paragraphs.isNotEmpty() && story.paragraphs.all { it.kind in setOf("fact", "tradition") })
            check(!story.modelId.startsWith("synthetic", ignoreCase = true) && !story.provider.startsWith("synthetic", ignoreCase = true))
            val generated = checkNotNull(storage.install(prepared, story)) { "GENERATED_STORY_INSTALL_REJECTED" }
            check(installer.publishedStory(generated.versionId) == generated)
            check(installer.publishedStory(seed.versionId) == seed)
            check(immutableTables.map { count("SELECT count(*) FROM $it") } == originalCounts)
            check(count("SELECT count(*) FROM story_version") == beforeVersionCount + 1)
            sql.query(SimpleSQLiteQuery("SELECT content_hash FROM story_version WHERE story_version_id=?", arrayOf(seed.versionId))).use {
                check(it.moveToFirst() && it.getString(0) == seedVersion.contentHash)
            }
            seedVersion.paragraphs.forEach { paragraph ->
                sql.query(SimpleSQLiteQuery("SELECT text FROM paragraph WHERE paragraph_id=?", arrayOf(paragraph.paragraphId))).use {
                    check(it.moveToFirst() && it.getString(0) == paragraph.text)
                }
            }
            check(count("SELECT sum(estimated_duration_ms) FROM paragraph WHERE story_version_id=?", generated.versionId) == story.estimatedDurationMs)
            mark("generation_planning_live_actual_https_story_installed_new_immutable_version_old_content_preserved")
            sql.query(SimpleSQLiteQuery("SELECT session_id,task,input_hash,cancel_epoch,status,model_id,provider,prompt_version,usage_json,turn_id,turn_version FROM generation_request WHERE request_id=?", arrayOf(request.id))).use {
                check(it.moveToFirst() && it.getString(0) == sessionId && it.getString(1) == "story" &&
                    it.getString(2) == story.inputHash && it.getLong(3) == 0L && it.getString(4) == "succeeded" &&
                    it.getString(5) == story.modelId && it.getString(6) == story.provider && it.getString(7) == story.promptVersion &&
                    it.isNull(9) && it.isNull(10))
                val usage = StrictJson.parse(it.getString(8).toByteArray()) as Map<*, *>
                check(usage.keys == setOf("inputTokens", "outputTokens") &&
                    usage["inputTokens"] == story.inputTokens && usage["outputTokens"] == story.outputTokens)
            }
            mark("generation_planning_live_request_scoped_provenance_and_usage_verified_no_version_request_fk_claim")

            val candidate = ResumeGenerationCandidate(generated.versionId, generated.paragraphIds.first(), "story")
            val resumeContext = ResumeGenerationContext(RESUME_CONTEXT, listOf(candidate), null) {
                active.get() && try { installer.publishedStory(generated.versionId) == generated } catch (_: Exception) { false }
            }
            val resumeStarted = SystemClock.elapsedRealtime()
            // submitResume retains its real 3,000 ms deadline. This outer wait only detects a broken
            // callback; it does not change request timing, retry, or use another provider/model.
            val resumed = await<ValidatedResumePlan>(8_000) {
                api.submitResume(OperationToken(sessionId, 0, UUID.randomUUID().toString()), resumeContext, it)
            }
            val elapsed = SystemClock.elapsedRealtime() - resumeStarted
            mark("generation_planning_live_resume_callback_elapsed_ms_$elapsed")
            when (resumed) {
                is GenerationOutcome.Success -> {
                    val plan = resumed.value
                    check(plan.transitionText == null)
                    if (plan.action == "wait") {
                        check(plan.targetContentId == null && plan.paragraphId == null)
                        mark("generation_planning_live_resume_success_wait_validated_no_target_selected")
                    } else {
                        check(plan.action in setOf("resume", "skip", "switch") && plan.targetContentId == candidate.contentId &&
                            plan.paragraphId == candidate.paragraphId)
                        mark("generation_planning_live_resume_success_exact_candidate_target_verified")
                    }
                }
                is GenerationOutcome.Failure -> {
                    if (resumed.failure.kind != GenerationFailureKind.RETRYABLE) {
                        mark("generation_planning_live_resume_failed_${resumed.failure.kind.name.lowercase()}")
                        throw GenerationException(resumed.failure.kind)
                    }
                    // The public client category covers deadline/network/retryable-provider errors;
                    // it cannot truthfully identify which one occurred or claim API target success.
                    mark("generation_planning_live_resume_retryable_or_3000ms_deadline_fallback_no_target_verified")
                }
            }
            check(count("SELECT count(*) FROM generation_request") == 1L)
            listOf("playback_attempt", "collection_record", "conversation_turn", "recording_segment", "route_point", "preference").forEach {
                check(count("SELECT count(*) FROM $it") == 0L)
            }
            mark("generation_planning_live_no_playback_conversation_location_or_preference_records")
        } finally {
            active.set(false)
            try { client?.close() }
            finally {
                try { database?.close() }
                finally {
                    try { check(context.deleteDatabase(databaseName) || !context.getDatabasePath(databaseName).exists()) }
                    finally { check(!incoming.exists() || incoming.delete()) }
                }
            }
        }
        mark("generation_planning_live_disposable_database_and_public_transfer_file_removed")
    }

    private fun readBundle(file: File): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(StrictJson.MAX_BYTES + 1)
        var length = 0
        while (length < buffer.size) {
            val read = input.read(buffer, length, buffer.size - length)
            if (read < 0) break
            if (read > 0) length += read
        }
        check(length in 1..StrictJson.MAX_BYTES) { "INVALID_PUBLIC_BUNDLE" }
        buffer.copyOf(length)
    }

    private fun <T> success(outcome: GenerationOutcome<T>, task: String, mark: (String) -> Unit): T = when (outcome) {
        is GenerationOutcome.Success -> outcome.value
        is GenerationOutcome.Failure -> {
            mark("generation_planning_live_${task}_failed_${outcome.failure.kind.name.lowercase()}")
            throw GenerationException(outcome.failure.kind)
        }
    }

    private fun <T> await(limitMs: Long, start: ((GenerationOutcome<T>) -> Unit) -> RuntimeCancellation): GenerationOutcome<T> {
        val result = CompletableFuture<GenerationOutcome<T>>()
        val cancellation = start { result.complete(it) }
        return try { result.get(limitMs, TimeUnit.MILLISECONDS) }
        catch (_: Exception) {
            cancellation.cancel()
            throw IllegalStateException("GENERATION_PLANNING_CALLBACK_UNAVAILABLE")
        }
    }
}
