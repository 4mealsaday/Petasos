package com.petasos.app.storage

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.generation.StrictJson
import com.petasos.app.location.RegionDatasetEntry
import com.petasos.app.location.RegionDatasetException
import com.petasos.app.location.RegionDatasetManifest
import com.petasos.app.location.RegionGeometryMetadata
import java.util.concurrent.Callable

internal data class RegionInstallResult(val newRevisions: Int, val unchangedRevisions: Int)

/** Worker-only, atomic geometry provenance installation. Does not grant a RegionPolicy or consent. */
internal class RoomRegionInstaller(private val database: StorageDatabase) {
    private val sql get() = database.openHelper.writableDatabase

    fun install(manifest: RegionDatasetManifest): RegionInstallResult = transaction {
        val source = manifest.source
        requireIdentity("SELECT source_revision_id FROM source_evidence WHERE source_id=? AND content_hash=?",
            source.sourceRevisionId, source.sourceId, source.contentHash)
        put("source_evidence", "source_revision_id", sourceValues(manifest))
        var added = 0
        var unchanged = 0
        for (region in manifest.regions) {
            put("region", "region_id", regionValues(region))
            requireIdentity("SELECT region_revision_id FROM region_revision WHERE code=? AND dataset_version=?",
                region.regionRevisionId, region.code, manifest.datasetVersion)
            if (putRevision(manifest, region)) added++ else unchanged++
        }
        RegionInstallResult(added, unchanged)
    }

    /** Geometry-use authority only. Visit-policy verification is independent and never changed here. */
    fun isAuthorized(manifest: RegionDatasetManifest, regionRevisionId: String): Boolean {
        return try {
            val entry = manifest.regions.singleOrNull { it.regionRevisionId == regionRevisionId } ?: return false
            if (entry.status != "ready") return false
            transaction {
                compare("source_evidence", "source_revision_id", sourceValues(manifest)) &&
                    compare("region", "region_id", regionValues(entry)) &&
                    compareRevisionGeometry(manifest, entry)
            }
        } catch (_: Exception) { false }
    }

    private fun sourceValues(manifest: RegionDatasetManifest): Map<String, Any?> = manifest.source.let { source ->
        linkedMapOf(
            "source_revision_id" to source.sourceRevisionId, "source_id" to source.sourceId,
            "provider" to source.provider, "source_url" to source.sourceUrl, "title" to source.title,
            "fetched_at" to source.fetchedAt, "source_updated_at" to null, "source_updated_raw" to source.sourceUpdatedRaw,
            "verified_at" to source.verifiedAt, "content_hash" to source.contentHash,
            "license_json" to StrictJson.stringify(mapOf("type" to "CC-BY-2.0-KR", "url" to source.licenseUrl,
                "scope" to "geometry", "reviewBasis" to source.reviewBasis)),
            "allowed_uses_json" to StrictJson.stringify(mapOf("allowGeneration" to false, "allowDisplay" to true,
                "allowDistribution" to true, "reviewBasis" to source.reviewBasis)),
            "attribution" to source.attribution, "review_status" to "verified",
        )
    }

    private fun regionValues(entry: RegionDatasetEntry): Map<String, Any?> =
        linkedMapOf("region_id" to entry.regionId, "name" to entry.name)

    private fun putRevision(manifest: RegionDatasetManifest, entry: RegionDatasetEntry): Boolean {
        if (compareRevisionGeometry(manifest, entry)) return false
        return put("region_revision", "region_revision_id", RegionGeometryMetadata.revisionIdentity(manifest, entry) + mapOf(
            "policy_json" to RegionGeometryMetadata.pendingPolicyJson(manifest, entry), "verified_at" to null))
    }

    private fun compareRevisionGeometry(manifest: RegionDatasetManifest, entry: RegionDatasetEntry): Boolean {
        if (!compare("region_revision", "region_revision_id", RegionGeometryMetadata.revisionIdentity(manifest, entry))) return false
        return query("SELECT policy_json FROM region_revision WHERE region_revision_id=?", entry.regionRevisionId).use {
            if (!it.moveToFirst()) return@use false
            val policy = try { StrictJson.parse(it.getString(0).toByteArray()) as? Map<*, *> }
                catch (_: Exception) { null }
            if (policy == null || !RegionGeometryMetadata.matchesPolicyGeometry(policy, manifest, entry))
                fail("REGION_IMMUTABLE_CONFLICT")
            // Neither reviewed JSON nor a NULL verification revocation is overwritten by asset reload.
            true
        }
    }

    private fun put(table: String, key: String, values: Map<String, Any?>): Boolean {
        if (compare(table, key, values)) return false
        sql.execSQL("INSERT INTO $table(${values.keys.joinToString(",")}) VALUES (${values.keys.joinToString(",") { "?" }})",
            values.values.toTypedArray())
        return true
    }

    /** All SQL identifiers come from fixed literals above; source values are bound parameters. */
    private fun compare(table: String, key: String, values: Map<String, Any?>): Boolean =
        query("SELECT ${values.keys.joinToString(",")} FROM $table WHERE $key=?", values[key]).use { row ->
            if (!row.moveToFirst()) return@use false
            values.values.forEachIndexed { index, expected ->
                val matches = when (expected) {
                    null -> row.isNull(index)
                    is String -> !row.isNull(index) && row.getString(index) == expected
                    is Long -> !row.isNull(index) && row.getLong(index) == expected
                    else -> false
                }
                if (!matches) fail("REGION_IMMUTABLE_CONFLICT")
            }
            if (row.moveToNext()) fail("REGION_IMMUTABLE_CONFLICT")
            true
        }

    private fun requireIdentity(statement: String, expected: String, vararg args: Any?) {
        query(statement, *args).use {
            if (it.moveToFirst() && it.getString(0) != expected) fail("REGION_IMMUTABLE_CONFLICT")
        }
    }
    private fun query(statement: String, vararg args: Any?): Cursor = sql.query(SimpleSQLiteQuery(statement, args))
    private fun <T> transaction(block: () -> T): T = try { database.runInTransaction(Callable { block() }) }
        catch (failure: RegionDatasetException) { throw failure }
        catch (_: Exception) { fail("REGION_STORAGE_UNAVAILABLE") }
    private fun fail(code: String): Nothing = throw RegionDatasetException(code)
}
