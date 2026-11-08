package com.petasos.app.storage

import android.app.Instrumentation
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.content.ContentException
import com.petasos.app.platform.android.content.BundledStoryCatalog
import java.util.UUID

/** Packaged public facts only, in a disposable DB. No product records, audio or network. */
object BundledStoryCatalogProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "bundled-story-probe-${UUID.randomUUID()}.db"
        val database = StorageDatabase.open(context, name)
        try {
            run {
                val sql = database.openHelper.writableDatabase
                fun count(statement: String, vararg args: Any?): Long = sql.query(SimpleSQLiteQuery(statement, args)).use {
                    check(it.moveToFirst()); it.getLong(0)
                }
                val bytes = context.assets.open(BundledStoryCatalog.ASSET).use { it.readBytes() }
                check(ContentBundleCodec.sha256(bytes) == BundledStoryCatalog.SHA256)
                val bundle = ContentBundleCodec.decode(bytes, System.currentTimeMillis())
                check(bundle.stories.size == 10 && bundle.versions.size == 10 && bundle.regionBindings.size == 10)
                check(bundle.places.isEmpty())
                // Existing identity stays intact even when it has no currently published version.
                sql.execSQL("INSERT INTO story(story_id,topic_id,title,core_question) VALUES ('existing-story','existing-topic','Existing title','Existing question')")
                val catalog = BundledStoryCatalog(context)
                check(catalog.loadVerified(database) == ContentInstallResult(10, 0))
                check(count("SELECT count(*) FROM story") == 11L)
                check(count("SELECT count(*) FROM story WHERE story_id='existing-story' AND title='Existing title' AND region_id IS NULL") == 1L)
                check(count("SELECT count(*) FROM story_region_binding") == 10L)
                check(count("SELECT count(*) FROM region_revision") == 264L)
                check(count("SELECT count(*) FROM region_revision WHERE verified_at IS NOT NULL") == 0L)
                check(count("SELECT count(*) FROM place") == 0L)
                listOf("trip", "session", "preference", "recording_segment", "playback_attempt", "collection_record").forEach {
                    check(count("SELECT count(*) FROM $it") == 0L)
                }
                RoomContentInstaller(database, System::currentTimeMillis, false).use { installer ->
                    bundle.versions.forEach { version ->
                        check(installer.publishedStory(version.storyVersionId).storyId == version.storyId)
                    }
                }
                mark("bundled_ten_reviewed_stories_and_exact_bindings_install_without_location_policy_or_trip")
                check(catalog.loadVerified(database) == ContentInstallResult(0, 10))
                check(count("SELECT count(*) FROM story") == 11L)
                mark("bundled_identical_reinstall_is_noop_and_preserves_prior_story")

                val source = bundle.sources.first().sourceRevisionId
                sql.execSQL("UPDATE source_evidence SET review_status='rejected' WHERE source_revision_id=?", arrayOf(source))
                try { catalog.loadVerified(database); error("revoked_content_reapproved") }
                catch (error: ContentException) { check(error.code == "CONTENT_IMMUTABLE_CONFLICT") }
                check(catalog.load(database) == null)
                check(count("SELECT count(*) FROM source_evidence WHERE source_revision_id=? AND review_status='rejected'", source) == 1L)
                check(count("SELECT count(*) FROM story") == 11L)
                check(count("SELECT count(*) FROM story_version WHERE status='published'") == 10L)
                mark("bundled_revoked_source_is_never_reapproved_or_deleted_on_startup")
            }
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
