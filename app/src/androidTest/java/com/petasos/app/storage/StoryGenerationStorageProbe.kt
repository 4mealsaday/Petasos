package com.petasos.app.storage

import android.app.Instrumentation
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.generation.StrictJson
import com.petasos.app.generation.ValidatedGenerationStory
import com.petasos.app.generation.ValidatedStoryParagraph
import com.petasos.app.playback.OperationToken
import com.petasos.app.runtime.RuntimeText
import java.util.UUID

/** Disposable synthetic Room DB only. No HTTP, real content, microphone, audio or autoplay. */
object StoryGenerationStorageProbe {
    private const val NOW = 10_000L

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "story-generation-probe-${UUID.randomUUID()}.db"
        try {
            SyntheticStorageFixture.create(context, name, wallTime = { NOW }).use { persistence ->
                val seed = SyntheticStorageFixture.install(persistence)
                persistence.openSession("generation-trip", "generation-session")
                val database = persistence.database
                val sql = database.openHelper.writableDatabase
                val store = RoomStoryGenerationStore(database, { NOW })
                fun count(statement: String): Long = sql.query(SimpleSQLiteQuery(statement)).use { check(it.moveToFirst()); it.getLong(0) }
                fun counts(): List<Long> = listOf("story", "story_version", "paragraph", "paragraph_fact", "fact_revision", "fact_source", "source_evidence", "generation_request", "collection_record", "playback_attempt")
                    .map { count("SELECT count(*) FROM $it") }
                fun prepare(excluded: List<String> = emptyList(), route: Long? = null, guard: () -> Boolean = { true }): PreparedStoryGeneration? =
                    store.prepare(OperationToken("generation-session", 0, "request-${UUID.randomUUID()}"), seed.versionId, excluded, route, guard)
                fun rights(enabled: Boolean) = sql.execSQL("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'",
                    arrayOf(StrictJson.stringify(SyntheticStorageFixture.allowedUses(generation = enabled))))
                val baseline = counts()
                val work = checkNotNull(prepare())
                check(counts() == baseline)
                check(work.context.storyId == seed.storyId && work.context.regionId == null)
                check(work.context.interestTags.isEmpty())
                check(work.context.approvedFactStableIds == mapOf("test-fact-r1" to "test-fact", "test-fact-r2" to "test-fact-two"))
                check(work.context.isCurrent())
                check(prepare(excluded = listOf("test-fact", "test-fact-two")) == null)
                check(prepare(guard = { false }) == null)
                check(store.prepare(OperationToken("missing-session", 0, "missing-request"), seed.versionId, emptyList()) { true } == null)
                check(store.prepare(OperationToken("generation-session", 1, "wrong-epoch"), seed.versionId, emptyList()) { true } == null)
                check(counts() == baseline)
                mark("story_generation_prepare_read_only_active_session_epoch_seed_and_stable_fact_exclusions")

                listOf("personal", "transition", "tradition").forEach { kind ->
                    check(store.install(work, response(kind = kind)) == null)
                }
                check(store.install(work, response(secondKind = "tradition")) == null)
                check(store.install(work, response(facts = listOf("unknown-fact"))) == null)
                check(store.install(work, response(sources = listOf("test-source-r2"))) == null)
                check(store.install(work, response(sources = listOf("test-source-r1", "test-source-r2"))) == null)
                check(store.install(work, response(duplicateFact = true)) == null)
                check(store.install(work, response(storyId = "model-invented-story")) == null)
                check(store.install(work, response(firstKey = "test-p1")) == null)
                check(store.install(checkNotNull(prepare(excluded = listOf("test-fact"))), response()) == null)
                check(counts() == baseline)
                mark("story_generation_personal_transition_kind_change_unknown_or_excluded_facts_and_incomplete_sources_rejected_without_writes")

                rights(false)
                check(!work.context.isCurrent() && prepare() == null)
                check(store.install(work, response()) == null)
                rights(true)
                sql.execSQL("UPDATE source_evidence SET content_hash=? WHERE source_revision_id='test-source-r1'", arrayOf("a".repeat(64)))
                check(!work.context.isCurrent() && store.install(work, response()) == null)
                val original = com.petasos.app.content.ContentBundleCodec.sha256("합성 저장소 출처 1 입니다. 실제 관광 사실을 주장하지 않습니다.".toByteArray(Charsets.UTF_8))
                sql.execSQL("UPDATE source_evidence SET content_hash=? WHERE source_revision_id='test-source-r1'", arrayOf(original))
                sql.execSQL("UPDATE paragraph SET text='Synthetic tampering only' WHERE paragraph_id='test-p1'")
                check(!work.context.isCurrent() && store.install(work, response()) == null)
                sql.execSQL("UPDATE paragraph SET text='합성 저장소 시험 문단 1입니다.' WHERE paragraph_id='test-p1'")
                check(counts() == baseline && work.context.isCurrent())
                mark("story_generation_source_rights_fingerprint_and_seed_content_hash_rechecked_before_install")

                val dependent = checkNotNull(prepare(route = 0))
                sql.execSQL("UPDATE session SET route_revision=1 WHERE session_id='generation-session'")
                check(!dependent.context.isCurrent() && store.install(dependent, response()) == null)
                check(work.context.isCurrent()) // An explicitly route-independent story survives route changes.
                sql.execSQL("UPDATE session SET cancel_epoch=1 WHERE session_id='generation-session'")
                check(!work.context.isCurrent() && store.install(work, response()) == null)
                sql.execSQL("UPDATE session SET cancel_epoch=0 WHERE session_id='generation-session'")
                check(counts() == baseline)
                mark("story_generation_cancel_epoch_and_only_declared_route_dependency_invalidate_work")

                sql.execSQL("INSERT INTO preference(scope_id,keywords_json,voice_json,story_frequency_json,location_enabled,version,updated_at) VALUES ('vehicle',?,'{}','{}',0,1,1)",
                    arrayOf(StrictJson.stringify(listOf("역사", "자연"))))
                val preferred = checkNotNull(prepare())
                check(preferred.context.interestTags == listOf("역사", "자연"))
                check(!work.context.isCurrent())
                sql.execSQL("UPDATE preference SET keywords_json=?,version=2 WHERE scope_id='vehicle'", arrayOf(StrictJson.stringify((1..21).map { "keyword-$it" })))
                check(prepare() == null && !preferred.context.isCurrent())
                sql.execSQL("UPDATE preference SET keywords_json=?,version=3 WHERE scope_id='vehicle'", arrayOf(StrictJson.stringify((1..20).map { "keyword-$it" })))
                check(checkNotNull(prepare()).context.interestTags.size == 20)
                check(counts() == baseline)
                mark("story_generation_reads_only_explicit_vehicle_keywords_max20_and_preference_changes_revoke_snapshot")

                var denyAfterAppend = false
                val race = checkNotNull(prepare(guard = { !denyAfterAppend || count("SELECT count(*) FROM story_version") == 1L }))
                denyAfterAppend = true
                check(store.install(race, response()) == null)
                check(counts() == baseline)
                sql.execSQL("CREATE TRIGGER generation_fail_provenance BEFORE INSERT ON generation_request BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
                try {
                    store.install(checkNotNull(prepare()), response())
                    error("expected_sanitized_storage_failure")
                } catch (failure: StorageUnavailable) { check(failure.code == "story_generation_storage_failure") }
                sql.execSQL("DROP TRIGGER generation_fail_provenance")
                check(counts() == baseline && count("SELECT count(*) FROM story_version WHERE status='draft'") == 0L)
                mark("story_generation_late_guard_and_provenance_failure_roll_back_entire_version_transaction")

                // Protect historical references while a new immutable version is appended.
                sql.execSQL("INSERT INTO playback_attempt(attempt_id,session_id,kind,paragraph_id,state,started_at,completed_at,clock_scope_id) VALUES ('old-heard','generation-session','story','test-p1','completed',1,2,'synthetic-clock')")
                val successful = checkNotNull(prepare())
                val competing = checkNotNull(prepare())
                val result = response()
                val installed = checkNotNull(store.install(successful, result))
                check(installed.storyId == seed.storyId && installed.versionId == result.storyVersionId)
                check(persistence.prepareStory(seed.versionId).paragraphIds == seed.paragraphIds)
                check(persistence.prepareStory(installed.versionId).paragraphIds == installed.paragraphIds)
                check(count("SELECT count(*) FROM story") == 1L && count("SELECT count(*) FROM story_version") == 2L)
                check(count("SELECT count(*) FROM source_evidence") == 2L && count("SELECT count(*) FROM fact_revision") == 2L)
                check(count("SELECT count(*) FROM playback_attempt WHERE attempt_id='old-heard' AND paragraph_id='test-p1'") == 1L)
                check(count("SELECT sum(estimated_duration_ms) FROM paragraph WHERE story_version_id='generated-v2'") == 2001L)
                check(count("SELECT count(*) FROM paragraph WHERE story_version_id='generated-v2' AND required=1 AND estimated_duration_ms>0") == 2L)
                check(count("SELECT count(*) FROM generation_request WHERE task='story' AND status='succeeded' AND turn_id IS NULL AND turn_version IS NULL AND input_hash='${"b".repeat(64)}'") == 1L)
                sql.query(SimpleSQLiteQuery("SELECT usage_json FROM generation_request")).use {
                    check(it.moveToFirst())
                    val usage = StrictJson.parse(it.getString(0).toByteArray()) as Map<*, *>
                    check(usage.keys == setOf("inputTokens", "outputTokens"))
                }
                check(count("SELECT count(*) FROM collection_record") == 0L)
                check(count("SELECT count(*) FROM playback_attempt") == 1L)
                val after = counts()
                check(!successful.context.isCurrent() && !competing.context.isCurrent())
                check(store.install(successful, result) == null && store.install(competing, response(versionId = "competing-v2")) == null)
                check(counts() == after)
                mark("story_generation_atomic_append_preserves_stable_graph_history_and_exact_estimated_duration_without_autoplay_or_credit")
                mark("story_generation_request_provenance_usage_only_and_concurrent_or_duplicate_install_rejected")

                val beforeReplacement = checkNotNull(prepare())
                persistence.openSession("generation-trip", "replacement-session")
                check(!beforeReplacement.context.isCurrent() && store.install(beforeReplacement, response(versionId = "stale-v3")) == null)
                check(counts() == after)
                store.close()
                mark("story_generation_session_replacement_revokes_old_generation_without_touching_content")
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }
        verifyRepeatedGenerationWithSharedSources(instrumentation, mark)
    }

    private fun verifyRepeatedGenerationWithSharedSources(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "story-generation-shared-pool-${UUID.randomUUID()}.db"
        try {
            SyntheticStorageFixture.create(context, name, wallTime = { NOW }).use { persistence ->
                @Suppress("UNCHECKED_CAST")
                val original = StrictJson.parse(SyntheticStorageFixture.bundle()) as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val sharedFacts = (original["facts"] as List<Map<String, Any?>>).map {
                    it + ("sourceRevisionIds" to listOf("test-source-r1"))
                }
                RoomContentInstaller(persistence.database, { NOW }, ownsDatabase = false)
                    .install(StrictJson.encode(original + ("facts" to sharedFacts)))
                persistence.openSession("shared-trip", "shared-session")
                val sql = persistence.database.openHelper.writableDatabase
                val storage = persistence.storyGenerationStore()
                fun prepare(seed: String, excluded: List<String> = emptyList()) = storage.prepare(
                    OperationToken("shared-session", 0, UUID.randomUUID().toString()), seed, excluded,
                ) { true }
                fun singleFactResult(version: String) = ValidatedGenerationStory("test-story", version,
                    listOf(ValidatedStoryParagraph("paragraph-$version", RuntimeText("하나의 합성 사실만 사용하는 시험입니다."),
                        "fact", listOf("test-source-r1"), listOf("test-fact-r1"))),
                    1000, "synthetic-model", "synthetic-provider", "synthetic-story-v0.8", 10, 10, "c".repeat(64))
                val first = checkNotNull(prepare("test-story-v1"))
                check(first.context.excludedFactIds.isEmpty())
                checkNotNull(storage.install(first, singleFactResult("shared-v2")))
                val second = checkNotNull(prepare("shared-v2"))
                check(second.context.approvedFactStableIds == mapOf("test-fact-r1" to "test-fact"))
                check(second.context.sourceRevisionIds == listOf("test-source-r1"))
                check(second.context.excludedFactIds == listOf("test-fact-two"))
                check(checkNotNull(prepare("shared-v2", listOf("caller-excluded"))).context.excludedFactIds ==
                    listOf("caller-excluded", "test-fact-two"))
                check(checkNotNull(prepare("shared-v2", (1..49).map { "caller-$it" })).context.excludedFactIds.size == 50)
                check(prepare("shared-v2", (1..50).map { "caller-$it" }) == null)
                mark("story_generation_partial_version_regeneration_excludes_known_unapproved_stable_facts_without_expanding_authority")

                // Merely intersecting a requested source is insufficient: the server cannot use
                // this revision because its second required source was not requested.
                sql.execSQL("INSERT INTO fact_revision(fact_revision_id,fact_id,version,statement,kind,verified_at) VALUES ('outside-r1','outside-fact',1,'Synthetic outside pool','verified_fact',1)")
                sql.execSQL("INSERT INTO fact_source(fact_revision_id,source_revision_id) VALUES ('outside-r1','test-source-r1')")
                sql.execSQL("INSERT INTO fact_source(fact_revision_id,source_revision_id) VALUES ('outside-r1','test-source-r2')")
                check(second.context.isCurrent())
                check("outside-fact" !in checkNotNull(prepare("shared-v2")).context.excludedFactIds)
                sql.execSQL("INSERT INTO fact_revision(fact_revision_id,fact_id,version,statement,kind,verified_at) VALUES ('new-pool-r1','new-pool-fact',1,'Synthetic new pool','verified_fact',1)")
                sql.execSQL("INSERT INTO fact_source(fact_revision_id,source_revision_id) VALUES ('new-pool-r1','test-source-r1')")
                check(!second.context.isCurrent() && storage.install(second, singleFactResult("shared-stale-v3")) == null)
                val changed = checkNotNull(prepare("shared-v2"))
                check(changed.context.excludedFactIds == listOf("new-pool-fact", "test-fact-two"))
                sql.execSQL("UPDATE fact_revision SET statement='Changed synthetic pool' WHERE fact_revision_id='new-pool-r1'")
                check(!changed.context.isCurrent() && storage.install(changed, singleFactResult("shared-stale-text-v3")) == null)
                val latestWork = checkNotNull(prepare("shared-v2"))
                checkNotNull(storage.install(latestWork, singleFactResult("shared-v3")))
                check(persistence.prepareStory("test-story-v1").paragraphIds == listOf("test-p1", "test-p2"))
                check(persistence.prepareStory("shared-v3").paragraphIds == listOf("paragraph-shared-v3"))
                sql.query("SELECT count(*) FROM generation_request").use { check(it.moveToFirst() && it.getLong(0) == 2L) }
                sql.query("SELECT count(*) FROM story_version").use { check(it.moveToFirst() && it.getLong(0) == 3L) }
                mark("story_generation_source_subset_pool_fingerprint_rechecked_and_effective_exclusions_reproduced_at_install")
                storage.close()
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }
    }

    private fun response(
        kind: String = "fact", secondKind: String = "fact", facts: List<String> = listOf("test-fact-r1"),
        sources: List<String> = listOf("test-source-r1"), duplicateFact: Boolean = false,
        storyId: String = "test-story", firstKey: String = "generated-p1", versionId: String = "generated-v2",
    ): ValidatedGenerationStory = ValidatedGenerationStory(storyId, versionId, listOf(
        ValidatedStoryParagraph(firstKey, RuntimeText("실제 관광 사실이 아닌 합성 생성 시험 문단입니다."), kind, sources, facts),
        ValidatedStoryParagraph("generated-p2", RuntimeText("두 번째 합성 생성 시험 문단입니다."), secondKind,
            listOf(if (duplicateFact) "test-source-r1" else "test-source-r2"),
            listOf(if (duplicateFact) "test-fact-r1" else "test-fact-r2")),
    ), 2001, "synthetic-model", "synthetic-provider", "synthetic-story-v0.8", 12, 18, "b".repeat(64))
}
