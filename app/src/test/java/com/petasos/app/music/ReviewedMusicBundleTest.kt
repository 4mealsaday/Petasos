package com.petasos.app.music

import com.petasos.app.generation.StrictJson
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class ReviewedMusicBundleTest {
    @Test fun packagedOriginalMatchesItsReviewedManifestAndCreditNotice() {
        val root = listOf(File("src/main/assets/music"), File("app/src/main/assets/music")).first { it.isDirectory }
        val bundle = ReviewedMusicBundleCodec.decode(File(root,"manifest.json").readBytes())
        assertEquals(1,bundle.entries.size)
        val entry = bundle.entries.single()
        val bytes = File(root,entry.bundledPath.substringAfter("music/")).readBytes()
        assertEquals(entry.asset.expectedBytes,bytes.size.toLong())
        assertEquals(entry.asset.sha256,ReviewedMusicBundleCodec.sha256(bytes))
        assertEquals(68_545L,entry.credit.track.durationMs)
        assertEquals("CC-BY-4.0",entry.credit.licenseName)
        assertEquals(Long.MAX_VALUE,entry.asset.rightsValidUntilMs)
        val notice = File(root,"CREDITS.txt").readText()
        assertTrue(notice.contains(entry.credit.track.title))
        assertTrue(notice.contains(entry.credit.track.artist))
        assertTrue(notice.contains(entry.credit.licenseUrl))
        assertTrue(notice.contains(entry.credit.sourceUrl))
    }

    @Test fun missingOrAmbiguousRightsAndUnknownFieldsFailClosed() {
        listOf(
            mapOf("distributionAllowed" to false), mapOf("license" to "royalty-free"),
            mapOf("licenseUrl" to "https://example.com/license"), mapOf("attribution" to ""),
            mapOf("rightsValidUntilMs" to 0L), mapOf("surprise" to true),
        ).forEach { change -> denied { ReviewedMusicBundleCodec.decode(manifest(change)) } }
    }

    @Test fun traversalRemotePathsOversizeAndMalformedMetadataAreRejected() {
        listOf(
            mapOf("fileName" to "../music.m4a"), mapOf("fileName" to "/music.m4a"),
            mapOf("fileName" to "https://example.com/music.m4a"), mapOf("sizeBytes" to 100_000_001L),
            mapOf("sizeBytes" to 0L), mapOf("sha256" to "invalid"), mapOf("durationMs" to 0L),
            mapOf("sourceUrl" to "http://example.com/music.m4a"),
            mapOf("sourceUrl" to "https://example.com/music.m4a?token=secret"),
        ).forEach { change -> denied { ReviewedMusicBundleCodec.decode(manifest(change)) } }
        denied { ReviewedMusicBundleCodec.decode(ByteArray(ReviewedMusicBundleCodec.MAX_MANIFEST_BYTES + 1)) }
    }

    @Test fun duplicateTracksAndDuplicateJsonKeysAreRejected() {
        val track = fixture()
        denied { ReviewedMusicBundleCodec.decode(StrictJson.encode(mapOf("formatVersion" to 1L,"tracks" to listOf(track,track)))) }
        val json = String(manifest(),Charsets.UTF_8).replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1")
        denied { ReviewedMusicBundleCodec.decode(json.toByteArray()) }
    }

    @Test fun revokedPlaybackRemainsReviewableButCannotBeResolved() = withRoot { root ->
        val bundle = ReviewedMusicBundleCodec.decode(manifest(mapOf("playbackAllowed" to false)))
        ReviewedMusicInstaller(root) { ByteArrayInputStream(content) }.install(bundle)
        val asset = bundle.entries.single().asset
        val catalog = LocalMusicCatalog(root,{ listOf(asset) },{ 10 })
        try { catalog.resolve(asset.assetId); fail("revoked playback") }
        catch (_: com.petasos.app.runtime.RuntimeContentUnavailable) { }
    }

    @Test fun validInstallIsAtomicAndAlreadyVerifiedBytesDoNotReopenTheBundle() = withRoot { root ->
        val bundle = ReviewedMusicBundleCodec.decode(manifest())
        var opens = 0
        val installer = ReviewedMusicInstaller(root) { opens++; ByteArrayInputStream(content) }
        installer.install(bundle)
        installer.install(bundle)
        assertEquals(1,opens)
        val target = File(root,bundle.entries.single().asset.relativePath)
        assertArrayEquals(content,target.readBytes())
        assertEquals(listOf(target.name),root.listFiles()!!.map { it.name })
    }

    @Test fun corruptShortAndOversizedStreamsNeverPublishPartialFiles() = withRoot { root ->
        val bundle = ReviewedMusicBundleCodec.decode(manifest())
        listOf(byteArrayOf(9,9,9),byteArrayOf(1,2),byteArrayOf(1,2,3,4)).forEach { bytes ->
            denied { ReviewedMusicInstaller(root) { ByteArrayInputStream(bytes) }.install(bundle) }
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test fun zeroProgressAndReadExceptionsCleanUpWithoutPublishing() = withRoot { root ->
        val bundle = ReviewedMusicBundleCodec.decode(manifest())
        val streams = listOf(
            object : java.io.InputStream() { override fun read() = 0; override fun read(b: ByteArray, off: Int, len: Int) = 0 },
            object : java.io.InputStream() { override fun read(): Int = throw java.io.IOException("fixture") },
        )
        streams.forEach { stream ->
            denied { ReviewedMusicInstaller(root) { stream }.install(bundle) }
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test fun invalidExistingFileIsRepairedOnlyByTheExactReviewedBytes() = withRoot { root ->
        val bundle = ReviewedMusicBundleCodec.decode(manifest())
        val target = File(root,bundle.entries.single().asset.relativePath)
        target.writeBytes(byteArrayOf(7,7,7))
        denied { ReviewedMusicInstaller(root) { ByteArrayInputStream(byteArrayOf(8,8,8)) }.install(bundle) }
        assertArrayEquals(byteArrayOf(7,7,7),target.readBytes())
        ReviewedMusicInstaller(root) { ByteArrayInputStream(content) }.install(bundle)
        assertArrayEquals(content,target.readBytes())
    }

    @Test fun existingSymlinkCannotEscapeThePrivateMusicDirectory() = withRoot { root ->
        val outside = File(root.parentFile,root.name + "-outside")
        try {
            outside.writeBytes(content)
            val bundle = ReviewedMusicBundleCodec.decode(manifest())
            val target = File(root,bundle.entries.single().asset.relativePath)
            Files.createSymbolicLink(target.toPath(),outside.toPath())
            denied { ReviewedMusicInstaller(root) { ByteArrayInputStream(content) }.install(bundle) }
            assertArrayEquals(content,outside.readBytes())
        } finally { outside.delete() }
    }

    private val content = byteArrayOf(1,2,3)
    private fun fixture(): Map<String,Any?> = mapOf(
        "assetId" to "fixture-music", "fileName" to "fixture.m4a",
        "sha256" to ReviewedMusicBundleCodec.sha256(content), "sizeBytes" to content.size.toLong(), "durationMs" to 1000L,
        "title" to "Fixture", "artist" to "Fixture author", "album" to "Fixture album",
        "license" to "CC-BY-4.0", "licenseUrl" to "https://creativecommons.org/licenses/by/4.0/",
        "sourceUrl" to "https://example.com/audio.m4a", "evidenceUrl" to "https://example.com/release/",
        "licenseEvidenceUrl" to "https://example.com/license/", "attribution" to "Fixture author",
        "changeNotice" to "No changes", "reviewedAt" to "2026-11-08", "reviewBasis" to "Synthetic test fixture only",
        "rightsValidUntilMs" to Long.MAX_VALUE, "playbackAllowed" to true, "distributionAllowed" to true,
    )
    private fun manifest(changes: Map<String,Any?> = emptyMap()): ByteArray = StrictJson.encode(
        mapOf("formatVersion" to 1L, "tracks" to listOf(fixture() + changes)))
    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("petasos-reviewed-music-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun denied(block: () -> Unit) {
        try { block(); fail("expected fail-closed music validation") }
        catch (_: MusicBundleException) { }
    }
}
