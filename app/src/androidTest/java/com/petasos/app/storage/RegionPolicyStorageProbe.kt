package com.petasos.app.storage

import android.app.Instrumentation
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.capability.location.AndroidLocationMetadata
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.generation.StrictJson
import com.petasos.app.location.*
import com.petasos.app.platform.android.location.BundledRegionCatalog
import com.petasos.app.platform.android.location.BundledRegionPolicyCatalog
import java.util.UUID

/** Disposable Room DB, packaged geometry and explicitly synthetic reviews/points. No product approval. */
object RegionPolicyStorageProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "region-policy-probe-${UUID.randomUUID()}.db"
        val catalog = BundledRegionCatalog(context)
        val manifest = catalog.readManifest()
        val now = maxOf(System.currentTimeMillis(), manifest.source.verifiedAt + 10_000)
        val reviewAt = manifest.source.verifiedAt + 1_000
        val first = manifest.regions.single { it.code == "11650" }
        val second = manifest.regions.first { it.status == "ready" && it.regionRevisionId != first.regionRevisionId }
        // Exercise the strict import boundary, not only the installer's directly constructed DTO path.
        fun review(entry: RegionDatasetEntry) = ReviewedRegionPolicyCodec.decode(StrictJson.encode(linkedMapOf(
            "formatVersion" to 1L, "regionRevisionId" to entry.regionRevisionId,
            "manifestSha256" to manifest.fingerprint, "geometrySha256" to entry.geometrySha256,
            "policy" to linkedMapOf("datasetVersion" to manifest.datasetVersion, "consecutiveSamples" to 2L,
                "minConfirmationDurationMs" to 500L, "boundaryMarginMeters" to 10L,
                "qualityAdapterVersion" to "synthetic-policy-probe-only", "coordinateReferenceSystem" to "EPSG:4326"),
            "verifiedAt" to reviewAt,
            "reviewBasis" to "SYNTHETIC TEST ONLY; does not approve any product region or real movement threshold",
            "evidenceSha256" to "a".repeat(64),
        )), now)
        val firstReview = review(first)
        val secondReview = review(second)
        try {
            SyntheticStorageFixture.create(context, name, wallTime = { now }).use { runtime ->
                val sql = runtime.database.openHelper.writableDatabase
                fun exec(statement: String, vararg args: Any?) = sql.execSQL(statement, args)
                fun count(statement: String, vararg args: Any?): Long = sql.query(SimpleSQLiteQuery(statement, args)).use {
                    check(it.moveToFirst()); it.getLong(0)
                }
                fun text(statement: String, vararg args: Any?): String? = sql.query(SimpleSQLiteQuery(statement, args)).use {
                    check(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
                }
                fun reject(action: () -> Unit) {
                    try { action(); error("region_policy_invalid_install_accepted") }
                    catch (_: RegionPolicyException) { }
                }
                val geometry = RoomRegionInstaller(runtime.database)
                val installer = RoomRegionPolicyInstaller(runtime.database, { now })
                val reader = RoomRegionPolicyStore(runtime.database, { now })
                geometry.install(manifest)
                check(reader.reviewedPolicy(first.regionRevisionId) == null)
                check(count("SELECT count(*) FROM region_revision WHERE verified_at IS NOT NULL") == 0L)
                val bundledPolicies = BundledRegionPolicyCatalog(context, { now })
                check(bundledPolicies.readBundle().reviews.isEmpty())
                check(manifest.regions.size == 264)
                val pendingBefore = manifest.regions.associate { entry -> entry.regionRevisionId to
                    text("SELECT policy_json FROM region_revision WHERE region_revision_id=?", entry.regionRevisionId) }
                check(pendingBefore.all { (id, json) -> json == RegionGeometryMetadata.pendingPolicyJson(
                    manifest, manifest.regions.single { it.regionRevisionId == id }) })
                repeat(2) {
                    val emptyInstall = bundledPolicies.install(runtime.database, manifest)
                    check(emptyInstall.newReviews == 0 && emptyInstall.unchangedReviews == 0)
                }
                check(count("SELECT count(*) FROM region_revision") == 264L)
                check(count("SELECT count(*) FROM region_revision WHERE verified_at IS NOT NULL") == 0L)
                check(pendingBefore.all { (id, json) ->
                    text("SELECT policy_json FROM region_revision WHERE region_revision_id=?", id) == json })
                check(count("SELECT count(*) FROM preference") == 0L && count("SELECT count(*) FROM session") == 0L &&
                    count("SELECT count(*) FROM collection_proof") == 0L)
                mark("region_policy_pinned_empty_product_catalog_noop_all_264_regions_remain_pending")

                // A failure after the first legitimate update must roll back the whole review list.
                reject { installer.install(manifest, listOf(firstReview, secondReview.copy(geometrySha256 = "b".repeat(64)))) }
                check(count("SELECT count(*) FROM region_revision WHERE verified_at IS NOT NULL") == 0L)
                check(text("SELECT policy_json FROM region_revision WHERE region_revision_id=?", first.regionRevisionId) ==
                    RegionGeometryMetadata.pendingPolicyJson(manifest, first))
                reject { installer.install(manifest, listOf(firstReview.copy(manifestSha256 = "b".repeat(64)))) }
                reject { installer.install(manifest, listOf(firstReview.copy(regionRevisionId = "missing-region"))) }
                reject { installer.install(manifest, listOf(firstReview.copy(verifiedAt = now + 1))) }
                reject { installer.install(manifest, listOf(firstReview.copy(verifiedAt = manifest.source.verifiedAt - 1))) }
                reject { installer.install(manifest, listOf(firstReview, firstReview)) }
                reject { installer.install(manifest, listOf(review(manifest.regions.first { it.status == "quarantined" }))) }
                val changedIdentity = RegionDatasetManifest(manifest.fingerprint, manifest.datasetVersion, manifest.source,
                    manifest.transform, manifest.regions.map { if (it.regionRevisionId == first.regionRevisionId) it.copy(name = "SYNTHETIC CONFLICT") else it })
                reject { installer.install(changedIdentity, listOf(firstReview)) }
                mark("region_policy_ready_geometry_identity_pins_hashes_time_order_and_atomic_rollback_verified")

                val installed = installer.install(manifest, listOf(firstReview))
                check(installed.newReviews == 1 && installed.unchangedReviews == 0)
                mark("region_policy_synthetic_review_json_decode_to_atomic_install_verified")
                val current = checkNotNull(reader.reviewedPolicy(first.regionRevisionId))
                check(current.regionId == first.regionId && current.policy == firstReview.policy)
                val originalJson = text("SELECT policy_json FROM region_revision WHERE region_revision_id=?", first.regionRevisionId)!!
                val repeated = installer.install(manifest, listOf(firstReview))
                check(repeated.newReviews == 0 && repeated.unchangedReviews == 1)
                reject { installer.install(manifest, listOf(firstReview.copy(reviewBasis = "SYNTHETIC DIFFERENT REVIEW"))) }
                reject { installer.install(manifest, listOf(firstReview.copy(policy = firstReview.policy.copy(consecutiveSamples = 3)))) }
                check(reader.reviewedPolicy(first.regionRevisionId)?.eligibilityKey == current.eligibilityKey)
                check(count("SELECT count(*) FROM preference") == 0L && count("SELECT count(*) FROM session") == 0L)
                mark("region_policy_first_finalization_exact_reinstall_noop_and_reviewed_payload_immutable")

                // Asset reload must preserve the approved policy instead of comparing it with pending JSON.
                val lookup = catalog.loadVerified(runtime.database)
                val latitude = 37.476967047424026
                val longitude = 127.01367673629329
                check(lookup.containing(latitude, longitude).single().regionRevisionId == first.regionRevisionId)
                check(text("SELECT policy_json FROM region_revision WHERE region_revision_id=?", first.regionRevisionId) == originalJson)
                runtime.openSession("region-policy-trip", "region-policy-session")
                RoomLocationStore(runtime.database, lookup, { now }, ownsDatabase = false).use { locations ->
                    check(!locations.isExplicitlyEnabled())
                    locations.openSegment("region-policy-trip", "region-policy-session", "region-policy-segment")
                    val normalizer = LocationNormalizer(LocationMeaningAdapter {
                        VerifiedLocationMeaning("synthetic-policy-probe-only", "epoch_ms", it.rawTimestampMillis, 1.0, 30.0)
                    })
                    fun point(index: Int) = normalizer.normalize(
                        LocationSample(latitude, longitude, 0.0, 0f, 1f, index * 1_000L, index * 1_000L, index * 1_000L),
                        LocationReadingKind.UPDATE, "region-policy-point-$index", "synthetic-policy-only", "synthetic-clock")
                    check(locations.append("region-policy-segment", listOf(point(1))).confirmedRegionId == null)
                    val committed = locations.append("region-policy-segment", listOf(point(2)))
                    check(committed.confirmedRegionId == first.regionId)
                    val confirmation = checkNotNull(committed.confirmedRegion)
                    check(locations.isConfirmedRegionCurrent(confirmation))
                    check(count("SELECT count(*) FROM collection_record WHERE collection_type='region'") == 1L)
                    check(count("SELECT count(*) FROM collection_proof WHERE region_revision_id=?", first.regionRevisionId) == 2L)
                    val proofCount = count("SELECT count(*) FROM collection_proof")
                    check(installer.install(manifest, listOf(firstReview)).unchangedReviews == 1)

                    val attributionBefore = text("SELECT attribution FROM source_evidence WHERE source_revision_id=?", manifest.source.sourceRevisionId)!!
                    val metadataPermit = ConfirmedRegionAuthority(confirmation)
                    check(metadataPermit.recheck(locations::isConfirmedRegionCurrent))
                    exec("UPDATE source_evidence SET attribution=? WHERE source_revision_id=?", attributionBefore + " SYNTHETIC METADATA", manifest.source.sourceRevisionId)
                    check(!locations.isConfirmedRegionCurrent(confirmation) && !metadataPermit.recheck(locations::isConfirmedRegionCurrent))
                    exec("UPDATE source_evidence SET attribution=? WHERE source_revision_id=?", attributionBefore, manifest.source.sourceRevisionId)
                    check(locations.isConfirmedRegionCurrent(confirmation) && !metadataPermit.recheck(locations::isConfirmedRegionCurrent))
                    val rightsBefore = text("SELECT allowed_uses_json FROM source_evidence WHERE source_revision_id=?", manifest.source.sourceRevisionId)!!
                    val rightsPermit = ConfirmedRegionAuthority(confirmation)
                    exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id=?",
                        StrictJson.stringify(mapOf("allowDisplay" to false, "allowDistribution" to true)), manifest.source.sourceRevisionId)
                    check(!locations.isConfirmedRegionCurrent(confirmation) && !rightsPermit.recheck(locations::isConfirmedRegionCurrent))
                    exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id=?", rightsBefore, manifest.source.sourceRevisionId)
                    check(locations.isConfirmedRegionCurrent(confirmation) && !rightsPermit.recheck(locations::isConfirmedRegionCurrent))
                    val policyPermit = ConfirmedRegionAuthority(confirmation)

                    exec("UPDATE region_revision SET verified_at=NULL WHERE region_revision_id=?", first.regionRevisionId)
                    check(reader.reviewedPolicy(first.regionRevisionId) == null)
                    check(!locations.isConfirmedRegionCurrent(confirmation) && !policyPermit.recheck(locations::isConfirmedRegionCurrent))
                    reject { installer.install(manifest, listOf(firstReview)) }
                    check(geometry.install(manifest).newRevisions == 0)
                    val reloaded = catalog.loadVerified(runtime.database)
                    check(reloaded.containing(latitude, longitude).single().regionRevisionId == first.regionRevisionId)
                    check(count("SELECT count(*) FROM region_revision WHERE region_revision_id=? AND verified_at IS NULL", first.regionRevisionId) == 1L)
                    check(text("SELECT policy_json FROM region_revision WHERE region_revision_id=?", first.regionRevisionId) == originalJson)
                    check(locations.append("region-policy-segment", listOf(point(3))).confirmedRegionId == null)
                    check(count("SELECT count(*) FROM collection_proof") == proofCount)
                    locations.finishSegment("region-policy-segment", "explicit_stop", interrupted = false)
                    mark("region_policy_catalog_reload_preserves_approval_then_revocation_existing_cards_and_proofs")
                    mark("region_policy_committed_confirmation_policy_source_rights_and_metadata_revocation_is_terminal")

                    // Corrupting a revoked row back to exact pending cannot erase the proof history guard.
                    exec("UPDATE region_revision SET policy_json=? WHERE region_revision_id=?",
                        RegionGeometryMetadata.pendingPolicyJson(manifest, first), first.regionRevisionId)
                    reject { installer.install(manifest, listOf(firstReview)) }
                    check(count("SELECT count(*) FROM collection_proof") == proofCount)
                    exec("UPDATE region_revision SET policy_json=? WHERE region_revision_id=?", originalJson, first.regionRevisionId)
                    mark("region_policy_exact_pending_with_existing_proofs_cannot_be_refinalized")
                }

                installer.install(manifest, listOf(secondReview))
                val secondCurrent = checkNotNull(reader.reviewedPolicy(second.regionRevisionId))
                val originalAttribution = text("SELECT attribution FROM source_evidence WHERE source_revision_id=?", manifest.source.sourceRevisionId)!!
                exec("UPDATE source_evidence SET attribution=? WHERE source_revision_id=?", originalAttribution + " SYNTHETIC CHANGE", manifest.source.sourceRevisionId)
                check(reader.reviewedPolicy(second.regionRevisionId)?.eligibilityKey != secondCurrent.eligibilityKey)
                reject { installer.install(manifest, listOf(secondReview)) }
                exec("UPDATE source_evidence SET attribution=? WHERE source_revision_id=?", originalAttribution, manifest.source.sourceRevisionId)
                val rights = text("SELECT allowed_uses_json FROM source_evidence WHERE source_revision_id=?", manifest.source.sourceRevisionId)!!
                exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id=?",
                    StrictJson.stringify(mapOf("allowDisplay" to false, "allowDistribution" to true)), manifest.source.sourceRevisionId)
                check(reader.reviewedPolicy(second.regionRevisionId) == null)
                reject { installer.install(manifest, listOf(secondReview)) }
                try { geometry.install(manifest); error("region_geometry_reinstall_restored_source_rights") }
                catch (_: RegionDatasetException) { }
                check(lookup.containing(latitude, longitude).isEmpty())
                check(text("SELECT allowed_uses_json FROM source_evidence WHERE source_revision_id=?", manifest.source.sourceRevisionId) != rights)
                exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id=?", rights, manifest.source.sourceRevisionId)
                exec("UPDATE source_evidence SET verified_at=? WHERE source_revision_id=?", now + 1, manifest.source.sourceRevisionId)
                check(reader.reviewedPolicy(second.regionRevisionId) == null)
                reject { installer.install(manifest, listOf(secondReview)) }
                exec("UPDATE source_evidence SET verified_at=? WHERE source_revision_id=?", manifest.source.verifiedAt, manifest.source.sourceRevisionId)
                check(count("SELECT count(*) FROM collection_proof") == 2L)
                mark("region_policy_current_source_metadata_rights_time_and_geometry_reinstall_cannot_restore_revocation")

                val legacy = StrictJson.stringify(mapOf("datasetVersion" to "synthetic-legacy", "consecutiveSamples" to 2,
                    "minConfirmationDurationMs" to 0, "boundaryMarginMeters" to 10,
                    "qualityAdapterVersion" to "synthetic-legacy", "coordinateReferenceSystem" to "EPSG:4326"))
                exec("INSERT INTO region(region_id,name) VALUES ('synthetic-legacy-region','SYNTHETIC ONLY')")
                exec("INSERT INTO region_revision(region_revision_id,region_id,code,dataset_version,name,source_revision_id,policy_json,verified_at) VALUES ('synthetic-legacy-r1','synthetic-legacy-region','00000','synthetic-legacy','SYNTHETIC ONLY',?,?,?)",
                    manifest.source.sourceRevisionId, legacy, reviewAt)
                check(reader.reviewedPolicy("synthetic-legacy-r1") != null)
                val markedLegacy = (StrictJson.parse(legacy.toByteArray()) as Map<*, *>).entries.associate { it.key.toString() to it.value } +
                    mapOf("policyStatus" to "verified")
                exec("UPDATE region_revision SET policy_json=? WHERE region_revision_id='synthetic-legacy-r1'", StrictJson.stringify(markedLegacy))
                check(reader.reviewedPolicy("synthetic-legacy-r1") == null)
                check(count("SELECT count(*) FROM preference") == 0L)
                mark("region_policy_minimal_legacy_contract_supported_but_partial_packaged_envelope_never_downgrades")

                // Same Room path at 5 Hz: N is a minimum, and the 2-second evidence suffix needs
                // eleven real stored rows. The pinned GEOS interior point is public geometry;
                // all measurements and this disposable review are explicitly synthetic.
                val highRateRegion = manifest.regions.single { it.code == "11710" }
                val highRateReview = review(highRateRegion).let { base ->
                    base.copy(policy = base.policy.copy(consecutiveSamples = 3, minConfirmationDurationMs = 2_000,
                        qualityAdapterVersion = AndroidLocationMeaningAdapter.VERSION))
                }
                installer.install(manifest, listOf(highRateReview))
                val highRateLatitude = 37.504905217504685
                val highRateLongitude = 127.10599606740601
                check(lookup.containing(highRateLatitude, highRateLongitude).single().regionRevisionId == highRateRegion.regionRevisionId)
                RoomLocationStore(runtime.database, lookup, { now }, ownsDatabase = false).use { locations ->
                    val segment = "region-policy-high-rate-segment"
                    locations.openSegment("region-policy-trip", "region-policy-session", segment)
                    val normalizer = LocationNormalizer(AndroidLocationMeaningAdapter(100.0))
                    fun point(index: Int, valid: Boolean = true): RecordedLocation {
                        val receipt = 100_000L + index * 200L
                        val metadata = if (valid) AndroidLocationMetadata("gps", receipt * 1_000_000,
                            receipt * 1_000_000, 1.0, false, null) else null
                        return normalizer.normalize(LocationSample(highRateLatitude, highRateLongitude, 0.0, 0f, 0f,
                            receipt, receipt, receipt, metadata), LocationReadingKind.UPDATE,
                            "region-policy-high-rate-point-$index", "synthetic-android-only", "synthetic-high-rate-clock")
                    }
                    fun proofIds(): List<String> = sql.query(SimpleSQLiteQuery("""
                        SELECT p.point_id FROM collection_proof proof
                        JOIN collection_record card ON card.collection_id=proof.collection_id
                        JOIN route_point p ON p.point_id=proof.point_id
                        WHERE card.collection_type='region' AND card.region_id=? ORDER BY p.sequence
                    """.trimIndent(), arrayOf<Any?>(highRateRegion.regionId))).use { cursor ->
                        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                    }
                    // A valid prefix before an invalid row cannot be silently filtered into the
                    // suffix. The ten valid rows after it cover only 1.8 seconds.
                    val prefix = (0..12).map { point(it, valid = it != 2) }
                    check(locations.append(segment, prefix).confirmedRegion == null)
                    check(count("SELECT count(*) FROM collection_record WHERE region_id=?", highRateRegion.regionId) == 0L)
                    check(proofIds().isEmpty())
                    val last = point(13)
                    val confirmation = checkNotNull(locations.append(segment, listOf(last)).confirmedRegion)
                    check(confirmation.regionRevisionId == highRateRegion.regionRevisionId && confirmation.lastPointId == last.pointId)
                    check(locations.isConfirmedRegionCurrent(confirmation))
                    val expectedProof = (3..13).map { "region-policy-high-rate-point-$it" }
                    check(proofIds() == expectedProof)
                    check(text("SELECT rule_version FROM collection_record WHERE region_id=?", highRateRegion.regionId) ==
                        "region-confirmation-hierarchy-v2")
                    check(count("SELECT count(*) FROM collection_proof WHERE region_revision_id=?", highRateRegion.regionRevisionId) == 11L)
                    val next = point(14)
                    check(locations.append(segment, listOf(next)).confirmedRegion?.lastPointId == next.pointId)
                    check(proofIds() == expectedProof) // Latest authority changes, earned proof does not.
                    check(locations.append(segment, listOf(point(15, valid = false))).confirmedRegion == null)
                    check(proofIds() == expectedProof)
                    check(count("SELECT count(*) FROM route_point WHERE segment_id=?", segment) == 16L)
                    check(count("SELECT count(*) FROM route_point WHERE segment_id=? AND quality_status='unknown'", segment) == 2L)
                    check(count("SELECT count(*) FROM collection_record WHERE region_id=?", highRateRegion.regionId) == 1L)
                    locations.finishSegment(segment, "explicit_stop", interrupted = false)
                }
                check(count("SELECT count(*) FROM preference") == 0L)
                mark("region_policy_5hz_minimum_count_duration_exact_11_row_suffix_and_v2_proof_verified")
                mark("region_policy_invalid_cutoff_latest_identity_all_raw_rows_and_earned_proof_preserved")
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }
    }
}
