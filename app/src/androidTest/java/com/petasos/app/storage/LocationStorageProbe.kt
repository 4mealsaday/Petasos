package com.petasos.app.storage

import android.app.Instrumentation
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.capability.navigation.NavigationSnapshot
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.content.ContentParagraph
import com.petasos.app.generation.StrictJson
import com.petasos.app.location.*
import java.util.UUID

/** Real Room transactions with synthetic location and boundary fixtures only; no SDK, GPS or network. */
object LocationStorageProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "location-storage-probe-${UUID.randomUUID()}.db"
        try {
            SyntheticStorageFixture.create(context, name, wallTime = { 100_000 }).use { runtime ->
                SyntheticStorageFixture.install(runtime)
                runtime.openSession("location-trip", "location-session")
                val sql = runtime.database.openHelper.writableDatabase
                fun exec(statement: String, vararg args: Any?) = sql.execSQL(statement, args)
                fun count(statement: String, vararg args: Any?): Long = sql.query(SimpleSQLiteQuery(statement, args)).use {
                    check(it.moveToFirst()); it.getLong(0)
                }
                fun text(statement: String): String? = sql.query(statement).use { check(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0) }
                var revision = "synthetic-region-r1"
                var boundaryEnabled = true
                val boundary = RegionBoundaryLookup { _, _ -> if (boundaryEnabled) listOf(
                    RegionContainment(revision, "synthetic-boundary-v1", "EPSG:4326", 100.0),
                ) else emptyList() }
                exec("INSERT INTO region(region_id,name) VALUES ('synthetic-region','TEST ONLY')")
                fun addRevision(id: String, code: String) {
                    exec("INSERT INTO region_revision(region_revision_id,region_id,code,dataset_version,name,source_revision_id,policy_json,verified_at) VALUES (?,'synthetic-region',?,'synthetic-boundary-v1','TEST ONLY','test-source-r1',?,1)",
                        id, code, policy())
                }
                addRevision(revision, "00001")
                RegionalBindingFixture.bindExistingStory(runtime.database, "test-story", revision)

                RoomLocationStore(runtime.database, boundary, { 100_000 }, ownsDatabase = false).use { store ->
                    check(!store.isExplicitlyEnabled())
                    exec("INSERT INTO preference(scope_id,keywords_json,voice_json,story_frequency_json,location_enabled,version,updated_at) VALUES ('vehicle','[]',?,?,0,1,1)",
                        "{\"locale\":\"ko-KR\",\"voiceId\":\"synthetic-test-only\",\"rate\":1}",
                        "{\"mode\":\"balanced\",\"betweenStoriesMusicTracks\":1}")
                    check(!store.isExplicitlyEnabled())
                    exec("UPDATE preference SET location_enabled=1,version=2,updated_at=2 WHERE scope_id='vehicle'")
                    check(store.isExplicitlyEnabled())
                    mark("location_missing_or_disabled_preference_does_not_imply_recording_consent")
                    store.openSegment("location-trip", "location-session", "location-segment-1")
                    val unknown = (0..1).map { point("unknown-$it", it * 1_000L, verified = false) }
                    check(store.append("location-segment-1", unknown).inserted == 2)
                    check(count("SELECT count(*) FROM route_point WHERE quality_status='unknown' AND observed_at IS NULL AND source_time_unit='unknown'") == 2L)
                    check(count("SELECT count(*) FROM collection_record") == 0L)
                    check(count("SELECT route_revision FROM session WHERE session_id='location-session'") == 0L)
                    mark("location_unknown_semantics_recorded_without_collection_or_gps_route_revision_change")

                    val first = listOf(point("valid-1", 2_000), point("invalid", 3_000, verified = false), point("valid-2", 4_000))
                    check(store.append("location-segment-1", first).confirmedRegionId == null)
                    check(count("SELECT count(*) FROM collection_record") == 0L)
                    check(store.append("location-segment-1", listOf(point("valid-3", 5_000))).confirmedRegionId == null)
                    val confirmed = store.append("location-segment-1", listOf(point("valid-4", 6_000)))
                    check(confirmed.acquiredRegionIds == setOf("synthetic-region") && confirmed.confirmedRegionId == "synthetic-region")
                    check(count("SELECT count(*) FROM collection_proof") == 3L)
                    check(count("SELECT count(*) FROM collection_proof p JOIN route_point r ON r.point_id=p.point_id WHERE r.quality_status='valid' AND p.region_revision_id='synthetic-region-r1'") == 3L)
                    check(store.currentRegionStories("synthetic-region").single().versionId == "test-story-v1")
                    mark("location_consecutive_valid_points_margin_duration_and_region_proofs_commit_atomically")

                    val event = point("provider-event-original", 7_000, providerEvent = "event-1")
                    check(store.append("location-segment-1", listOf(event)).inserted == 1)
                    check(store.append("location-segment-1", listOf(event)).inserted == 0)
                    val duplicate = store.append("location-segment-1", listOf(point("provider-event-repeat", 7_000, providerEvent = "event-1")))
                    check(duplicate.duplicateEvents == 1 && duplicate.inserted == 0)
                    check(store.append("location-segment-1", listOf(point("same-coordinate-new-event", 8_000))).inserted == 1)
                    check(count("SELECT count(*) FROM collection_record") == 1L)
                    mark("location_point_retry_idempotent_provider_event_dedup_only_same_coordinates_preserved")

                    revision = "synthetic-region-r2"
                    addRevision(revision, "00002")
                    check(store.append("location-segment-1", (9..11).map { point("revision-$it", it * 1_000L) }).confirmedRegionId == "synthetic-region")
                    check(count("SELECT count(*) FROM collection_record") == 1L)
                    check(count("SELECT count(*) FROM collection_proof WHERE region_revision_id='synthetic-region-r1'") == 3L)
                    check(count("SELECT count(*) FROM collection_proof WHERE region_revision_id='synthetic-region-r2'") == 0L)
                    mark("location_region_code_revision_preserves_original_card_and_proof")

                    exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'", StrictJson.stringify(SyntheticStorageFixture.allowedUses(display = false)))
                    check(store.append("location-segment-1", listOf(point("revoked", 12_000))).confirmedRegionId == null)
                    check(store.currentRegionStories("synthetic-region").isEmpty())
                    exec("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='test-source-r1'", StrictJson.stringify(SyntheticStorageFixture.allowedUses()))
                    boundaryEnabled = false
                    check(store.append("location-segment-1", listOf(point("no-boundary", 13_000))).confirmedRegionId == null)
                    boundaryEnabled = true
                    exec("UPDATE region_revision SET verified_at=NULL WHERE region_revision_id=?", revision)
                    check(store.append("location-segment-1", listOf(point("unverified-policy", 14_000))).confirmedRegionId == null)
                    exec("UPDATE region_revision SET verified_at=1 WHERE region_revision_id=?", revision)
                    mark("location_revoked_rights_missing_boundary_and_unreviewed_policy_hold_automatic_features")

                    val beforeRollback = count("SELECT count(*) FROM route_point")
                    try {
                        store.append("location-segment-1", listOf(point("rolled-back", 15_000), event.copy(latitude = 0.0)))
                        error("location_conflicting_retry_accepted")
                    } catch (_: StorageUnavailable) { }
                    check(count("SELECT count(*) FROM route_point") == beforeRollback)
                    check(count("SELECT count(*) FROM route_point WHERE point_id='rolled-back'") == 0L)
                    mark("location_conflicting_retry_rolls_back_entire_batch")

                    RoomContentInstaller(runtime.database, { 100_000 }, ownsDatabase = false).use { installer ->
                        installer.install(overlappingStoryBundle())
                        RegionalBindingFixture.bindExistingStory(runtime.database, "test-overlap-story", revision)
                        check(store.currentRegionStories("synthetic-region").map { it.storyId }.toSet() == setOf("test-story", "test-overlap-story"))
                        exec("INSERT INTO playback_attempt(attempt_id,session_id,kind,paragraph_id,state,started_at,interrupted_at,clock_scope_id) VALUES ('location-interrupted','location-session','story','test-p1','interrupted',1,2,'synthetic-clock')")
                        check(store.currentRegionStories("synthetic-region").size == 2)
                        exec("INSERT INTO playback_attempt(attempt_id,session_id,kind,paragraph_id,state,started_at,completed_at,clock_scope_id) VALUES ('location-heard','location-session','story','test-p1','completed',1,2,'synthetic-clock')")
                        check(store.currentRegionStories("synthetic-region").isEmpty())
                        check(installer.publishedStory("test-story-v1").storyId == "test-story")
                        check(installer.publishedStory("test-overlap-v1").storyId == "test-overlap-story")
                    }
                    mark("location_automatic_candidates_exclude_completed_story_and_shared_fact_but_explicit_replay_remains_available")

                    RoomNavigationStore(runtime.database, ownsDatabase = false).use { navigation ->
                        check(navigation.readRevision("location-session") == 0L)
                        check(navigation.apply("location-session", NavigationSnapshot(2, 2, true, false)))
                        check(!navigation.apply("location-session", NavigationSnapshot(1, 1, true, false)))
                        check(navigation.isCurrent("location-session", 2))
                        check(navigation.isCurrent("location-session", null))
                        check(navigation.apply("location-session", NavigationSnapshot(3, null, false, false)))
                        check(navigation.readRevision("location-session") == 3L)
                        check(!navigation.isCurrent("location-session", 2))
                        check(navigation.isCurrent("location-session", null))
                    }
                    mark("navigation_revision_monotonic_null_route_preserves_counter_and_route_independent_context")

                    runtime.openSession("location-trip", "location-session-2")
                    check(text("SELECT status FROM recording_segment WHERE segment_id='location-segment-1'") == "interrupted")
                    check(text("SELECT ended_at FROM recording_segment WHERE segment_id='location-segment-1'") == null)
                    check(count("SELECT interruption_detected_at FROM recording_segment WHERE segment_id='location-segment-1'") == 100_000L)
                    check(count("SELECT last_observed_at FROM recording_segment WHERE segment_id='location-segment-1'") == 14_000L)
                    RoomNavigationStore(runtime.database, ownsDatabase = false).use { navigation ->
                        check(!navigation.apply("location-session", NavigationSnapshot(4, 4, true, false)))
                        check(!navigation.isCurrent("location-session", null))
                        check(navigation.readRevision("location-session-2") == 0L)
                    }
                    try { store.append("location-segment-1", listOf(point("stale-session", 16_000))); error("stale_location_session_accepted") }
                    catch (_: StorageUnavailable) { }
                    store.openSegment("location-trip", "location-session-2", "location-segment-2")
                    check(store.append("location-segment-2", listOf(point("new-session", 17_000))).confirmedRegionId == null)
                    store.finishSegment("location-segment-2", "explicit_stop", interrupted = false)
                    check(count("SELECT ended_at FROM recording_segment WHERE segment_id='location-segment-2'") == 100_000L)
                    check(count("SELECT count(*) FROM collection_record") == 1L)
                    mark("location_session_recovery_keeps_unknown_end_time_and_new_segment_requires_fresh_confirmation")
                }
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }
    }

    private fun policy(): String = StrictJson.stringify(mapOf(
        "datasetVersion" to "synthetic-boundary-v1", "consecutiveSamples" to 3, "minConfirmationDurationMs" to 2_000,
        "boundaryMarginMeters" to 10, "qualityAdapterVersion" to "synthetic-location-v1", "coordinateReferenceSystem" to "EPSG:4326",
    ))

    /** A distinct story shares one stable fact with the original; only automatic repetition is filtered. */
    private fun overlappingStoryBundle(): ByteArray {
        val original = StrictJson.parse(SyntheticStorageFixture.bundle()) as Map<*, *>
        val paragraph = ContentParagraph("test-overlap-p1", 0, true, "합성 중복 사실 시험 문단입니다.", null, 1_000, listOf("test-fact-r1"))
        return StrictJson.encode(mapOf("formatVersion" to 1, "sources" to original["sources"], "facts" to original["facts"],
            "places" to emptyList<Any?>(),
            "stories" to listOf(mapOf("storyId" to "test-overlap-story", "topicId" to "test-overlap-topic", "title" to "SYNTHETIC OVERLAP ONLY",
                "coreQuestion" to "Synthetic duplicate filtering only", "interestTags" to emptyList<String>(), "placeId" to null)),
            "versions" to listOf(mapOf("storyVersionId" to "test-overlap-v1", "storyId" to "test-overlap-story", "version" to 1,
                "contentHash" to ContentBundleCodec.versionHash("test-overlap-story", 1, listOf(paragraph)), "createdAt" to 1, "verifiedAt" to 1,
                "validUntil" to null, "estimatedDurationMs" to 1_000, "status" to "published",
                "paragraphs" to listOf(mapOf("paragraphId" to paragraph.paragraphId, "ordinal" to paragraph.ordinal,
                    "required" to paragraph.required, "text" to paragraph.text, "audioRef" to paragraph.audioRef,
                    "estimatedDurationMs" to paragraph.estimatedDurationMs, "factRevisionIds" to paragraph.factRevisionIds))))))
    }

    private fun point(id: String, elapsed: Long, verified: Boolean = true, providerEvent: String? = null): RecordedLocation {
        val meaning = if (verified) LocationMeaningAdapter {
            VerifiedLocationMeaning("synthetic-location-v1", "epoch_ms", it.rawTimestampMillis, 5.0, 30.0)
        } else LocationMeaningAdapter.UNKNOWN
        return LocationNormalizer(meaning).normalize(LocationSample(37.0, 127.0, 0.0, 0f, 5f, elapsed, elapsed, elapsed),
            LocationReadingKind.UPDATE, id, "synthetic-test-only", "synthetic-clock", if (providerEvent == null) null else "synthetic-provider-epoch", providerEvent)
    }
}
