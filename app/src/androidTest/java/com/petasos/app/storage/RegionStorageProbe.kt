package com.petasos.app.storage

import android.app.Instrumentation
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.location.LocationMeaningAdapter
import com.petasos.app.location.LocationNormalizer
import com.petasos.app.location.RegionDatasetException
import com.petasos.app.location.RegionDatasetManifest
import com.petasos.app.location.RegionDatasetManifestCodec
import com.petasos.app.location.VerifiedLocationMeaning
import com.petasos.app.platform.android.location.BundledRegionCatalog
import java.util.UUID

/** Actual packaged official geometry and Room; only synthetic location samples, no GPS or consent. */
object RegionStorageProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "region-storage-probe-${UUID.randomUUID()}.db"
        try {
            SyntheticStorageFixture.create(context, name, wallTime = { 100_000 }).use { runtime ->
                val sql = runtime.database.openHelper.writableDatabase
                fun count(statement: String, vararg args: Any?): Long = sql.query(SimpleSQLiteQuery(statement, args)).use {
                    check(it.moveToFirst()); it.getLong(0)
                }
                val catalog = BundledRegionCatalog(context)
                val manifest = catalog.readManifest()
                val credits = catalog.creditsText()
                check(credits.contains(manifest.source.attribution) && credits.contains(manifest.source.sourceUrl) &&
                    credits.contains(manifest.source.licenseUrl))
                check(count("SELECT count(*) FROM source_evidence") == 0L && count("SELECT count(*) FROM session") == 0L)
                mark("region_official_attribution_license_and_source_are_readable_without_storage_or_session_creation")
                val installer = RoomRegionInstaller(runtime.database)
                val first = installer.install(manifest)
                check(first.newRevisions == manifest.regions.size && first.unchangedRevisions == 0)
                val second = installer.install(manifest)
                check(second.newRevisions == 0 && second.unchangedRevisions == manifest.regions.size)
                check(count("SELECT count(*) FROM source_evidence WHERE content_hash=? AND review_status='verified'", manifest.source.contentHash) == 1L)
                check(count("SELECT count(*) FROM region_revision WHERE verified_at IS NOT NULL") == 0L)
                check(count("SELECT count(*) FROM preference") == 0L && count("SELECT count(*) FROM story") == 0L && count("SELECT count(*) FROM place") == 0L)
                mark("region_official_packaged_manifest_pin_atomic_install_idempotence_no_policy_or_consent_grant")

                val lookup = catalog.loadVerified(runtime.database)
                // Public fixture coordinate; never the emulator/device position. Compiler cross-checks this point.
                val latitude = 37.476967047424026
                val longitude = 127.01367673629329
                val expected = manifest.regions.single { it.code == "11650" }
                check(expected.status == "ready")
                val match = lookup.containing(latitude, longitude).single()
                check(match.regionRevisionId == expected.regionRevisionId && match.datasetVersion == manifest.datasetVersion &&
                    match.coordinateReferenceSystem == "EPSG:4326" && match.interiorDistanceMeters > 0)
                check(lookup.containing(0.0, 0.0).isEmpty())
                mark("region_actual_official_binary_hash_counts_bounds_and_public_interior_fixture_verified")

                check(manifest.regions.single { it.code == "30140" }.status == "quarantined")
                check(lookup.containing(36.279712498507024, 127.40189987343622).isEmpty())
                mark("region_actual_quarantined_source_geometry_withholds_all_matches_without_repair_or_guess")

                val originalRevisionCount = count("SELECT count(*) FROM region_revision")
                val rollbackEntry = expected.copy(regionId = "synthetic-rollback-region", regionRevisionId = "synthetic-rollback-r1", code = "99999", name = "SYNTHETIC ROLLBACK ONLY")
                val conflict = RegionDatasetManifest("e".repeat(64), manifest.datasetVersion, manifest.source, manifest.transform,
                    listOf(rollbackEntry, expected.copy(name = "SYNTHETIC IMMUTABLE CONFLICT")))
                try { installer.install(conflict); error("region_immutable_conflict_accepted") }
                catch (failure: RegionDatasetException) { check(failure.code == "REGION_IMMUTABLE_CONFLICT") }
                check(count("SELECT count(*) FROM region WHERE region_id='synthetic-rollback-region'") == 0L)
                check(count("SELECT count(*) FROM region_revision") == originalRevisionCount)
                mark("region_conflicting_reinstall_rolls_back_all_preceding_insertions")

                runtime.openSession("region-probe-trip", "region-probe-session")
                RoomLocationStore(runtime.database, lookup, { 100_000 }, ownsDatabase = false).use { locations ->
                    check(!locations.isExplicitlyEnabled())
                    locations.openSegment("region-probe-trip", "region-probe-session", "region-probe-segment")
                    fun point(id: String, time: Long, verified: Boolean) = LocationNormalizer(if (verified) LocationMeaningAdapter {
                        VerifiedLocationMeaning("synthetic-region-probe-only", "epoch_ms", it.rawTimestampMillis, 1.0, 30.0)
                    } else LocationMeaningAdapter.UNKNOWN).normalize(
                        LocationSample(latitude, longitude, 0.0, 0f, 1f, time, time, time), LocationReadingKind.UPDATE,
                        id, "synthetic-region-probe-only", "synthetic-clock", null, null)
                    val unknown = locations.append("region-probe-segment", (1..3).map { point("unknown-$it", it * 1000L, false) })
                    check(unknown.inserted == 3 && unknown.confirmedRegionId == null && unknown.acquiredRegionIds.isEmpty())
                    val valid = locations.append("region-probe-segment", (4..6).map { point("valid-$it", it * 1000L, true) })
                    check(valid.inserted == 3 && valid.confirmedRegionId == null && valid.acquiredRegionIds.isEmpty())
                    check(count("SELECT count(*) FROM collection_record") == 0L && count("SELECT count(*) FROM collection_proof") == 0L)
                    check(count("SELECT count(*) FROM preference") == 0L)
                    locations.finishSegment("region-probe-segment", "explicit_stop", interrupted = false)
                }
                mark("region_unknown_location_and_unreviewed_policy_independently_withhold_region_confirmation_cards_and_proofs")

                sql.execSQL("UPDATE source_evidence SET review_status='rejected' WHERE source_revision_id=?", arrayOf(manifest.source.sourceRevisionId))
                check(!installer.isAuthorized(manifest, expected.regionRevisionId))
                check(lookup.containing(latitude, longitude).isEmpty())
                try { installer.install(manifest); error("region_revocation_overwritten") }
                catch (failure: RegionDatasetException) { check(failure.code == "REGION_IMMUTABLE_CONFLICT") }
                check(count("SELECT count(*) FROM source_evidence WHERE review_status='rejected'") == 1L)
                mark("region_source_revocation_withdraws_existing_lookup_and_reinstall_cannot_restore_authority")

                val manifestBytes = context.assets.open(BundledRegionCatalog.MANIFEST_ASSET).use { it.readBytes() }
                try {
                    RegionDatasetManifestCodec.decode(manifestBytes + byteArrayOf(32), BundledRegionCatalog.MANIFEST_SHA256)
                    error("region_manifest_tamper_accepted")
                } catch (failure: RegionDatasetException) { check(failure.code == "INVALID_REGION_DATASET") }
                mark("region_packaged_review_pin_rejects_changed_manifest_bytes")
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }
    }
}
