package com.petasos.app.location

import com.petasos.app.generation.StrictJson

/** Developer-reviewed local metadata; parsing never establishes the truth of the review evidence. */
internal data class RegionPolicyReview(
    val regionRevisionId: String,
    val manifestSha256: String,
    val geometrySha256: String,
    val policy: RegionPolicy,
    val verifiedAt: Long,
    val reviewBasis: String,
    val evidenceSha256: String,
) {
    override fun toString() = "RegionPolicyReview([redacted])"
}

internal class RegionPolicyException(val code: String = "INVALID_REGION_POLICY") : IllegalArgumentException(code)

/** One strict review per document. Batch atomicity and database currentness belong to the installer. */
internal object ReviewedRegionPolicyCodec {
    const val MAX_BYTES = StrictJson.MAX_BYTES
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val decimalPattern = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
    private val rootKeys = setOf("formatVersion", "regionRevisionId", "manifestSha256", "geometrySha256",
        "policy", "verifiedAt", "reviewBasis", "evidenceSha256")
    private val policyKeys = setOf("datasetVersion", "consecutiveSamples", "minConfirmationDurationMs",
        "boundaryMarginMeters", "qualityAdapterVersion", "coordinateReferenceSystem")
    private val reviewKeys = setOf("formatVersion", "verifiedAt", "reviewBasis", "evidenceSha256")
    private val storedKeys get() = RegionGeometryMetadata.geometryKeys + policyKeys + setOf("policyStatus", "review")

    fun decode(bytes: ByteArray, nowEpochMs: Long): RegionPolicyReview = checked {
        val root = obj(StrictJson.parse(bytes), rootKeys)
        need(number(root, "formatVersion") == 1L)
        val review = RegionPolicyReview(text(root, "regionRevisionId", 128), text(root, "manifestSha256", 64),
            text(root, "geometrySha256", 64), readPolicy(obj(root["policy"], policyKeys)),
            number(root, "verifiedAt"), text(root, "reviewBasis", 4000), text(root, "evidenceSha256", 64))
        validate(review, nowEpochMs)
        review
    }

    /** Also validates directly constructed DTOs before a write; no default thresholds are supplied. */
    fun validate(review: RegionPolicyReview, nowEpochMs: Long): Unit = checked {
        need(nowEpochMs >= 0 && review.verifiedAt in 0..nowEpochMs)
        need(idPattern.matches(review.regionRevisionId) && hashPattern.matches(review.manifestSha256) &&
            hashPattern.matches(review.geometrySha256) && hashPattern.matches(review.evidenceSha256))
        val policy = review.policy
        need(idPattern.matches(policy.datasetVersion) && policy.consecutiveSamples in 2..100 &&
            policy.minConfirmationDurationMs >= 0 && policy.coordinateReferenceSystem == "EPSG:4326")
        string(policy.qualityAdapterVersion, 200)
        string(review.reviewBasis, 4000)
        integerMargin(policy.boundaryMarginMeters)
        // Validate surrogate pairs and the common bounded JSON representation for constructed DTOs.
        StrictJson.encode(reviewMap(review))
        Unit
    }

    /** This is identity/chronology validation; current source permissions are a storage responsibility. */
    fun validateAgainstManifest(
        manifest: RegionDatasetManifest, review: RegionPolicyReview, nowEpochMs: Long,
    ): RegionDatasetEntry = checked {
        validate(review, nowEpochMs)
        val entry = manifest.regions.singleOrNull { it.regionRevisionId == review.regionRevisionId } ?: invalid()
        need(entry.status == "ready" && entry.reasonCode == null && manifest.fingerprint == review.manifestSha256 &&
            entry.geometrySha256 == review.geometrySha256 && manifest.datasetVersion == review.policy.datasetVersion &&
            manifest.source.verifiedAt in 0..review.verifiedAt)
        entry
    }

    /**
     * Canonical stored envelope. The caller must first validate against its current clock and source
     * rights; using the review time here only checks representation and source-to-review chronology.
     */
    fun storedPolicyJson(
        manifest: RegionDatasetManifest, entry: RegionDatasetEntry, review: RegionPolicyReview,
    ): String = checked {
        need(validateAgainstManifest(manifest, review, review.verifiedAt) == entry)
        val stored = RegionGeometryMetadata.pendingPolicy(manifest, entry).toMutableMap()
        stored["policyStatus"] = "verified"
        stored.putAll(policyMap(review.policy))
        stored["review"] = linkedMapOf("formatVersion" to 1L, "verifiedAt" to review.verifiedAt,
            "reviewBasis" to review.reviewBasis, "evidenceSha256" to review.evidenceSha256)
        StrictJson.encode(stored).toString(Charsets.UTF_8)
    }

    /**
     * Strict marked-envelope reader, independent of a supplied manifest. Does not accept legacy or
     * pending JSON. The store must compare the returned time to the non-null DB verified_at and
     * check current source rights plus the expected immutable geometry identity.
     */
    fun decodeStored(bytes: ByteArray, regionRevisionId: String, nowEpochMs: Long): RegionPolicyReview = checked {
        val root = obj(StrictJson.parse(bytes), storedKeys)
        need(root["policyStatus"] == "verified")
        validateStoredGeometry(root)
        val metadata = obj(root["review"], reviewKeys)
        need(number(metadata, "formatVersion") == 1L)
        val review = RegionPolicyReview(regionRevisionId, text(root, "manifestSha256", 64),
            text(root, "geometrySha256", 64), readPolicy(root.filterKeys { it in policyKeys }),
            number(metadata, "verifiedAt"), text(metadata, "reviewBasis", 4000), text(metadata, "evidenceSha256", 64))
        validate(review, nowEpochMs)
        review
    }

    private fun readPolicy(row: Map<String, Any?>): RegionPolicy {
        val count = number(row, "consecutiveSamples")
        need(count in 2..100)
        val margin = number(row, "boundaryMarginMeters")
        // A Long may round when used by the existing geometric Double domain. Never change a review
        // threshold silently, including Long.MAX_VALUE's saturating Double-to-Long conversion.
        need(margin >= 0 && margin != Long.MAX_VALUE && margin.toDouble().toLong() == margin)
        return RegionPolicy(text(row, "datasetVersion", 128), count.toInt(), number(row, "minConfirmationDurationMs"),
            margin.toDouble(), text(row, "qualityAdapterVersion", 200), text(row, "coordinateReferenceSystem", 32))
    }

    private fun validateStoredGeometry(row: Map<String, Any?>) {
        need(row["coordinateReferenceSystem"] == "EPSG:4326" && row["sourceCoordinateReferenceSystem"] == "EPSG:5179")
        need(row["geometryStatus"] == "ready" && row["geometryReasonCode"] == null)
        need(hashPattern.matches(text(row, "manifestSha256", 64)) && hashPattern.matches(text(row, "geometrySha256", 64)))
        val asset = text(row, "geometryAsset", 240)
        need(Regex("regions/[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*").matches(asset) &&
            asset.split('/').none { it == "." || it == ".." })
        need(number(row, "geometryBytes") in 1..PackedRegionBoundaryLookup.MAX_BYTES)
        text(row, "transformLibraryVersion", 128)
        text(row, "transformOperation", 2000)
        val accuracy = text(row, "transformExpectedAccuracyMeters", 64)
        need(decimalPattern.matches(accuracy))
        val parsed = accuracy.toDoubleOrNull() ?: invalid()
        need(parsed.isFinite() && parsed in 0.0..100.0)
    }

    private fun reviewMap(review: RegionPolicyReview): Map<String, Any?> = linkedMapOf(
        "formatVersion" to 1L, "regionRevisionId" to review.regionRevisionId,
        "manifestSha256" to review.manifestSha256, "geometrySha256" to review.geometrySha256,
        "policy" to policyMap(review.policy), "verifiedAt" to review.verifiedAt,
        "reviewBasis" to review.reviewBasis, "evidenceSha256" to review.evidenceSha256,
    )

    private fun policyMap(policy: RegionPolicy): Map<String, Any?> = linkedMapOf(
        "datasetVersion" to policy.datasetVersion, "consecutiveSamples" to policy.consecutiveSamples.toLong(),
        "minConfirmationDurationMs" to policy.minConfirmationDurationMs,
        "boundaryMarginMeters" to integerMargin(policy.boundaryMarginMeters),
        "qualityAdapterVersion" to policy.qualityAdapterVersion,
        "coordinateReferenceSystem" to policy.coordinateReferenceSystem,
    )

    private fun integerMargin(value: Double): Long {
        need(value.isFinite() && value >= 0 && value < Long.MAX_VALUE.toDouble() && value.toBits() != Long.MIN_VALUE)
        return value.toLong().also { need(it.toDouble() == value) }
    }

    private fun obj(value: Any?, keys: Set<String>): Map<String, Any?> {
        need(value is Map<*, *> && value.keys == keys)
        @Suppress("UNCHECKED_CAST") return value as Map<String, Any?>
    }
    private fun number(row: Map<String, Any?>, key: String): Long = row[key] as? Long ?: invalid()
    private fun text(row: Map<String, Any?>, key: String, max: Int): String = string(row[key] as? String ?: invalid(), max)
    private fun string(value: String, max: Int): String = value.also {
        need(it.isNotBlank() && it.length <= max && it.none { c -> c.isISOControl() && c !in "\n\r\t" })
    }
    private fun need(value: Boolean) { if (!value) invalid() }
    private fun invalid(): Nothing = throw RegionPolicyException()
    private inline fun <T> checked(action: () -> T): T = try { action() }
        catch (error: RegionPolicyException) { throw error }
        catch (_: Exception) { invalid() }
}
