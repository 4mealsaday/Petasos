package com.petasos.app.storage

import android.app.Instrumentation
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.content.ContentException
import com.petasos.app.content.ContentParagraph
import com.petasos.app.generation.StrictJson
import java.io.File
import java.util.UUID

/** Actual Room/TX-05 assertions in disposable databases; no audio, location or network calls. */
object ContentInstallProbe {
    private const val NOW = 1_800_000_000_000L
    private val tables = listOf("source_evidence", "place", "place_external_id", "fact_revision", "fact_source",
        "story", "story_version", "paragraph", "paragraph_fact")

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "content-install-probe-${UUID.randomUUID()}.db"
        var database = StorageDatabase.open(context, name)
        var installer = RoomContentInstaller(database, { NOW }, ownsDatabase = false)
        fun sql() = database.openHelper.writableDatabase
        fun count(statement: String, vararg args: Any?): Long = sql().query(SimpleSQLiteQuery(statement, args)).use { check(it.moveToFirst()); it.getLong(0) }
        fun counts() = tables.associateWith { count("SELECT count(*) FROM $it") }
        fun text(statement: String, vararg args: Any?): String = sql().query(SimpleSQLiteQuery(statement, args)).use { check(it.moveToFirst()); it.getString(0) }
        try {
            val first = fixture("base")
            val firstBytes = StrictJson.encode(first)
            check(installer.install(firstBytes) == ContentInstallResult(1, 0))
            check(tables.all { count("SELECT count(*) FROM $it") == 1L })
            check(count("SELECT count(*) FROM story_version WHERE status='draft'") == 0L)
            val prepared = installer.publishedStory("base-v1")
            check(prepared.storyId == "base-story" && prepared.paragraphIds == listOf("base-p1"))
            check(count("SELECT count(*) FROM place WHERE latitude IS NULL AND longitude IS NULL AND region_id IS NULL AND landmark_policy_json IS NULL") == 1L)
            mark("content_tx05_installs_all_links_before_publishing_without_region_inference")

            val initial = counts()
            check(installer.install(firstBytes) == ContentInstallResult(0, 1))
            check(counts() == initial)
            mark("content_identical_bundle_reinstall_is_noop")

            val changedSource = fixture("base")
            row(changedSource, "sources")["title"] = "Different reviewed source metadata"
            expectCode("CONTENT_IMMUTABLE_CONFLICT") { installer.install(StrictJson.encode(changedSource)) }
            val changedFact = fixture("base")
            row(changedFact, "facts")["statement"] = "Different claim under an existing revision"
            expectCode("CONTENT_IMMUTABLE_CONFLICT") { installer.install(StrictJson.encode(changedFact)) }
            val changedStory = fixture("base")
            row(changedStory, "stories")["title"] = "Different story identity metadata"
            expectCode("CONTENT_IMMUTABLE_CONFLICT") { installer.install(StrictJson.encode(changedStory)) }
            check(counts() == initial)
            mark("content_existing_source_fact_story_revision_changes_rejected")

            val duplicateSourceIdentity = fixture("base")
            row(duplicateSourceIdentity, "sources")["sourceRevisionId"] = "base-renamed-source-r1"
            row(duplicateSourceIdentity, "facts")["sourceRevisionIds"] = listOf("base-renamed-source-r1")
            expectCode("CONTENT_IMMUTABLE_CONFLICT") { installer.install(StrictJson.encode(duplicateSourceIdentity)) }
            val duplicateVersionIdentity = fixture("base")
            row(duplicateVersionIdentity, "versions")["storyVersionId"] = "base-renamed-v1"
            expectCode("CONTENT_IMMUTABLE_CONFLICT") { installer.install(StrictJson.encode(duplicateVersionIdentity)) }
            check(counts() == initial)
            mark("content_revision_identity_aliases_cannot_bypass_existing_natural_keys")

            // Valid new source and changed provenance must not extend an existing fact revision.
            val changedLinks = fixture("base")
            val extraSource = row(fixture("extra-source"), "sources")
            @Suppress("UNCHECKED_CAST")
            (changedLinks["sources"] as MutableList<Any?>).add(extraSource)
            row(changedLinks, "facts")["sourceRevisionIds"] = listOf("base-source-r1", "extra-source-source-r1")
            expectCode("CONTENT_IMMUTABLE_CONFLICT") { installer.install(StrictJson.encode(changedLinks)) }
            check(counts() == initial)
            check(count("SELECT count(*) FROM source_evidence WHERE source_revision_id='extra-source-source-r1'") == 0L)
            mark("content_existing_fact_links_are_immutable_and_prior_inserts_roll_back")

            val changedExternal = fixture("external-conflict")
            @Suppress("UNCHECKED_CAST")
            val external = (row(changedExternal, "places")["externalIds"] as List<MutableMap<String, Any?>>).single()
            external["externalId"] = "base-external"
            expectCode("CONTENT_IMMUTABLE_CONFLICT") { installer.install(StrictJson.encode(changedExternal)) }
            check(counts() == initial)
            mark("content_external_id_cannot_be_remapped_by_name_or_new_bundle")

            // Retain real playback/card references to the old version when appending a revision.
            sql().execSQL("INSERT INTO trip(trip_id,started_at,status) VALUES ('content-trip',1,'active')")
            sql().execSQL("INSERT INTO session(session_id,trip_id,started_at,status) VALUES ('content-session','content-trip',1,'active')")
            sql().execSQL("INSERT INTO playback_attempt(attempt_id,session_id,kind,paragraph_id,state,started_at,completed_at,clock_scope_id) VALUES ('content-attempt','content-session','story','base-p1','completed',2,3,'content-test-clock')")
            sql().execSQL("INSERT INTO collection_record(collection_id,collection_type,story_id,story_version_id,rule_version,earned_at,created_at) VALUES ('content-card','story','base-story','base-v1','0.8',4,4)")
            sql().execSQL("INSERT INTO collection_proof(collection_id,proof_key,attempt_id) VALUES ('content-card','base-p1','content-attempt')")
            val oldText = text("SELECT text FROM paragraph WHERE paragraph_id='base-p1'")
            check(installer.install(StrictJson.encode(fixture("base", version = 2))) == ContentInstallResult(1, 0))
            check(count("SELECT count(*) FROM story_version WHERE story_id='base-story' AND status='published'") == 2L)
            check(installer.publishedStory("base-v1").paragraphIds == listOf("base-p1"))
            check(installer.publishedStory("base-v2").paragraphIds == listOf("base-p2"))
            check(text("SELECT text FROM paragraph WHERE paragraph_id='base-p1'") == oldText)
            check(count("SELECT count(*) FROM collection_proof WHERE collection_id='content-card' AND proof_key='base-p1' AND attempt_id='content-attempt'") == 1L)
            check(count("SELECT count(*) FROM playback_attempt WHERE attempt_id='content-attempt' AND paragraph_id='base-p1' AND state='completed'") == 1L)
            mark("content_new_version_appends_preserving_playback_card_and_previous_text")

            val beforeRollback = counts()
            sql().execSQL("CREATE TRIGGER content_fail_at_publish BEFORE UPDATE OF status ON story_version WHEN NEW.status='published' AND NEW.story_version_id='rollback-v1' BEGIN SELECT RAISE(ABORT, 'injected'); END")
            expectCode("CONTENT_STORAGE_FAILURE") { installer.install(StrictJson.encode(fixture("rollback"))) }
            check(counts() == beforeRollback)
            check(count("SELECT count(*) FROM source_evidence WHERE source_revision_id='rollback-source-r1'") == 0L)
            check(count("SELECT count(*) FROM story_version WHERE status='draft'") == 0L)
            sql().execSQL("DROP TRIGGER content_fail_at_publish")
            mark("content_publish_failure_rolls_back_entire_tx05")

            // Corrupt an already installed graph in this disposable DB only. Reinstall never repairs it silently.
            sql().execSQL("DELETE FROM paragraph_fact WHERE paragraph_id='base-p1'")
            expectCode("CONTENT_IMMUTABLE_CONFLICT") { installer.install(firstBytes) }
            expectCode("CONTENT_UNAVAILABLE") { installer.publishedStory("base-v1") }
            sql().execSQL("INSERT INTO paragraph_fact(paragraph_id,fact_revision_id) VALUES ('base-p1','base-fact-r1')")
            sql().execSQL("INSERT INTO paragraph(paragraph_id,story_version_id,ordinal,required,text,estimated_duration_ms) VALUES ('unexpected-paragraph','base-v1',1,0,'Test only',1)")
            expectCode("CONTENT_IMMUTABLE_CONFLICT") { installer.install(firstBytes) }
            sql().execSQL("DELETE FROM paragraph WHERE paragraph_id='unexpected-paragraph'")
            mark("content_missing_links_or_extra_published_paragraphs_are_not_silently_repaired")

            val integrity = fixture("integrity")
            val secondFact = LinkedHashMap(row(integrity, "facts")).apply {
                this["factRevisionId"] = "integrity-second-fact-r1"
                this["factId"] = "integrity-second-fact"
            }
            @Suppress("UNCHECKED_CAST")
            (integrity["facts"] as MutableList<Any?>).add(secondFact)
            val integrityParagraphs = listOf(
                ContentParagraph("integrity-first", 0, true, "First reviewed test paragraph.", null, 1000, listOf("integrity-fact-r1")),
                ContentParagraph("integrity-last", 1, true, "Last reviewed test paragraph.", null, 1000, listOf("integrity-second-fact-r1")),
            )
            row(integrity, "versions").apply {
                this["paragraphs"] = integrityParagraphs.map(::paragraphDocument)
                this["estimatedDurationMs"] = 2000
                this["contentHash"] = ContentBundleCodec.versionHash("integrity-story", 1, integrityParagraphs)
            }
            check(installer.install(StrictJson.encode(integrity)) == ContentInstallResult(1, 0))
            check(installer.publishedStory("integrity-v1").paragraphIds == listOf("integrity-first", "integrity-last"))
            sql().execSQL("UPDATE paragraph SET text='Mutated unreviewed text' WHERE paragraph_id='integrity-last'")
            expectCode("CONTENT_UNAVAILABLE") { installer.publishedStory("integrity-v1") }
            sql().execSQL("UPDATE paragraph SET text=? WHERE paragraph_id='integrity-last'", arrayOf(integrityParagraphs[1].text))
            sql().execSQL("UPDATE paragraph SET required=0 WHERE story_version_id='integrity-v1'")
            expectCode("CONTENT_UNAVAILABLE") { installer.publishedStory("integrity-v1") }
            sql().execSQL("UPDATE paragraph SET required=1 WHERE story_version_id='integrity-v1'")
            sql().execSQL("UPDATE story_version SET estimated_duration_ms=2001 WHERE story_version_id='integrity-v1'")
            expectCode("CONTENT_UNAVAILABLE") { installer.publishedStory("integrity-v1") }
            sql().execSQL("UPDATE story_version SET estimated_duration_ms=2000 WHERE story_version_id='integrity-v1'")
            check(installer.publishedStory("integrity-v1").paragraphIds.size == 2)
            sql().execSQL("DELETE FROM paragraph_fact WHERE paragraph_id='integrity-last'")
            sql().execSQL("DELETE FROM paragraph WHERE paragraph_id='integrity-last'")
            expectCode("CONTENT_UNAVAILABLE") { installer.publishedStory("integrity-v1") }
            // Matching the shortened duration still cannot make a truncated version pass its original hash.
            sql().execSQL("UPDATE story_version SET estimated_duration_ms=1000 WHERE story_version_id='integrity-v1'")
            expectCode("CONTENT_UNAVAILABLE") { installer.publishedStory("integrity-v1") }
            mark("content_preparation_rejects_tail_deletion_text_mutation_missing_required_and_duration_drift")

            sql().execSQL("UPDATE source_evidence SET review_status='rejected' WHERE source_revision_id='base-source-r1'")
            expectCode("CONTENT_UNAVAILABLE") { installer.publishedStory("base-v1") }
            expectCode("CONTENT_IMMUTABLE_CONFLICT") { installer.install(firstBytes) }
            sql().execSQL("UPDATE source_evidence SET review_status='verified' WHERE source_revision_id='base-source-r1'")
            val allowedUses = text("SELECT allowed_uses_json FROM source_evidence WHERE source_revision_id='base-source-r1'")
            sql().execSQL("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='base-source-r1'", arrayOf("{\"allowDisplay\":false,\"allowDistribution\":true}"))
            expectCode("CONTENT_UNAVAILABLE") { installer.publishedStory("base-v1") }
            sql().execSQL("UPDATE source_evidence SET allowed_uses_json=? WHERE source_revision_id='base-source-r1'", arrayOf(allowedUses))
            sql().execSQL("UPDATE story_version SET status='retired' WHERE story_version_id='base-v1'")
            expectCode("CONTENT_UNAVAILABLE") { installer.publishedStory("base-v1") }
            expectCode("CONTENT_IMMUTABLE_CONFLICT") { installer.install(firstBytes) }
            sql().execSQL("UPDATE story_version SET status='published',valid_until=? WHERE story_version_id='base-v1'", arrayOf(NOW))
            expectCode("CONTENT_UNAVAILABLE") { installer.publishedStory("base-v1") }
            sql().execSQL("UPDATE story_version SET valid_until=NULL WHERE story_version_id='base-v1'")
            mark("content_preparation_rechecks_current_rights_review_status_and_expiry")

            installer.close()
            database.close()
            database = StorageDatabase.open(context, name)
            installer = RoomContentInstaller(database, { NOW }, ownsDatabase = false)
            check(installer.install(firstBytes) == ContentInstallResult(0, 1))
            check(installer.publishedStory("base-v2").paragraphIds == listOf("base-p2"))
            check(count("SELECT count(*) FROM collection_record WHERE collection_id='content-card'") == 1L)
            mark("content_published_versions_and_history_survive_database_reopen")
        } finally {
            installer.close()
            database.close()
            context.deleteDatabase(name)
        }
        val incoming = File(context.cacheDir, "content-bundle.json")
        if (incoming.exists()) {
            try {
                val bytes = readBundle(incoming)
                val bundle = ContentBundleCodec.decode(bytes, System.currentTimeMillis())
                val realName = "content-real-bundle-probe-${UUID.randomUUID()}.db"
                try {
                    RoomContentInstaller(context, realName).use { real ->
                        check(real.install(bytes) == ContentInstallResult(bundle.versions.size, 0))
                        check(real.install(bytes) == ContentInstallResult(0, bundle.versions.size))
                        bundle.versions.forEach { version ->
                            val prepared = real.publishedStory(version.storyVersionId)
                            check(prepared.storyId == version.storyId && prepared.paragraphIds == version.paragraphs.map { it.paragraphId })
                        }
                    }
                    mark("content_real_reviewed_bundle_imported_and_reinstalled_in_disposable_database")
                } finally { context.deleteDatabase(realName) }
            } finally { incoming.delete() }
        } else mark("content_no_external_bundle_supplied_synthetic_checks_only")
    }

    /** Explicit host-invoked local installation only; production rows remain after instrumentation exits. */
    fun installLocal(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val incoming = File(context.cacheDir, "content-bundle.json")
        check(incoming.isFile) { "content_bundle_required" }
        try {
            val bytes = readBundle(incoming)
            val bundle = ContentBundleCodec.decode(bytes, System.currentTimeMillis())
            RoomContentInstaller(context).use { installer ->
                val result = installer.install(bytes)
                check(result.newVersions + result.unchangedVersions == bundle.versions.size)
                mark("content_local_install_new_${result.newVersions}_unchanged_${result.unchangedVersions}")
                bundle.versions.forEach { version ->
                    val prepared = installer.publishedStory(version.storyVersionId)
                    check(prepared.storyId == version.storyId && prepared.paragraphIds == version.paragraphs.map { it.paragraphId })
                }
                check(installer.install(bytes) == ContentInstallResult(0, bundle.versions.size))
                mark("content_local_installed_versions_available_and_reinstall_noop")
            }
        } finally { incoming.delete() }
    }

    private fun readBundle(file: File): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(StrictJson.MAX_BYTES + 1)
        var length = 0
        while (length < buffer.size) {
            val count = input.read(buffer, length, buffer.size - length)
            if (count < 0) break
            if (count == 0) continue
            length += count
        }
        if (length > StrictJson.MAX_BYTES) throw ContentException("INVALID_BUNDLE")
        buffer.copyOf(length)
    }

    private fun expectCode(code: String, action: () -> Unit) {
        try { action() } catch (failure: ContentException) { check(failure.code == code) { "unexpected_content_error" }; return }
        error("content_operation_was_not_rejected")
    }

    @Suppress("UNCHECKED_CAST")
    private fun row(document: MutableMap<String, Any?>, key: String): MutableMap<String, Any?> =
        (document.getValue(key) as List<MutableMap<String, Any?>>).single()

    private fun paragraphDocument(paragraph: ContentParagraph): Map<String, Any?> = linkedMapOf(
        "paragraphId" to paragraph.paragraphId, "ordinal" to paragraph.ordinal,
        "required" to paragraph.required, "text" to paragraph.text, "audioRef" to paragraph.audioRef,
        "estimatedDurationMs" to paragraph.estimatedDurationMs, "factRevisionIds" to paragraph.factRevisionIds,
    )

    private fun fixture(prefix: String, version: Int = 1): MutableMap<String, Any?> {
        val normalizedText = "합성 설치 검증 출처입니다. 실제 관광 사실을 주장하지 않습니다."
        val paragraph = ContentParagraph("$prefix-p$version", 0, true, "합성 설치 검증의 $version 번째 문단입니다.", null, 1000, listOf("$prefix-fact-r1"))
        val paragraphMap = paragraphDocument(paragraph)
        return linkedMapOf(
            "formatVersion" to 1,
            "sources" to mutableListOf<Any?>(linkedMapOf<String, Any?>(
                "sourceRevisionId" to "$prefix-source-r1", "sourceId" to "$prefix-source", "provider" to "synthetic-test",
                "sourceUrl" to "https://example.invalid/synthetic", "title" to "SYNTHETIC TEST SOURCE",
                "fetchedAt" to NOW - 1000, "sourceUpdatedAt" to null, "sourceUpdatedRaw" to null,
                "verifiedAt" to NOW - 500, "contentHash" to ContentBundleCodec.sha256(normalizedText.toByteArray()),
                "license" to mapOf("type" to "synthetic-test", "url" to "https://example.invalid/license", "scope" to "text", "reviewBasis" to "Test APK only"),
                "allowedUses" to mapOf("allowGeneration" to false, "allowDisplay" to true, "allowDistribution" to true, "reviewBasis" to "Test APK only"),
                "attribution" to "Synthetic test fixture", "reviewStatus" to "verified", "normalizedText" to normalizedText,
            )),
            "facts" to mutableListOf<Any?>(linkedMapOf<String, Any?>("factRevisionId" to "$prefix-fact-r1", "factId" to "$prefix-fact", "version" to 1,
                "statement" to normalizedText, "kind" to "verified_fact", "verifiedAt" to NOW - 500, "sourceRevisionIds" to listOf("$prefix-source-r1"))),
            "places" to mutableListOf<Any?>(linkedMapOf<String, Any?>("placeId" to "$prefix-place", "name" to "Synthetic place", "aliases" to emptyList<String>(),
                "latitude" to null, "longitude" to null, "coordinateSystem" to null, "coordinateSourceRevisionId" to null, "coordinateReviewBasis" to null,
                "externalIds" to listOf(linkedMapOf<String, Any?>("provider" to "synthetic-test", "externalId" to "$prefix-external", "verifiedAt" to NOW - 500)))),
            "stories" to mutableListOf<Any?>(linkedMapOf<String, Any?>("storyId" to "$prefix-story", "topicId" to "test-topic", "title" to "Synthetic story",
                "coreQuestion" to "Does atomic installation preserve revisions?", "interestTags" to listOf("test"), "placeId" to "$prefix-place")),
            "versions" to mutableListOf<Any?>(linkedMapOf<String, Any?>("storyVersionId" to "$prefix-v$version", "storyId" to "$prefix-story", "version" to version,
                "contentHash" to ContentBundleCodec.versionHash("$prefix-story", version, listOf(paragraph)), "createdAt" to NOW - 100 + version,
                "verifiedAt" to NOW - 50, "validUntil" to null, "estimatedDurationMs" to 1000, "status" to "published", "paragraphs" to listOf(paragraphMap))),
        )
    }
}
