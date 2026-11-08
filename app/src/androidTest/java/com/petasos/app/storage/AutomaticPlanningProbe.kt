package com.petasos.app.storage

import android.app.Instrumentation
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.content.ContentParagraph
import com.petasos.app.content.ContentVersion
import com.petasos.app.generation.StrictJson
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.location.LocationFreshness
import com.petasos.app.location.LocationMeaningAdapter
import com.petasos.app.location.LocationNormalizer
import com.petasos.app.location.VerifiedLocationMeaning
import com.petasos.app.planning.*
import com.petasos.app.playback.*
import com.petasos.app.runtime.*
import java.util.UUID

/** Disposable real Room + synthetic content/scripted runtime. No GPS, microphone, speaker or HTTP. */
object AutomaticPlanningProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "automatic-planning-${UUID.randomUUID()}.db"
        try {
            SyntheticStorageFixture.create(context, name, wallTime = { 100_000 }).use { persistence ->
                SyntheticStorageFixture.install(persistence)
                val store = RoomAutomaticStoryStore(persistence.database, { 100_000 })
                val preferences = RoomVehiclePreferenceStore(persistence.database, { 100_000 })
                val installer = RoomContentInstaller(persistence.database, { 100_000 }, ownsDatabase = false)
                fun exec(statement: String, vararg args: Any?) = persistence.database.openHelper.writableDatabase.execSQL(statement, args)
                fun count(statement: String): Long = persistence.database.openHelper.writableDatabase.query(statement).use {
                    check(it.moveToFirst()); it.getLong(0)
                }
                check(store.candidates(null).isEmpty())
                check(count("SELECT count(*) FROM preference") == 0L)
                check(count("SELECT count(*) FROM session") == 0L)
                check(count("SELECT count(*) FROM trip") == 0L)
                check(count("SELECT count(*) FROM playback_attempt") == 0L)
                mark("automatic_missing_preferences_and_read_only_lookup_create_no_runtime_rows")

                installer.install(bundle(listOf(
                    FixtureStory("z-region", emptyList()),
                    FixtureStory("a-interest", listOf("history")),
                    FixtureStory("b-overlap", listOf("history"), stableFact = "a-interest-fact", factVersion = 2),
                    FixtureStory("c-case", listOf("History")),
                )))
                exec("INSERT INTO region(region_id,name) VALUES ('automatic-region','SYNTHETIC TEST ONLY')")
                exec("INSERT INTO region_revision(region_revision_id,region_id,code,dataset_version,name,source_revision_id,policy_json,verified_at) VALUES ('automatic-region-r1','automatic-region','00001','synthetic-boundary-v1','SYNTHETIC TEST ONLY','test-source-r1','{\"geometryStatus\":\"ready\"}',NULL)")
                RegionalBindingFixture.bindExistingStory(persistence.database, "z-region", "automatic-region-r1")
                preferences.setKeywords(listOf("history"), null)
                val initial = store.candidates("automatic-region")
                check(initial.map { it.story.storyId } == listOf("z-region", "a-interest", "b-overlap"))
                check(initial.first().reason == CandidateReason.CURRENT_REGION)
                check(initial.drop(1).all { it.reason == CandidateReason.INTEREST && it.regionId == null })
                check(store.candidates(null).map { it.story.storyId } == listOf("a-interest", "b-overlap"))
                check(initial.all { store.isCurrent(it, "automatic-region") })
                check(!store.isCurrent(initial.first(), null))
                check(!initial[1].toString().contains("history"))
                check(!preferences.readSettings().locationEnabled)
                mark("automatic_region_precedes_exact_explicit_interest_and_interest_needs_no_location")
                run {
                    // Explicitly synthetic interpretation; the product Pleos adapter remains UNKNOWN.
                    val point = LocationNormalizer(LocationMeaningAdapter {
                        VerifiedLocationMeaning("synthetic-freshness-only", "epoch_ms", it.rawTimestampMillis, 1.0, 30.0)
                    }).normalize(LocationSample(37.48, 127.03, 0.0, 0f, 1f, 1, 10_000, 20_000),
                        LocationReadingKind.LAST_KNOWN, "synthetic-cache", "synthetic", "synthetic-clock")
                    val deadline = checkNotNull(LocationFreshness.deadlineElapsedMs(point))
                    check(deadline == 20_001L && point.observedAtEpochMs == 1L && point.receivedAtEpochMs == 10_000L)
                    var now = 20_000L
                    var offered: Pair<PreparedStory, String>? = null
                    val bridge = AutomaticStoryPlaybackBridge(ContentPlanner("synthetic-clock"),
                        { region, excluded -> store.candidates(region, excluded) },
                        object : PlannedStoryOutput {
                            override fun offer(story: PreparedStory, offerId: String) { offered = story to offerId }
                            override fun withdraw(storyId: String, versionId: String) { offered = null }
                        }, { now }, store::isCurrent)
                    val planning = PlanningContext("automatic-region", null, now, "synthetic-clock", true, false,
                        point.receivedAtElapsedMs, locationFreshUntilElapsedMs = deadline)
                    bridge.update(planning)
                    val (story, offerId) = checkNotNull(offered)
                    check(story.storyId == "z-region")
                    val reference = OutputReference.StoryParagraph(story.storyId, story.versionId, story.paragraphIds.first())
                    now = deadline
                    check(bridge.isAutomaticStoryCurrent(offerId, reference) && bridge.isAutomaticStoryOffered(offerId, reference))
                    now++ // No new location/update/timer is needed to revoke SDK-start permission.
                    check(!bridge.isAutomaticStoryCurrent(offerId, reference) && !bridge.isAutomaticStoryOffered(offerId, reference))
                    bridge.update(planning.copy(elapsedMs = now))
                    check(offered?.first?.storyId == "a-interest")
                    check(!preferences.readSettings().locationEnabled)
                    bridge.clear()
                }
                mark("automatic_cached_measurement_age_expires_at_output_authorization_without_refresh_interest_remains_available")
                val timing = RoomStoryTimingStore(persistence)
                check(timing.paragraphDurations(initial.first().story) == listOf(1000L))
                check(timing.paragraphDurations(initial.first().story.copy(storyId = "wrong-story")) == null)
                exec("UPDATE paragraph SET estimated_duration_ms=2000 WHERE paragraph_id='z-region-p1'")
                check(timing.paragraphDurations(initial.first().story) == null) // Metadata is in the reviewed content hash.
                exec("UPDATE paragraph SET estimated_duration_ms=1000 WHERE paragraph_id='z-region-p1'")
                check(timing.paragraphDurations(initial.first().story) == listOf(1000L))
                mark("automatic_duration_reads_exact_reviewed_paragraph_order_and_rejects_tampering")

                preferences.setKeywords(listOf("History"), preferences.readSettings().version)
                check(!store.isCurrent(initial[1], "automatic-region"))
                check(store.isCurrent(initial.first(), "automatic-region"))
                check(store.candidates(null).single().story.storyId == "c-case")
                exec("DELETE FROM preference")
                check(store.candidates(null).isEmpty())
                check(store.candidates("automatic-region").single().story.storyId == "z-region")
                preferences.setKeywords(listOf("history"), null)
                val beforeInvalid = store.candidates(null).first()
                exec("UPDATE preference SET keywords_json='[1]' WHERE scope_id='vehicle'")
                check(!store.isCurrent(beforeInvalid, null))
                check(store.candidates("automatic-region").single().story.storyId == "z-region")
                preferences.setKeywords(listOf("history"), preferences.readRecordingSettings().version)
                mark("automatic_changed_deleted_or_invalid_preferences_revoke_only_interest_authority")

                val beforeMetadata = store.candidates(null).first()
                exec("UPDATE story SET interest_tags_json='[\"other\"]' WHERE story_id='a-interest'")
                check(!store.isCurrent(beforeMetadata, null))
                exec("UPDATE story SET interest_tags_json='[\"history\"]',title='CHANGED SYNTHETIC TITLE' WHERE story_id='a-interest'")
                check(!store.isCurrent(beforeMetadata, null))
                val metadataCurrent = store.candidates(null).first()
                check(store.isCurrent(metadataCurrent, null))
                exec("UPDATE story SET interest_tags_json='[1]' WHERE story_id='a-interest'")
                check(!store.isCurrent(metadataCurrent, null))
                exec("UPDATE story SET interest_tags_json='[\"history\"]' WHERE story_id='a-interest'")
                exec("UPDATE story SET region_id='automatic-region' WHERE story_id='a-interest'")
                check(!store.isCurrent(metadataCurrent, "automatic-region"))
                val rawReclassified = store.candidates("automatic-region")
                check(rawReclassified.first().story.storyId == "z-region" && rawReclassified.first().reason == CandidateReason.CURRENT_REGION)
                check(rawReclassified.single { it.story.storyId == "a-interest" }.let {
                    it.reason == CandidateReason.INTEREST && it.regionId == null
                })
                check(count("SELECT count(*) FROM story_region_binding WHERE story_id='a-interest'") == 0L)
                exec("UPDATE story SET region_id=NULL WHERE story_id='a-interest'")
                mark("automatic_story_metadata_revoke_old_offer_keys_raw_region_reassignment_cannot_authorize_region")

                val current = store.candidates(null).first()
                exec("UPDATE story_version SET valid_until=100000 WHERE story_version_id='a-interest-v1'")
                check(!store.isCurrent(current, null))
                exec("UPDATE story_version SET valid_until=NULL,status='retired' WHERE story_version_id='a-interest-v1'")
                check(!store.isCurrent(current, null))
                exec("UPDATE story_version SET status='published' WHERE story_version_id='a-interest-v1'")
                exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'",
                    StrictJson.stringify(SyntheticStorageFixture.allowedUses(display = false)))
                check(store.candidates("automatic-region").isEmpty())
                check(!store.isCurrent(current, null))
                exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'",
                    StrictJson.stringify(SyntheticStorageFixture.allowedUses(generation = false)))
                check(store.candidates(null).size == 2) // Local playback does not require generation permission.
                exec("UPDATE paragraph SET text='TAMPERED SYNTHETIC TEXT' WHERE paragraph_id='a-interest-p1'")
                check(!store.isCurrent(current, null))
                exec("UPDATE paragraph SET text=? WHERE paragraph_id='a-interest-p1'", SYNTHETIC_TEXT)
                exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'",
                    StrictJson.stringify(SyntheticStorageFixture.allowedUses()))
                val nextParagraph = ContentParagraph("a-interest-v2-p1", 0, true, SYNTHETIC_TEXT, null, 1000, listOf("a-interest-fact-r1"))
                val next = ContentVersion("a-interest-v2", "a-interest", 2,
                    ContentBundleCodec.versionHash("a-interest", 2, listOf(nextParagraph)), 2, 2, null, 1000, "published", listOf(nextParagraph))
                installer.installGeneratedVersion("a-interest-v1", next)
                check(!store.isCurrent(current, null))
                check(store.candidates(null).first().story.versionId == "a-interest-v2")
                mark("automatic_current_rights_expiry_retirement_hash_and_latest_published_version_are_rechecked")

                persistence.openSession("automatic-trip", "automatic-session")
                exec("INSERT INTO playback_attempt(attempt_id,session_id,kind,paragraph_id,state,started_at,interrupted_at,clock_scope_id) VALUES ('automatic-interrupted','automatic-session','story','a-interest-p1','interrupted',1,2,'synthetic-clock')")
                check(store.candidates(null).size == 2)
                val beforeCompleted = store.candidates(null)
                exec("INSERT INTO playback_attempt(attempt_id,session_id,kind,paragraph_id,state,started_at,completed_at,clock_scope_id) VALUES ('automatic-completed','automatic-session','story','a-interest-p1','completed',1,2,'synthetic-clock')")
                check(store.candidates(null).isEmpty())
                check(beforeCompleted.none { store.isCurrent(it, null) })
                persistence.openSession("automatic-next-trip", "automatic-next-session")
                check(store.candidates(null).isEmpty())
                check(installer.publishedStory("a-interest-v2").storyId == "a-interest")
                check(installer.publishedStory("b-overlap-v1").storyId == "b-overlap")
                check(count("SELECT count(*) FROM playback_attempt") == 2L)
                mark("automatic_completed_story_and_stable_fact_across_revision_trip_excluded_interrupted_and_explicit_replay_preserved")

                val padding = (0..71).map { FixtureStory("d-padding-${it.toString().padStart(3, '0')}", listOf("unselected")) }
                (padding + FixtureStory("zz-late-match", listOf("late"))).chunked(24).forEach { installer.install(bundle(it)) }
                preferences.setKeywords(listOf("unselected"), preferences.readSettings().version)
                check(store.candidates(null).size == 64)
                exec("UPDATE story SET region_id='automatic-region' WHERE story_id LIKE 'd-padding-%'")
                val reviewedPriority = store.candidates("automatic-region")
                check(reviewedPriority.size == 64 && reviewedPriority.first().story.storyId == "z-region")
                check(reviewedPriority.first().reason == CandidateReason.CURRENT_REGION &&
                    reviewedPriority.drop(1).all { it.reason == CandidateReason.INTEREST && it.regionId == null })
                exec("UPDATE story SET region_id=NULL WHERE story_id LIKE 'd-padding-%'")
                mark("automatic_reviewed_region_priority_precedes_unreviewed_metadata_and_result_limit")
                val excluded = padding.take(64).map { it.id }.toSet()
                check(store.candidates(null, excluded).map { it.story.storyId } == padding.drop(64).map { it.id })
                exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'",
                    StrictJson.stringify(SyntheticStorageFixture.allowedUses(display = false)))
                check(store.candidates(null, excluded).isEmpty())
                exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'",
                    StrictJson.stringify(SyntheticStorageFixture.allowedUses()))
                check(store.candidates(null, excluded).first().story.storyId == "d-padding-064")
                mark("automatic_session_started_exclusions_precede_result_limit_without_bypassing_tags_or_rights")
                preferences.setKeywords(listOf("late"), preferences.readSettings().version)
                check(store.candidates(null, excluded).single().story.storyId == "zz-late-match")
                check(store.candidates(null).single().story.storyId == "zz-late-match")
                check(count("SELECT count(*) FROM playback_attempt") == 2L)
                check(count("SELECT count(*) FROM route_point") == 0L)
                check(count("SELECT count(*) FROM pending_conversation") == 0L)
                mark("automatic_tag_filter_precedes_result_limit_and_queries_never_create_location_or_private_payload")
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }

        listOf("preference", "rights", "history", "metadata").forEach { boundary ->
            verifyFinalOutputBoundary(instrumentation, boundary)
        }
        mark("automatic_room_mutation_after_attempt_registration_blocks_scripted_sdk_start_at_final_output_boundary")
    }

    private fun verifyFinalOutputBoundary(instrumentation: Instrumentation, boundary: String) {
        val context = instrumentation.targetContext
        val name = "automatic-boundary-${UUID.randomUUID()}.db"
        try {
            SyntheticStorageFixture.create(context, name, wallTime = { 100_000 }).use { persistence ->
                SyntheticStorageFixture.install(persistence)
                val sql = persistence.database.openHelper.writableDatabase
                sql.execSQL("UPDATE story SET interest_tags_json='[\"history\"]' WHERE story_id='test-story'")
                val preferences = RoomVehiclePreferenceStore(persistence.database, { 100_000 })
                preferences.setKeywords(listOf("history"), null)
                val store = RoomAutomaticStoryStore(persistence.database, { 100_000 })
                val candidate = store.candidates(null).single()
                var injected = false
                val delegate = object : RuntimePersistence by persistence {
                    override fun registerOutput(attempt: OperationToken, reference: OutputReference, turnId: String?) {
                        persistence.registerOutput(attempt, reference, turnId)
                        if (injected) return
                        injected = true
                        when (boundary) {
                            "preference" -> preferences.setKeywords(emptyList(), preferences.readSettings().version)
                            "rights" -> sql.execSQL("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'",
                                arrayOf(StrictJson.stringify(SyntheticStorageFixture.allowedUses(display = false))))
                            "history" -> sql.execSQL("INSERT INTO playback_attempt(attempt_id,session_id,kind,paragraph_id,state,started_at,completed_at,clock_scope_id) VALUES ('boundary-heard',?,'story','test-p2','completed',1,2,'synthetic-clock')", arrayOf(attempt.sessionId))
                            "metadata" -> sql.execSQL("UPDATE story SET interest_tags_json='[]' WHERE story_id='test-story'")
                            else -> error("unknown_synthetic_boundary")
                        }
                    }
                }
                val queue = ScriptedQueue()
                val speech = ScriptedSpeech()
                val runtime = PlaybackRuntime(PlaybackCoordinator("boundary-trip", "boundary-session"), queue,
                    RuntimeClock { 1_000 }, delegate, speech, contentAuthority = RuntimeContentAuthority { offer, reference ->
                        offer == "synthetic-offer" && reference.storyId == candidate.story.storyId &&
                            reference.versionId == candidate.story.versionId && store.isCurrent(candidate, null)
                    })
                queue.drain()
                runtime.dispatch(PlaybackEvent.OfferStory(candidate.story, "synthetic-offer"))
                runtime.dispatch(PlaybackEvent.Play)
                queue.drain()
                check(injected && speech.starts == 0)
                check(sql.query("SELECT count(*) FROM playback_attempt WHERE state IN ('prepared','playing')").use { it.moveToFirst(); it.getLong(0) } == 0L)
                runtime.close()
                queue.drain()
                check(runtime.snapshot().closed)
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }
    }

    private class ScriptedQueue : RuntimeQueue {
        private val tasks = ArrayDeque<() -> Unit>()
        override fun execute(task: () -> Unit) { tasks.addLast(task) }
        override fun schedule(delayMs: Long, task: () -> Unit) = RuntimeCancellation { }
        fun drain() {
            var count = 0
            while (tasks.isNotEmpty()) { check(++count < 1000); tasks.removeFirst().invoke() }
        }
    }

    private class ScriptedSpeech : RuntimeSpeech {
        var starts = 0
        override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) {
            starts++
            callback(RuntimeOutputEvent.FAILED)
            callback(RuntimeOutputEvent.STOPPED)
        }
        override fun stopOutput(attempt: OperationToken) = Unit
        override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) { error("microphone_not_allowed_in_probe") }
        override fun stopInput(input: OperationToken) = Unit
        override fun close() = Unit
    }

    private data class FixtureStory(val id: String, val tags: List<String>, val stableFact: String = "$id-fact", val factVersion: Int = 1)

    private fun bundle(stories: List<FixtureStory>): ByteArray {
        @Suppress("UNCHECKED_CAST")
        val base = StrictJson.parse(SyntheticStorageFixture.bundle()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val baseFact = (base.getValue("facts") as List<Map<String, Any?>>).first()
        val paragraphs = stories.associate { story -> story.id to listOf(ContentParagraph("${story.id}-p1", 0, true,
            SYNTHETIC_TEXT, null, 1000, listOf("${story.id}-fact-r${story.factVersion}"))) }
        return StrictJson.encode(base + mapOf(
            "facts" to stories.map { baseFact + mapOf("factRevisionId" to "${it.id}-fact-r${it.factVersion}",
                "factId" to it.stableFact, "version" to it.factVersion) },
            "stories" to stories.map { mapOf("storyId" to it.id, "topicId" to "synthetic-topic", "title" to "SYNTHETIC TEST ONLY",
                "coreQuestion" to "Synthetic automatic planning only", "interestTags" to it.tags, "placeId" to null) },
            "versions" to stories.map { story -> mapOf("storyVersionId" to "${story.id}-v1", "storyId" to story.id, "version" to 1,
                "contentHash" to ContentBundleCodec.versionHash(story.id, 1, paragraphs.getValue(story.id)),
                "createdAt" to 1, "verifiedAt" to 1, "validUntil" to null, "estimatedDurationMs" to 1000, "status" to "published",
                "paragraphs" to paragraphs.getValue(story.id).map { p -> mapOf("paragraphId" to p.paragraphId,
                    "ordinal" to p.ordinal, "required" to p.required, "text" to p.text, "audioRef" to null,
                    "estimatedDurationMs" to p.estimatedDurationMs, "factRevisionIds" to p.factRevisionIds) }) },
        ))
    }

    private const val SYNTHETIC_TEXT = "자동편성 검증용 합성 공개 문단입니다. 실제 관광 사실이 아닙니다."
}
