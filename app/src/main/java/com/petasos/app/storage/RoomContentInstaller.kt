package com.petasos.app.storage

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.petasos.app.content.ContentBundle
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.content.ContentException
import com.petasos.app.content.ContentParagraph
import com.petasos.app.content.ContentVersion
import com.petasos.app.generation.StrictJson
import com.petasos.app.playback.PreparedStory
import java.util.concurrent.Callable

data class ContentInstallResult(val newVersions: Int, val unchangedVersions: Int)

/** TX-05: reviewed bundles are validated, compared, and published atomically on a worker thread. */
class RoomContentInstaller internal constructor(
    private val database: StorageDatabase,
    private val wallTime: () -> Long,
    private val ownsDatabase: Boolean,
) : AutoCloseable {
    constructor(context: Context, databaseName: String = "petasos.db") : this(
        StorageDatabase.open(context, databaseName), System::currentTimeMillis, true,
    )

    private val sql: SupportSQLiteDatabase get() = database.openHelper.writableDatabase

    fun install(bytes: ByteArray): ContentInstallResult {
        val bundle = ContentBundleCodec.decode(bytes, wallTime())
        return transaction { installValidated(bundle) }
    }

    /** Read-only playback preparation; never starts audio or restores an old playback attempt. */
    fun publishedStory(storyVersionId: String): PreparedStory = transaction {
        val version = query("SELECT story_id,version,content_hash,created_at,verified_at,valid_until,estimated_duration_ms,status FROM story_version WHERE story_version_id=?", storyVersionId).use {
            if (!it.moveToFirst() || it.getString(7) != "published" || it.getLong(1) !in 1..Int.MAX_VALUE.toLong() ||
                it.getLong(3) < 0 || it.isNull(4) || it.getLong(4) < 0 ||
                (!it.isNull(5) && it.getLong(5) <= wallTime()) || it.getLong(6) !in 1..3_600_000)
                fail("CONTENT_UNAVAILABLE")
            ContentVersion(storyVersionId, it.getString(0), it.getInt(1), it.getString(2), it.getLong(3), it.getLong(4),
                if (it.isNull(5)) null else it.getLong(5), it.getLong(6), it.getString(7), emptyList())
        }
        val paragraphs = mutableListOf<ContentParagraph>()
        val stableFacts = mutableSetOf<String>()
        query("SELECT paragraph_id,ordinal,required,text,audio_ref,estimated_duration_ms FROM paragraph WHERE story_version_id=? ORDER BY ordinal", storyVersionId).use {
            while (it.moveToNext()) {
                val text = it.getString(3)
                if (paragraphs.size >= 64 || it.getLong(1) != paragraphs.size.toLong() || it.getLong(2) !in 0..1 ||
                    text.isBlank() || text.length > 4000 || !it.isNull(4) || it.getLong(5) !in 1..3_600_000)
                    fail("CONTENT_UNAVAILABLE")
                val facts = mutableListOf<String>()
                query("SELECT pf.fact_revision_id,f.fact_id FROM paragraph_fact pf JOIN fact_revision f ON f.fact_revision_id=pf.fact_revision_id WHERE pf.paragraph_id=? ORDER BY pf.fact_revision_id", it.getString(0)).use { row ->
                    while (row.moveToNext()) {
                        if (!stableFacts.add(row.getString(1))) fail("CONTENT_UNAVAILABLE")
                        facts += row.getString(0)
                    }
                }
                if (facts.isEmpty()) fail("CONTENT_UNAVAILABLE")
                paragraphs += ContentParagraph(it.getString(0), it.getInt(1), it.getInt(2) == 1, text, null, it.getLong(5), facts)
            }
        }
        if (paragraphs.isEmpty() || paragraphs.none { it.required } || paragraphs.sumOf { it.estimatedDurationMs } != version.estimatedDurationMs)
            fail("CONTENT_UNAVAILABLE")
        val hash = try { ContentBundleCodec.versionHash(version.storyId, version.version, paragraphs) }
        catch (_: Exception) { fail("CONTENT_UNAVAILABLE") }
        if (hash != version.contentHash) fail("CONTENT_UNAVAILABLE")
        query("SELECT count(*) FROM paragraph p WHERE p.story_version_id=? AND (NOT EXISTS (SELECT 1 FROM paragraph_fact pf WHERE pf.paragraph_id=p.paragraph_id) OR EXISTS (SELECT 1 FROM paragraph_fact pf WHERE pf.paragraph_id=p.paragraph_id AND NOT EXISTS (SELECT 1 FROM fact_source fs WHERE fs.fact_revision_id=pf.fact_revision_id)))", storyVersionId).use {
            if (!it.moveToFirst() || it.getLong(0) != 0L) fail("CONTENT_UNAVAILABLE")
        }
        query("SELECT DISTINCT s.review_status,s.allowed_uses_json FROM paragraph p JOIN paragraph_fact pf ON pf.paragraph_id=p.paragraph_id JOIN fact_source fs ON fs.fact_revision_id=pf.fact_revision_id JOIN source_evidence s ON s.source_revision_id=fs.source_revision_id WHERE p.story_version_id=?", storyVersionId).use {
            while (it.moveToNext()) {
                if (it.getString(0) != "verified") fail("CONTENT_UNAVAILABLE")
                val uses = try { StrictJson.parse(it.getString(1).toByteArray()) as? Map<*, *> }
                catch (_: Exception) { null }
                if (uses?.get("allowDisplay") != true || uses?.get("allowDistribution") != true) fail("CONTENT_UNAVAILABLE")
            }
        }
        PreparedStory(version.storyId, storyVersionId, paragraphs.map { it.paragraphId })
    }

    /** Internal TX-05 append: only revisions already authorized by an installed seed may be used.
     * No source, fact, stable story, place or region is inserted or edited by generated content.
     */
    internal fun installGeneratedVersion(seedVersionId: String, version: ContentVersion): ContentInstallResult = transaction {
        val seed = publishedStory(seedVersionId)
        val id = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
        if (seed.storyId != version.storyId || !id.matches(version.storyVersionId) || version.status != "published" ||
            version.createdAt < 0 || version.verifiedAt < 0 || version.validUntil?.let { it <= wallTime() } == true ||
            version.estimatedDurationMs !in 1..3_600_000 || version.paragraphs.size !in 1..12 ||
            version.paragraphs.none { it.required }) fail("CONTENT_UNAVAILABLE")
        val next = query("SELECT max(version) FROM story_version WHERE story_id=?", seed.storyId).use {
            if (!it.moveToFirst() || it.isNull(0)) fail("CONTENT_UNAVAILABLE")
            it.getLong(0) + 1
        }
        if (next !in 1..Int.MAX_VALUE.toLong() || version.version.toLong() != next) fail("CONTENT_IMMUTABLE_CONFLICT")
        val approved = mutableSetOf<String>()
        query("SELECT pf.fact_revision_id FROM paragraph p JOIN paragraph_fact pf ON pf.paragraph_id=p.paragraph_id WHERE p.story_version_id=?", seedVersionId).use {
            while (it.moveToNext()) approved += it.getString(0)
        }
        val stableFacts = mutableSetOf<String>()
        val paragraphIds = mutableSetOf<String>()
        version.paragraphs.forEachIndexed { ordinal, p ->
            if (p.ordinal != ordinal || !id.matches(p.paragraphId) || !paragraphIds.add(p.paragraphId) ||
                p.text.isBlank() || p.text.length > 240 || p.audioRef != null || p.estimatedDurationMs !in 1..3_600_000 ||
                p.factRevisionIds.isEmpty() || p.factRevisionIds.distinct().size != p.factRevisionIds.size ||
                p.factRevisionIds.any { it !in approved }) fail("CONTENT_UNAVAILABLE")
            p.factRevisionIds.forEach { fact ->
                query("SELECT fact_id,kind,verified_at FROM fact_revision WHERE fact_revision_id=?", fact).use {
                    if (!it.moveToFirst() || !stableFacts.add(it.getString(0)) || it.getString(1) !in setOf("verified_fact", "tradition") ||
                        it.isNull(2) || it.getLong(2) < 0) fail("CONTENT_UNAVAILABLE")
                }
                query("SELECT s.review_status,s.verified_at,s.allowed_uses_json FROM fact_source fs JOIN source_evidence s ON s.source_revision_id=fs.source_revision_id WHERE fs.fact_revision_id=?", fact).use {
                    if (!it.moveToFirst()) fail("CONTENT_UNAVAILABLE")
                    do {
                        val uses = try { StrictJson.parse(it.getString(2).toByteArray()) as? Map<*, *> } catch (_: Exception) { null }
                        if (it.getString(0) != "verified" || it.isNull(1) || it.getLong(1) < 0 ||
                            uses?.get("allowGeneration") != true || uses?.get("allowDisplay") != true || uses?.get("allowDistribution") != true)
                            fail("CONTENT_UNAVAILABLE")
                    } while (it.moveToNext())
                }
            }
        }
        if (version.paragraphs.sumOf { it.estimatedDurationMs } != version.estimatedDurationMs ||
            ContentBundleCodec.versionHash(version.storyId, version.version, version.paragraphs) != version.contentHash)
            fail("CONTENT_UNAVAILABLE")
        installVersions(listOf(version))
    }

    private fun installValidated(bundle: ContentBundle): ContentInstallResult {
        val bindings = bundle.regionBindings.associateBy { it.storyId }
        bundle.sources.forEach { source ->
            requireIdentity("SELECT source_revision_id FROM source_evidence WHERE source_id=? AND content_hash=?", source.sourceRevisionId,
                source.sourceId, source.contentHash)
            put("source_evidence", listOf("source_revision_id"), linkedMapOf(
                "source_revision_id" to source.sourceRevisionId, "source_id" to source.sourceId,
                "provider" to source.provider, "source_url" to source.sourceUrl, "title" to source.title,
                "fetched_at" to source.fetchedAt, "source_updated_at" to source.sourceUpdatedAt,
                "source_updated_raw" to source.sourceUpdatedRaw, "verified_at" to source.verifiedAt,
                "content_hash" to source.contentHash, "license_json" to ContentBundleCodec.licenseJson(source),
                "allowed_uses_json" to ContentBundleCodec.usesJson(source), "attribution" to source.attribution,
                "review_status" to source.reviewStatus,
            ))
        }
        bundle.places.forEach { place ->
            put("place", listOf("place_id"), linkedMapOf(
                "place_id" to place.placeId, "region_id" to null, "name" to place.name,
                "aliases_json" to StrictJson.stringify(place.aliases), "latitude" to place.latitude?.toDouble(),
                "longitude" to place.longitude?.toDouble(), "coordinate_source_revision_id" to place.coordinateSourceRevisionId,
                "landmark_policy_json" to null,
            ))
            place.externalIds.forEach { external ->
                put("place_external_id", listOf("provider", "external_id"), linkedMapOf(
                    "provider" to external.provider, "external_id" to external.externalId,
                    "place_id" to place.placeId, "verified_at" to external.verifiedAt,
                ))
            }
        }
        bundle.facts.forEach { fact ->
            requireIdentity("SELECT fact_revision_id FROM fact_revision WHERE fact_id=? AND version=?", fact.factRevisionId,
                fact.factId, fact.version)
            val inserted = put("fact_revision", listOf("fact_revision_id"), linkedMapOf(
                "fact_revision_id" to fact.factRevisionId, "fact_id" to fact.factId, "version" to fact.version,
                "statement" to fact.statement, "kind" to fact.kind, "verified_at" to fact.verifiedAt,
            ))
            if (!inserted) requireSet("SELECT source_revision_id FROM fact_source WHERE fact_revision_id=?", fact.factRevisionId, fact.sourceRevisionIds)
            fact.sourceRevisionIds.forEach { source ->
                put("fact_source", listOf("fact_revision_id", "source_revision_id"), linkedMapOf(
                    "fact_revision_id" to fact.factRevisionId, "source_revision_id" to source,
                ))
            }
        }
        bundle.stories.forEach { story ->
            put("story", listOf("story_id"), linkedMapOf(
                "story_id" to story.storyId, "topic_id" to story.topicId, "title" to story.title,
                "core_question" to story.coreQuestion, "interest_tags_json" to StrictJson.stringify(story.interestTags),
                "place_id" to story.placeId, "region_id" to bindings[story.storyId]?.regionId,
            ))
        }
        val regions = RoomRegionalContentBindings(database, wallTime)
        bundle.regionBindings.forEach(regions::install)
        return installVersions(bundle.versions)
    }

    private fun installVersions(versions: List<ContentVersion>): ContentInstallResult {
        var added = 0
        var unchanged = 0
        versions.forEach { version ->
            requireIdentity("SELECT story_version_id FROM story_version WHERE story_id=? AND version=?", version.storyVersionId,
                version.storyId, version.version)
            val values = linkedMapOf<String, Any?>(
                "story_version_id" to version.storyVersionId, "story_id" to version.storyId, "version" to version.version,
                "content_hash" to version.contentHash, "created_at" to version.createdAt, "verified_at" to version.verifiedAt,
                "valid_until" to version.validUntil, "estimated_duration_ms" to version.estimatedDurationMs,
                "status" to "published",
            )
            val exists = compare("story_version", listOf("story_version_id"), values)
            if (exists) requireSet("SELECT paragraph_id FROM paragraph WHERE story_version_id=?", version.storyVersionId, version.paragraphs.map { it.paragraphId })
            else insert("story_version", values + ("status" to "draft"))
            version.paragraphs.forEach { paragraph ->
                val inserted = put("paragraph", listOf("paragraph_id"), linkedMapOf(
                    "paragraph_id" to paragraph.paragraphId, "story_version_id" to version.storyVersionId,
                    "ordinal" to paragraph.ordinal, "required" to if (paragraph.required) 1 else 0,
                    "text" to paragraph.text, "audio_ref" to paragraph.audioRef,
                    "estimated_duration_ms" to paragraph.estimatedDurationMs,
                ))
                if (!inserted) requireSet("SELECT fact_revision_id FROM paragraph_fact WHERE paragraph_id=?", paragraph.paragraphId, paragraph.factRevisionIds)
                paragraph.factRevisionIds.forEach { fact ->
                    put("paragraph_fact", listOf("paragraph_id", "fact_revision_id"), linkedMapOf(
                        "paragraph_id" to paragraph.paragraphId, "fact_revision_id" to fact,
                    ))
                }
            }
            if (exists) unchanged++ else {
                sql.execSQL("UPDATE story_version SET status='published' WHERE story_version_id=? AND status='draft'", arrayOf(version.storyVersionId))
                added++
            }
        }
        return ContentInstallResult(added, unchanged)
    }

    private fun put(table: String, keys: List<String>, values: Map<String, Any?>): Boolean {
        if (compare(table, keys, values)) return false
        insert(table, values)
        return true
    }

    /** All table/column identifiers are compile-time literals; values always use bound parameters. */
    private fun compare(table: String, keys: List<String>, values: Map<String, Any?>): Boolean {
        val columns = values.keys.toList()
        query("SELECT ${columns.joinToString(",")} FROM $table WHERE ${keys.joinToString(" AND ") { "$it=?" }}",
            *keys.map { values[it] }.toTypedArray()).use { row ->
            if (!row.moveToFirst()) return false
            columns.forEachIndexed { index, column ->
                val expected = values[column]
                val same = when (expected) {
                    null -> row.isNull(index)
                    is String -> !row.isNull(index) && row.getString(index) == expected
                    is Int -> !row.isNull(index) && row.getLong(index) == expected.toLong()
                    is Long -> !row.isNull(index) && row.getLong(index) == expected
                    is Double -> !row.isNull(index) && row.getDouble(index) == expected
                    else -> false
                }
                if (!same) fail("CONTENT_IMMUTABLE_CONFLICT")
            }
            if (row.moveToNext()) fail("CONTENT_IMMUTABLE_CONFLICT")
            return true
        }
    }

    private fun insert(table: String, values: Map<String, Any?>) {
        sql.execSQL("INSERT INTO $table(${values.keys.joinToString(",")}) VALUES (${values.keys.joinToString(",") { "?" }})", values.values.toTypedArray())
    }

    private fun requireSet(statement: String, owner: String, expected: List<String>) {
        val actual = mutableSetOf<String>()
        query(statement, owner).use { while (it.moveToNext()) actual += it.getString(0) }
        if (actual != expected.toSet()) fail("CONTENT_IMMUTABLE_CONFLICT")
    }

    private fun requireIdentity(statement: String, expectedId: String, vararg arguments: Any?) {
        query(statement, *arguments).use {
            if (it.moveToFirst() && it.getString(0) != expectedId) fail("CONTENT_IMMUTABLE_CONFLICT")
        }
    }

    private fun query(statement: String, vararg arguments: Any?): Cursor = sql.query(SimpleSQLiteQuery(statement, arguments))

    private fun <T> transaction(action: () -> T): T = try {
        database.runInTransaction(Callable { action() })
    } catch (failure: ContentException) {
        throw failure
    } catch (_: Exception) {
        // SQLite exceptions can embed content and URLs. Expose a fixed code only.
        fail("CONTENT_STORAGE_FAILURE")
    }

    private fun fail(code: String): Nothing = throw ContentException(code)
    override fun close() { if (ownsDatabase) database.close() }
}
