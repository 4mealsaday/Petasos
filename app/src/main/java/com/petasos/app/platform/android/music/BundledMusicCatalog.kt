package com.petasos.app.platform.android.music

import android.content.Context
import com.petasos.app.music.LocalMusicCatalog
import com.petasos.app.music.MusicCatalog
import com.petasos.app.music.MusicCredit
import com.petasos.app.music.MusicTrackInfo
import com.petasos.app.music.ReviewedMusicAsset
import com.petasos.app.music.ReviewedMusicBundle
import com.petasos.app.music.ReviewedMusicBundleCodec
import com.petasos.app.music.ReviewedMusicInstaller
import com.petasos.app.planning.AutomaticMusicCandidate
import com.petasos.app.runtime.RuntimeContentUnavailable
import com.petasos.app.runtime.RuntimeMusicSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * Reviewed app assets only. Construction performs no file I/O; all public reads run on a worker.
 * No remote URI, arbitrary import, private recording, or model output enters this catalog.
 */
class BundledMusicCatalog(context: Context, private val now: () -> Long = System::currentTimeMillis) : MusicCatalog {
    private val app = context.applicationContext
    private class Snapshot(val bundle: ReviewedMusicBundle, assets: List<ReviewedMusicAsset>, val local: LocalMusicCatalog) {
        val assets: List<ReviewedMusicAsset> = Collections.unmodifiableList(assets.toList())
    }
    private val installed = AtomicReference<Snapshot?>()

    override fun resolve(assetId: String): RuntimeMusicSource = available {
        snapshot().local.resolve(assetId)
    }

    /** Manifest order is deterministic; each candidate includes a complete current hash/rights check. */
    fun automaticCandidates(): List<AutomaticMusicCandidate> = try {
        val state = snapshot()
        state.bundle.entries.mapNotNull { entry ->
            try {
                AutomaticMusicCandidate(state.local.prepare(entry.asset.assetId),
                    ReviewedMusicBundleCodec.sha256("${state.bundle.fingerprint}:${entry.asset.assetId}".toByteArray(Charsets.UTF_8)))
            } catch (_: Exception) { null }
        }
    } catch (_: Exception) { emptyList() }

    fun credits(): List<MusicCredit> = available { snapshot().bundle.entries.map { it.credit } }
    fun creditsText(): String = credits().joinToString("\n\n") { it.displayText() }
    fun trackInfo(assetId: String): MusicTrackInfo? = available {
        snapshot().bundle.entries.singleOrNull { it.asset.assetId == assetId }?.credit?.track
    }

    @Synchronized private fun snapshot(): Snapshot {
        installed.get()?.let { return it }
        val bytes = app.assets.open("music/manifest.json").use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0 || output.size() + count > ReviewedMusicBundleCodec.MAX_MANIFEST_BYTES) throw RuntimeContentUnavailable()
                output.write(buffer,0,count)
            }
            output.toByteArray()
        }
        val bundle = ReviewedMusicBundleCodec.decode(bytes)
        val root = File(app.noBackupFilesDir, "reviewed-music")
        ReviewedMusicInstaller(root, app.assets::open).install(bundle)
        val local = LocalMusicCatalog(root, { installed.get()?.assets.orEmpty() }, now)
        return Snapshot(bundle, bundle.entries.map { it.asset }, local).also(installed::set)
    }

    private inline fun <T> available(block: () -> T): T = try { block() }
        catch (_: Exception) { throw RuntimeContentUnavailable() }
}
