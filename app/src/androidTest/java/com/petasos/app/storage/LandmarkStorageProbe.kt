package com.petasos.app.storage

import android.app.Instrumentation
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.content.ContentParagraph
import com.petasos.app.content.ContentVersion
import com.petasos.app.generation.StrictJson
import com.petasos.app.location.LandmarkDirectionRule
import com.petasos.app.location.LandmarkPassedRule
import java.util.UUID

/** Disposable Room and synthetic public content only. No location SDK, microphone, audio or model. */
object LandmarkStorageProbe {
    private const val NOW = 100_000L
    private const val PLACE = "landmark-test-place"
    private const val COORDINATE_SOURCE = "landmark-coordinate-r1"

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "landmark-storage-${UUID.randomUUID()}.db"
        try {
            SyntheticStorageFixture.create(context, name, wallTime = { NOW }).use { persistence ->
                SyntheticStorageFixture.install(persistence)
                val database = persistence.database
                val sql = database.openHelper.writableDatabase
                val store = RoomLandmarkCandidates(database, { NOW })
                val installer = RoomContentInstaller(database, { NOW }, ownsDatabase = false)
                fun exec(statement: String, vararg args: Any?) = sql.execSQL(statement, args)
                fun count(statement: String): Long = sql.query(statement).use { check(it.moveToFirst()); it.getLong(0) }
                fun setPolicy(value: Any?, placeId: String = PLACE) = exec("UPDATE place SET landmark_policy_json=? WHERE place_id=?",
                    value?.let(StrictJson::stringify), placeId)
                fun current() = store.candidates().single()

                check(store.candidates().isEmpty())
                // Independent coordinate provenance: no narrative paragraph cites this source.
                exec("""INSERT INTO source_evidence(source_revision_id,source_id,provider,source_url,title,fetched_at,
                    source_updated_at,source_updated_raw,verified_at,content_hash,license_json,allowed_uses_json,attribution,review_status)
                    SELECT ?, 'landmark-coordinate-source',provider,source_url,title,fetched_at,source_updated_at,
                    source_updated_raw,verified_at,content_hash,license_json,allowed_uses_json,attribution,review_status
                    FROM source_evidence WHERE source_revision_id='test-source-r1'""", COORDINATE_SOURCE)
                exec("INSERT INTO place(place_id,name,latitude,longitude,coordinate_source_revision_id) VALUES (?,'SYNTHETIC LANDMARK ONLY',37.0,127.0,?)", PLACE, COORDINATE_SOURCE)
                exec("UPDATE story SET place_id=? WHERE story_id='test-story'", PLACE)
                check(store.candidates().isEmpty()) // A place/coordinate alone is not selection policy.
                setPolicy(policy())
                val initial = current()
                check(initial.placeId == PLACE && initial.story.storyId == "test-story")
                check(initial.latitude == 37.0 && initial.longitude == 127.0 && initial.coordinateSourceRevisionId == COORDINATE_SOURCE)
                check(initial.policy.approachRadiusMeters == 500.0 && initial.policy.cooldownMs == 300_000L)
                check(initial.policy.directionRule == LandmarkDirectionRule.DECREASING_DISTANCE &&
                    initial.policy.passedRule == LandmarkPassedRule.INCREASING_DISTANCE_AFTER_APPROACH)
                check(store.isCurrent(initial) && store.candidates(setOf("test-story")).isEmpty())
                check(!store.isCurrent(initial, setOf("test-story")))
                check(!initial.toString().contains("127.0") && !initial.toString().contains(PLACE))
                check(count("SELECT count(*) FROM preference") == 0L && count("SELECT count(*) FROM session") == 0L)
                mark("landmark_reviewed_policy_coordinates_source_enable_inventory_without_location_consent_or_preferences")

                val invalidPolicies = listOf(
                    policy("reviewStatus" to "pending"), policy("verifiedAt" to NOW + 1), policy("verifiedAt" to 0L),
                    policy("reviewBasis" to " "), policy("approachRadiusMeters" to 0L), policy("cooldownMs" to -1L),
                    policy("approachRadiusMeters" to "500"), policy("verifiedAt" to true), policy("cooldownMs" to "300000"),
                    policy("directionRule" to "any_direction"), policy("passedRule" to "elapsed_time"),
                    policy("qualityAdapterVersion" to ""), policy("policyVersion" to ""),
                    policy("storyId" to "another-story"), policy("associationFactRevisionId" to "missing-fact"),
                    policy("storyId" to "bad story id"), policy().apply { remove("associationFactRevisionId") },
                    policy("unreviewedExtraFlag" to true), policy().apply { remove("qualityAdapterVersion") },
                )
                invalidPolicies.forEach { value -> setPolicy(value); check(store.candidates().isEmpty() && !store.isCurrent(initial)) }
                exec("UPDATE place SET landmark_policy_json=? WHERE place_id=?", "{malformed", PLACE)
                check(store.candidates().isEmpty())
                setPolicy(null)
                check(store.candidates().isEmpty())
                setPolicy(policy())
                check(store.isCurrent(initial))
                exec("UPDATE place SET latitude=NULL,longitude=NULL WHERE place_id=?", PLACE)
                check(store.candidates().isEmpty())
                exec("UPDATE place SET latitude=37.0,longitude=127.0,coordinate_source_revision_id=NULL WHERE place_id=?", PLACE)
                check(store.candidates().isEmpty())
                exec("UPDATE place SET coordinate_source_revision_id=? WHERE place_id=?", COORDINATE_SOURCE, PLACE)
                check(store.isCurrent(initial))
                mark("landmark_unreviewed_missing_unknown_future_or_incomplete_policy_and_coordinates_fail_closed")

                installer.install(storyBundle("unreviewed-story"))
                exec("UPDATE story SET place_id=? WHERE story_id='unreviewed-story'", PLACE)
                check(store.candidates().single().story.storyId == "test-story" && store.isCurrent(initial))
                exec("INSERT INTO place(place_id,name,latitude,longitude,coordinate_source_revision_id) VALUES ('another-reviewed-place','SYNTHETIC OTHER PLACE ONLY',37.0,127.0,?)", COORDINATE_SOURCE)
                setPolicy(policy(storyId = "unreviewed-story", associationFactId = "unreviewed-story-fact-r1"), "another-reviewed-place")
                exec("UPDATE story SET place_id='another-reviewed-place' WHERE story_id='test-story'")
                check(store.candidates().isEmpty() && !store.isCurrent(initial))
                exec("UPDATE story SET place_id=? WHERE story_id='test-story'", PLACE)
                check(store.isCurrent(initial))
                mark("landmark_raw_place_reassignment_does_not_approve_another_story_for_a_reviewed_place")

                exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id=?",
                    StrictJson.stringify(SyntheticStorageFixture.allowedUses(display = false)), COORDINATE_SOURCE)
                check(store.candidates().isEmpty() && !store.isCurrent(initial))
                check(installer.publishedStory("test-story-v1").storyId == "test-story")
                exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id=?",
                    StrictJson.stringify(SyntheticStorageFixture.allowedUses(generation = false)), COORDINATE_SOURCE)
                val manualOnly = current()
                check(store.isCurrent(manualOnly)) // Local landmark playback does not grant generation permission.
                exec("UPDATE source_evidence SET verified_at=3 WHERE source_revision_id=?", COORDINATE_SOURCE)
                check(store.candidates().isEmpty()) // Policy review cannot predate the coordinate review.
                exec("UPDATE source_evidence SET verified_at=? WHERE source_revision_id=?", NOW + 1, COORDINATE_SOURCE)
                check(store.candidates().isEmpty())
                exec("UPDATE source_evidence SET verified_at=-1 WHERE source_revision_id=?", COORDINATE_SOURCE)
                check(store.candidates().isEmpty())
                exec("UPDATE source_evidence SET verified_at=1,allowed_uses_json=? WHERE source_revision_id=?",
                    StrictJson.stringify(SyntheticStorageFixture.allowedUses()), COORDINATE_SOURCE)
                check(store.isCurrent(initial))
                exec("UPDATE source_evidence SET review_status='rejected' WHERE source_revision_id=?", COORDINATE_SOURCE)
                check(store.candidates().isEmpty())
                exec("UPDATE source_evidence SET review_status='verified' WHERE source_revision_id=?", COORDINATE_SOURCE)
                mark("landmark_independent_coordinate_source_revocation_and_future_review_block_candidates_without_generation_promotion")

                val beforeMetadata = current()
                exec("UPDATE place SET longitude=127.001 WHERE place_id=?", PLACE)
                check(!store.isCurrent(beforeMetadata) && current().eligibilityKey != beforeMetadata.eligibilityKey)
                exec("UPDATE place SET longitude=127.0 WHERE place_id=?", PLACE)
                setPolicy(policy("cooldownMs" to 300_001L))
                check(!store.isCurrent(beforeMetadata))
                setPolicy(policy())
                exec("UPDATE source_evidence SET title='CHANGED SYNTHETIC COORDINATE REVIEW' WHERE source_revision_id=?", COORDINATE_SOURCE)
                check(!store.isCurrent(beforeMetadata))
                exec("UPDATE source_evidence SET title='SYNTHETIC TEST ONLY' WHERE source_revision_id=?", COORDINATE_SOURCE)
                check(store.isCurrent(beforeMetadata))
                exec("UPDATE fact_revision SET statement='CHANGED SYNTHETIC FACT' WHERE fact_revision_id='test-fact-r1'")
                check(!store.isCurrent(beforeMetadata) && current().eligibilityKey != beforeMetadata.eligibilityKey)
                exec("UPDATE fact_revision SET statement='Synthetic test fixture 1 only' WHERE fact_revision_id='test-fact-r1'")
                check(store.isCurrent(beforeMetadata))
                exec("UPDATE fact_revision SET verified_at=? WHERE fact_revision_id='test-fact-r1'", NOW + 1)
                check(store.candidates().isEmpty())
                exec("UPDATE fact_revision SET verified_at=1 WHERE fact_revision_id='test-fact-r1'")
                mark("landmark_current_fingerprint_rechecks_place_policy_coordinate_source_and_story_fact_metadata")

                exec("UPDATE paragraph SET text='TAMPERED SYNTHETIC TEXT' WHERE paragraph_id='test-p1'")
                check(store.candidates().isEmpty())
                exec("UPDATE paragraph SET text='합성 저장소 시험 문단 1입니다.' WHERE paragraph_id='test-p1'")
                exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r2'",
                    StrictJson.stringify(SyntheticStorageFixture.allowedUses(distribution = false)))
                check(store.candidates().isEmpty())
                exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r2'",
                    StrictJson.stringify(SyntheticStorageFixture.allowedUses()))
                exec("UPDATE story_version SET valid_until=? WHERE story_version_id='test-story-v1'", NOW)
                check(store.candidates().isEmpty())
                exec("UPDATE story_version SET valid_until=NULL WHERE story_version_id='test-story-v1'")
                val paragraph = ContentParagraph("landmark-generated-p", 0, true, "Synthetic current landmark detail.", null, 1000, listOf("test-fact-r2"))
                val generated = ContentVersion("landmark-generated-v2", "test-story", 2,
                    ContentBundleCodec.versionHash("test-story", 2, listOf(paragraph)), 2, 2, null, 1000, "published", listOf(paragraph))
                installer.installGeneratedVersion("test-story-v1", generated)
                check(!store.isCurrent(initial) && current().story.versionId == "landmark-generated-v2")
                exec("UPDATE source_evidence SET review_status='rejected' WHERE source_revision_id=?", COORDINATE_SOURCE)
                check(store.candidates().isEmpty()) // Independent proof still matters after generated fact omission.
                exec("UPDATE source_evidence SET review_status='verified' WHERE source_revision_id=?", COORDINATE_SOURCE)
                mark("landmark_shared_story_hash_rights_expiry_latest_version_and_independent_coordinate_review_are_rechecked")

                val beforeAssociation = current()
                exec("UPDATE fact_revision SET kind='tradition' WHERE fact_revision_id='test-fact-r1'")
                check(store.candidates().isEmpty() && !store.isCurrent(beforeAssociation))
                exec("UPDATE fact_revision SET kind='verified_fact',verified_at=3 WHERE fact_revision_id='test-fact-r1'")
                check(store.candidates().isEmpty()) // The policy review must not predate association review.
                exec("UPDATE fact_revision SET verified_at=1 WHERE fact_revision_id='test-fact-r1'")
                exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'",
                    StrictJson.stringify(SyntheticStorageFixture.allowedUses(display = false)))
                check(store.candidates().isEmpty() && !store.isCurrent(beforeAssociation))
                check(installer.publishedStory("landmark-generated-v2").storyId == "test-story")
                exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'",
                    StrictJson.stringify(SyntheticStorageFixture.allowedUses(distribution = false)))
                check(store.candidates().isEmpty())
                exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'",
                    StrictJson.stringify(SyntheticStorageFixture.allowedUses(generation = false)))
                check(store.isCurrent(current())) // Association display rights do not grant generation.
                exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'",
                    StrictJson.stringify(SyntheticStorageFixture.allowedUses()))
                check(store.isCurrent(beforeAssociation))
                exec("UPDATE source_evidence SET title='CHANGED OMITTED ASSOCIATION SOURCE' WHERE source_revision_id='test-source-r1'")
                check(!store.isCurrent(beforeAssociation))
                exec("UPDATE source_evidence SET title='SYNTHETIC TEST ONLY' WHERE source_revision_id='test-source-r1'")
                check(store.isCurrent(beforeAssociation))
                exec("DELETE FROM fact_source WHERE fact_revision_id='test-fact-r1'")
                check(store.candidates().isEmpty())
                exec("INSERT INTO fact_source(fact_revision_id,source_revision_id) VALUES ('test-fact-r1','test-source-r1')")
                exec("UPDATE fact_revision SET statement='CHANGED OMITTED ASSOCIATION' WHERE fact_revision_id='test-fact-r1'")
                check(!store.isCurrent(beforeAssociation) && current().eligibilityKey != beforeAssociation.eligibilityKey)
                exec("UPDATE fact_revision SET statement='Synthetic test fixture 1 only' WHERE fact_revision_id='test-fact-r1'")
                check(store.isCurrent(beforeAssociation))
                mark("landmark_durable_story_place_association_fact_and_sources_are_rechecked_after_generated_omission")

                installer.install(storyBundle("overlap", stableFactId = "test-fact", factVersion = 2))
                exec("INSERT INTO place(place_id,name,latitude,longitude,coordinate_source_revision_id) VALUES ('overlap-place','SYNTHETIC OVERLAP ONLY',37.0,127.0,?)", COORDINATE_SOURCE)
                setPolicy(policy(storyId = "overlap", associationFactId = "overlap-fact-r2"), "overlap-place")
                exec("UPDATE story SET place_id='overlap-place' WHERE story_id='overlap'")
                check(store.candidates().size == 2)
                persistence.openSession("landmark-trip", "landmark-session")
                exec("INSERT INTO playback_attempt(attempt_id,session_id,kind,paragraph_id,state,started_at,interrupted_at,clock_scope_id) VALUES ('landmark-interrupted','landmark-session','story','test-p1','interrupted',1,2,'landmark-clock')")
                check(store.candidates().size == 2)
                val beforeComplete = store.candidates()
                exec("INSERT INTO playback_attempt(attempt_id,session_id,kind,paragraph_id,state,started_at,completed_at,clock_scope_id) VALUES ('landmark-completed','landmark-session','story','test-p1','completed',1,2,'landmark-clock')")
                check(store.candidates().isEmpty() && beforeComplete.none { store.isCurrent(it) })
                check(installer.publishedStory("landmark-generated-v2").storyId == "test-story")
                mark("landmark_shared_global_completed_story_stable_fact_and_session_exclusions_preserve_explicit_replay")

                val inventoryIds = (0..64).map { "inventory-${it.toString().padStart(3, '0')}" }
                inventoryIds.forEach { id ->
                    installer.install(storyBundle(id))
                    val placeId = "$id-place"
                    exec("INSERT INTO place(place_id,name,latitude,longitude,coordinate_source_revision_id) VALUES (?,'SYNTHETIC INVENTORY ONLY',37.0,127.0,?)", placeId, COORDINATE_SOURCE)
                    setPolicy(policy(storyId = id, associationFactId = "$id-fact-r1"), placeId)
                    exec("UPDATE story SET place_id=? WHERE story_id=?", placeId, id)
                }
                val inventory = store.candidates()
                check(inventory.map { it.story.storyId } == inventoryIds)
                check(store.candidates(inventoryIds.take(64).toSet()).single().story.storyId == inventoryIds.last())
                check(!store.isCurrent(inventory.first(), inventoryIds.take(64).toSet()))
                check(count("SELECT count(*) FROM route_point") == 0L && count("SELECT count(*) FROM collection_record") == 0L)
                check(count("SELECT count(*) FROM pending_conversation") == 0L && count("SELECT count(*) FROM preference") == 0L)
                mark("landmark_inventory_is_not_alphabetically_capped_before_spatial_selection_and_never_writes_location_or_consent")
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }
    }

    private fun policy(change: Pair<String, Any?>? = null, storyId: String = "test-story",
        associationFactId: String = "test-fact-r1"): MutableMap<String, Any?> = linkedMapOf<String, Any?>(
        "approachRadiusMeters" to 500L, "passedRule" to "increasing_distance_after_approach",
        "directionRule" to "decreasing_distance", "cooldownMs" to 300_000L,
        "policyVersion" to "synthetic-landmark-policy-v1", "qualityAdapterVersion" to "synthetic-landmark-adapter-v1",
        "reviewStatus" to "verified", "verifiedAt" to 2L, "reviewBasis" to "Synthetic test only; no actual landmark approval.",
        "storyId" to storyId, "associationFactRevisionId" to associationFactId,
    ).apply { change?.let { this[it.first] = it.second } }

    @Suppress("UNCHECKED_CAST")
    private fun storyBundle(id: String, stableFactId: String = "$id-fact", factVersion: Int = 1): ByteArray {
        val base = (StrictJson.parse(SyntheticStorageFixture.bundle()) as Map<String, Any?>).toMutableMap()
        val factRevision = "$id-fact-r$factVersion"
        val paragraph = ContentParagraph("$id-p1", 0, true, "Synthetic landmark inventory detail.", null, 1000, listOf(factRevision))
        base["facts"] = listOf(mapOf("factRevisionId" to factRevision, "factId" to stableFactId, "version" to factVersion,
            "statement" to "Synthetic inventory fact only.", "kind" to "verified_fact", "verifiedAt" to 1,
            "sourceRevisionIds" to listOf("test-source-r1")))
        base["stories"] = listOf(mapOf("storyId" to id, "topicId" to "$id-topic", "title" to "SYNTHETIC INVENTORY ONLY",
            "coreQuestion" to "Synthetic landmark verification only", "interestTags" to emptyList<String>(), "placeId" to null))
        base["versions"] = listOf(mapOf("storyVersionId" to "$id-v1", "storyId" to id, "version" to 1,
            "contentHash" to ContentBundleCodec.versionHash(id, 1, listOf(paragraph)), "createdAt" to 1, "verifiedAt" to 1,
            "validUntil" to null, "estimatedDurationMs" to 1000, "status" to "published", "paragraphs" to listOf(mapOf(
                "paragraphId" to paragraph.paragraphId, "ordinal" to 0, "required" to true, "text" to paragraph.text,
                "audioRef" to null, "estimatedDurationMs" to 1000, "factRevisionIds" to paragraph.factRevisionIds))))
        return StrictJson.encode(base)
    }
}
