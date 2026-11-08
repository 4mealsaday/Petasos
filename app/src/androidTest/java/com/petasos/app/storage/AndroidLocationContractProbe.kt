package com.petasos.app.storage

import android.app.Instrumentation
import android.location.Location
import android.os.Build
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.generation.StrictJson
import com.petasos.app.location.*
import com.petasos.app.platform.android.location.AndroidLocationSource
import com.petasos.app.platform.android.location.LocationAccess
import java.util.UUID

/** Public provider metadata and synthetic Android Location/Room data only. Never subscribes or reads a fix. */
object AndroidLocationContractProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val before = LocationAccess.hasRequiredPermissions(context)
        AndroidLocationSource.inspect(context)
        val selected = AndroidLocationSource.preferredProvider(context)
        check(selected == null || selected in setOf("fused", "gps", "network"))
        check(before == LocationAccess.hasRequiredPermissions(context))
        mark("android_location_public_provider_metadata_no_coordinate_read_or_permission_change")
        mark("android_location_required_permissions_${before}")
        mark("android_location_selected_provider_${selected ?: "unavailable"}")

        val normalizer = LocationNormalizer(AndroidLocationMeaningAdapter(100.0))
        fun sample(index: Long): com.petasos.app.capability.location.LocationSample {
            val location = Location("gps").apply {
                latitude = 37.0; longitude = 127.0
                time = index * 1_000
                elapsedRealtimeNanos = index * 1_000_000_000
                accuracy = 5f
            }
            // A synthetic wall-clock offset must not replace measured monotonic age.
            return AndroidLocationSource.sample(location, 900_000 + index * 1_000, (index + 1) * 1_000_000_000)
        }
        fun point(index: Long) = normalizer.normalize(sample(index), LocationReadingKind.UPDATE,
            "android-point-$index", "android-location-manager", "android-synthetic-clock")
        val first = point(1)
        check(first.quality == LocationQuality.VALID && first.provider == "android-location-manager:gps")
        check(first.observedAtEpochMs == 1_000L && first.receivedAtEpochMs == 901_000L)
        check(first.measurementAgeAtReceiptMs == 1_000L && LocationFreshness.deadlineElapsedMs(first) == 11_000L)
        check(sample(1).rawConfidence.isNaN() && sample(1).altitude.isNaN() && sample(1).heading.isNaN())
        val unknown = Location("gps").apply { latitude = 37.0; longitude = 127.0; time = 1_000; elapsedRealtimeNanos = 1_000_000_000 }
        check(normalizer.normalize(AndroidLocationSource.sample(unknown, 1_001, 1_001_000_000),
            LocationReadingKind.UPDATE, "no-accuracy", "android-location-manager", "clock").quality == LocationQuality.UNKNOWN)
        if (Build.VERSION.SDK_INT >= 31) {
            unknown.accuracy = 5f; unknown.isMock = true
            check(normalizer.normalize(AndroidLocationSource.sample(unknown, 1_001, 1_001_000_000),
                LocationReadingKind.UPDATE, "mock", "android-location-manager", "clock").quality != LocationQuality.VALID)
        }
        mark("android_location_actual_Location_mapping_monotonic_age_missing_accuracy_and_mock_exclusion")

        val name = "android-location-contract-${UUID.randomUUID()}.db"
        try {
            SyntheticStorageFixture.create(context, name, wallTime = { 1_000_000 }).use { runtime ->
                SyntheticStorageFixture.install(runtime)
                runtime.openSession("android-trip", "android-session")
                val sql = runtime.database.openHelper.writableDatabase
                val policy = StrictJson.stringify(mapOf("datasetVersion" to "synthetic-android-region",
                    "consecutiveSamples" to 2, "minConfirmationDurationMs" to 500,
                    "boundaryMarginMeters" to 10, "qualityAdapterVersion" to AndroidLocationMeaningAdapter.VERSION,
                    "coordinateReferenceSystem" to "EPSG:4326"))
                sql.execSQL("INSERT INTO region(region_id,name) VALUES ('android-region','SYNTHETIC ONLY')")
                sql.execSQL("INSERT INTO region_revision(region_revision_id,region_id,code,dataset_version,name,source_revision_id,policy_json,verified_at) VALUES ('android-region-r1','android-region','00000','synthetic-android-region','SYNTHETIC ONLY','test-source-r1',?,1)", arrayOf(policy))
                val boundary = RegionBoundaryLookup { _, _ -> listOf(RegionContainment("android-region-r1", "synthetic-android-region", "EPSG:4326", 1_000.0)) }
                RoomLocationStore(runtime.database, boundary, { 1_000_000 }, ownsDatabase = false).use { locations ->
                    check(!locations.isExplicitlyEnabled())
                    locations.openSegment("android-trip", "android-session", "android-segment")
                    check(locations.append("android-segment", listOf(first)).confirmedRegionId == null)
                    check(locations.append("android-segment", listOf(point(2))).confirmedRegionId == "android-region")
                    sql.query("SELECT provider,observed_at,provider_confidence_json FROM route_point WHERE point_id='android-point-1'").use {
                        check(it.moveToFirst() && it.getString(0) == "android-location-manager:gps" && it.getLong(1) == 1_000L)
                        val json = StrictJson.parse(it.getString(2).toByteArray()) as Map<*, *>
                        check(json["measurementAgeAtReceiptMs"] == 1_000L && json["androidLocation"] is Map<*, *>)
                    }
                    mark("android_location_original_measurement_provenance_and_monotonic_age_roundtrip_in_Room")
                    val corrupted = (StrictJson.parse(point(2).providerConfidenceJson.toByteArray()) as Map<*, *>)
                        .entries.associate { it.key.toString() to it.value }.toMutableMap()
                    corrupted["measurementAgeAtReceiptMs"] = 0
                    sql.execSQL("UPDATE route_point SET provider_confidence_json=? WHERE point_id='android-point-2'", arrayOf(StrictJson.stringify(corrupted)))
                    check(locations.append("android-segment", listOf(point(3))).confirmedRegionId == null)
                    sql.query(SimpleSQLiteQuery("SELECT count(*) FROM collection_record")).use { check(it.moveToFirst() && it.getLong(0) == 1L) }
                    check(!locations.isExplicitlyEnabled())
                    locations.finishSegment("android-segment", "explicit_stop", interrupted = false)
                    mark("android_location_corrupt_monotonic_evidence_cannot_confirm_region_no_product_consent_change")
                }
            }
        } finally { context.deleteDatabase(name) }
    }
}
