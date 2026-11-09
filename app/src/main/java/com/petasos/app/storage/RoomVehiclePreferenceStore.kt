package com.petasos.app.storage

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.generation.StrictJson
import com.petasos.app.records.VehiclePreferences
import com.petasos.app.records.VehicleRecordingSettings
import java.util.concurrent.Callable

/** The UI must refresh after a concurrent edit; a stale consent must never overwrite a newer one. */
class PreferenceVersionConflict : Exception("preference_version_conflict")

/** A fixed error code only: selected keywords and stored JSON must not enter diagnostics. */
class InvalidPreferenceInput : IllegalArgumentException("invalid_preference_input")

/**
 * Short worker-thread transactions for DATA-12's single vehicle scope. Reads never insert a row.
 * Only an explicit user setting action may call a setter. This store never starts a session,
 * records a location, invokes a platform permission prompt, or changes summary policy.
 */
class RoomVehiclePreferenceStore internal constructor(
    private val database: StorageDatabase,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val ownsDatabase: Boolean = false,
) : AutoCloseable {
    constructor(context: Context, databaseName: String = "petasos.db") : this(
        StorageDatabase.open(context, databaseName), System::currentTimeMillis, true,
    )

    private val sql get() = database.openHelper.writableDatabase

    /** Independent of unrelated JSON validity, so malformed interests cannot prevent consent withdrawal. */
    fun readRecordingSettings(): VehicleRecordingSettings = transaction {
        readRow()?.recording ?: missing()
    }

    fun readSettings(): VehiclePreferences = transaction {
        val row = readRow()
        VehiclePreferences(row?.recording ?: missing(), row?.let { decodeKeywords(it.keywords) } ?: emptyList())
    }

    /** expectedVersion=null means the caller observed no row, not permission for an unconditional write. */
    fun setLocationEnabled(enabled: Boolean, expectedVersion: Long?): VehicleRecordingSettings {
        validateExpected(expectedVersion)
        return transaction {
            val previous = readRow()
            checkVersion(previous, expectedVersion)
            if (previous != null && previous.recording.locationEnabled == enabled) return@transaction previous.recording
            val version = nextVersion(previous)
            val updatedAt = wallTime() // Actual UTC commit time may move backward; revision orders changes.
            if (previous == null) insert(enabled, emptyList(), updatedAt)
            else sql.execSQL("UPDATE preference SET location_enabled=?,version=?,updated_at=? WHERE scope_id='vehicle' AND version=?",
                arrayOf<Any?>(if (enabled) 1 else 0, version, updatedAt, previous.recording.version))
            val result = readRow()?.recording ?: unavailable()
            if (result != VehicleRecordingSettings(enabled, version, updatedAt)) unavailable()
            result
        }
    }

    /** Explicit interest replacement preserves location consent and the other JSON settings exactly. */
    fun setKeywords(keywords: List<String>, expectedVersion: Long?): VehiclePreferences {
        validateExpected(expectedVersion)
        val selected = keywords.toList()
        if (!validKeywords(selected)) throw InvalidPreferenceInput()
        return transaction {
            val previous = readRow()
            checkVersion(previous, expectedVersion)
            // An explicit replacement can repair invalid interests; unrelated reads never repair them.
            val oldKeywords = previous?.let {
                try { decodeKeywords(it.keywords) } catch (_: StorageUnavailable) { null }
            }
            if (previous != null && oldKeywords == selected) return@transaction VehiclePreferences(previous.recording, selected)
            val version = nextVersion(previous)
            val updatedAt = wallTime()
            if (previous == null) insert(false, selected, updatedAt)
            else sql.execSQL("UPDATE preference SET keywords_json=?,version=?,updated_at=? WHERE scope_id='vehicle' AND version=?",
                arrayOf<Any?>(StrictJson.stringify(selected), version, updatedAt, previous.recording.version))
            val result = readRow() ?: unavailable()
            if (result.recording != VehicleRecordingSettings(previous?.recording?.locationEnabled ?: false, version, updatedAt) ||
                decodeKeywords(result.keywords) != selected) unavailable()
            VehiclePreferences(result.recording, selected)
        }
    }

    private class Row(val recording: VehicleRecordingSettings, val keywords: String) {
        override fun toString() = "VehiclePreferenceRow([redacted])"
    }

    private fun readRow(): Row? = sql.query(SimpleSQLiteQuery(
        "SELECT location_enabled,version,updated_at,keywords_json FROM preference WHERE scope_id='vehicle'",
    )).use { row ->
        if (!row.moveToFirst()) return@use null
        if ((0..2).any { row.getType(it) != Cursor.FIELD_TYPE_INTEGER } || row.getLong(0) !in 0..1 ||
            row.getLong(1) < 1 || row.isNull(3)) unavailable()
        Row(VehicleRecordingSettings(row.getLong(0) == 1L, row.getLong(1), row.getLong(2)), row.getString(3))
    }

    private fun insert(enabled: Boolean, keywords: List<String>, updatedAt: Long) {
        // DATA-12 defines these defaults. A null voiceId selects the installed default voice;
        // it is not a fabricated SDK identifier and does not claim a supported voice/rate choice.
        sql.execSQL("INSERT INTO preference(scope_id,keywords_json,voice_json,story_frequency_json,location_enabled,version,updated_at) VALUES ('vehicle',?,?,?,?,1,?)",
            arrayOf<Any?>(StrictJson.stringify(keywords), DEFAULT_VOICE, DEFAULT_FREQUENCY, if (enabled) 1 else 0, updatedAt))
    }

    private fun nextVersion(row: Row?): Long {
        val version = row?.recording?.version ?: return 1
        if (version == Long.MAX_VALUE) throw StorageUnavailable("preference_revision_exhausted")
        return version + 1
    }

    private fun checkVersion(row: Row?, expected: Long?) {
        if (row?.recording?.version != expected) throw PreferenceVersionConflict()
    }
    private fun validateExpected(expected: Long?) {
        if (expected != null && expected < 1) throw InvalidPreferenceInput()
    }
    private fun decodeKeywords(json: String): List<String> {
        val values = try { StrictJson.parse(json.toByteArray(Charsets.UTF_8)) as? List<*> } catch (_: Exception) { null }
            ?: unavailable()
        val result = values.map { it as? String ?: unavailable() }
        if (!validKeywords(result)) unavailable()
        return result
    }
    private fun validKeywords(values: List<String>): Boolean = values.size <= 20 &&
        values.distinct().size == values.size && values.all { it.isNotBlank() && it.length <= 256 }
    private fun missing() = VehicleRecordingSettings(false, null, null)
    private fun unavailable(): Nothing = throw StorageUnavailable("preference_storage_unavailable")
    private fun <T> transaction(block: () -> T): T = try { database.runInTransaction(Callable { block() }) }
    catch (failure: PreferenceVersionConflict) { throw failure }
    catch (failure: InvalidPreferenceInput) { throw failure }
    catch (failure: StorageUnavailable) { throw failure }
    catch (_: Exception) { unavailable() }

    override fun close() { if (ownsDatabase) database.close() }

    private companion object {
        const val DEFAULT_VOICE = "{\"locale\":\"ko-KR\",\"voiceId\":null,\"rate\":1.0}"
        const val DEFAULT_FREQUENCY = "{\"mode\":\"balanced\",\"betweenStoriesMusicTracks\":1}"
    }
}
