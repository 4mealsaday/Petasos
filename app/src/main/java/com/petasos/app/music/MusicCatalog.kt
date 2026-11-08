package com.petasos.app.music

import com.petasos.app.playback.PreparedMusic
import com.petasos.app.runtime.RuntimeContentUnavailable
import com.petasos.app.runtime.RuntimeMusicSource
import java.io.File
import java.security.MessageDigest

/** Music is opt-in reviewed content. An empty catalog is a valid installation. */
fun interface MusicCatalog {
    fun resolve(assetId: String): RuntimeMusicSource
    fun prepare(assetId: String): PreparedMusic = resolve(assetId).let { PreparedMusic(it.assetId, it.durationMs) }
}

object EmptyMusicCatalog : MusicCatalog {
    override fun resolve(assetId: String): RuntimeMusicSource = throw RuntimeContentUnavailable()
}

/** Built by an installation/review boundary, never from model output or an external media URI. */
data class ReviewedMusicAsset(
    val assetId: String,
    val relativePath: String,
    val sha256: String,
    val rightsValidUntilMs: Long,
    val playbackAllowed: Boolean,
    val durationMs: Long? = null,
    val expectedBytes: Long? = null,
)

class LocalMusicCatalog(
    private val root: File,
    /** Immutable review objects from an atomic in-memory snapshot; never perform I/O here. */
    private val reviewedAssets: () -> List<ReviewedMusicAsset>,
    private val now: () -> Long = System::currentTimeMillis,
) : MusicCatalog {
    override fun resolve(assetId: String): RuntimeMusicSource {
        val matches = reviewedAssets().filter { it.assetId == assetId }
        val asset = matches.singleOrNull() ?: throw RuntimeContentUnavailable()
        if (!asset.playbackAllowed || asset.rightsValidUntilMs <= now() ||
            !asset.sha256.matches(Regex("[0-9a-f]{64}")) || File(asset.relativePath).isAbsolute ||
            asset.durationMs?.let { it <= 0 } == true ||
            asset.expectedBytes?.let { it !in 1..100_000_000L } == true) throw RuntimeContentUnavailable()
        val base = root.canonicalFile
        val file = File(base, asset.relativePath).canonicalFile
        if (!file.toPath().startsWith(base.toPath()) || file == base || !file.isFile || file.length() !in 1..100_000_000L ||
            asset.expectedBytes?.let { file.length() != it } == true) {
            throw RuntimeContentUnavailable()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(16_384)
            var total = 0L
            while (true) {
                val length = input.read(bytes)
                if (length < 0) break
                total += length
                if (length == 0 || total > (asset.expectedBytes ?: 100_000_000L)) throw RuntimeContentUnavailable()
                digest.update(bytes, 0, length)
            }
            if (asset.expectedBytes?.let { total != it } == true) throw RuntimeContentUnavailable()
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != asset.sha256) throw RuntimeContentUnavailable()
        val source = RuntimeMusicSource(asset.assetId, file.toURI().toString(), asset.durationMs, current = {
            // A replacement review, even with equal fields, invalidates a previously prepared source.
            val currentAsset = reviewedAssets().singleOrNull { it.assetId == asset.assetId }
            currentAsset === asset && asset.playbackAllowed && asset.rightsValidUntilMs > now()
        })
        if (!source.isCurrent()) throw RuntimeContentUnavailable()
        return source
    }
}
