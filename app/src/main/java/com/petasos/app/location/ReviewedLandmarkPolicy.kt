package com.petasos.app.location

import com.petasos.app.generation.StrictJson

/** A developer-reviewed explicit story/place association. No thresholds or approvals are inferred. */
internal data class LandmarkPolicyReview(
    val placeId: String,
    val placeMetadataSha256: String,
    val storyId: String,
    val associationFactRevisionId: String,
    val policy: LandmarkPolicy,
    val verifiedAt: Long,
    val reviewBasis: String,
    val evidenceSha256: String,
) { override fun toString() = "LandmarkPolicyReview([redacted])" }

internal data class StoredLandmarkPolicy(val review: LandmarkPolicyReview, val status: String) {
    override fun toString() = "StoredLandmarkPolicy([redacted])"
}

internal class LandmarkPolicyException(val code: String = "INVALID_LANDMARK_POLICY") : IllegalArgumentException(code)

/** Strict integer JSON, preserving the existing DATA-11 threshold representation.
 * Evidence hashes are review references, not a claim that this parser inspected evidence files.
 */
internal object ReviewedLandmarkPolicyCodec {
    const val MAX_REVIEWS = 512
    private val id = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
    private val hash = Regex("[0-9a-f]{64}")
    internal val legacyKeys = setOf("approachRadiusMeters", "passedRule", "directionRule", "cooldownMs",
        "policyVersion", "qualityAdapterVersion", "reviewStatus", "verifiedAt", "reviewBasis",
        "storyId", "associationFactRevisionId")
    private val keys = legacyKeys + setOf("formatVersion", "placeId", "placeMetadataSha256", "evidenceSha256")

    fun decode(bytes: ByteArray, nowEpochMs: Long): LandmarkPolicyReview = checked {
        val stored = read(obj(StrictJson.parse(bytes), keys), nowEpochMs)
        need(stored.status == "verified")
        stored.review
    }

    fun decodeStored(bytes: ByteArray, nowEpochMs: Long): StoredLandmarkPolicy = checked {
        read(obj(StrictJson.parse(bytes), keys), nowEpochMs)
    }

    fun decodeBundle(bytes: ByteArray, nowEpochMs: Long): List<LandmarkPolicyReview> = checked {
        val root = obj(StrictJson.parse(bytes), setOf("formatVersion", "reviews"))
        need(number(root, "formatVersion") == 1L)
        val values = root["reviews"] as? List<*> ?: invalid()
        need(values.size <= MAX_REVIEWS)
        val reviews = values.map { value ->
            val stored = read(obj(value, keys), nowEpochMs)
            need(stored.status == "verified")
            stored.review
        }
        validateBatch(reviews, nowEpochMs)
        reviews
    }

    fun validateBatch(reviews: List<LandmarkPolicyReview>, nowEpochMs: Long): Unit = checked {
        need(reviews.size <= MAX_REVIEWS && reviews.map { it.placeId }.distinct().size == reviews.size &&
            reviews.map { it.storyId }.distinct().size == reviews.size)
        reviews.forEach { validate(it, nowEpochMs) }
    }

    fun validate(review: LandmarkPolicyReview, nowEpochMs: Long): Unit = checked {
        need(nowEpochMs >= 0 && review.verifiedAt in 0..nowEpochMs)
        need(id.matches(review.placeId) && id.matches(review.storyId) && id.matches(review.associationFactRevisionId) &&
            hash.matches(review.placeMetadataSha256) && hash.matches(review.evidenceSha256))
        string(review.reviewBasis, 4000)
        string(review.policy.policyVersion, 200)
        string(review.policy.qualityAdapterVersion, 200)
        need(review.policy.cooldownMs >= 0)
        integerRadius(review.policy.approachRadiusMeters)
        StrictJson.encode(map(review, "verified"))
        Unit
    }

    fun storedPolicyJson(review: LandmarkPolicyReview): String = checked {
        validate(review, review.verifiedAt)
        StrictJson.encode(map(review, "verified")).toString(Charsets.UTF_8)
    }

    /** Revocation retains the exact reviewed association; reinstall never promotes this marker. */
    fun rejectedPolicyJson(review: LandmarkPolicyReview): String = checked {
        validate(review, review.verifiedAt)
        StrictJson.encode(map(review, "rejected")).toString(Charsets.UTF_8)
    }

    private fun read(root: Map<String, Any?>, now: Long): StoredLandmarkPolicy {
        need(number(root, "formatVersion") == 1L)
        val status = text(root, "reviewStatus", 16)
        need(status == "verified" || status == "rejected")
        need(root["passedRule"] == "increasing_distance_after_approach" && root["directionRule"] == "decreasing_distance")
        val radius = number(root, "approachRadiusMeters")
        need(radius > 0 && radius != Long.MAX_VALUE && radius.toDouble().toLong() == radius)
        val review = LandmarkPolicyReview(text(root, "placeId", 128), text(root, "placeMetadataSha256", 64),
            text(root, "storyId", 128), text(root, "associationFactRevisionId", 128),
            LandmarkPolicy(radius.toDouble(), LandmarkPassedRule.INCREASING_DISTANCE_AFTER_APPROACH,
                LandmarkDirectionRule.DECREASING_DISTANCE, number(root, "cooldownMs"),
                text(root, "policyVersion", 200), text(root, "qualityAdapterVersion", 200)),
            number(root, "verifiedAt"), text(root, "reviewBasis", 4000), text(root, "evidenceSha256", 64))
        validate(review, now)
        return StoredLandmarkPolicy(review, status)
    }

    private fun map(review: LandmarkPolicyReview, status: String): Map<String, Any?> = linkedMapOf(
        "formatVersion" to 1L, "placeId" to review.placeId, "placeMetadataSha256" to review.placeMetadataSha256,
        "storyId" to review.storyId, "associationFactRevisionId" to review.associationFactRevisionId,
        "approachRadiusMeters" to integerRadius(review.policy.approachRadiusMeters),
        "passedRule" to "increasing_distance_after_approach", "directionRule" to "decreasing_distance",
        "cooldownMs" to review.policy.cooldownMs, "policyVersion" to review.policy.policyVersion,
        "qualityAdapterVersion" to review.policy.qualityAdapterVersion, "reviewStatus" to status,
        "verifiedAt" to review.verifiedAt, "reviewBasis" to review.reviewBasis, "evidenceSha256" to review.evidenceSha256,
    )

    private fun integerRadius(value: Double): Long {
        need(value.isFinite() && value > 0 && value < Long.MAX_VALUE.toDouble())
        return value.toLong().also { need(it.toDouble() == value) }
    }
    private fun obj(value: Any?, expected: Set<String>): Map<String, Any?> {
        need(value is Map<*, *> && value.keys == expected)
        @Suppress("UNCHECKED_CAST") return value as Map<String, Any?>
    }
    private fun number(row: Map<String, Any?>, key: String): Long = row[key] as? Long ?: invalid()
    private fun text(row: Map<String, Any?>, key: String, max: Int): String = string(row[key] as? String ?: invalid(), max)
    private fun string(value: String, max: Int): String = value.also {
        need(it.isNotBlank() && it.length <= max && it.none { c -> c.isISOControl() && c !in "\n\r\t" })
    }
    private fun need(condition: Boolean) { if (!condition) invalid() }
    private fun invalid(): Nothing = throw LandmarkPolicyException()
    private inline fun <T> checked(action: () -> T): T = try { action() }
        catch (failure: LandmarkPolicyException) { throw failure }
        catch (_: Exception) { invalid() }
}
