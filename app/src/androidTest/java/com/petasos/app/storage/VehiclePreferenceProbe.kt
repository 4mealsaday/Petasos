package com.petasos.app.storage

import android.app.Instrumentation
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.location.RegionBoundaryLookup
import com.petasos.app.records.VehiclePreferences
import com.petasos.app.records.VehicleRecordingSettings
import org.json.JSONObject
import java.util.UUID

/** Disposable Room only. Does not access product preferences, location providers, payload keys or audio. */
object VehiclePreferenceProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "vehicle-preference-probe-${UUID.randomUUID()}.db"
        var now = 1_000L
        try {
            val database = StorageDatabase.open(context, name)
            try {
                val sql = database.openHelper.writableDatabase
                val store = RoomVehiclePreferenceStore(database, { now })
                val competingStore = RoomVehiclePreferenceStore(database, { now })
                fun exec(statement: String, vararg args: Any?) = sql.execSQL(statement, args)
                fun count(statement: String): Long = sql.query(statement).use { check(it.moveToFirst()); it.getLong(0) }
                fun raw(): List<String> = sql.query("SELECT keywords_json,voice_json,story_frequency_json,location_enabled,version,updated_at FROM preference WHERE scope_id='vehicle'").use {
                    check(it.moveToFirst()); (0..5).map(it::getString)
                }
                fun unchanged(block: () -> Unit) {
                    val before = raw()
                    block()
                    check(raw() == before)
                }
                fun noCollection() {
                    listOf("trip", "session", "recording_segment", "route_point", "playback_attempt",
                        "conversation_turn", "pending_conversation", "conversation_summary", "collection_record",
                        "collection_proof", "generation_request").forEach { table ->
                        check(count("SELECT count(*) FROM $table") == 0L)
                    }
                }
                fun defaults() {
                    val values = raw()
                    // DATA-12 rate is a decimal; generation.StrictJson intentionally accepts integers only.
                    val voice = JSONObject(values[1])
                    check(voice.keys().asSequence().toSet() == setOf("locale", "voiceId", "rate"))
                    check(voice.getString("locale") == "ko-KR" && voice.isNull("voiceId") && voice.getDouble("rate") == 1.0)
                    val frequency = JSONObject(values[2])
                    check(frequency.keys().asSequence().toSet() == setOf("mode", "betweenStoriesMusicTracks"))
                    check(frequency.getString("mode") == "balanced" && frequency.getInt("betweenStoriesMusicTracks") == 1)
                }

                repeat(2) {
                    check(store.readRecordingSettings() == VehicleRecordingSettings(false, null, null))
                    val preferences = store.readSettings()
                    check(!preferences.locationEnabled && preferences.version == null && preferences.updatedAt == null && preferences.keywords.isEmpty())
                }
                check(count("SELECT count(*) FROM preference") == 0L)
                noCollection()
                mark("preferences_missing_read_is_off_without_inserting_or_starting_collection")

                check(store.setLocationEnabled(false, null) == VehicleRecordingSettings(false, 1, now))
                defaults()
                now = 2_000
                unchanged { check(store.setLocationEnabled(false, 1) == VehicleRecordingSettings(false, 1, 1_000)) }
                unchanged { expectConflict { store.setLocationEnabled(true, null) } }
                unchanged { expectInvalid { store.setLocationEnabled(true, 0) } }
                noCollection()
                mark("preferences_explicit_initial_save_uses_data12_defaults_and_noop_keeps_revision_time")

                val selectedJson = "[ \"TEST_ONLY_interest\", \"TEST_ONLY_history\" ]"
                val voiceJson = "{\"locale\":\"ko-KR\",\"voiceId\":\"TEST_ONLY_existing_voice\",\"rate\":1.0,\"future\":{\"preserve\":true}}"
                val frequencyJson = "{\"mode\":\"TEST_ONLY_existing_mode\",\"betweenStoriesMusicTracks\":7,\"future\":true}"
                exec("UPDATE preference SET keywords_json=?,voice_json=?,story_frequency_json=? WHERE scope_id='vehicle'", selectedJson, voiceJson, frequencyJson)
                check(store.setLocationEnabled(true, 1) == VehicleRecordingSettings(true, 2, now))
                check(raw().take(3) == listOf(selectedJson, voiceJson, frequencyJson))
                RoomLocationStore(database, RegionBoundaryLookup.NONE, { now }, ownsDatabase = false).use {
                    check(it.isExplicitlyEnabled())
                }
                noCollection()
                mark("preferences_location_toggle_preserves_other_json_and_does_not_create_recording_session")

                val observedByFirst = store.readRecordingSettings()
                val observedBySecond = competingStore.readRecordingSettings()
                now = 500 // UTC can move backward; the revision remains the ordering authority.
                check(store.setLocationEnabled(false, observedByFirst.version) == VehicleRecordingSettings(false, 3, 500))
                unchanged { expectConflict { competingStore.setLocationEnabled(true, observedBySecond.version) } }
                check(raw().take(3) == listOf(selectedJson, voiceJson, frequencyJson))
                mark("preferences_revision_prevents_lost_consent_updates_even_when_utc_moves_backward")

                val currentKeywords = listOf("TEST_ONLY_interest", "TEST_ONLY_history")
                now = 3_000
                unchanged { check(store.setKeywords(currentKeywords, 3).version == 3L) }
                val selected = (1..20).map { "TEST_ONLY_keyword_$it" }.toMutableList()
                selected[0] = "x".repeat(256)
                val maximum = store.setKeywords(selected, 3)
                check(maximum.version == 4L && maximum.updatedAt == now && maximum.keywords.size == 20 && !maximum.locationEnabled)
                selected.clear()
                check(maximum.keywords.size == 20 && store.readSettings().keywords == maximum.keywords)
                check(raw().slice(1..2) == listOf(voiceJson, frequencyJson))
                unchanged { expectInvalid { store.setKeywords((1..21).map { "TEST_ONLY_$it" }, 4) } }
                unchanged { expectInvalid { store.setKeywords(listOf("x".repeat(257)), 4) } }
                unchanged { expectInvalid { store.setKeywords(listOf("  \t"), 4) } }
                unchanged { expectInvalid { store.setKeywords(listOf("same", "same"), 4) } }
                unchanged { expectInvalid { store.setKeywords(emptyList(), -1) } }
                unchanged { expectConflict { store.setKeywords(emptyList(), 3) } }
                check(!maximum.toString().contains("TEST_ONLY") && !maximum.recording.toString().contains("false"))
                val copied = mutableListOf("TEST_ONLY_copied")
                val snapshot = VehiclePreferences(maximum.recording, copied)
                copied.clear()
                check(snapshot.keywords == listOf("TEST_ONLY_copied"))
                mark("preferences_keywords_enforce_generation_bounds_copy_input_and_preserve_other_settings")

                now = 4_000
                val enabled = store.setLocationEnabled(true, 4)
                exec("UPDATE preference SET keywords_json='[invalid_test_fixture' WHERE scope_id='vehicle'")
                expectStorage { store.readSettings() }
                check(store.readRecordingSettings() == enabled)
                check(store.setLocationEnabled(false, enabled.version) == VehicleRecordingSettings(false, 6, now))
                check(raw()[0] == "[invalid_test_fixture" && raw().slice(1..2) == listOf(voiceJson, frequencyJson))
                val repaired = store.setKeywords(emptyList(), 6)
                check(repaired.keywords.isEmpty() && repaired.version == 7L && !repaired.locationEnabled)
                mark("preferences_invalid_interests_cannot_block_consent_withdrawal_and_explicit_edit_repairs_only_interests")

                exec("CREATE TRIGGER preference_probe_update_failure BEFORE UPDATE ON preference BEGIN SELECT RAISE(ABORT,'synthetic_preference_update_failure'); END")
                unchanged { expectStorage { store.setLocationEnabled(true, 7) } }
                unchanged { expectStorage { store.setKeywords(listOf("TEST_ONLY_change"), 7) } }
                exec("DROP TRIGGER preference_probe_update_failure")
                check(store.readSettings().version == 7L)
                mark("preferences_sql_failure_rolls_back_with_fixed_error_code")

                exec("UPDATE preference SET version=? WHERE scope_id='vehicle'", Long.MAX_VALUE)
                unchanged { expectStorage("preference_revision_exhausted") { store.setLocationEnabled(true, Long.MAX_VALUE) } }
                unchanged { expectStorage("preference_revision_exhausted") { store.setKeywords(listOf("TEST_ONLY_change"), Long.MAX_VALUE) } }
                unchanged { check(store.setLocationEnabled(false, Long.MAX_VALUE).version == Long.MAX_VALUE) }
                exec("UPDATE preference SET version=7 WHERE scope_id='vehicle'")
                noCollection()
                mark("preferences_revision_overflow_never_reuses_a_version_or_changes_consent")
            } finally { database.close() }

            val reopened = StorageDatabase.open(context, name)
            try {
                val store = RoomVehiclePreferenceStore(reopened, { now })
                val result = store.readSettings()
                check(!result.locationEnabled && result.version == 7L && result.updatedAt == 4_000L && result.keywords.isEmpty())
                val sql = reopened.openHelper.writableDatabase
                sql.query(SimpleSQLiteQuery("SELECT count(*) FROM preference")).use { check(it.moveToFirst() && it.getLong(0) == 1L) }
                mark("preferences_settings_survive_room_reopen_without_implicit_write")

                // A second first-write path uses only this disposable fixture, never the product DB.
                sql.execSQL("DELETE FROM preference")
                sql.execSQL("CREATE TRIGGER preference_probe_insert_failure BEFORE INSERT ON preference BEGIN SELECT RAISE(ABORT,'synthetic_preference_insert_failure'); END")
                expectStorage { store.setKeywords(listOf("TEST_ONLY_initial"), null) }
                check(store.readRecordingSettings().version == null)
                sql.execSQL("DROP TRIGGER preference_probe_insert_failure")
                val initialKeywords = store.setKeywords(listOf("TEST_ONLY_initial"), null)
                check(initialKeywords.version == 1L && !initialKeywords.locationEnabled && initialKeywords.updatedAt == now)
                check(initialKeywords.keywords == listOf("TEST_ONLY_initial"))
                mark("preferences_interests_first_save_does_not_opt_in_to_location_and_failed_insert_leaves_no_row")
            } finally { reopened.close() }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun expectConflict(block: () -> Unit) {
        try { block(); error("preference_stale_write_accepted") }
        catch (failure: PreferenceVersionConflict) { check(failure.message == "preference_version_conflict") }
    }

    private fun expectInvalid(block: () -> Unit) {
        try { block(); error("preference_invalid_input_accepted") }
        catch (failure: InvalidPreferenceInput) { check(failure.message == "invalid_preference_input") }
    }

    private fun expectStorage(code: String = "preference_storage_unavailable", block: () -> Unit) {
        try { block(); error("preference_storage_failure_accepted") }
        catch (failure: StorageUnavailable) { check(failure.message == code) }
    }
}
