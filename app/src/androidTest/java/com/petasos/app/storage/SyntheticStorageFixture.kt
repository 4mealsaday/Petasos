package com.petasos.app.storage

import android.content.Context
import android.os.SystemClock
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.content.ContentParagraph
import com.petasos.app.generation.StrictJson
import com.petasos.app.playback.PreparedStory
import java.security.KeyStore
import java.util.UUID

/** Test APK only: synthetic assertions, never reviewed travel content or a production installer. */
object SyntheticStorageFixture {
    fun create(context: Context, databaseName: String, wallTime: () -> Long = System::currentTimeMillis): RoomRuntimePersistence = RoomRuntimePersistence(
        StorageDatabase.open(context, databaseName),
        PayloadCipher(AndroidPayloadKeys(namespace(databaseName))),
        wallTime, SystemClock::elapsedRealtime, UUID.randomUUID().toString(),
    )

    fun install(
        store: RoomRuntimePersistence,
        firstParagraphText: String = "합성 저장소 시험 문단 1입니다.",
        firstDurationMs: Long = 1000,
    ): PreparedStory = RoomContentInstaller(store.database, System::currentTimeMillis, ownsDatabase = false).use { installer ->
        installer.install(bundle(firstParagraphText, firstDurationMs))
        installer.publishedStory("test-story-v1")
    }

    /** The same TX-05 decoder/hash/installer is used by probes and reviewed local content. */
    fun bundle(firstParagraphText: String = "합성 저장소 시험 문단 1입니다.", firstDurationMs: Long = 1000): ByteArray {
        val paragraphs = listOf(
            ContentParagraph("test-p1", 0, true, firstParagraphText, null, firstDurationMs, listOf("test-fact-r1")),
            ContentParagraph("test-p2", 1, true, "합성 저장소 시험 문단 2입니다.", null, 1000, listOf("test-fact-r2")),
        )
        val sources = (1..2).map { index ->
            val text = "합성 저장소 출처 $index 입니다. 실제 관광 사실을 주장하지 않습니다."
            mapOf("sourceRevisionId" to "test-source-r$index", "sourceId" to "test-source-$index",
                "provider" to "synthetic-test", "sourceUrl" to "https://example.invalid/synthetic/$index",
                "title" to "SYNTHETIC TEST ONLY", "fetchedAt" to 1, "sourceUpdatedAt" to null,
                "sourceUpdatedRaw" to null, "verifiedAt" to 1, "contentHash" to ContentBundleCodec.sha256(text.toByteArray(Charsets.UTF_8)),
                "license" to mapOf("type" to "synthetic-test-only", "url" to "https://example.invalid/license",
                    "scope" to "text", "reviewBasis" to "Test APK fixture; no actual tourism or media rights claimed."),
                "allowedUses" to allowedUses(), "attribution" to "Test APK only", "reviewStatus" to "verified", "normalizedText" to text)
        }
        return StrictJson.encode(mapOf("formatVersion" to 1, "sources" to sources,
            "facts" to (1..2).map { index -> mapOf("factRevisionId" to "test-fact-r$index",
                "factId" to if (index == 1) "test-fact" else "test-fact-two", "version" to 1,
                "statement" to "Synthetic test fixture $index only", "kind" to "verified_fact", "verifiedAt" to 1,
                "sourceRevisionIds" to listOf("test-source-r$index")) },
            "places" to emptyList<Any?>(),
            "stories" to listOf(mapOf("storyId" to "test-story", "topicId" to "test-topic", "title" to "SYNTHETIC TEST ONLY",
                "coreQuestion" to "Storage verification only", "interestTags" to emptyList<String>(), "placeId" to null)),
            "versions" to listOf(mapOf("storyVersionId" to "test-story-v1", "storyId" to "test-story", "version" to 1,
                "contentHash" to ContentBundleCodec.versionHash("test-story", 1, paragraphs), "createdAt" to 1, "verifiedAt" to 1,
                "validUntil" to null, "estimatedDurationMs" to firstDurationMs + 1000, "status" to "published",
                "paragraphs" to paragraphs.map { p -> mapOf("paragraphId" to p.paragraphId, "ordinal" to p.ordinal,
                    "required" to p.required, "text" to p.text, "audioRef" to p.audioRef, "estimatedDurationMs" to p.estimatedDurationMs,
                    "factRevisionIds" to p.factRevisionIds) }))))
    }

    fun allowedUses(generation: Boolean = true, display: Boolean = true, distribution: Boolean = true): Map<String, Any> =
        mapOf("allowGeneration" to generation, "allowDisplay" to display, "allowDistribution" to distribution,
            "reviewBasis" to "Synthetic test fixture permission only.")

    fun deleteEncryptionKey(databaseName: String) {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("${namespace(databaseName)}.v1.aes")
    }

    fun cleanup(context: Context, databaseName: String) {
        context.deleteDatabase(databaseName)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.deleteEntry("${namespace(databaseName)}.v1.aes")
        keys.deleteEntry("${namespace(databaseName)}.v1.hmac")
    }

    private fun namespace(databaseName: String) = "com.petasos.app.tests.$databaseName"
}
