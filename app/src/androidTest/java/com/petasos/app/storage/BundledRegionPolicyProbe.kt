package com.petasos.app.storage

import android.app.Instrumentation
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.capability.location.AndroidLocationMetadata
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.location.*
import com.petasos.app.platform.android.location.BundledRegionCatalog
import com.petasos.app.platform.android.location.BundledRegionPolicyCatalog
import java.util.UUID

/**
 * Actual APK review inventory, disposable Room and public synthetic Android points only.
 * Thresholds come exclusively from the selected catalog. This test does not approve them.
 */
internal object BundledRegionPolicyProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "bundled-region-policy-probe-" + UUID.randomUUID() + ".db"
        val catalog = BundledRegionCatalog(context)
        val manifest = catalog.readManifest()
        val now = System.currentTimeMillis()
        check(now >= manifest.source.verifiedAt)
        val policies = BundledRegionPolicyCatalog(context, { now })
        val bundle = policies.readBundle()
        val entries = manifest.regions.associateBy { it.regionRevisionId }
        check(bundle.manifestSha256 == manifest.fingerprint && bundle.reviews.size == TARGET_CODES.size)
        check(bundle.reviews.map { entries.getValue(it.regionRevisionId).code }.toSet() == TARGET_CODES)
        check(bundle.reviews.all { it.policy.qualityAdapterVersion == AndroidLocationMeaningAdapter.VERSION })
        val reviews = bundle.reviews.sortedBy { entries.getValue(it.regionRevisionId).code }
        val reviewedIds = reviews.map { it.regionRevisionId }.toSet()
        try {
            SyntheticStorageFixture.create(context, name, wallTime = { now }).use { runtime ->
                val sql = runtime.database.openHelper.writableDatabase
                fun exec(statement: String, vararg args: Any?) = sql.execSQL(statement, args)
                fun count(statement: String, vararg args: Any?): Long =
                    sql.query(SimpleSQLiteQuery(statement, args)).use { check(it.moveToFirst()); it.getLong(0) }
                fun text(statement: String, vararg args: Any?): String? =
                    sql.query(SimpleSQLiteQuery(statement, args)).use {
                        check(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
                    }
                fun reject(action: () -> Unit) {
                    try { action(); error("bundled_region_policy_invalid_install_accepted") }
                    catch (_: RegionPolicyException) { }
                }
                fun proofIds(regionId: String): List<String> = sql.query(SimpleSQLiteQuery("""
                    SELECT point.point_id FROM collection_proof proof
                    JOIN collection_record card ON card.collection_id=proof.collection_id
                    JOIN route_point point ON point.point_id=proof.point_id
                    WHERE card.collection_type='region' AND card.region_id=? ORDER BY point.sequence
                """.trimIndent(), arrayOf<Any?>(regionId))).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                val geometry = RoomRegionInstaller(runtime.database)
                val installer = RoomRegionPolicyInstaller(runtime.database, { now })
                val reader = RoomRegionPolicyStore(runtime.database, { now })
                geometry.install(manifest)
                check(count("SELECT count(*) FROM region_revision") == 264L)
                check(count("SELECT count(*) FROM region_revision WHERE verified_at IS NOT NULL") == 0L)

                // The first eight valid finalizations must roll back when the last pin conflicts.
                reject { installer.install(manifest, reviews.dropLast(1) +
                    reviews.last().copy(geometrySha256 = "0".repeat(64))) }
                check(count("SELECT count(*) FROM region_revision WHERE verified_at IS NOT NULL") == 0L)
                check(manifest.regions.all { text("SELECT policy_json FROM region_revision WHERE region_revision_id=?",
                    it.regionRevisionId) == RegionGeometryMetadata.pendingPolicyJson(manifest, it) })

                val firstInstall = policies.install(runtime.database, manifest)
                check(firstInstall.newReviews == 9 && firstInstall.unchangedReviews == 0)
                val repeated = policies.install(runtime.database, manifest)
                check(repeated.newReviews == 0 && repeated.unchangedReviews == 9)
                val lookup = catalog.loadVerified(runtime.database)
                check(count("SELECT count(*) FROM region_revision WHERE verified_at IS NOT NULL") == 9L)
                check(count("SELECT count(*) FROM region_revision WHERE verified_at IS NULL") == 255L)
                check(manifest.regions.filter { it.regionRevisionId !in reviewedIds }.all {
                    text("SELECT policy_json FROM region_revision WHERE region_revision_id=?", it.regionRevisionId) ==
                        RegionGeometryMetadata.pendingPolicyJson(manifest, it)
                })
                val exactStored = reviews.associate { review ->
                    val entry = entries.getValue(review.regionRevisionId)
                    check(reader.reviewedPolicy(review.regionRevisionId)?.policy == review.policy)
                    val expected = ReviewedRegionPolicyCodec.storedPolicyJson(manifest, entry, review)
                    check(text("SELECT policy_json FROM region_revision WHERE region_revision_id=?", review.regionRevisionId) == expected)
                    review.regionRevisionId to expected
                }
                check(listOf("preference", "trip", "session", "route_point", "collection_record", "collection_proof")
                    .all { count("SELECT count(*) FROM " + it) == 0L })
                reject { installer.install(manifest, listOf(reviews.first().copy(reviewBasis = "SYNTHETIC CONFLICT"))) }
                mark("region_policy_actual_catalog_exact_9_first_install_255_pending_reinstall_noop_and_atomic_rollback")

                runtime.openSession("bundled-policy-trip", "bundled-policy-session")
                val confirmations = linkedMapOf<String, ConfirmedRegion>()
                val expectedProofs = linkedMapOf<String, List<String>>()
                RoomLocationStore(runtime.database, lookup, { now }, ownsDatabase = false).use { locations ->
                    check(!locations.isExplicitlyEnabled())
                    reviews.forEach { review ->
                        val entry = entries.getValue(review.regionRevisionId)
                        val coordinate = PUBLIC_INTERIORS.getValue(entry.code)
                        val match = lookup.containing(coordinate.latitude, coordinate.longitude).single()
                        check(match.regionRevisionId == entry.regionRevisionId)
                        check(match.interiorDistanceMeters > review.policy.boundaryMarginMeters + ACCURACY_METERS)
                        // Match the already selected policy exactly; these limits bound the test,
                        // and never clamp or replace a review's count, duration or margin.
                        val requiredCount = requiredCount(review.policy)
                        val segment = "bundled-policy-segment-" + entry.code
                        fun point(index: Int) = syntheticPoint(coordinate, "bundled-policy-point-" + entry.code,
                            index, now)
                        locations.openSegment("bundled-policy-trip", "bundled-policy-session", segment)
                        val prefix = (0 until requiredCount - 1).map(::point)
                        prefix.chunked(LocationRecorder.MAX_BATCH_POINTS).forEach {
                            check(locations.append(segment, it).confirmedRegion == null)
                        }
                        check(proofIds(entry.regionId).isEmpty())
                        val last = point(requiredCount - 1)
                        val committed = locations.append(segment, listOf(last))
                        val confirmation = checkNotNull(committed.confirmedRegion)
                        check(confirmation.regionRevisionId == entry.regionRevisionId && confirmation.lastPointId == last.pointId)
                        check(committed.acquiredRegionIds == setOf(entry.regionId))
                        check(locations.isConfirmedRegionCurrent(confirmation))
                        val expectedIds = (0 until requiredCount).map { point(it).pointId }
                        check(proofIds(entry.regionId) == expectedIds)
                        check(text("SELECT rule_version FROM collection_record WHERE region_id=?", entry.regionId) ==
                            "region-confirmation-hierarchy-v2")
                        check(locations.append(segment, listOf(point(requiredCount))).acquiredRegionIds.isEmpty())
                        check(proofIds(entry.regionId) == expectedIds)
                        confirmations[entry.regionRevisionId] = confirmation
                        expectedProofs[entry.regionId] = expectedIds
                        locations.finishSegment(segment, "explicit_stop", interrupted = false)
                    }
                    check(count("SELECT count(*) FROM collection_record WHERE collection_type='region'") == 9L)
                    check(count("SELECT count(*) FROM collection_proof") == expectedProofs.values.sumOf { it.size }.toLong())
                    check(listOf("41460", "44130").all { code ->
                        val parent = manifest.regions.single { it.code == code }
                        reader.reviewedPolicy(parent.regionRevisionId) == null &&
                            count("SELECT count(*) FROM collection_record WHERE region_id=?", parent.regionId) == 0L
                    })
                    check(count("SELECT count(*) FROM preference") == 0L && !locations.isExplicitlyEnabled())
                    mark("region_policy_actual_selected_values_android_adapter_exact_proofs_9_leaf_cards_no_parent_or_consent_change")

                    val first = reviews.first()
                    val firstEntry = entries.getValue(first.regionRevisionId)
                    val confirmation = confirmations.getValue(first.regionRevisionId)
                    val authority = ConfirmedRegionAuthority(confirmation)
                    check(authority.recheck(locations::isConfirmedRegionCurrent))
                    exec("UPDATE region_revision SET verified_at=NULL WHERE region_revision_id=?", first.regionRevisionId)
                    check(reader.reviewedPolicy(first.regionRevisionId) == null)
                    check(!locations.isConfirmedRegionCurrent(confirmation) && !authority.recheck(locations::isConfirmedRegionCurrent))
                    reject { policies.install(runtime.database, manifest) }
                    geometry.install(manifest)
                    val geometryOnly = RegionGeometryProbeFixture.load(context, runtime.database)
                    val coordinate = PUBLIC_INTERIORS.getValue(firstEntry.code)
                    check(geometryOnly.containing(coordinate.latitude, coordinate.longitude).single().regionRevisionId == first.regionRevisionId)
                    check(text("SELECT verified_at FROM region_revision WHERE region_revision_id=?", first.regionRevisionId) == null)
                    check(text("SELECT policy_json FROM region_revision WHERE region_revision_id=?", first.regionRevisionId) ==
                        exactStored.getValue(first.regionRevisionId))
                    val revokedSegment = "bundled-policy-revoked-segment"
                    locations.openSegment("bundled-policy-trip", "bundled-policy-session", revokedSegment)
                    val revokedPoints = (0 until requiredCount(first.policy)).map {
                        syntheticPoint(coordinate, "bundled-policy-revoked-point", it, now)
                    }
                    revokedPoints.chunked(LocationRecorder.MAX_BATCH_POINTS).forEach {
                        val result = locations.append(revokedSegment, it)
                        check(result.confirmedRegion == null && result.acquiredRegionIds.isEmpty())
                    }
                    locations.finishSegment(revokedSegment, "explicit_stop", interrupted = false)
                    check(expectedProofs.all { (id, ids) -> proofIds(id) == ids })
                    mark("region_policy_actual_catalog_revocation_reinstall_denied_geometry_does_not_restore_card_authority")

                    val second = reviews[1]
                    val secondConfirmation = confirmations.getValue(second.regionRevisionId)
                    val sourceAuthority = ConfirmedRegionAuthority(secondConfirmation)
                    check(sourceAuthority.recheck(locations::isConfirmedRegionCurrent))
                    exec("UPDATE source_evidence SET review_status='rejected' WHERE source_revision_id=?", manifest.source.sourceRevisionId)
                    check(reviews.all { reader.reviewedPolicy(it.regionRevisionId) == null })
                    check(!locations.isConfirmedRegionCurrent(secondConfirmation) &&
                        !sourceAuthority.recheck(locations::isConfirmedRegionCurrent))
                    reject { policies.install(runtime.database, manifest) }
                    try { geometry.install(manifest); error("bundled_region_geometry_reinstall_restored_source") }
                    catch (_: RegionDatasetException) { }
                    check(text("SELECT review_status FROM source_evidence WHERE source_revision_id=?", manifest.source.sourceRevisionId) == "rejected")
                    check(lookup.containing(coordinate.latitude, coordinate.longitude).isEmpty())
                    check(expectedProofs.all { (id, ids) -> proofIds(id) == ids })
                    check(exactStored.all { (id, json) ->
                        text("SELECT policy_json FROM region_revision WHERE region_revision_id=?", id) == json })
                    check(count("SELECT count(*) FROM collection_record WHERE collection_type='region'") == 9L)
                    check(count("SELECT count(*) FROM preference") == 0L && count("SELECT count(*) FROM playback_attempt") == 0L)
                    sql.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) }
                    mark("region_policy_actual_source_withdrawal_revokes_all_9_preserves_review_payloads_cards_and_proofs")
                }
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }
    }

    private fun requiredCount(policy: RegionPolicy): Int {
        val duration = policy.minConfirmationDurationMs
        check(duration in 0L..19_800L)
        return maxOf(policy.consecutiveSamples,
            ((duration + SAMPLE_INTERVAL_MS - 1) / SAMPLE_INTERVAL_MS + 1).toInt()).also {
            check(it in 2..RegionConfirmation.MAX_PROOF_POINTS)
        }
    }

    private fun syntheticPoint(coordinate: BoundaryCoordinate, idPrefix: String, index: Int, now: Long): RecordedLocation {
        val elapsed = 100_000L + index * SAMPLE_INTERVAL_MS
        val epoch = now - 100_000L + index * SAMPLE_INTERVAL_MS
        val metadata = AndroidLocationMetadata("gps", elapsed * 1_000_000, elapsed * 1_000_000,
            ACCURACY_METERS, false, null)
        return LocationNormalizer(AndroidLocationMeaningAdapter(100.0)).normalize(
            LocationSample(coordinate.latitude, coordinate.longitude, Double.NaN, Float.NaN, Float.NaN,
                epoch, epoch, elapsed, metadata), LocationReadingKind.UPDATE, idPrefix + "-" + index,
            "synthetic-android", "bundled-policy-synthetic-clock")
    }

    private const val SAMPLE_INTERVAL_MS = 200L
    private const val ACCURACY_METERS = 3.0
    private val TARGET_CODES = setOf("11140", "41461", "41463", "41465", "41550", "44131", "44133", "30110", "30200")
    // Public GEOS representative points copied from the existing pinned independent oracle:
    // app/src/test/resources/regions/ngii-d7a3751bd89f-oracle.tsv
    // SHA-256 df882478b062711037b22ad62086a15f2fab19c2cec0a562021fe79d237f099c.
    // Coordinates are synthetic inputs, never observed positions or claims of actual travel.
    private val PUBLIC_INTERIORS = mapOf(
        "11140" to BoundaryCoordinate(126.9900720576623, 37.55599378252191),
        "41461" to BoundaryCoordinate(127.2401717817209, 37.22208584738077),
        "41463" to BoundaryCoordinate(127.11843998146597, 37.27010250289983),
        "41465" to BoundaryCoordinate(127.08872446149552, 37.332800839080576),
        "41550" to BoundaryCoordinate(127.30575173915207, 37.02410747765556),
        "44131" to BoundaryCoordinate(127.22607417690516, 36.756010470507945),
        "44133" to BoundaryCoordinate(127.17038416158073, 36.8791150323137),
        "30110" to BoundaryCoordinate(127.46814396453676, 36.32149123265438),
        "30200" to BoundaryCoordinate(127.34530136163772, 36.38213949912902),
    )
}
