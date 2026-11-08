package com.petasos.app.storage

import android.app.Instrumentation
import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.content.ContentException
import com.petasos.app.content.ContentParagraph
import com.petasos.app.generation.StrictJson
import com.petasos.app.generation.ValidatedGenerationStory
import com.petasos.app.generation.ValidatedStoryParagraph
import com.petasos.app.planning.CandidateReason
import com.petasos.app.playback.OperationToken
import com.petasos.app.runtime.RuntimeText
import org.json.JSONObject
import java.util.UUID

/** Disposable real Room databases only; no production content, location, audio, network or consent writes. */
object RegionalContentProbe {
    private const val NOW = 10_000L
    private const val REGION = "regional-test-region"
    private const val REVISION = "regional-test-region-r1"
    private const val GEOMETRY_SOURCE = "regional-test-geometry-r1"
    private const val POLICY = "{\"geometryStatus\":\"ready\",\"policyStatus\":\"pending\"}"
    private val contentTables = listOf("source_evidence", "region", "region_revision", "place", "place_external_id",
        "fact_revision", "fact_source", "story", "story_region_binding", "story_version", "paragraph", "paragraph_fact",
        "preference", "collection_record", "playback_attempt", "generation_request")

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        migration(instrumentation, mark)
        val context = instrumentation.targetContext
        val name = "regional-content-probe-${UUID.randomUUID()}.db"
        val database = StorageDatabase.open(context, name)
        try {
            val sql = database.openHelper.writableDatabase
            seedGeometry(sql)
            sql.execSQL("INSERT INTO preference(scope_id,keywords_json,voice_json,story_frequency_json,location_enabled,version,updated_at) VALUES ('vehicle','[]','{}','{}',0,7,100)")
            val policyBefore = snapshot(sql, listOf("region", "region_revision", "preference"))
            val installer = RoomContentInstaller(database, { NOW }, ownsDatabase = false)
            val candidates = RoomAutomaticStoryStore(database, { NOW })
            val validBytes = StrictJson.encode(fixture("regional"))
            check(installer.install(validBytes) == ContentInstallResult(1, 0))
            check(count(sql, "SELECT count(*) FROM story WHERE story_id='regional-story' AND region_id=?", REGION) == 1L)
            check(count(sql, "SELECT count(*) FROM story_region_binding WHERE story_id='regional-story' AND region_revision_id=?", REVISION) == 1L)
            check(snapshot(sql, listOf("region", "region_revision", "preference")) == policyBefore)
            check(count(sql, "SELECT count(*) FROM region_revision WHERE verified_at IS NOT NULL") == 0L)
            check(count(sql, "SELECT count(*) FROM collection_record") == 0L)
            check(candidates.candidates(null).isEmpty())
            check(candidates.candidates("different-region").isEmpty())
            val offered = candidates.candidates(REGION).single()
            check(offered.story.storyId == "regional-story" && offered.reason == CandidateReason.CURRENT_REGION)
            check(candidates.isCurrent(offered, REGION))
            mark("regional_content_exact_reviewed_binding_selects_region_without_policy_consent_coordinates_or_visit_credit")

            val installedState = snapshot(sql, contentTables)
            check(installer.install(validBytes) == ContentInstallResult(0, 1))
            check(snapshot(sql, contentTables) == installedState)
            mark("regional_content_identical_review_and_bundle_reinstall_is_noop")

            fun rejectBundle(prefix: String, mutate: (MutableMap<String, Any?>) -> Unit) {
                val before = snapshot(sql, contentTables)
                val invalid = fixture(prefix).also(mutate)
                expectContentRejected { installer.install(StrictJson.encode(invalid)) }
                check(snapshot(sql, contentTables) == before)
            }
            rejectBundle("missing") { row(it, "regionBindings")["regionRevisionId"] = "missing-revision" }
            rejectBundle("mismatch-code") { row(it, "regionBindings")["regionCode"] = "99999" }
            rejectBundle("mismatch-dataset") { row(it, "regionBindings")["datasetVersion"] = "other-dataset" }
            rejectBundle("mismatch-name") { row(it, "regionBindings")["regionName"] = "Other region" }
            rejectBundle("tradition") { rows(it, "facts").first()["kind"] = "tradition" }
            rejectBundle("future") { row(it, "regionBindings")["verifiedAt"] = NOW + 1 }
            sql.execSQL("UPDATE region_revision SET policy_json=? WHERE region_revision_id=?",
                arrayOf("{\"geometryStatus\":\"quarantined\",\"policyStatus\":\"pending\"}", REVISION))
            rejectBundle("quarantined") { }
            check(!candidates.isCurrent(offered, REGION) && candidates.candidates(REGION).isEmpty())
            sql.execSQL("UPDATE region_revision SET policy_json=? WHERE region_revision_id=?", arrayOf(POLICY, REVISION))
            check(snapshot(sql, contentTables) == installedState)
            mark("regional_content_missing_mismatched_quarantined_or_unreviewed_bindings_reject_with_atomic_rollback")

            val legacy = fixture("legacy").apply { this["formatVersion"] = 1; remove("regionBindings") }
            check(installer.install(StrictJson.encode(legacy)) == ContentInstallResult(1, 0))
            val beforeLegacyRemap = snapshot(sql, contentTables)
            expectContentRejected("CONTENT_IMMUTABLE_CONFLICT") { installer.install(StrictJson.encode(fixture("legacy"))) }
            check(snapshot(sql, contentTables) == beforeLegacyRemap)
            check(count(sql, "SELECT count(*) FROM story WHERE story_id='legacy-story' AND region_id IS NULL") == 1L)
            check(count(sql, "SELECT count(*) FROM story_region_binding WHERE story_id='legacy-story'") == 0L)
            mark("regional_content_v1_null_region_identity_cannot_be_silently_remapped_by_v2")

            sql.execSQL("INSERT INTO trip(trip_id,started_at,status) VALUES ('regional-trip',1,'active')")
            sql.execSQL("INSERT INTO session(session_id,trip_id,started_at,status) VALUES ('regional-session','regional-trip',1,'active')")
            val generation = RoomStoryGenerationStore(database, { NOW })
            fun prepare(seed: String = "regional-v1") = generation.prepare(
                OperationToken("regional-session", 0, "request-${UUID.randomUUID()}"), seed, emptyList(), routeRevision = 0) { true }
            val work = checkNotNull(prepare())
            check(work.context.regionId == REGION && work.context.routeRevision == 0L && work.context.isCurrent())
            check(work.context.approvedFactStableIds.keys == setOf("regional-association-r1", "regional-detail-r1"))
            check(work.context.sourceRevisionIds.toSet() == setOf("regional-association-source-r1", "regional-detail-source-r1"))
            check(GEOMETRY_SOURCE !in work.context.sourceRevisionIds)
            val generated = ValidatedGenerationStory("regional-story", "regional-generated-v2", listOf(
                ValidatedStoryParagraph("regional-generated-p1", RuntimeText("Synthetic generated detail only."), "fact",
                    listOf("regional-detail-source-r1"), listOf("regional-detail-r1")),
            ), 1000, "synthetic-model", "synthetic-provider", "synthetic-prompt", 1, 1, "b".repeat(64))
            check(generation.install(work, generated)?.versionId == "regional-generated-v2")
            check(count(sql, "SELECT count(*) FROM paragraph_fact WHERE paragraph_id='regional-generated-p1' AND fact_revision_id='regional-association-r1'") == 0L)
            val next = checkNotNull(prepare("regional-generated-v2"))
            check(next.context.regionId == REGION && next.context.sourceRevisionIds == listOf("regional-detail-source-r1"))
            val generatedOffer = candidates.candidates(REGION).single()
            check(generatedOffer.story.versionId == "regional-generated-v2")
            mark("regional_content_generation_context_retains_region_and_generated_version_may_omit_association_fact")

            sql.execSQL("UPDATE source_evidence SET review_status='rejected' WHERE source_revision_id='regional-association-source-r1'")
            check(!candidates.isCurrent(generatedOffer, REGION) && candidates.candidates(REGION).isEmpty())
            check(!next.context.isCurrent() && prepare("regional-generated-v2") == null)
            // The remaining paragraph is still ordinary playable content; its old region authority is what was revoked.
            check(installer.publishedStory("regional-generated-v2").paragraphIds == listOf("regional-generated-p1"))
            sql.execSQL("UPDATE source_evidence SET review_status='verified' WHERE source_revision_id='regional-association-source-r1'")
            check(candidates.candidates(REGION).single().story.versionId == "regional-generated-v2")
            sql.execSQL("UPDATE fact_revision SET kind='tradition' WHERE fact_revision_id='regional-association-r1'")
            check(candidates.candidates(REGION).isEmpty() && prepare("regional-generated-v2") == null)
            sql.execSQL("UPDATE fact_revision SET kind='verified_fact' WHERE fact_revision_id='regional-association-r1'")
            sql.execSQL("UPDATE story_region_binding SET verified_at=NULL WHERE story_id='regional-story'")
            check(candidates.candidates(REGION).isEmpty() && prepare("regional-generated-v2") == null)
            check(snapshot(sql, listOf("region", "region_revision", "preference")) == policyBefore)
            check(count(sql, "SELECT count(*) FROM collection_record") == 0L && count(sql, "SELECT count(*) FROM playback_attempt") == 0L)
            mark("regional_content_association_source_fact_and_review_revocation_survives_generated_fact_omission")
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun migration(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "regional-migration-probe-${UUID.randomUUID()}.db"
        val schema = instrumentation.context.assets.open("com.petasos.app.storage.StorageDatabase/1.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        check(schema.getInt("version") == 1)
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (index in 0 until entities.length()) {
                        val entity = entities.getJSONObject(index)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices")
                        if (indices != null) for (entry in 0 until indices.length())
                            db.execSQL(indices.getJSONObject(entry).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
                    SchemaConstraints.installVersion1(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("unexpected_fixture_upgrade")
            }).build())
        var upgraded: StorageDatabase? = null
        try {
            val old = helper.writableDatabase
            val paragraph = ContentParagraph("migration-paragraph", 0, true, "Synthetic preserved text.", null, 1000, listOf("migration-fact"))
            old.execSQL("INSERT INTO trip(trip_id,started_at,status,revision) VALUES ('migration-trip',1,'active',9)")
            old.execSQL("INSERT INTO session(session_id,trip_id,started_at,status) VALUES ('migration-session','migration-trip',1,'active')")
            seedSource(old, "migration-source")
            old.execSQL("INSERT INTO fact_revision(fact_revision_id,fact_id,version,statement,kind,verified_at) VALUES ('migration-fact','migration-stable-fact',1,'Synthetic preserved fact.','verified_fact',1)")
            old.execSQL("INSERT INTO fact_source VALUES ('migration-fact','migration-source')")
            old.execSQL("INSERT INTO story(story_id,topic_id,title,core_question) VALUES ('migration-story','migration-topic','Synthetic preserved story','Synthetic question')")
            old.execSQL("INSERT INTO story_version(story_version_id,story_id,version,content_hash,created_at,verified_at,estimated_duration_ms,status) VALUES ('migration-v1','migration-story',1,?,1,1,1000,'published')",
                arrayOf(ContentBundleCodec.versionHash("migration-story", 1, listOf(paragraph))))
            old.execSQL("INSERT INTO paragraph(paragraph_id,story_version_id,ordinal,required,text,estimated_duration_ms) VALUES ('migration-paragraph','migration-v1',0,1,?,1000)", arrayOf(paragraph.text))
            old.execSQL("INSERT INTO paragraph_fact VALUES ('migration-paragraph','migration-fact')")
            old.execSQL("INSERT INTO playback_attempt(attempt_id,session_id,kind,paragraph_id,state,started_at,completed_at,clock_scope_id) VALUES ('migration-attempt','migration-session','story','migration-paragraph','completed',2,3,'migration-clock')")
            old.execSQL("INSERT INTO collection_record(collection_id,collection_type,story_id,story_version_id,rule_version,earned_at,created_at) VALUES ('migration-card','story','migration-story','migration-v1','0.8',4,4)")
            old.execSQL("INSERT INTO collection_proof(collection_id,proof_key,attempt_id) VALUES ('migration-card','migration-paragraph','migration-attempt')")
            old.execSQL("INSERT INTO preference(scope_id,keywords_json,voice_json,story_frequency_json,location_enabled,version,updated_at) VALUES ('vehicle','[\"Synthetic interest\"]','{}','{}',0,4,1)")
            val preservedTables = listOf("trip", "session", "source_evidence", "fact_revision", "fact_source", "story", "story_version",
                "paragraph", "paragraph_fact", "playback_attempt", "collection_record", "collection_proof", "preference")
            val before = snapshot(old, preservedTables)
            check(old.version == 1)
            helper.close()
            val current = StorageDatabase.open(context, name).also { upgraded = it }
            val sql = current.openHelper.writableDatabase
            check(sql.version == 2 && snapshot(sql, preservedTables) == before)
            check(count(sql, "SELECT count(*) FROM story_region_binding") == 0L)
            check(RoomContentInstaller(current, { NOW }, false).publishedStory("migration-v1").paragraphIds == listOf("migration-paragraph"))
            var rejected = false
            try { sql.execSQL("UPDATE playback_attempt SET state='failed' WHERE attempt_id='migration-attempt'") }
            catch (_: Exception) { rejected = true }
            check(rejected && snapshot(sql, preservedTables) == before)
            sql.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) }
            mark("regional_content_real_schema1_to_2_migration_preserves_content_history_cards_consent_and_terminal_constraints")
        } finally {
            helper.close()
            upgraded?.close()
            context.deleteDatabase(name)
        }
    }

    private fun seedGeometry(sql: SupportSQLiteDatabase) {
        seedSource(sql, GEOMETRY_SOURCE)
        sql.execSQL("INSERT INTO region(region_id,name) VALUES (?,?)", arrayOf(REGION, "Synthetic region"))
        sql.execSQL("INSERT INTO region_revision(region_revision_id,region_id,code,dataset_version,name,source_revision_id,policy_json,verified_at) VALUES (?,?,'00123','synthetic-dataset','Synthetic region',?,?,NULL)",
            arrayOf(REVISION, REGION, GEOMETRY_SOURCE, POLICY))
    }

    private fun seedSource(sql: SupportSQLiteDatabase, id: String) {
        sql.execSQL("INSERT INTO source_evidence(source_revision_id,source_id,provider,source_url,title,fetched_at,verified_at,content_hash,license_json,allowed_uses_json,attribution,review_status) VALUES (?,?,'synthetic-test','https://example.invalid/source','Synthetic source',1,1,?,'{}',?,'Synthetic only','verified')",
            arrayOf(id, id, "a".repeat(64), StrictJson.stringify(uses(generation = false))))
    }

    private fun uses(generation: Boolean = true): Map<String, Any> = mapOf("allowGeneration" to generation, "allowDisplay" to true,
        "allowDistribution" to true, "reviewBasis" to "Synthetic test only")

    private fun fixture(prefix: String): MutableMap<String, Any?> {
        val kinds = listOf("association", "detail")
        val paragraphs = kinds.mapIndexed { index, kind ->
            ContentParagraph("$prefix-p$index", index, true, "Synthetic $kind paragraph.", null, 1000, listOf("$prefix-$kind-r1"))
        }
        return linkedMapOf(
            "formatVersion" to 2,
            "sources" to kinds.map { kind ->
                val text = "Synthetic $kind evidence only."
                linkedMapOf<String, Any?>("sourceRevisionId" to "$prefix-$kind-source-r1", "sourceId" to "$prefix-$kind-source",
                    "provider" to "synthetic-test", "sourceUrl" to "https://example.invalid/source", "title" to "Synthetic source",
                    "fetchedAt" to 1, "sourceUpdatedAt" to null, "sourceUpdatedRaw" to null, "verifiedAt" to 100,
                    "contentHash" to ContentBundleCodec.sha256(text.toByteArray()), "normalizedText" to text,
                    "license" to mapOf("type" to "synthetic", "url" to "https://example.invalid/license", "scope" to "text", "reviewBasis" to "Synthetic only"),
                    "allowedUses" to uses(), "attribution" to "Synthetic only", "reviewStatus" to "verified")
            },
            "facts" to kinds.map { kind -> linkedMapOf<String, Any?>("factRevisionId" to "$prefix-$kind-r1",
                "factId" to "$prefix-$kind", "version" to 1, "statement" to "Synthetic $kind evidence only.", "kind" to "verified_fact",
                "verifiedAt" to 100, "sourceRevisionIds" to listOf("$prefix-$kind-source-r1")) },
            "places" to emptyList<Any>(),
            "stories" to listOf(linkedMapOf<String, Any?>("storyId" to "$prefix-story", "topicId" to "synthetic-topic", "title" to "Synthetic story",
                "coreQuestion" to "Synthetic regional association?", "interestTags" to emptyList<String>(), "placeId" to null)),
            "versions" to listOf(linkedMapOf<String, Any?>("storyVersionId" to "$prefix-v1", "storyId" to "$prefix-story", "version" to 1,
                "contentHash" to ContentBundleCodec.versionHash("$prefix-story", 1, paragraphs), "createdAt" to 101, "verifiedAt" to 102,
                "validUntil" to null, "estimatedDurationMs" to 2000, "status" to "published", "paragraphs" to paragraphs.map { p ->
                    mapOf("paragraphId" to p.paragraphId, "ordinal" to p.ordinal, "required" to p.required, "text" to p.text,
                        "audioRef" to p.audioRef, "estimatedDurationMs" to p.estimatedDurationMs, "factRevisionIds" to p.factRevisionIds) })),
            "regionBindings" to listOf(linkedMapOf<String, Any?>("storyId" to "$prefix-story", "regionId" to REGION,
                "regionRevisionId" to REVISION, "regionCode" to "00123", "datasetVersion" to "synthetic-dataset", "regionName" to "Synthetic region",
                "regionSourceRevisionId" to GEOMETRY_SOURCE, "associationFactRevisionId" to "$prefix-association-r1",
                "verifiedAt" to 200, "reviewBasis" to "Synthetic reviewed association only")),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun rows(document: Map<String, Any?>, key: String) = document.getValue(key) as List<MutableMap<String, Any?>>
    private fun row(document: Map<String, Any?>, key: String) = rows(document, key).single()
    private fun count(sql: SupportSQLiteDatabase, statement: String, vararg args: Any?) =
        sql.query(SimpleSQLiteQuery(statement, args)).use { check(it.moveToFirst()); it.getLong(0) }

    private fun snapshot(sql: SupportSQLiteDatabase, tables: List<String>): Map<String, List<List<Any?>>> = tables.associateWith { table ->
        sql.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
                    when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).toList()
                        else -> cursor.getString(index)
                    }
                })
            }
        }
    }

    private fun expectContentRejected(code: String? = null, operation: () -> Unit) {
        try { operation() } catch (failure: ContentException) {
            check(code == null || failure.code == code) { "unexpected_regional_content_error" }
            return
        }
        error("regional_content_operation_was_not_rejected")
    }
}
