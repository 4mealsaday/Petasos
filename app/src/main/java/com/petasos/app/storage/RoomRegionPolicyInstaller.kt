package com.petasos.app.storage

import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.location.RegionDatasetManifest
import com.petasos.app.location.RegionGeometryMetadata
import com.petasos.app.location.RegionPolicyException
import com.petasos.app.location.RegionPolicyReview
import com.petasos.app.location.ReviewedRegionPolicyCodec
import java.util.concurrent.Callable

internal data class RegionPolicyInstallResult(val newReviews: Int, val unchangedReviews: Int)

/** First reviewed policy finalization only; no source, geometry, consent, proof, or schema mutation. */
internal class RoomRegionPolicyInstaller(
    private val database: StorageDatabase,
    private val wallTime: () -> Long = System::currentTimeMillis,
) {
    private val sql get() = database.openHelper.writableDatabase

    fun install(manifest: RegionDatasetManifest, reviews: List<RegionPolicyReview>): RegionPolicyInstallResult = try {
        val copy = reviews.toList()
        requirePolicy(copy.size in 1..512 && copy.map { it.regionRevisionId }.distinct().size == copy.size)
        database.runInTransaction(Callable {
            val now = wallTime()
            val geometry = RoomRegionInstaller(database)
            var added = 0
            var unchanged = 0
            for (review in copy) {
                val entry = ReviewedRegionPolicyCodec.validateAgainstManifest(manifest, review, now)
                requirePolicy(geometry.isAuthorized(manifest, entry.regionRevisionId))
                val expected = ReviewedRegionPolicyCodec.storedPolicyJson(manifest, entry, review)
                val pending = RegionGeometryMetadata.pendingPolicyJson(manifest, entry)
                sql.query(SimpleSQLiteQuery("SELECT policy_json,verified_at FROM region_revision WHERE region_revision_id=?",
                    arrayOf(entry.regionRevisionId))).use { row ->
                    requirePolicy(row.moveToFirst())
                    val policy = row.getString(0)
                    val verified = if (row.isNull(1)) null else row.getLong(1)
                    if (policy == expected && verified == review.verifiedAt) unchanged++
                    else {
                        requirePolicy(policy == pending && verified == null)
                        val proofs = sql.query(SimpleSQLiteQuery("SELECT count(*) FROM collection_proof WHERE region_revision_id=?",
                            arrayOf(entry.regionRevisionId))).use { check(it.moveToFirst()); it.getLong(0) }
                        requirePolicy(proofs == 0L)
                        sql.execSQL("UPDATE region_revision SET policy_json=?,verified_at=? WHERE region_revision_id=? AND policy_json=? AND verified_at IS NULL",
                            arrayOf<Any?>(expected, review.verifiedAt, entry.regionRevisionId, pending))
                        requirePolicy(sql.query("SELECT changes()").use { it.moveToFirst() && it.getLong(0) == 1L })
                        added++
                    }
                }
                requirePolicy(RoomRegionPolicyStore(database, { now }).reviewedPolicy(entry.regionRevisionId)?.policy == review.policy)
            }
            RegionPolicyInstallResult(added, unchanged)
        })
    } catch (failure: RegionPolicyException) { throw failure }
      catch (_: Exception) { throw RegionPolicyException("REGION_POLICY_STORAGE_UNAVAILABLE") }

    private fun requirePolicy(value: Boolean) { if (!value) throw RegionPolicyException("REGION_POLICY_INSTALL_REJECTED") }
}
