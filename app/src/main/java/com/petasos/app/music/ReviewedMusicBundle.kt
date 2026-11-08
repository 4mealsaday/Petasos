package com.petasos.app.music

import com.petasos.app.generation.StrictJson
import java.net.URI
import java.security.MessageDigest
import java.util.Collections

/** Public, reviewed music metadata. This manifest is packaged with the app, never model supplied. */
data class MusicTrackInfo(
    val assetId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

data class MusicCredit(
    val track: MusicTrackInfo,
    val licenseName: String,
    val licenseUrl: String,
    val sourceUrl: String,
    val attribution: String,
    val changeNotice: String,
) {
    fun displayText(): String = listOf(
        "${track.title} — ${track.artist}", track.album, attribution,
        "$licenseName: $licenseUrl", "원본: $sourceUrl", changeNotice,
    ).joinToString("\n")
}

internal data class ReviewedMusicEntry(
    val asset: ReviewedMusicAsset,
    val bundledPath: String,
    val credit: MusicCredit,
    val evidenceUrl: String,
    val licenseEvidenceUrl: String,
    val reviewedAt: String,
    val reviewBasis: String,
)

internal class ReviewedMusicBundle(val fingerprint: String, entries: List<ReviewedMusicEntry>) {
    val entries: List<ReviewedMusicEntry> = Collections.unmodifiableList(entries.toList())
}

/** Strict and bounded format; successful decoding is not an online license-verification service. */
internal object ReviewedMusicBundleCodec {
    const val MAX_MANIFEST_BYTES = 65_536
    private val keys = arrayOf(
        "assetId", "fileName", "sha256", "sizeBytes", "durationMs", "title", "artist", "album",
        "license", "licenseUrl", "sourceUrl", "evidenceUrl", "licenseEvidenceUrl", "attribution",
        "changeNotice", "reviewedAt", "reviewBasis", "rightsValidUntilMs", "playbackAllowed", "distributionAllowed",
    )

    fun decode(bytes: ByteArray): ReviewedMusicBundle = try {
        need(bytes.size in 1..MAX_MANIFEST_BYTES)
        val root = obj(StrictJson.parse(bytes.copyOf()), "formatVersion", "tracks")
        need(number(root, "formatVersion") == 1L)
        val rows = root["tracks"] as? List<*> ?: invalid()
        need(rows.size in 1..16)
        val entries = rows.map { raw ->
            val item = obj(raw, *keys)
            val id = string(item, "assetId", 128)
            need(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}").matches(id))
            val fileName = string(item, "fileName", 128)
            need(Regex("[A-Za-z0-9][A-Za-z0-9_-]*\\.(m4a|mp3|ogg|wav|flac)").matches(fileName))
            val hash = string(item, "sha256", 64)
            need(Regex("[0-9a-f]{64}").matches(hash))
            val size = number(item, "sizeBytes").also { need(it in 1..100_000_000L) }
            val duration = number(item, "durationMs").also { need(it in 1..3_600_000L) }
            val allowed = item["playbackAllowed"] as? Boolean ?: invalid()
            val distribution = item["distributionAllowed"] as? Boolean ?: invalid()
            // Revocation is supported, but an unreviewed distribution grant is never installable.
            need(distribution)
            val expires = number(item, "rightsValidUntilMs").also { need(it > 0) }
            val license = string(item, "license", 32)
            val licenseUrl = https(item, "licenseUrl")
            need(when (license) {
                "CC-BY-4.0" -> licenseUrl == "https://creativecommons.org/licenses/by/4.0/"
                "CC0-1.0" -> licenseUrl == "https://creativecommons.org/publicdomain/zero/1.0/"
                else -> false
            })
            val track = MusicTrackInfo(id, string(item,"title",200), string(item,"artist",200), string(item,"album",200), duration)
            val credit = MusicCredit(track, license, licenseUrl, https(item,"sourceUrl"),
                string(item,"attribution",2000), string(item,"changeNotice",500))
            ReviewedMusicEntry(
                ReviewedMusicAsset(id, "$hash.${fileName.substringAfterLast('.')}", hash, expires, allowed, duration, size),
                "music/$fileName", credit, https(item,"evidenceUrl"), https(item,"licenseEvidenceUrl"),
                string(item,"reviewedAt",40).also { need(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(it)) },
                string(item,"reviewBasis",4000),
            )
        }
        need(entries.map { it.asset.assetId }.distinct().size == entries.size)
        need(entries.map { it.bundledPath }.distinct().size == entries.size)
        need(entries.map { it.asset.sha256 }.distinct().size == entries.size)
        ReviewedMusicBundle(sha256(bytes), entries)
    } catch (error: MusicBundleException) { throw error }
      catch (_: Exception) { invalid() }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun obj(value: Any?, vararg keys: String): Map<String, Any?> {
        need(value is Map<*, *> && value.keys == keys.toSet())
        @Suppress("UNCHECKED_CAST") return value as Map<String, Any?>
    }
    private fun number(value: Map<String, Any?>, key: String): Long = value[key] as? Long ?: invalid()
    private fun string(value: Map<String, Any?>, key: String, max: Int): String {
        val result = value[key] as? String ?: invalid()
        need(result.isNotBlank() && result.length <= max && result.none { it.code < 32 && it !in "\n\r\t" })
        return result
    }
    private fun https(value: Map<String, Any?>, key: String): String = string(value,key,2048).also {
        val uri = URI(it)
        need(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
    }
    private fun need(value: Boolean) { if (!value) invalid() }
    private fun invalid(): Nothing = throw MusicBundleException("INVALID_MUSIC_BUNDLE")
}

internal class MusicBundleException(val code: String) : IllegalArgumentException(code)
