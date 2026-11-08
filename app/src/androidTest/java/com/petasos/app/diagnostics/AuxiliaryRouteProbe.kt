package com.petasos.app.diagnostics

import android.app.Instrumentation
import android.os.SystemClock
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.capability.location.AndroidLocationMetadata
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.generation.DeviceGenerationConfigStore
import com.petasos.app.location.AndroidLocationMeaningAdapter
import com.petasos.app.location.BoundaryCoordinate
import com.petasos.app.location.LocationNormalizer
import com.petasos.app.location.RecordedLocation
import com.petasos.app.planning.ReviewedRouteForecast
import com.petasos.app.planning.auxiliary.*
import com.petasos.app.platform.android.location.BundledRegionCatalog
import com.petasos.app.platform.android.location.BundledRegionPolicyCatalog
import com.petasos.app.storage.RoomRegionPolicyStore
import com.petasos.app.storage.StorageDatabase
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Exactly one authenticated public-example request. Geometry is real; the Android point and clock
 * used to exercise anchoring are explicitly synthetic. No observed location, navigation, product
 * records, consent, configuration mutation, audio, model, or raw-route file is involved.
 */
object AuxiliaryRouteProbe {
    class Failure(val outcome: String, val code: String) : Exception(code)

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val started = SystemClock.elapsedRealtime()
        fun withinBudget() {
            requireProbe(SystemClock.elapsedRealtime() - started < 60_000, "auxiliary_probe_budget_exceeded")
        }
        val context = instrumentation.targetContext
        val databaseName = "auxiliary-route-probe-${UUID.randomUUID()}.db"
        val configuration = DeviceGenerationConfigStore(context)
        val future = CompletableFuture<AuxiliaryRouteOutcome>()
        val response = AuxiliaryRouteClient(configuration).use { client ->
            // The unchanged public NAVER Directions 5 example, never the user's destination.
            // https://api.ncloud-docs.com/docs/application-maps-directions5
            val request = AuxiliaryRouteRequest(BoundaryCoordinate(127.1058342, 37.359708),
                BoundaryCoordinate(129.075986, 35.179470))
            mark("auxiliary_public_official_example_request_count_1")
            val cancellation = client.submit(request, { true }) { future.complete(it) }
            val outcome = try { future.get(20, TimeUnit.SECONDS) }
            catch (_: TimeoutException) { throw Failure("BLOCKED", "auxiliary_https_callback_timeout") }
            finally { cancellation.cancel() }
            when (outcome) {
                is AuxiliaryRouteOutcome.Success -> outcome.value
                is AuxiliaryRouteOutcome.Failure -> throw Failure("BLOCKED", "auxiliary_https_${outcome.kind.name.lowercase()}")
            }
        }
        withinBudget()
        requireProbe(response.timingUsable, "auxiliary_timing_unusable")
        val route = response.geometry
        mark("auxiliary_https_codec_validated")
        mark("auxiliary_path_points_${route.path.size}")
        mark("auxiliary_guide_count_${route.guides.size}")
        mark("auxiliary_route_sha256_${route.routeKey}")

        val database = StorageDatabase.open(context, databaseName)
        try {
            run {
                val catalog = BundledRegionCatalog(context)
                val manifest = catalog.readManifest()
                val bundledPolicies = BundledRegionPolicyCatalog(context).readBundle()
                val regionEntries = manifest.regions.associateBy { it.regionRevisionId }
                requireProbe(bundledPolicies.reviews.size == 9 && bundledPolicies.reviews.map {
                    regionEntries.getValue(it.regionRevisionId).code
                }.toSet() == setOf("11140", "41461", "41463", "41465", "41550", "44131", "44133", "30110", "30200"),
                    "auxiliary_packaged_policy_inventory_mismatch")
                catalog.loadVerified(database) // Disposable geometry/provenance and the actual pinned policy inventory.
                val sql = database.openHelper.writableDatabase
                fun policySnapshot(): List<List<String?>> = sql.query(
                    "SELECT region_revision_id,policy_json,verified_at FROM region_revision ORDER BY region_revision_id"
                ).use { rows ->
                    buildList {
                        while (rows.moveToNext()) add(listOf(rows.getString(0), rows.getString(1),
                            if (rows.isNull(2)) null else rows.getLong(2).toString()))
                    }
                }
                val policyBefore = policySnapshot()
                requireProbe(policyBefore.size == 264 && policyBefore.count { it[2] != null } == 9,
                    "auxiliary_packaged_policy_bootstrap_mismatch")
                val policyReader = RoomRegionPolicyStore(database)
                requireProbe(bundledPolicies.reviews.all {
                    policyReader.reviewedPolicy(it.regionRevisionId)?.policy == it.policy
                }, "auxiliary_packaged_policy_values_mismatch")
                val lookup = catalog.loadPolyline(database)
                val projectionStart = SystemClock.elapsedRealtime()
                val timeline = AuxiliaryRegionTimelineProjector(lookup).project(route)
                    ?: throw Failure("FAIL", "auxiliary_projection_invalid")
                val projectionMs = SystemClock.elapsedRealtime() - projectionStart
                withinBudget()
                mark("auxiliary_projection_elapsed_ms_$projectionMs")
                mark("auxiliary_known_window_count_${timeline.windows.size}")
                mark("auxiliary_unknown_guide_count_${timeline.unknownGuideCount}")
                mark("auxiliary_evaluated_guide_count_${timeline.evaluatedGuideCount}")
                mark("auxiliary_geometry_budget_exhausted_${timeline.geometryBudgetExhausted}")
                requireProbe(timeline.windows.isNotEmpty(), "auxiliary_no_proved_region_windows")
                val authorized = catalog.polylineAuthorizer(database)
                requireProbe(authorized(timeline), "auxiliary_geometry_authority_missing")
                mark("auxiliary_actual_ngii_hierarchy_projection_validated")

                // Search a bounded set of PUBLIC route segments for an unambiguous synthetic
                // position with a nonempty future intersection. Never loosen production matching.
                val selection = findAnchorPoint(route, timeline, { authorized(timeline) }, ::withinBudget)
                    ?: throw Failure("FAIL", "auxiliary_no_usable_synthetic_anchor")
                val point = selection.first
                val forecast = selection.second
                requireProbe(forecast.passages.isNotEmpty(), "auxiliary_anchor_empty")
                mark("auxiliary_synthetic_android_anchor_passage_count_${forecast.passages.size}")

                val expiring = AuxiliaryRouteAnchor(ANCHOR_CONTEXT, route, timeline, 0, 11_000)
                requireProbe(expiring.update(point, { true }, SYNTHETIC_NOW) != null, "auxiliary_expiry_fixture_invalid")
                requireProbe(expiring.current(ANCHOR_CONTEXT, 11_000) == null &&
                    expiring.current(ANCHOR_CONTEXT, SYNTHETIC_NOW) == null, "auxiliary_source_expiry_not_terminal")
                mark("auxiliary_source_expiry_revokes_and_cannot_resurrect")

                val revocable = AuxiliaryRouteAnchor(ANCHOR_CONTEXT, route, timeline, 0, 60_000)
                requireProbe(revocable.update(point, { authorized(timeline) }, SYNTHETIC_NOW) != null,
                    "auxiliary_revocation_fixture_invalid")
                sql.execSQL("UPDATE source_evidence SET review_status='rejected' WHERE source_revision_id=?",
                    arrayOf(manifest.source.sourceRevisionId))
                requireProbe(!authorized(timeline) && revocable.current(ANCHOR_CONTEXT, SYNTHETIC_NOW) == null,
                    "auxiliary_source_revocation_not_applied")
                // A new owner-created snapshot is also withheld. Existing immutable geometry is
                // not live authority: the authorizer above revokes its use after source withdrawal.
                val first = timeline.windows.first()
                val firstGuide = route.guides[first.startGuideIndex]
                requireProbe(catalog.loadPolyline(database).containing(route.path.subList(firstGuide.startPointIndex,
                    firstGuide.endPointIndex + 1)) == null, "auxiliary_revoked_geometry_still_authorized")
                mark("auxiliary_disposable_source_revocation_rejects_cached_and_new_forecasts")

                fun count(table: String): Long = sql.query(SimpleSQLiteQuery("SELECT count(*) FROM $table")).use {
                    requireProbe(it.moveToFirst(), "auxiliary_count_failed"); it.getLong(0)
                }
                requireProbe(listOf("trip", "session", "route_point", "collection_record", "collection_proof")
                    .all { count(it) == 0L }, "auxiliary_unexpected_durable_travel_records")
                requireProbe(policySnapshot() == policyBefore, "auxiliary_packaged_policy_mutated")
                requireProbe(bundledPolicies.reviews.all { policyReader.reviewedPolicy(it.regionRevisionId) == null },
                    "auxiliary_source_revocation_left_policy_current")
                withinBudget()
                mark("auxiliary_packaged_policies_unchanged_no_location_trip_or_collection_created")
            }
        } finally {
            database.close()
            requireProbe(context.deleteDatabase(databaseName), "auxiliary_disposable_database_cleanup_failed")
        }
        withinBudget()
        mark("auxiliary_public_route_memory_only_probe_complete")
    }

    private fun findAnchorPoint(route: AuxiliaryRouteGeometry, timeline: AuxiliaryRegionTimeline,
                                authority: () -> Boolean, withinBudget: () -> Unit): Pair<RecordedLocation, ReviewedRouteForecast>? {
        val segmentIndexes = linkedSetOf<Int>()
        route.guides.forEach { guide ->
            if (guide.endPointIndex > guide.startPointIndex) {
                segmentIndexes += guide.startPointIndex
                segmentIndexes += (guide.startPointIndex + guide.endPointIndex - 1) / 2
                segmentIndexes += guide.endPointIndex - 1
            }
        }
        for ((attempt, index) in segmentIndexes.take(96).withIndex()) {
            withinBudget()
            val a = route.path[index]; val b = route.path[index + 1]
            if (a == b) continue
            val position = BoundaryCoordinate((a.longitude + b.longitude) / 2, (a.latitude + b.latitude) / 2)
            val point = syntheticPoint(position, attempt)
            val anchor = AuxiliaryRouteAnchor(ANCHOR_CONTEXT, route, timeline, 0, 60_000)
            val forecast = anchor.update(point, authority, SYNTHETIC_NOW)
            if (forecast != null) return point to forecast
        }
        return null
    }

    private fun syntheticPoint(position: BoundaryCoordinate, attempt: Int): RecordedLocation {
        val metadata = AndroidLocationMetadata("gps", SYNTHETIC_NOW * 1_000_000, SYNTHETIC_NOW * 1_000_000,
            5.0, false, null)
        return LocationNormalizer(AndroidLocationMeaningAdapter(100.0)).normalize(
            LocationSample(position.latitude, position.longitude, 0.0, 0f, 0f,
                1_000_000 + SYNTHETIC_NOW, 1_000_000 + SYNTHETIC_NOW, SYNTHETIC_NOW, metadata),
            LocationReadingKind.UPDATE, "auxiliary-public-synthetic-$attempt", "android", ANCHOR_CONTEXT.clockScopeId,
            routeRevision = ANCHOR_CONTEXT.routeRevision)
    }

    private fun requireProbe(condition: Boolean, code: String) { if (!condition) throw Failure("FAIL", code) }
    private const val SYNTHETIC_NOW = 10_000L
    private val ANCHOR_CONTEXT = AuxiliaryRouteAnchorContext("auxiliary-public-probe", "auxiliary-synthetic-clock", 1, 1)
}
