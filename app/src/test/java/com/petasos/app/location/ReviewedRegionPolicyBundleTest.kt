package com.petasos.app.location

import com.petasos.app.generation.StrictJson
import org.junit.Assert.*
import org.junit.Test

/** Synthetic inventories only; a valid digest is not a claim that review evidence is true. */
class ReviewedRegionPolicyBundleTest {
    @Test fun emptyInventoryIsValidAndDoesNotInventReviews() {
        val value = decode(root())
        assertTrue(value.reviews.isEmpty())
        assertEquals("a".repeat(64), value.manifestSha256)
        assertEquals(RegionDatasetManifestCodec.sha256(StrictJson.encode(root())), value.fingerprint)
        assertEquals("RegionPolicyBundle([redacted])", value.toString())
    }

    @Test fun embeddedReviewRetainsTheSingleReviewContractAndOrder() {
        val first = review("synthetic-first")
        val second = review("synthetic-second") + ("geometrySha256" to "d".repeat(64))
        val bundle = decode(root(listOf(first, second)))
        assertEquals(listOf("synthetic-first", "synthetic-second"), bundle.reviews.map { it.regionRevisionId })
        assertEquals(ReviewedRegionPolicyCodec.decode(StrictJson.encode(first), 3_000), bundle.reviews.first())
        assertEquals(ReviewedRegionPolicyCodec.decode(StrictJson.encode(second), 3_000), bundle.reviews.last())
    }

    @Test fun externalPinRejectsChangedBytesEvenIfJsonMeaningIsUnchanged() {
        val bytes = StrictJson.encode(root())
        val pin = RegionDatasetManifestCodec.sha256(bytes)
        rejects { ReviewedRegionPolicyBundleCodec.decode(bytes, "0".repeat(64), 3_000) }
        rejects { ReviewedRegionPolicyBundleCodec.decode(bytes, pin.uppercase(), 3_000) }
        rejects { ReviewedRegionPolicyBundleCodec.decode(bytes + byteArrayOf(32), pin, 3_000) }
    }

    @Test fun catalogRootRequiresExactKeysAndIntegerVersionOne() {
        root().keys.forEach { rejects { decode(root() - it) } }
        rejects { decode(root() + ("approved" to true)) }
        for (invalid in listOf(null, true, "1", 0L, 2L))
            rejects { decode(root() + ("formatVersion" to invalid)) }
        for (invalid in listOf(null, true, "[]", mapOf("review" to review())))
            rejects { decode(root() + ("reviews" to invalid)) }
    }

    @Test fun manifestDigestMustBeLowercaseAndMatchEveryEmbeddedReview() {
        for (invalid in listOf(null, "", "a".repeat(63), "A".repeat(64), "g".repeat(64)))
            rejects { decode(root() + ("manifestSha256" to invalid)) }
        rejects { decode(root(listOf(review(), review("another") + ("manifestSha256" to "d".repeat(64))))) }
    }

    @Test fun duplicateRegionIdsAndMoreThan512EntriesAreRejected() {
        rejects { decode(root(listOf(review(), review()))) }
        rejects { decode(root(List(513) { null })) }
    }

    @Test fun laterInvalidReviewRejectsTheWholeInventoryInsteadOfReturningAPrefix() {
        val valid = review()
        val next = review("synthetic-next")
        for (invalid in listOf(null, "not-a-review", next - "evidenceSha256", next + ("extra" to true),
            next + ("verifiedAt" to 3_001L)))
            rejects { decode(root(listOf(valid, invalid))) }
    }

    @Test fun nestedDuplicateKeysAndNonintegerThresholdsAreNotNormalizedAway() {
        val bytes = StrictJson.stringify(root(listOf(review())))
        rejectsText(bytes.replace("\"verifiedAt\":2000", "\"verifiedAt\":2000,\"verifiedAt\":2000"))
        rejectsText(bytes.replace("\"boundaryMarginMeters\":5", "\"boundaryMarginMeters\":5.0"))
        rejectsText(bytes.replace("\"boundaryMarginMeters\":5", "\"boundaryMarginMeters\":5e0"))
        rejectsText(bytes.replace("\"reviews\":", "\"reviews\":[],\"reviews\":"))
    }

    @Test fun malformedUtf8EmptyOversizeAndNonObjectInputsUseFixedErrors() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0xc3.toByte(), 0x28),
            ByteArray(ReviewedRegionPolicyBundleCodec.MAX_BYTES + 1), "[]".toByteArray(), "PRIVATE_PAYLOAD".toByteArray()))
            rejects { ReviewedRegionPolicyBundleCodec.decode(bytes, RegionDatasetManifestCodec.sha256(bytes), 3_000) }
        val valid = StrictJson.encode(root())
        rejects { ReviewedRegionPolicyBundleCodec.decode(valid, RegionDatasetManifestCodec.sha256(valid), -1) }
    }

    @Test fun decodedInventoryDoesNotRetainMutableInputAndCannotBeEdited() {
        val raw = StrictJson.encode(root(listOf(review())))
        val value = ReviewedRegionPolicyBundleCodec.decode(raw, RegionDatasetManifestCodec.sha256(raw), 3_000)
        raw.fill(0)
        assertEquals("synthetic-r1", value.reviews.single().regionRevisionId)
        assertThrows(UnsupportedOperationException::class.java) { (value.reviews as MutableList<RegionPolicyReview>).clear() }
        val mutable = mutableListOf(value.reviews.single())
        val copied = RegionPolicyBundle("f".repeat(64), "a".repeat(64), mutable)
        mutable.clear()
        assertEquals(1, copied.reviews.size)
    }

    private fun decode(value: Map<String, Any?>): RegionPolicyBundle {
        val bytes = StrictJson.encode(value)
        return ReviewedRegionPolicyBundleCodec.decode(bytes, RegionDatasetManifestCodec.sha256(bytes), 3_000)
    }
    private fun rejectsText(value: String) {
        val bytes = value.toByteArray()
        rejects { ReviewedRegionPolicyBundleCodec.decode(bytes, RegionDatasetManifestCodec.sha256(bytes), 3_000) }
    }
    private fun rejects(action: () -> Unit) {
        try { action(); fail("invalid policy inventory accepted") }
        catch (failure: RegionPolicyException) {
            assertEquals("INVALID_REGION_POLICY_BUNDLE", failure.code)
            assertEquals(failure.code, failure.message)
        }
    }
    private fun root(reviews: List<Any?> = emptyList()): Map<String, Any?> =
        mapOf("formatVersion" to 1L, "manifestSha256" to "a".repeat(64), "reviews" to reviews)
    private fun review(id: String = "synthetic-r1"): Map<String, Any?> = mapOf(
        "formatVersion" to 1L, "regionRevisionId" to id, "manifestSha256" to "a".repeat(64),
        "geometrySha256" to "b".repeat(64), "verifiedAt" to 2_000L,
        "reviewBasis" to "SYNTHETIC TEST ONLY; no real policy approval", "evidenceSha256" to "c".repeat(64),
        "policy" to mapOf("datasetVersion" to "synthetic-dataset", "consecutiveSamples" to 2L,
            "minConfirmationDurationMs" to 500L, "boundaryMarginMeters" to 5L,
            "qualityAdapterVersion" to "synthetic-quality", "coordinateReferenceSystem" to "EPSG:4326"),
    )
}
