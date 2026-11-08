package com.petasos.app.location

import com.petasos.app.generation.StrictJson
import java.util.Collections

/** APK-reviewed local inventory, not an authenticated remote policy distribution protocol. */
internal class RegionPolicyBundle(
    val fingerprint: String,
    val manifestSha256: String,
    reviews: List<RegionPolicyReview>,
) {
    val reviews: List<RegionPolicyReview> = Collections.unmodifiableList(ArrayList(reviews))
    override fun toString() = "RegionPolicyBundle([redacted])"
}

/**
 * The caller supplies an independently pinned catalog hash. Embedded reviews retain the exact single
 * review schema. Evidence-file truth and its SHA are reviewed offline; this codec does not open,
 * authenticate or interpret the evidence artifact itself.
 */
internal object ReviewedRegionPolicyBundleCodec {
    const val MAX_BYTES = StrictJson.MAX_BYTES
    private val digestPattern = Regex("[0-9a-f]{64}")

    fun decode(bytes: ByteArray, expectedSha256: String, nowEpochMs: Long): RegionPolicyBundle = try {
        need(bytes.size in 1..MAX_BYTES && digestPattern.matches(expectedSha256) && nowEpochMs >= 0)
        val snapshot = bytes.copyOf()
        val fingerprint = RegionDatasetManifestCodec.sha256(snapshot)
        need(fingerprint == expectedSha256)
        val parsed = StrictJson.parse(snapshot)
        need(parsed is Map<*, *> && parsed.keys == setOf("formatVersion", "manifestSha256", "reviews"))
        val root = parsed as Map<*, *>
        need(root["formatVersion"] is Long && root["formatVersion"] == 1L)
        val manifest = root["manifestSha256"] as? String ?: invalid()
        need(digestPattern.matches(manifest))
        val rows = root["reviews"] as? List<*> ?: invalid()
        need(rows.size <= 512)
        val reviews = rows.map { raw ->
            ReviewedRegionPolicyCodec.decode(StrictJson.encode(raw), nowEpochMs).also {
                need(it.manifestSha256 == manifest)
            }
        }
        need(reviews.map { it.regionRevisionId }.distinct().size == reviews.size)
        RegionPolicyBundle(fingerprint, manifest, reviews)
    } catch (_: Exception) { invalid() }

    private fun need(value: Boolean) { if (!value) invalid() }
    private fun invalid(): Nothing = throw RegionPolicyException("INVALID_REGION_POLICY_BUNDLE")
}
