package com.petasos.app.storage

import android.app.Instrumentation
import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentException
import com.petasos.app.generation.StrictJson
import com.petasos.app.location.LandmarkPolicyException
import com.petasos.app.location.LandmarkPolicyReview
import com.petasos.app.location.ReviewedLandmarkPolicyCodec
import com.petasos.app.platform.android.content.BundledLandmarkPolicyCatalog
import com.petasos.app.platform.android.content.BundledStoryCatalog
import java.util.UUID

/** Actual packaged public content and reviewed policies in a disposable DB only.
 * This proves installation/currentness, not live approach, road membership, driving or audio.
 */
object BundledLandmarkPolicyProbe {
    private val expectedStories = mapOf(
        "f79e9b40-aa96-5972-b59f-aa99f7bc8522" to "03849d89-1640-5603-be8b-9835d371ed02",
        "f560a058-6fad-5f6f-9ac9-03ea8743ceb3" to "666b77d3-aba4-5ea6-b9fc-1a4c6556eef5",
    )

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "bundled-landmark-policy-${UUID.randomUUID()}.db"
        val database = StorageDatabase.open(context, name)
        try {
            val sql = database.openHelper.writableDatabase
            fun rows(statement: String, vararg args: Any?): List<List<String?>> =
                sql.query(SimpleSQLiteQuery(statement, args)).use { cursor ->
                    buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map {
                        if (cursor.isNull(it)) null else cursor.getString(it)
                    }) }
                }
            fun count(statement: String, vararg args: Any?) = rows(statement, *args).single().single()!!.toLong()
            fun policy(placeId: String) = rows("SELECT landmark_policy_json FROM place WHERE place_id=?", placeId).single().single()
            fun contentSnapshot() = listOf("story", "story_version", "paragraph", "paragraph_fact", "fact_revision",
                "fact_source", "story_region_binding", "place_external_id", "region", "region_revision").associateWith {
                rows("SELECT * FROM $it ORDER BY 1,2")
            }
            fun placeSnapshot() = rows("SELECT place_id,region_id,name,aliases_json,latitude,longitude,coordinate_source_revision_id FROM place ORDER BY place_id")
            fun requireNoUserRecords() = listOf("trip", "session", "preference", "recording_segment", "route_point",
                "playback_attempt", "playback_checkpoint", "conversation_turn", "pending_conversation", "conversation_summary",
                "generation_request", "collection_record", "collection_proof").forEach {
                check(count("SELECT count(*) FROM $it") == 0L)
            }
            fun rejectPolicy(action: () -> Unit) {
                try { action(); error("packaged_landmark_policy_invalid_install_accepted") }
                catch (_: LandmarkPolicyException) { }
            }
            val stories = BundledStoryCatalog(context)
            val catalog = BundledLandmarkPolicyCatalog(context)
            val reviews = requireExpectedReviews(catalog.readReviews())
            check(stories.loadVerified(database) == ContentInstallResult(10, 0))
            check(count("SELECT count(*) FROM place") == 2L)
            check(count("SELECT count(*) FROM place WHERE landmark_policy_json IS NULL") == 2L)
            check(count("SELECT count(*) FROM story WHERE place_id IS NOT NULL") == 0L)
            val candidates = RoomLandmarkCandidates(database)
            check(candidates.candidates().isEmpty())
            val contentBefore = contentSnapshot()
            val placesBefore = placeSnapshot()
            val sourcesBefore = rows("SELECT * FROM source_evidence ORDER BY source_revision_id")
            requireNoUserRecords()

            check(catalog.install(database) == LandmarkPolicyInstallResult(2, 0))
            check(requireCurrentPackagedPolicies(context, database) == 2)
            val issued = candidates.candidates()
            check(issued.size == 2 && issued.associate { it.placeId to it.story.storyId } == expectedStories)
            check(issued.all { candidates.isCurrent(it) })
            check(contentSnapshot() == contentBefore && placeSnapshot() == placesBefore)
            check(rows("SELECT * FROM source_evidence ORDER BY source_revision_id") == sourcesBefore)
            check(count("SELECT count(*) FROM story WHERE place_id IS NOT NULL") == 0L)
            requireNoUserRecords()
            mark("bundled_landmark_two_exact_public_policies_current_and_fresh_candidates_without_story_FK_rewrite")

            val storedPolicies = reviews.associate { it.placeId to policy(it.placeId) }
            check(catalog.install(database) == LandmarkPolicyInstallResult(0, 2))
            check(stories.loadVerified(database) == ContentInstallResult(0, 10))
            check(catalog.install(database) == LandmarkPolicyInstallResult(0, 2))
            check(requireCurrentPackagedPolicies(context, database) == 2)
            check(candidates.candidates().size == 2 && issued.all { candidates.isCurrent(it) })
            check(reviews.all { policy(it.placeId) == storedPolicies[it.placeId] })
            check(contentSnapshot() == contentBefore && placeSnapshot() == placesBefore)
            check(rows("SELECT * FROM source_evidence ORDER BY source_revision_id") == sourcesBefore)
            requireNoUserRecords()
            mark("bundled_landmark_exact_reinstall_preserves_content_coordinates_region_policies_and_no_user_records")

            val installer = RoomLandmarkPolicyInstaller(database)
            val rejected = reviews.first()
            val remaining = reviews.last()
            check(installer.revoke(rejected))
            check(!installer.revoke(rejected))
            val rejectedJson = ReviewedLandmarkPolicyCodec.rejectedPolicyJson(rejected)
            check(policy(rejected.placeId) == rejectedJson)
            check(!candidates.isCurrent(issued.single { it.placeId == rejected.placeId }))
            check(candidates.candidates().single().placeId == remaining.placeId)
            check(candidates.isCurrent(issued.single { it.placeId == remaining.placeId }))
            check(stories.loadVerified(database) == ContentInstallResult(0, 10))
            rejectPolicy { catalog.install(database) }
            check(catalog.load(database) == null)
            check(policy(rejected.placeId) == rejectedJson && policy(remaining.placeId) == storedPolicies[remaining.placeId])
            check(contentSnapshot() == contentBefore && placeSnapshot() == placesBefore)
            check(rows("SELECT * FROM source_evidence ORDER BY source_revision_id") == sourcesBefore)
            mark("bundled_landmark_rejected_review_remains_rejected_after_content_and_policy_reload")

            // Simulated rights withdrawal affects only this disposable DB and is never restored.
            val store = RoomLandmarkPolicyStore(database, System::currentTimeMillis)
            val remainingRecord = checkNotNull(store.reviewedPolicy(remaining.placeId))
            val sourceId = remainingRecord.place.coordinateSourceRevisionId
            val rights = StrictJson.parse(checkNotNull(remainingRecord.coordinateSource["allowed_uses_json"] as? String)
                .toByteArray(Charsets.UTF_8)) as Map<*, *>
            val withdrawn = rights.entries.associate { it.key as String to it.value }.toMutableMap()
            withdrawn["allowDisplay"] = false
            val withdrawnJson = StrictJson.stringify(withdrawn)
            val otherSourcesBefore = rows("SELECT * FROM source_evidence WHERE source_revision_id<>? ORDER BY source_revision_id", sourceId)
            sql.execSQL("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id=?", arrayOf(withdrawnJson, sourceId))
            check(store.reviewedPolicy(remaining.placeId) == null)
            check(candidates.candidates().isEmpty() && issued.none { candidates.isCurrent(it) })
            // A single still-verified policy isolates rights failure from the other rejected policy.
            rejectPolicy { installer.install(listOf(remaining)) }
            check(catalog.load(database) == null)
            try { stories.loadVerified(database); error("packaged_landmark_source_rights_restored") }
            catch (failure: ContentException) { check(failure.code == "CONTENT_IMMUTABLE_CONFLICT") }
            check(stories.load(database) == null)
            check(rows("SELECT allowed_uses_json FROM source_evidence WHERE source_revision_id=?", sourceId).single().single() == withdrawnJson)
            check(rows("SELECT * FROM source_evidence WHERE source_revision_id<>? ORDER BY source_revision_id", sourceId) == otherSourcesBefore)
            check(policy(rejected.placeId) == rejectedJson && policy(remaining.placeId) == storedPolicies[remaining.placeId])
            check(contentSnapshot() == contentBefore && placeSnapshot() == placesBefore)
            check(count("SELECT count(*) FROM story WHERE place_id IS NOT NULL") == 0L)
            requireNoUserRecords()
            mark("bundled_landmark_withdrawn_coordinate_display_rights_reject_new_and_issued_candidates_and_are_never_restored")
        } finally { database.close(); context.deleteDatabase(name) }
    }

    /** Read-only product assertion after real Host browse/initialization. Never installs or repairs.
     * Completed stories may correctly have no automatic candidates; policy authority is independent.
     */
    internal fun requireCurrentPackagedPolicies(context: Context, database: StorageDatabase): Int {
        val reviews = requireExpectedReviews(BundledLandmarkPolicyCatalog(context).readReviews())
        val store = RoomLandmarkPolicyStore(database, System::currentTimeMillis)
        val sql = database.openHelper.readableDatabase
        for (review in reviews) {
            val current = checkNotNull(store.reviewedPolicy(review.placeId))
            check(current.storyId == review.storyId && current.policy == review.policy &&
                current.place.fingerprint() == review.placeMetadataSha256 &&
                current.association.metadata["fact_revision_id"] == review.associationFactRevisionId)
            sql.query(SimpleSQLiteQuery("SELECT landmark_policy_json FROM place WHERE place_id=?", arrayOf(review.placeId))).use {
                check(it.moveToFirst() && !it.isNull(0) && it.getString(0) == ReviewedLandmarkPolicyCodec.storedPolicyJson(review) && !it.moveToNext())
            }
            sql.query(SimpleSQLiteQuery("SELECT place_id FROM story WHERE story_id=?", arrayOf(review.storyId))).use {
                check(it.moveToFirst() && it.isNull(0) && !it.moveToNext())
            }
        }
        return reviews.size
    }

    private fun requireExpectedReviews(reviews: List<LandmarkPolicyReview>): List<LandmarkPolicyReview> {
        check(reviews.size == 2 && reviews.associate { it.placeId to it.storyId } == expectedStories)
        return reviews.sortedBy { it.placeId }
    }
}
