package com.petasos.app.location

import com.petasos.app.generation.StrictJson
import org.junit.Assert.*
import org.junit.Test

/** Synthetic review thresholds only; none authorize a real place or measured route. */
class ReviewedLandmarkPolicyTest {
    @Test fun exactReviewKeepsExplicitStoryPlaceAssociationAndPolicy() {
        val review = decode()
        assertEquals("synthetic-place", review.placeId)
        assertEquals("synthetic-story", review.storyId)
        assertEquals("synthetic-association-r1", review.associationFactRevisionId)
        assertEquals(250.0, review.policy.approachRadiusMeters, 0.0)
        assertEquals(60_000L, review.policy.cooldownMs)
        assertEquals(LandmarkDirectionRule.DECREASING_DISTANCE, review.policy.directionRule)
        assertEquals(LandmarkPassedRule.INCREASING_DISTANCE_AFTER_APPROACH, review.policy.passedRule)
        assertEquals("LandmarkPolicyReview([redacted])", review.toString())
    }

    @Test fun exactCanonicalReinstallRepresentationIsDeterministic() {
        val review = decode()
        val json = ReviewedLandmarkPolicyCodec.storedPolicyJson(review)
        assertEquals(review, ReviewedLandmarkPolicyCodec.decode(json.toByteArray(), NOW))
        assertEquals(json, ReviewedLandmarkPolicyCodec.storedPolicyJson(review))
        val reversed = root().toList().reversed().toMap()
        assertEquals(json, ReviewedLandmarkPolicyCodec.storedPolicyJson(decode(reversed)))
    }

    @Test fun rejectedStoredReviewRetainsIdentityButIsNotAnInstallReview() {
        val review = decode()
        val bytes = ReviewedLandmarkPolicyCodec.rejectedPolicyJson(review).toByteArray()
        val rejected = ReviewedLandmarkPolicyCodec.decodeStored(bytes, NOW)
        assertEquals("rejected", rejected.status)
        assertEquals(review, rejected.review)
        assertEquals("StoredLandmarkPolicy([redacted])", rejected.toString())
        rejects { ReviewedLandmarkPolicyCodec.decode(bytes, NOW) }
        rejects { bundle(listOf(StrictJson.parse(bytes))) }
    }

    @Test fun everyMissingOrUnknownKeyIsRejectedWithoutLegacyDowngrade() {
        root().keys.forEach { key -> rejects { decode(root() - key) } }
        rejects { decode(root() + ("approachDefault" to true)) }
        rejects { decode(root().filterKeys { it in ReviewedLandmarkPolicyCodec.legacyKeys }) }
        rejects { decode(root() + ("reviewStatus" to "pending")) }
    }

    @Test fun formatAndTimestampRequireIntegersWithCurrentReviewTime() {
        listOf(null, false, "1", 0L, 2L).forEach { value -> rejects { decode(root() + ("formatVersion" to value)) } }
        listOf(null, true, "2000", -1L, NOW + 1).forEach { value -> rejects { decode(root() + ("verifiedAt" to value)) } }
        assertEquals(NOW, decode(root() + ("verifiedAt" to NOW)).verifiedAt)
        rejects { ReviewedLandmarkPolicyCodec.decode(StrictJson.encode(root()), -1) }
    }

    @Test fun idsHashesAndReviewTextAreValidatedWithoutFallbacks() {
        listOf("placeId", "storyId", "associationFactRevisionId").forEach { key ->
            listOf("", "../place", " x", "x".repeat(129), false).forEach { value -> rejects { decode(root() + (key to value)) } }
        }
        listOf("placeMetadataSha256", "evidenceSha256").forEach { key ->
            listOf("", "A".repeat(64), "f".repeat(63), "g".repeat(64), 4L).forEach { value -> rejects { decode(root() + (key to value)) } }
        }
        listOf("reviewBasis", "policyVersion", "qualityAdapterVersion").forEach { key ->
            listOf("", " \n", "bad\u0000value", false).forEach { value -> rejects { decode(root() + (key to value)) } }
        }
        rejects { decode(root() + ("reviewBasis" to "a".repeat(4001))) }
        rejects { decode(root() + ("policyVersion" to "a".repeat(201))) }
        rejects { decode(root() + ("qualityAdapterVersion" to "a".repeat(201))) }
    }

    @Test fun unknownRulesAndMissingNumericThresholdsCannotChooseDefaults() {
        rejects { decode(root() + ("directionRule" to "any_direction")) }
        rejects { decode(root() + ("passedRule" to "timeout")) }
        listOf("approachRadiusMeters", "cooldownMs").forEach { key ->
            listOf(null, false, "250", -1L).forEach { value -> rejects { decode(root() + (key to value)) } }
        }
        rejects { decode(root() + ("approachRadiusMeters" to 0L)) }
        assertEquals(0L, decode(root() + ("cooldownMs" to 0L)).policy.cooldownMs)
    }

    @Test fun radiusCannotLoseIntegerPrecisionDuringDoubleConversion() {
        rejects { decode(root() + ("approachRadiusMeters" to 9_007_199_254_740_993L)) }
        rejects { decode(root() + ("approachRadiusMeters" to Long.MAX_VALUE)) }
        val review = decode()
        rejects { ReviewedLandmarkPolicyCodec.validate(review.copy(policy = review.policy.copy(approachRadiusMeters = 0.5)), NOW) }
        rejects { ReviewedLandmarkPolicyCodec.validate(review.copy(policy = review.policy.copy(approachRadiusMeters = Long.MAX_VALUE.toDouble())), NOW) }
    }

    @Test fun duplicateKeysDecimalExponentAndInvalidUnicodeAreRejected() {
        val raw = StrictJson.encode(root()).toString(Charsets.UTF_8)
        rejects { ReviewedLandmarkPolicyCodec.decode(raw.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1").toByteArray(), NOW) }
        listOf("250.0", "2.5e2", "NaN").forEach { value ->
            rejects { ReviewedLandmarkPolicyCodec.decode(raw.replace("\"approachRadiusMeters\":250", "\"approachRadiusMeters\":$value").toByteArray(), NOW) }
        }
        rejects { ReviewedLandmarkPolicyCodec.decode(raw.replace("Synthetic evidence", "\\ud800").toByteArray(), NOW) }
    }

    @Test fun constructedReviewsAreRevalidatedAndErrorsHaveFixedCodes() {
        val review = decode()
        rejects { ReviewedLandmarkPolicyCodec.validate(review.copy(placeId = "secret invalid"), NOW) }
        rejects { ReviewedLandmarkPolicyCodec.validate(review.copy(verifiedAt = NOW + 1), NOW) }
        rejects { ReviewedLandmarkPolicyCodec.validate(review.copy(evidenceSha256 = "private payload"), NOW) }
    }

    @Test fun emptyBundleDoesNotInventAnyApproval() {
        assertTrue(bundle(emptyList()).isEmpty())
        assertEquals(listOf(decode()), bundle(listOf(root())))
    }

    @Test fun bundleRequiresExactRootAndVersionAndList() {
        val map = mapOf("formatVersion" to 1L, "reviews" to emptyList<Any>())
        listOf(map - "formatVersion", map + ("extra" to true), map + ("formatVersion" to true),
            map + ("reviews" to null), map + ("reviews" to mapOf("place" to "invalid"))).forEach { value ->
            rejects { ReviewedLandmarkPolicyCodec.decodeBundle(StrictJson.encode(value), NOW) }
        }
    }

    @Test fun duplicatePlaceOrStoryIsAmbiguousAndRejectsTheWholeBatch() {
        rejects { bundle(listOf(root(), root() + ("storyId" to "different-story"))) }
        rejects { bundle(listOf(root(), root() + ("placeId" to "different-place"))) }
        val review = decode()
        rejects { ReviewedLandmarkPolicyCodec.validateBatch(listOf(review, review.copy(placeId = "other-place")), NOW) }
        rejects { ReviewedLandmarkPolicyCodec.validateBatch(List(513) { review.copy(placeId = "p-$it", storyId = "s-$it") }, NOW) }
    }

    @Test fun invalidLaterEntryCannotReturnPartiallyDecodedReviews() {
        rejects { bundle(listOf(root(), root() + mapOf("placeId" to "other-place", "storyId" to "other-story", "verifiedAt" to NOW + 1))) }
        rejects { ReviewedLandmarkPolicyCodec.decodeBundle(ByteArray(StrictJson.MAX_BYTES + 1) { 32 }, NOW) }
    }

    @Test fun metadataPinsExactCoordinatesSourceAndPlaceFieldsWithoutPolicyState() {
        val place = place()
        val fingerprint = place.fingerprint()
        assertEquals(64, fingerprint.length)
        assertEquals(fingerprint, place.copy().fingerprint())
        val changes = listOf(place.copy(placeId = "other-place"), place.copy(regionId = "region"),
            place.copy(name = "Other name"), place.copy(aliasesJson = "[\"alias\"]"),
            place.copy(latitude = Math.nextUp(place.latitude)), place.copy(longitude = Math.nextUp(place.longitude)),
            place.copy(coordinateSourceRevisionId = "other-coordinate-revision"))
        changes.forEach { assertNotEquals(fingerprint, it.fingerprint()) }
        assertEquals("LandmarkPlaceMetadata([redacted])", place.toString())
    }

    @Test fun metadataRejectsNonfiniteAndOutOfRangeCoordinates() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 90.0001, -90.0001).forEach { value ->
            try { place().copy(latitude = value).fingerprint(); fail("invalid coordinate accepted") }
            catch (_: IllegalArgumentException) { }
        }
        listOf(Double.NEGATIVE_INFINITY, 180.0001, -180.0001).forEach { value ->
            try { place().copy(longitude = value).fingerprint(); fail("invalid coordinate accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    private fun decode(value: Map<String, Any?> = root()) = ReviewedLandmarkPolicyCodec.decode(StrictJson.encode(value), NOW)
    private fun bundle(values: List<Any?>) = ReviewedLandmarkPolicyCodec.decodeBundle(
        StrictJson.encode(mapOf("formatVersion" to 1L, "reviews" to values)), NOW)
    private fun root(): Map<String, Any?> = linkedMapOf(
        "formatVersion" to 1L, "placeId" to "synthetic-place", "placeMetadataSha256" to place().fingerprint(),
        "storyId" to "synthetic-story", "associationFactRevisionId" to "synthetic-association-r1",
        "approachRadiusMeters" to 250L, "passedRule" to "increasing_distance_after_approach",
        "directionRule" to "decreasing_distance", "cooldownMs" to 60_000L,
        "policyVersion" to "synthetic-policy-v1", "qualityAdapterVersion" to "synthetic-quality-v1",
        "reviewStatus" to "verified", "verifiedAt" to 2_000L, "reviewBasis" to "Synthetic evidence",
        "evidenceSha256" to "a".repeat(64),
    )
    private fun place() = LandmarkPlaceMetadata("synthetic-place", null, "Synthetic place", "[]", 37.0, 127.0, "synthetic-coordinate-r1")
    private fun rejects(action: () -> Any?) {
        try { action(); fail("invalid review accepted") }
        catch (failure: LandmarkPolicyException) { assertEquals("INVALID_LANDMARK_POLICY", failure.code) }
    }
    private companion object { const val NOW = 3_000L }
}
