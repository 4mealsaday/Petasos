package com.petasos.app.storage

import android.app.Instrumentation
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.content.ContentException
import com.petasos.app.content.ContentParagraph
import com.petasos.app.content.ContentVersion
import com.petasos.app.generation.StrictJson
import com.petasos.app.location.LandmarkPlaceMetadata
import com.petasos.app.location.LandmarkPolicyException
import com.petasos.app.location.LandmarkPolicyReview
import com.petasos.app.location.ReviewedLandmarkPolicyCodec
import java.util.UUID

/** Test APK only: disposable Room, synthetic coordinates/content/review and scripted history.
 * Never reads a real policy review, product database, location source, microphone, audio or network.
 */
object LandmarkPolicyStorageProbe {
    private const val NOW = 100_000L
    private const val FIRST_PLACE = "landmark-policy-first-place"
    private const val SECOND_PLACE = "landmark-policy-second-place"
    private const val SECOND_STORY = "landmark-policy-second-story"
    private const val COORDINATE_SOURCE = "landmark-policy-coordinate-r1"

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "landmark-policy-${UUID.randomUUID()}.db"
        try {
            SyntheticStorageFixture.create(context, name, wallTime = { NOW }).use { runtime ->
                val database = runtime.database
                val sql = database.openHelper.writableDatabase
                val content = RoomContentInstaller(database, { NOW }, ownsDatabase = false)
                val policies = RoomLandmarkPolicyInstaller(database, { NOW })
                val candidates = RoomLandmarkCandidates(database, { NOW })
                fun exec(statement: String, vararg args: Any?) = sql.execSQL(statement, args)
                fun count(statement: String, vararg args: Any?): Long = sql.query(SimpleSQLiteQuery(statement, args)).use {
                    check(it.moveToFirst()); it.getLong(0)
                }
                fun text(statement: String, vararg args: Any?): String? = sql.query(SimpleSQLiteQuery(statement, args)).use {
                    check(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
                }
                fun policyJson(placeId: String = FIRST_PLACE) = text("SELECT landmark_policy_json FROM place WHERE place_id=?", placeId)
                fun setPolicy(value: String?, placeId: String = FIRST_PLACE) =
                    exec("UPDATE place SET landmark_policy_json=? WHERE place_id=?", value, placeId)
                fun reject(action: () -> Unit) {
                    try { action(); error("landmark_policy_invalid_install_accepted") }
                    catch (_: LandmarkPolicyException) { }
                }
                fun rejectContent(action: () -> Unit) {
                    try { action(); error("landmark_policy_changed_place_accepted") }
                    catch (_: ContentException) { }
                }
                fun single() = candidates.candidates().single()
                fun immutableCounts() = listOf("source_evidence", "fact_revision", "fact_source", "place", "story").map {
                    count("SELECT count(*) FROM $it")
                }

                val bytes = contentBundle()
                check(content.install(bytes).newVersions == 2)
                check(count("SELECT count(*) FROM story WHERE place_id IS NULL") == 2L)
                check(count("SELECT count(*) FROM place WHERE landmark_policy_json IS NULL") == 2L)
                check(candidates.candidates().isEmpty())
                val first = review(FIRST_PLACE, "test-story", "test-fact-r1")
                val second = review(SECOND_PLACE, SECOND_STORY, "test-fact-r2")
                val immutableBefore = immutableCounts()
                check(policies.install(policyBundle(emptyList())).newReviews == 0)
                check(policies.install(emptyList()).unchangedReviews == 0)
                // First write is valid; failure at the second review must undo it in the same transaction.
                reject { policies.install(policyBundle(listOf(first, second.copy(placeMetadataSha256 = "b".repeat(64))))) }
                check(policyJson() == null && policyJson(SECOND_PLACE) == null)
                reject { policies.install(listOf(first, first.copy(placeId = SECOND_PLACE))) }
                reject { policies.install(listOf(first.copy(storyId = "missing-story"))) }
                reject { policies.install(listOf(first.copy(associationFactRevisionId = "missing-fact"))) }
                reject { policies.install(listOf(first.copy(verifiedAt = 0))) }
                check(immutableCounts() == immutableBefore && candidates.candidates().isEmpty())
                mark("landmark_policy_synthetic_TX05_places_and_strict_review_batch_atomic_rollback_no_auto_approval")

                // Interrupted playback predates policy installation and must remain eligible/history intact.
                runtime.openSession("landmark-policy-trip", "landmark-policy-session")
                exec("INSERT INTO playback_attempt(attempt_id,session_id,kind,paragraph_id,state,started_at,interrupted_at,clock_scope_id) VALUES ('landmark-policy-interrupted','landmark-policy-session','story','test-p1','interrupted',1,2,'landmark-policy-clock')")
                val installed = policies.install(policyBundle(listOf(first)))
                check(installed.newReviews == 1 && installed.unchangedReviews == 0)
                val originalJson = checkNotNull(policyJson())
                val initial = single()
                check(initial.story.storyId == "test-story" && initial.placeId == FIRST_PLACE && candidates.isCurrent(initial))
                check(count("SELECT count(*) FROM story WHERE place_id IS NULL") == 2L)
                check(candidates.candidates(setOf("test-story")).isEmpty() && !candidates.isCurrent(initial, setOf("test-story")))
                val repeat = policies.install(listOf(first))
                check(repeat.newReviews == 0 && repeat.unchangedReviews == 1)
                reject { policies.install(listOf(first.copy(reviewBasis = "OTHER SYNTHETIC REVIEW"))) }
                reject { policies.install(listOf(first.copy(policy = first.policy.copy(cooldownMs = 1)))) }
                check(content.install(bytes).unchangedVersions == 2 && policyJson() == originalJson)
                check(immutableCounts() == immutableBefore && count("SELECT count(*) FROM playback_attempt") == 1L)
                check(text("SELECT state FROM playback_attempt WHERE attempt_id='landmark-policy-interrupted'") == "interrupted")
                mark("landmark_policy_NULL_story_place_explicit_binding_exact_reinstall_TX05_policy_and_history_preserved")

                // Compatibility cannot downgrade a pinned NULL-story review into an unpinned legacy one.
                @Suppress("UNCHECKED_CAST")
                val parsed = StrictJson.parse(originalJson.toByteArray()) as Map<String, Any?>
                val legacy = StrictJson.stringify(parsed.filterKeys { it in ReviewedLandmarkPolicyCodec.legacyKeys })
                setPolicy(legacy)
                check(candidates.candidates().isEmpty())
                exec("UPDATE story SET place_id=? WHERE story_id='test-story'", FIRST_PLACE)
                check(single().story.storyId == "test-story")
                exec("UPDATE story SET place_id=? WHERE story_id='test-story'", SECOND_PLACE)
                check(candidates.candidates().isEmpty())
                setPolicy(originalJson)
                check(candidates.candidates().isEmpty())
                exec("UPDATE story SET place_id=? WHERE story_id='test-story'", FIRST_PLACE)
                check(single().story.storyId == "test-story")
                exec("UPDATE story SET place_id=NULL WHERE story_id='test-story'")
                check(candidates.isCurrent(initial))
                setPolicy(StrictJson.stringify(parsed - "evidenceSha256"))
                check(candidates.candidates().isEmpty())
                reject { policies.install(listOf(first)) }
                check(content.install(bytes).unchangedVersions == 2)
                check(policyJson() == StrictJson.stringify(parsed - "evidenceSha256"))
                setPolicy(originalJson) // Restore synthetic corruption; this is not a supported product mutation.
                mark("landmark_policy_legacy_only_nonnull_exact_place_and_marked_envelope_never_downgrades")

                exec("UPDATE place SET longitude=127.001 WHERE place_id=?", FIRST_PLACE)
                check(candidates.candidates().isEmpty() && !candidates.isCurrent(initial))
                reject { policies.install(listOf(first)) }
                rejectContent { content.install(bytes) }
                exec("UPDATE place SET longitude=127.0 WHERE place_id=?", FIRST_PLACE)
                exec("UPDATE place SET coordinate_source_revision_id='test-source-r1' WHERE place_id=?", FIRST_PLACE)
                check(candidates.candidates().isEmpty())
                reject { policies.install(listOf(first)) }
                exec("UPDATE place SET coordinate_source_revision_id=? WHERE place_id=?", COORDINATE_SOURCE, FIRST_PLACE)
                check(candidates.isCurrent(initial))
                // Both coordinate and association sources must have been reviewed by policy time.
                for (sourceId in listOf(COORDINATE_SOURCE, "test-source-r1")) {
                    exec("UPDATE source_evidence SET verified_at=3 WHERE source_revision_id=?", sourceId)
                    check(candidates.candidates().isEmpty())
                    reject { policies.install(listOf(first)) }
                    exec("UPDATE source_evidence SET verified_at=1 WHERE source_revision_id=?", sourceId)
                }
                exec("UPDATE fact_revision SET verified_at=3 WHERE fact_revision_id='test-fact-r1'")
                check(candidates.candidates().isEmpty())
                reject { policies.install(listOf(first)) }
                exec("UPDATE fact_revision SET verified_at=1,kind='tradition' WHERE fact_revision_id='test-fact-r1'")
                check(candidates.candidates().isEmpty())
                reject { policies.install(listOf(first)) }
                exec("UPDATE fact_revision SET kind='verified_fact' WHERE fact_revision_id='test-fact-r1'")
                check(candidates.isCurrent(initial))
                mark("landmark_policy_exact_coordinate_source_pin_verified_fact_and_review_chronology_rechecked")

                for (sourceId in listOf(COORDINATE_SOURCE, "test-source-r1")) {
                    val rights = checkNotNull(text("SELECT allowed_uses_json FROM source_evidence WHERE source_revision_id=?", sourceId))
                    for (uses in listOf(SyntheticStorageFixture.allowedUses(display = false), SyntheticStorageFixture.allowedUses(distribution = false))) {
                        exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id=?", StrictJson.stringify(uses), sourceId)
                        check(candidates.candidates().isEmpty() && !candidates.isCurrent(initial))
                        reject { policies.install(listOf(first)) }
                    }
                    exec("UPDATE source_evidence SET allowed_uses_json=?,review_status='rejected' WHERE source_revision_id=?", rights, sourceId)
                    check(candidates.candidates().isEmpty())
                    reject { policies.install(listOf(first)) }
                    exec("UPDATE source_evidence SET review_status='verified' WHERE source_revision_id=?", sourceId)
                }
                // Independent coordinate metadata explicitly has no generation grant.
                check(candidates.isCurrent(initial))
                check(text("SELECT allowed_uses_json FROM source_evidence WHERE source_revision_id=?", COORDINATE_SOURCE)!!.contains("\"allowGeneration\":false"))
                mark("landmark_policy_coordinate_and_association_rights_withdrawal_rejects_new_and_issued_candidates")

                val paragraph = ContentParagraph("landmark-policy-generated-p", 0, true, "Synthetic generated detail only.", null, 1000, listOf("test-fact-r2"))
                val generated = ContentVersion("landmark-policy-generated-v2", "test-story", 2,
                    ContentBundleCodec.versionHash("test-story", 2, listOf(paragraph)), 2, 2, null, 1000, "published", listOf(paragraph))
                content.installGeneratedVersion("test-story-v1", generated)
                val generatedCandidate = single()
                check(generatedCandidate.story.versionId == generated.storyVersionId && !candidates.isCurrent(initial))
                val associationRights = checkNotNull(text("SELECT allowed_uses_json FROM source_evidence WHERE source_revision_id='test-source-r1'"))
                exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'",
                    StrictJson.stringify(SyntheticStorageFixture.allowedUses(display = false)))
                check(content.publishedStory(generated.storyVersionId).storyId == "test-story")
                check(candidates.candidates().isEmpty() && !candidates.isCurrent(generatedCandidate))
                reject { policies.install(listOf(first)) }
                exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'", associationRights)
                check(candidates.isCurrent(generatedCandidate))
                mark("landmark_policy_generated_omission_keeps_independent_association_source_revocation")

                // Inject a conflicting valid envelope only in this disposable DB to exercise read guards.
                val duplicate = second.copy(storyId = first.storyId, associationFactRevisionId = first.associationFactRevisionId)
                reject { policies.install(listOf(duplicate)) }
                setPolicy(ReviewedLandmarkPolicyCodec.storedPolicyJson(duplicate), SECOND_PLACE)
                check(candidates.candidates().isEmpty() && !candidates.isCurrent(generatedCandidate))
                reject { policies.install(listOf(first)) }
                setPolicy(null, SECOND_PLACE) // Remove fixture corruption, not a product revocation path.
                check(candidates.isCurrent(generatedCandidate))
                mark("landmark_policy_duplicate_story_place_ambiguity_rejected_without_alphabetical_selection")

                // Seed exact completed history/cards/proofs without claiming actual audio playback.
                for (index in 1..2) exec("INSERT INTO playback_attempt(attempt_id,session_id,kind,paragraph_id,state,started_at,completed_at,clock_scope_id) VALUES (?, 'landmark-policy-session','story',?,'completed',1,2,'landmark-policy-clock')",
                    "landmark-policy-completed-$index", "test-p$index")
                exec("INSERT INTO collection_record(collection_id,collection_type,story_id,story_version_id,rule_version,earned_at,created_at) VALUES ('landmark-policy-card','story','test-story','test-story-v1','0.8',2,2)")
                for (index in 1..2) exec("INSERT INTO collection_proof(collection_id,proof_key,attempt_id) VALUES ('landmark-policy-card',?,?)",
                    "test-p$index", "landmark-policy-completed-$index")
                check(candidates.candidates().isEmpty() && !candidates.isCurrent(generatedCandidate))
                check(content.publishedStory(generated.storyVersionId).storyId == "test-story")
                check(policies.install(listOf(first)).unchangedReviews == 1)
                check(policies.revoke(first) && !policies.revoke(first))
                val rejectedJson = ReviewedLandmarkPolicyCodec.rejectedPolicyJson(first)
                check(policyJson() == rejectedJson && candidates.candidates().isEmpty())
                reject { policies.install(listOf(first)) }
                check(content.install(bytes).unchangedVersions == 2 && policyJson() == rejectedJson)
                check(immutableCounts() == immutableBefore && count("SELECT count(*) FROM playback_attempt") == 3L)
                check(count("SELECT count(*) FROM collection_record WHERE collection_id='landmark-policy-card' AND story_id='test-story' AND story_version_id='test-story-v1'") == 1L)
                check(count("SELECT count(*) FROM collection_proof WHERE collection_id='landmark-policy-card'") == 2L)
                check(count("SELECT count(*) FROM story WHERE place_id IS NULL") == 2L)
                check(count("SELECT count(*) FROM route_point") == 0L && count("SELECT count(*) FROM preference") == 0L &&
                    count("SELECT count(*) FROM pending_conversation") == 0L && count("SELECT count(*) FROM recording_segment") == 0L)
                mark("landmark_policy_completed_history_cards_and_proofs_preserved_rejected_reinstall_cannot_reactivate")
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }
        mark("landmark_policy_disposable_database_cleaned_no_product_policy_location_audio_or_model")
    }

    private fun metadata(placeId: String) = LandmarkPlaceMetadata(placeId, null, "SYNTHETIC LANDMARK POLICY ONLY", "[]",
        if (placeId == FIRST_PLACE) 37.0 else 37.1, 127.0, COORDINATE_SOURCE)

    private fun review(placeId: String, storyId: String, factId: String): LandmarkPolicyReview =
        ReviewedLandmarkPolicyCodec.decode(StrictJson.encode(linkedMapOf(
            "formatVersion" to 1L, "placeId" to placeId, "placeMetadataSha256" to metadata(placeId).fingerprint(),
            "storyId" to storyId, "associationFactRevisionId" to factId,
            "approachRadiusMeters" to 500L, "passedRule" to "increasing_distance_after_approach", "directionRule" to "decreasing_distance",
            "cooldownMs" to 300_000L, "policyVersion" to "synthetic-landmark-policy-only-v1", "qualityAdapterVersion" to "synthetic-landmark-quality-only-v1",
            "reviewStatus" to "verified", "verifiedAt" to 2L,
            "reviewBasis" to "SYNTHETIC TEST ONLY; no actual place, route, threshold or approach approval.", "evidenceSha256" to "a".repeat(64),
        )), NOW)

    private fun policyBundle(reviews: List<LandmarkPolicyReview>) = StrictJson.encode(mapOf("formatVersion" to 1L,
        "reviews" to reviews.map { StrictJson.parse(ReviewedLandmarkPolicyCodec.storedPolicyJson(it).toByteArray()) }))

    @Suppress("UNCHECKED_CAST")
    private fun contentBundle(): ByteArray {
        val base = (StrictJson.parse(SyntheticStorageFixture.bundle()) as Map<String, Any?>).toMutableMap()
        val sources = base["sources"] as List<Map<String, Any?>>
        val coordinateText = "Synthetic coordinate metadata only; no real POI or measured accuracy."
        base["sources"] = sources + (sources.first() + mapOf("sourceRevisionId" to COORDINATE_SOURCE,
            "sourceId" to "landmark-policy-coordinate-source", "normalizedText" to coordinateText,
            "contentHash" to ContentBundleCodec.sha256(coordinateText.toByteArray(Charsets.UTF_8)),
            "allowedUses" to SyntheticStorageFixture.allowedUses(generation = false)))
        base["places"] = listOf(FIRST_PLACE, SECOND_PLACE).map { id ->
            val place = metadata(id)
            mapOf("placeId" to id, "name" to place.name, "aliases" to emptyList<String>(),
                "latitude" to place.latitude.toString(), "longitude" to place.longitude.toString(),
                "coordinateSystem" to "WGS84", "coordinateSourceRevisionId" to COORDINATE_SOURCE,
                "coordinateReviewBasis" to "Synthetic coordinate fixture only", "externalIds" to emptyList<Any>())
        }
        base["stories"] = (base["stories"] as List<Map<String, Any?>>) + mapOf("storyId" to SECOND_STORY,
            "topicId" to "test-topic", "title" to "SYNTHETIC SECOND STORY ONLY", "coreQuestion" to "Synthetic policy rollback only",
            "interestTags" to emptyList<String>(), "placeId" to null)
        val paragraph = ContentParagraph("landmark-policy-second-p", 0, true, "Synthetic second story only.", null, 1000, listOf("test-fact-r2"))
        base["versions"] = (base["versions"] as List<Map<String, Any?>>) + mapOf("storyVersionId" to "landmark-policy-second-v1",
            "storyId" to SECOND_STORY, "version" to 1L, "contentHash" to ContentBundleCodec.versionHash(SECOND_STORY, 1, listOf(paragraph)),
            "createdAt" to 1L, "verifiedAt" to 1L, "validUntil" to null, "estimatedDurationMs" to 1000L, "status" to "published",
            "paragraphs" to listOf(mapOf("paragraphId" to paragraph.paragraphId, "ordinal" to 0L, "required" to true,
                "text" to paragraph.text, "audioRef" to null, "estimatedDurationMs" to 1000L, "factRevisionIds" to paragraph.factRevisionIds)))
        return StrictJson.encode(base)
    }
}
