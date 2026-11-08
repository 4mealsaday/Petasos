package com.petasos.app.storage

import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.location.LandmarkPolicyException
import com.petasos.app.location.LandmarkPolicyReview
import com.petasos.app.location.ReviewedLandmarkPolicyCodec
import java.util.concurrent.Callable

internal data class LandmarkPolicyInstallResult(val newReviews: Int, val unchangedReviews: Int)

/** Explicit first policy installation only; no automatic approval, source/place/story/history writes.
 * A rejected stored envelope is retained permanently. Deleting it manually is outside this API.
 */
internal class RoomLandmarkPolicyInstaller(private val database: StorageDatabase,
    private val wallTime: () -> Long = System::currentTimeMillis) {
    private val sql get() = database.openHelper.writableDatabase

    fun install(bytes: ByteArray): LandmarkPolicyInstallResult = install(ReviewedLandmarkPolicyCodec.decodeBundle(bytes, wallTime()))

    fun install(reviews: List<LandmarkPolicyReview>): LandmarkPolicyInstallResult = checked {
        val frozen = reviews.toList()
        val now = wallTime()
        ReviewedLandmarkPolicyCodec.validateBatch(frozen, now)
        if (frozen.isEmpty()) return@checked LandmarkPolicyInstallResult(0, 0)
        database.runInTransaction(Callable {
            val store = RoomLandmarkPolicyStore(database) { now }
            var added = 0
            var unchanged = 0
            for (review in frozen) {
                val refs = store.references()
                // A retained rejected association also reserves its story/place identity.
                need(refs.none { it.storyId == review.storyId && it.place["place_id"] != review.placeId })
                need(store.prospective(review) != null)
                val expected = ReviewedLandmarkPolicyCodec.storedPolicyJson(review)
                sql.query(SimpleSQLiteQuery("SELECT landmark_policy_json FROM place WHERE place_id=?", arrayOf(review.placeId))).use { row ->
                    need(row.moveToFirst())
                    if (!row.isNull(0)) {
                        need(row.getString(0) == expected)
                        unchanged++
                    } else {
                        sql.execSQL("UPDATE place SET landmark_policy_json=? WHERE place_id=? AND landmark_policy_json IS NULL",
                            arrayOf(expected, review.placeId))
                        need(sql.query("SELECT changes()").use { it.moveToFirst() && it.getLong(0) == 1L })
                        added++
                    }
                }
                need(store.reviewedPolicy(review.placeId)?.storyId == review.storyId)
                // Validate current published text/rights without excluding already-heard explicit content.
                val version = sql.query(SimpleSQLiteQuery("SELECT story_version_id FROM story_version WHERE story_id=? AND status='published' ORDER BY version DESC LIMIT 1",
                    arrayOf(review.storyId))).use { rows -> need(rows.moveToFirst()); rows.getString(0) }
                RoomContentInstaller(database, { now }, false).publishedStory(version)
            }
            LandmarkPolicyInstallResult(added, unchanged)
        })
    }

    /** Compare-and-set revocation preserves the review instead of making it look uninstalled. */
    fun revoke(review: LandmarkPolicyReview): Boolean = checked {
        ReviewedLandmarkPolicyCodec.validate(review, wallTime())
        val expected = ReviewedLandmarkPolicyCodec.storedPolicyJson(review)
        val rejected = ReviewedLandmarkPolicyCodec.rejectedPolicyJson(review)
        database.runInTransaction(Callable {
            sql.query(SimpleSQLiteQuery("SELECT landmark_policy_json FROM place WHERE place_id=?", arrayOf(review.placeId))).use { row ->
                need(row.moveToFirst() && !row.isNull(0))
                if (row.getString(0) == rejected) false else {
                    need(row.getString(0) == expected)
                    sql.execSQL("UPDATE place SET landmark_policy_json=? WHERE place_id=? AND landmark_policy_json=?",
                        arrayOf(rejected, review.placeId, expected))
                    need(sql.query("SELECT changes()").use { it.moveToFirst() && it.getLong(0) == 1L })
                    true
                }
            }
        })
    }

    private fun need(value: Boolean) { if (!value) throw LandmarkPolicyException("LANDMARK_POLICY_INSTALL_REJECTED") }
    private inline fun <T> checked(action: () -> T): T = try { action() }
        catch (failure: LandmarkPolicyException) { throw failure }
        catch (_: Exception) { throw LandmarkPolicyException("LANDMARK_POLICY_STORAGE_UNAVAILABLE") }
}
