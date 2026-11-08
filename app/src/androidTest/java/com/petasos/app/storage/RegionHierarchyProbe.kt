package com.petasos.app.storage

import android.app.Instrumentation
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.generation.StrictJson
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.location.*
import com.petasos.app.planning.CandidateReason
import com.petasos.app.platform.android.location.BundledRegionCatalog
import java.util.UUID

/**
 * Disposable Room only. Real packaged boundaries/hierarchy, synthetic policies, points and content.
 * Does not approve product thresholds, read a device location, change consent or open product DBs.
 */
object RegionHierarchyProbe {
    private const val STORY = "test-story"
    private const val ASSOCIATION_SOURCE = "hierarchy-synthetic-association-source-r1"
    private const val ASSOCIATION_FACT = "hierarchy-synthetic-association-r1"
    private const val ADAPTER = "synthetic-hierarchy-probe-only"

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "region-hierarchy-probe-${UUID.randomUUID()}.db"
        val catalog = BundledRegionCatalog(context)
        val manifest = catalog.readManifest()
        val hierarchy = catalog.readHierarchy(manifest)
        val leaf = manifest.regions.single { it.code == "41463" }
        val parent = manifest.regions.single { it.code == "41460" }
        val now = maxOf(System.currentTimeMillis(), manifest.source.verifiedAt + 10_000)
        val reviewAt = manifest.source.verifiedAt + 1_000
        // Public TourAPI museum point used solely as fixed synthetic input, never a live fix or route.
        val latitude = 37.2738968100
        val longitude = 127.1571252752
        check(hierarchy.scope(leaf.regionId).eligibleStoryRegionIds == listOf(leaf.regionId, parent.regionId))
        check(hierarchy.agrees(leaf.regionId, parent.regionId) && !hierarchy.agrees(parent.regionId, leaf.regionId))
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
                val geometry = RoomRegionInstaller(runtime.database)
                geometry.install(manifest)
                val ready = manifest.regions.filter { it.status == "ready" }
                val assets = ready.associate { it.regionRevisionId to it.geometryAsset }
                val raw = PackedRegionBoundaryLookup(ready.map {
                    PackedRegionBoundaryMetadata(it.regionRevisionId, manifest.datasetVersion, it.geometrySha256,
                        it.geometryBytes, it.ringCount, it.pointCount, it.bounds)
                }, open = { context.assets.open(assets.getValue(it.regionRevisionId)) },
                    isAuthorized = { geometry.isAuthorized(manifest, it.regionRevisionId) },
                    quarantinedBounds = manifest.regions.filter { it.status == "quarantined" }.map { it.bounds })
                val rawMatches = raw.containing(latitude, longitude)
                check(rawMatches.map { it.regionRevisionId }.toSet() == setOf(leaf.regionRevisionId, parent.regionRevisionId))
                check(rawMatches.all { it.interiorDistanceMeters > 20.0 })
                check(hierarchy.select(rawMatches)?.regionRevisionId == leaf.regionRevisionId)
                check(hierarchy.select(rawMatches.filter { it.regionRevisionId == parent.regionRevisionId }) == null)
                mark("region_hierarchy_actual_ngii_parent_leaf_overlap_selects_exact_reviewed_leaf_no_parent_fallback")

                // No parent visit policy is approved. Only a disposable synthetic leaf review is installed.
                val policyReview = ReviewedRegionPolicyCodec.decode(StrictJson.encode(linkedMapOf(
                    "formatVersion" to 1L, "regionRevisionId" to leaf.regionRevisionId,
                    "manifestSha256" to manifest.fingerprint, "geometrySha256" to leaf.geometrySha256,
                    "policy" to linkedMapOf("datasetVersion" to manifest.datasetVersion, "consecutiveSamples" to 2L,
                        "minConfirmationDurationMs" to 500L, "boundaryMarginMeters" to 10L,
                        "qualityAdapterVersion" to ADAPTER, "coordinateReferenceSystem" to "EPSG:4326"),
                    "verifiedAt" to reviewAt,
                    "reviewBasis" to "SYNTHETIC TEST ONLY; no production location policy or measured trip claim",
                    "evidenceSha256" to "a".repeat(64),
                )), now)
                check(RoomRegionPolicyInstaller(runtime.database, { now }).install(manifest, listOf(policyReview)).newReviews == 1)
                check(count("SELECT count(*) FROM region_revision WHERE verified_at IS NOT NULL") == 1L)
                check(count("SELECT count(*) FROM region_revision WHERE region_revision_id=? AND verified_at IS NULL", parent.regionRevisionId) == 1L)
                val lookup = RegionGeometryProbeFixture.load(context, runtime.database)
                check(lookup.selectionKey == hierarchy.fingerprint)
                check(lookup.containing(latitude, longitude).single().regionRevisionId == leaf.regionRevisionId)

                val content = contentForParent(manifest, parent, reviewAt)
                RoomContentInstaller(runtime.database, { now }, ownsDatabase = false).use { installer ->
                    check(installer.install(content) == ContentInstallResult(1, 0))
                    check(installer.install(content) == ContentInstallResult(0, 1))
                }
                fun checkParentBinding() {
                    check(text("SELECT region_id FROM story WHERE story_id=?", STORY) == parent.regionId)
                    check(text("SELECT region_id FROM story_region_binding WHERE story_id=?", STORY) == parent.regionId)
                    check(text("SELECT region_revision_id FROM story_region_binding WHERE story_id=?", STORY) == parent.regionRevisionId)
                }
                checkParentBinding()
                val candidates = RoomAutomaticStoryStore(runtime.database, { now }, ownsDatabase = false, hierarchy = hierarchy)
                val offered = candidates.candidates(leaf.regionId).single()
                check(offered.story.storyId == STORY && offered.regionId == parent.regionId && offered.reason == CandidateReason.CURRENT_REGION)
                check(candidates.isCurrent(offered, leaf.regionId))
                check(!candidates.isCurrent(offered, parent.regionId)) // Scope identity differs; parent is no leaf proof.
                check(candidates.candidates("kr-sigungu-44131").isEmpty())
                check(RoomAutomaticStoryStore(runtime.database, { now }).candidates(leaf.regionId).isEmpty())
                check(count("SELECT count(*) FROM collection_record") == 0L)
                mark("region_hierarchy_leaf_scope_offers_original_parent_story_binding_and_rechecks_exact_scope")

                // Simulate a corrupt/outdated binding outside the installer. The same stable parent
                // ID cannot substitute a different geometry revision into an APK-pinned hierarchy.
                val alternateRevision = "hierarchy-synthetic-other-parent-r1"
                val alternateDataset = "synthetic-other-parent-dataset"
                exec("INSERT INTO region_revision(region_revision_id,region_id,code,dataset_version,name,source_revision_id,policy_json,verified_at) " +
                    "SELECT ?,region_id,code,?,name,source_revision_id,policy_json,verified_at FROM region_revision WHERE region_revision_id=?",
                    alternateRevision, alternateDataset, parent.regionRevisionId)
                exec("UPDATE story_region_binding SET region_revision_id=?,dataset_version=? WHERE story_id=?",
                    alternateRevision, alternateDataset, STORY)
                check(RoomAutomaticStoryStore(runtime.database, { now }).candidates(parent.regionId).single().story.storyId == STORY)
                check(candidates.candidates(leaf.regionId).isEmpty() && !candidates.isCurrent(offered, leaf.regionId))
                exec("UPDATE story_region_binding SET region_revision_id=?,dataset_version=? WHERE story_id=?",
                    parent.regionRevisionId, manifest.datasetVersion, STORY)
                exec("DELETE FROM region_revision WHERE region_revision_id=?", alternateRevision)
                check(candidates.isCurrent(offered, leaf.regionId))
                checkParentBinding()
                mark("region_hierarchy_same_parent_id_different_geometry_revision_cannot_authorize_new_or_existing_offer")

                runtime.openSession("hierarchy-trip", "hierarchy-session")
                RoomLocationStore(runtime.database, lookup, { now }, ownsDatabase = false).use { locations ->
                    check(!locations.isExplicitlyEnabled())
                    locations.openSegment("hierarchy-trip", "hierarchy-session", "hierarchy-segment")
                    val normalizer = LocationNormalizer(LocationMeaningAdapter {
                        VerifiedLocationMeaning(ADAPTER, "epoch_ms", it.rawTimestampMillis, 1.0, 30.0)
                    })
                    fun point(index: Int): RecordedLocation {
                        val epoch = now - 5_000 + index * 1_000L
                        return normalizer.normalize(LocationSample(latitude, longitude, 0.0, 0f, 1f,
                            epoch, epoch, index * 1_000L), LocationReadingKind.UPDATE,
                            "hierarchy-point-$index", "synthetic-hierarchy-only", "synthetic-hierarchy-clock")
                    }
                    check(locations.append("hierarchy-segment", listOf(point(1))).confirmedRegion == null)
                    val committed = locations.append("hierarchy-segment", listOf(point(2)))
                    val confirmation = checkNotNull(committed.confirmedRegion)
                    check(confirmation.regionId == leaf.regionId && confirmation.regionRevisionId == leaf.regionRevisionId)
                    check(committed.acquiredRegionIds == setOf(leaf.regionId))
                    check(locations.isConfirmedRegionCurrent(confirmation))
                    check(count("SELECT count(*) FROM collection_record WHERE collection_type='region'") == 1L)
                    check(count("SELECT count(*) FROM collection_record WHERE collection_type='region' AND region_id=?", leaf.regionId) == 1L)
                    check(count("SELECT count(*) FROM collection_record WHERE region_id=?", parent.regionId) == 0L)
                    check(count("SELECT count(*) FROM collection_proof WHERE region_revision_id=?", leaf.regionRevisionId) == 2L)
                    check(count("SELECT count(*) FROM collection_proof WHERE region_revision_id=?", parent.regionRevisionId) == 0L)
                    check(locations.currentRegionStories(leaf.regionId).single().storyId == STORY)
                    checkParentBinding()
                    mark("region_hierarchy_synthetic_leaf_confirmation_earns_one_leaf_card_two_proofs_and_no_parent_card")

                    // Parent textual association can be revoked without withdrawing the leaf's geometry policy.
                    exec("UPDATE source_evidence SET review_status='rejected' WHERE source_revision_id=?", ASSOCIATION_SOURCE)
                    check(candidates.candidates(leaf.regionId).isEmpty() && !candidates.isCurrent(offered, leaf.regionId))
                    check(locations.isConfirmedRegionCurrent(confirmation))
                    exec("UPDATE source_evidence SET review_status='verified' WHERE source_revision_id=?", ASSOCIATION_SOURCE)
                    check(candidates.isCurrent(offered, leaf.regionId))
                    mark("region_hierarchy_parent_association_source_revocation_rejects_story_without_erasing_leaf_visit_authority")

                    // Actual parent and leaf geometry share one NGII source. Its revocation affects both.
                    val geometryPermit = ConfirmedRegionAuthority(confirmation)
                    check(geometryPermit.recheck(locations::isConfirmedRegionCurrent))
                    exec("UPDATE source_evidence SET review_status='rejected' WHERE source_revision_id=?", manifest.source.sourceRevisionId)
                    check(!geometry.isAuthorized(manifest, parent.regionRevisionId))
                    check(candidates.candidates(leaf.regionId).isEmpty() && !candidates.isCurrent(offered, leaf.regionId))
                    check(lookup.containing(latitude, longitude).isEmpty())
                    check(!locations.isConfirmedRegionCurrent(confirmation) && !geometryPermit.recheck(locations::isConfirmedRegionCurrent))
                    exec("UPDATE source_evidence SET review_status='verified' WHERE source_revision_id=?", manifest.source.sourceRevisionId)
                    check(locations.isConfirmedRegionCurrent(confirmation) && candidates.isCurrent(offered, leaf.regionId))
                    check(!geometryPermit.recheck(locations::isConfirmedRegionCurrent))
                    mark("region_hierarchy_shared_ngii_source_revocation_rejects_parent_candidate_and_leaf_authority_terminally")

                    val policyPermit = ConfirmedRegionAuthority(confirmation)
                    check(policyPermit.recheck(locations::isConfirmedRegionCurrent))
                    exec("UPDATE region_revision SET verified_at=NULL WHERE region_revision_id=?", leaf.regionRevisionId)
                    check(RoomRegionPolicyStore(runtime.database, { now }).reviewedPolicy(leaf.regionRevisionId) == null)
                    check(!locations.isConfirmedRegionCurrent(confirmation) && !policyPermit.recheck(locations::isConfirmedRegionCurrent))
                    check(locations.append("hierarchy-segment", listOf(point(3))).confirmedRegion == null)
                    // Metadata availability is deliberately separate from live authority. Host must gate output.
                    check(candidates.isCurrent(offered, leaf.regionId))
                    check(count("SELECT count(*) FROM collection_record WHERE collection_type='region'") == 1L)
                    check(count("SELECT count(*) FROM collection_proof") == 2L)
                    checkParentBinding()
                    check(count("SELECT count(*) FROM preference") == 0L)
                    check(count("SELECT count(*) FROM playback_attempt") == 0L)
                    sql.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) }
                    locations.finishSegment("hierarchy-segment", "explicit_stop", interrupted = false)
                    mark("region_hierarchy_leaf_policy_revocation_removes_authority_preserves_parent_metadata_and_existing_proofs")
                }
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }
    }

    private fun contentForParent(manifest: RegionDatasetManifest, parent: RegionDatasetEntry, reviewAt: Long): ByteArray {
        val document = (StrictJson.parse(SyntheticStorageFixture.bundle()) as Map<*, *>).entries
            .associate { it.key as String to it.value }.toMutableMap()
        val text = "SYNTHETIC parent association only. No actual tourism fact or location approval."
        document["formatVersion"] = 2L
        document["sources"] = (document.getValue("sources") as List<*>) + mapOf(
            "sourceRevisionId" to ASSOCIATION_SOURCE, "sourceId" to "hierarchy-synthetic-association-source",
            "provider" to "synthetic-test", "sourceUrl" to "https://example.invalid/hierarchy-association",
            "title" to "SYNTHETIC ASSOCIATION ONLY", "fetchedAt" to 1L, "sourceUpdatedAt" to null,
            "sourceUpdatedRaw" to null, "verifiedAt" to 1L, "contentHash" to ContentBundleCodec.sha256(text.toByteArray()),
            "normalizedText" to text, "license" to mapOf("type" to "synthetic-test-only",
                "url" to "https://example.invalid/license", "scope" to "text", "reviewBasis" to "Test APK only"),
            "allowedUses" to SyntheticStorageFixture.allowedUses(), "attribution" to "Synthetic fixture only",
            "reviewStatus" to "verified")
        document["facts"] = (document.getValue("facts") as List<*>) + mapOf(
            "factRevisionId" to ASSOCIATION_FACT, "factId" to "hierarchy-synthetic-association", "version" to 1L,
            "statement" to text, "kind" to "verified_fact", "verifiedAt" to 1L,
            "sourceRevisionIds" to listOf(ASSOCIATION_SOURCE))
        document["regionBindings"] = listOf(mapOf(
            "storyId" to STORY, "regionId" to parent.regionId, "regionRevisionId" to parent.regionRevisionId,
            "regionCode" to parent.code, "datasetVersion" to manifest.datasetVersion, "regionName" to parent.name,
            "regionSourceRevisionId" to manifest.source.sourceRevisionId, "associationFactRevisionId" to ASSOCIATION_FACT,
            "verifiedAt" to reviewAt, "reviewBasis" to "Synthetic fixture binding only; no actual content review"))
        return StrictJson.encode(document)
    }
}
