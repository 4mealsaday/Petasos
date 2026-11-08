package com.petasos.app.music

import com.petasos.app.runtime.RuntimeContentUnavailable
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class MusicCatalogTest {
    @Test fun rightsAreRecheckedAfterPreparation() = withCatalog { catalog, change ->
        assertEquals("music-1", catalog.prepare("music-1").assetId)
        change { it.copy(playbackAllowed = false) }
        denied { catalog.resolve("music-1") }
    }
    @Test fun changedBytesAreNeverPlayedUnderOldHash() = withCatalog { catalog, _ ->
        val source = catalog.resolve("music-1")
        java.io.File(java.net.URI(source.uri)).writeBytes(byteArrayOf(9))
        denied { catalog.resolve("music-1") }
    }
    @Test fun expiryIsCheckedAtResolution() = withCatalog { catalog, change ->
        change { it.copy(rightsValidUntilMs = 100) }
        denied { catalog.resolve("music-1") }
    }
    @Test fun traversalAndAbsolutePathsAreRejected() = withCatalog { catalog, change ->
        change { it.copy(relativePath = "../outside.wav") }
        denied { catalog.resolve("music-1") }
        change { it.copy(relativePath = "/private/tmp/outside.wav") }
        denied { catalog.resolve("music-1") }
    }
    @Test fun emptyCatalogNeverInventsMusic() { denied { EmptyMusicCatalog.resolve("music-1") } }
    @Test fun preparedSourceIsRevokedByReplacementReviewEvenWhenFieldsMatch() = withCatalog { catalog, change ->
        val source = catalog.resolve("music-1")
        assertTrue(source.isCurrent())
        change { it.copy() }
        assertFalse(source.isCurrent())
        assertTrue(catalog.resolve("music-1").isCurrent())
    }
    @Test fun preparedSourceIsRevokedBeforePlayerStartWhenRightsAreWithdrawn() = withCatalog { catalog, change ->
        val source = catalog.resolve("music-1")
        change { it.copy(playbackAllowed = false) }
        assertFalse(source.isCurrent())
        denied { catalog.resolve("music-1") }
    }
    @Test fun exactSizeMismatchIsDeniedEvenWithMatchingContentHash() = withCatalog { catalog, change ->
        change { it.copy(expectedBytes = 2) }
        denied { catalog.resolve("music-1") }
    }
    @Test fun preparedSourceExpiresWithoutAnyFurtherFileResolution() {
        val root = Files.createTempDirectory("petasos-music-expiry-test").toFile()
        try {
            val bytes = byteArrayOf(1,2,3)
            java.io.File(root,"asset.wav").writeBytes(bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val asset = ReviewedMusicAsset("music-1","asset.wav",hash,101,true)
            var now = 100L
            val catalog = LocalMusicCatalog(root,{ listOf(asset) },{ now })
            val source = catalog.resolve("music-1")
            assertTrue(source.isCurrent())
            now = 101
            assertFalse(source.isCurrent())
        } finally { root.deleteRecursively() }
    }

    private fun withCatalog(block: (LocalMusicCatalog, ((ReviewedMusicAsset) -> ReviewedMusicAsset) -> Unit) -> Unit) {
        val root = Files.createTempDirectory("petasos-music-test").toFile()
        try {
            val bytes = byteArrayOf(1, 2, 3)
            java.io.File(root, "asset.wav").writeBytes(bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            var asset = ReviewedMusicAsset("music-1", "asset.wav", hash, 101, true)
            val catalog = LocalMusicCatalog(root, { listOf(asset) }, { 100 })
            block(catalog) { update -> asset = update(asset) }
        } finally { root.deleteRecursively() }
    }
    private fun denied(block: () -> Unit) {
        try { block(); fail("expected denied content") } catch (_: RuntimeContentUnavailable) { }
    }
}
