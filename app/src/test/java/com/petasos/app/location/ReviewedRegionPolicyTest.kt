package com.petasos.app.location

import com.petasos.app.generation.StrictJson
import org.junit.Assert.*
import org.junit.Test

/** Public synthetic metadata only. These thresholds are not a reviewed policy for any real region. */
class ReviewedRegionPolicyTest {
    @Test fun exactReviewDecodesWithoutAddingPolicyDefaults() {
        val review = decode()
        assertEquals("synthetic-r1", review.regionRevisionId)
        assertEquals("synthetic-dataset", review.policy.datasetVersion)
        assertEquals(3, review.policy.consecutiveSamples)
        assertEquals(2_000L, review.policy.minConfirmationDurationMs)
        assertEquals(5.0, review.policy.boundaryMarginMeters, 0.0)
        assertEquals("synthetic-quality-only", review.policy.qualityAdapterVersion)
        assertEquals(2_000L, review.verifiedAt)
        assertEquals("RegionPolicyReview([redacted])", review.toString())
        assertEquals(manifest().regions.single(), ReviewedRegionPolicyCodec.validateAgainstManifest(manifest(), review, 3_000))
    }

    @Test fun missingOrAdditionalRootPolicyAndReviewKeysAreRejected() {
        root().keys.forEach { key -> rejects { decode(root() - key) } }
        policy().keys.forEach { key -> rejects { decode(root() + ("policy" to (policy() - key))) } }
        rejects { decode(root() + ("unreviewed" to true)) }
        rejects { decode(root() + ("policy" to (policy() + ("policyVersion" to "guessed")))) }
        val stored = stored()
        rejects { storedDecode(stored + ("extra" to true)) }
        val metadata = stored["review"] as Map<*, *>
        metadata.keys.forEach { key -> rejects { storedDecode(stored + ("review" to (metadata - key))) } }
        rejects { storedDecode(stored + ("review" to (metadata + ("reviewer" to "not-in-contract")))) }
    }

    @Test fun rootVersionMustBeIntegerOne() {
        listOf(null, false, "1", 0L, 2L, Long.MAX_VALUE).forEach { version ->
            rejects { decode(root() + ("formatVersion" to version)) }
        }
    }

    @Test fun repeatedKeysAtEveryLevelFailClosed() {
        val valid = StrictJson.stringify(root())
        rejects { decodeText(valid.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1")) }
        rejects { decodeText(valid.replace("\"consecutiveSamples\":3", "\"consecutiveSamples\":3,\"consecutiveSamples\":3")) }
        val persisted = StrictJson.stringify(stored())
        rejects { ReviewedRegionPolicyCodec.decodeStored(persisted.replace("\"verifiedAt\":2000",
            "\"verifiedAt\":2000,\"verifiedAt\":2000").toByteArray(), "synthetic-r1", 3_000) }
    }

    @Test fun integerFieldsRejectCoercionFractionsExponentsAndOverflow() {
        for (key in listOf("consecutiveSamples", "minConfirmationDurationMs", "boundaryMarginMeters")) {
            listOf(null, true, "3").forEach { invalid -> rejects { decodePolicy(key, invalid) } }
        }
        val valid = StrictJson.stringify(root())
        for (value in listOf("5.0", "5e0", "9223372036854775808", "NaN", "Infinity"))
            rejects { decodeText(valid.replace("\"boundaryMarginMeters\":5", "\"boundaryMarginMeters\":$value")) }
        listOf(null, true, "2000").forEach { rejects { decode(root() + ("verifiedAt" to it)) } }
    }

    @Test fun sampleBoundsAndNegativeThresholdsAreRejectedBeforeNarrowing() {
        listOf(-1L, 0L, 1L, 101L, Long.MAX_VALUE).forEach { rejects { decodePolicy("consecutiveSamples", it) } }
        rejects { decodePolicy("minConfirmationDurationMs", -1L) }
        rejects { decodePolicy("boundaryMarginMeters", -1L) }
        assertEquals(2, decodePolicy("consecutiveSamples", 2L).policy.consecutiveSamples)
        assertEquals(100, decodePolicy("consecutiveSamples", 100L).policy.consecutiveSamples)
        assertEquals(0L, decodePolicy("minConfirmationDurationMs", 0L).policy.minConfirmationDurationMs)
        assertEquals(0.0, decodePolicy("boundaryMarginMeters", 0L).policy.boundaryMarginMeters, 0.0)
    }

    @Test fun integerMarginMustSurviveTheExistingGeometricDoubleDomainExactly() {
        rejects { decodePolicy("boundaryMarginMeters", 9_007_199_254_740_993L) }
        rejects { decodePolicy("boundaryMarginMeters", Long.MAX_VALUE) }
        val exactlyRepresentable = 9_007_199_254_740_994L
        val review = decodePolicy("boundaryMarginMeters", exactlyRepresentable)
        val persisted = ReviewedRegionPolicyCodec.storedPolicyJson(manifest(), manifest().regions.single(), review)
        assertEquals(exactlyRepresentable, (StrictJson.parse(persisted.toByteArray()) as Map<*, *>)["boundaryMarginMeters"])
        assertEquals(review, ReviewedRegionPolicyCodec.decodeStored(persisted.toByteArray(), "synthetic-r1", 3_000))
    }

    @Test fun timestampMustBeNonnegativeAndNotInTheFuture() {
        rejects { decode(root() + ("verifiedAt" to -1L)) }
        rejects { decode(root() + ("verifiedAt" to 3_001L)) }
        rejects { ReviewedRegionPolicyCodec.decode(StrictJson.encode(root()), -1) }
        assertEquals(0L, decode(root() + ("verifiedAt" to 0L)).verifiedAt)
        assertEquals(3_000L, decode(root() + ("verifiedAt" to 3_000L)).verifiedAt)
    }

    @Test fun identifiersAndHashPinsCannotBeBlankMalformedOrWrongCase() {
        listOf("", " ", "../revision", "한글", "a".repeat(129)).forEach {
            rejects { decode(root() + ("regionRevisionId" to it)) }
        }
        for (field in listOf("manifestSha256", "geometrySha256", "evidenceSha256")) {
            listOf("", "a".repeat(63), "A".repeat(64), "g".repeat(64), null).forEach {
                rejects { decode(root() + (field to it)) }
            }
        }
        rejects { decodePolicy("datasetVersion", "arbitrary version with spaces") }
        rejects { decodePolicy("coordinateReferenceSystem", "EPSG:5179") }
    }

    @Test fun reviewTextIsBoundedAndCannotContainControlsOrMalformedUnicode() {
        listOf("", " ", "x".repeat(4001), "hidden\u0000payload", "\uD800").forEach {
            if (it == "\uD800") {
                val text = StrictJson.stringify(root()).replace("Synthetic boundary review only", "\\ud800")
                rejects { decodeText(text) }
            } else rejects { decode(root() + ("reviewBasis" to it)) }
        }
        listOf("", " ", "x".repeat(201), "bad\u007fadapter").forEach { rejects { decodePolicy("qualityAdapterVersion", it) } }
        assertEquals("line one\nline two", decode(root() + ("reviewBasis" to "line one\nline two")).reviewBasis)
    }

    @Test fun malformedUtf8OversizeAndNonObjectsUseOneFixedFailureCode() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), ByteArray(ReviewedRegionPolicyCodec.MAX_BYTES + 1),
            "[]".toByteArray(), "null".toByteArray(), "PRIVATE_BAD_PAYLOAD".toByteArray()))
            rejects { ReviewedRegionPolicyCodec.decode(bytes, 3_000) }
    }

    @Test fun directlyConstructedDtosReceiveTheSameValidation() {
        val valid = decode()
        rejects { ReviewedRegionPolicyCodec.validate(valid.copy(reviewBasis = ""), 3_000) }
        rejects { ReviewedRegionPolicyCodec.validate(valid.copy(verifiedAt = 3_001), 3_000) }
        rejects { ReviewedRegionPolicyCodec.validate(valid.copy(policy = valid.policy.copy(boundaryMarginMeters = 1.5)), 3_000) }
        rejects { ReviewedRegionPolicyCodec.validate(valid.copy(policy = valid.policy.copy(boundaryMarginMeters = -0.0)), 3_000) }
        rejects { ReviewedRegionPolicyCodec.validate(valid.copy(policy = valid.policy.copy(boundaryMarginMeters = Long.MAX_VALUE.toDouble())), 3_000) }
        rejects { ReviewedRegionPolicyCodec.validate(valid.copy(reviewBasis = "\uD800"), 3_000) }
    }

    @Test fun manifestLinkageRequiresExactReadyRevisionAndHashes() {
        val manifest = manifest()
        val valid = decode()
        for (review in listOf(valid.copy(regionRevisionId = "another-r1"), valid.copy(manifestSha256 = "d".repeat(64)),
            valid.copy(geometrySha256 = "d".repeat(64)), valid.copy(policy = valid.policy.copy(datasetVersion = "another-dataset"))))
            rejects { ReviewedRegionPolicyCodec.validateAgainstManifest(manifest, review, 3_000) }
        val quarantined = changedManifest(entry = manifest.regions.single().copy(status = "quarantined", reasonCode = "INVALID_TOPOLOGY"))
        rejects { ReviewedRegionPolicyCodec.validateAgainstManifest(quarantined, valid, 3_000) }
        val duplicate = RegionDatasetManifest(manifest.fingerprint, manifest.datasetVersion, manifest.source,
            manifest.transform, manifest.regions + manifest.regions)
        rejects { ReviewedRegionPolicyCodec.validateAgainstManifest(duplicate, valid, 3_000) }
    }

    @Test fun policyReviewCannotPredateItsGeometrySourceReview() {
        val valid = decode()
        rejects { ReviewedRegionPolicyCodec.validateAgainstManifest(changedManifest(sourceTime = -1), valid, 3_000) }
        rejects { ReviewedRegionPolicyCodec.validateAgainstManifest(changedManifest(sourceTime = 2_001), valid, 3_000) }
        assertEquals("synthetic-r1", ReviewedRegionPolicyCodec.validateAgainstManifest(changedManifest(sourceTime = 2_000), valid, 3_000).regionRevisionId)
    }

    @Test fun storedEnvelopeKeepsGeometryAndExactReviewAndRoundTrips() {
        val manifest = manifest()
        val entry = manifest.regions.single()
        val review = decode()
        val json = ReviewedRegionPolicyCodec.storedPolicyJson(manifest, entry, review)
        val stored = StrictJson.parse(json.toByteArray()) as Map<*, *>
        assertEquals(18, stored.size)
        assertEquals("verified", stored["policyStatus"])
        assertTrue(RegionGeometryMetadata.matchesPolicyGeometry(stored, manifest, entry))
        assertEquals(mapOf("formatVersion" to 1L, "verifiedAt" to 2_000L,
            "reviewBasis" to "Synthetic boundary review only", "evidenceSha256" to "c".repeat(64)), stored["review"])
        assertEquals(review, ReviewedRegionPolicyCodec.decodeStored(json.toByteArray(), entry.regionRevisionId, 3_000))
        assertEquals(json, ReviewedRegionPolicyCodec.storedPolicyJson(manifest, entry, review))
    }

    @Test fun writerRejectsAnEntryOtherThanTheManifestEntry() {
        val manifest = manifest()
        val entry = manifest.regions.single().copy(name = "Not the reviewed entry")
        rejects { ReviewedRegionPolicyCodec.storedPolicyJson(manifest, entry, decode()) }
    }

    @Test fun strictStoredReaderCannotAcceptPendingLegacyOrIncompleteMarkedPolicies() {
        val manifest = manifest()
        rejects { storedDecode(RegionGeometryMetadata.pendingPolicy(manifest, manifest.regions.single())) }
        rejects { storedDecode(policy()) }
        stored().keys.forEach { rejects { storedDecode(stored() - it) } }
        rejects { storedDecode(stored() + ("policyStatus" to "pending")) }
        rejects { storedDecode(stored() + ("policyStatus" to "rejected")) }
    }

    @Test fun markedEnvelopeMustKeepItsOwnValidMetadataAndClock() {
        val stored = stored()
        val metadata = stored["review"] as Map<*, *>
        rejects { storedDecode(stored + ("review" to (metadata + ("formatVersion" to 2L)))) }
        rejects { storedDecode(stored + ("review" to (metadata + ("verifiedAt" to 3_001L)))) }
        rejects { storedDecode(stored + ("review" to (metadata + ("evidenceSha256" to "bad")))) }
        rejects { ReviewedRegionPolicyCodec.decodeStored(StrictJson.encode(stored), "", 3_000) }
    }

    @Test fun markedEnvelopeRejectsInvalidGeometryRepresentation() {
        val bad = listOf(
            "sourceCoordinateReferenceSystem" to "EPSG:4326", "geometryStatus" to "quarantined",
            "geometryReasonCode" to "INVALID_TOPOLOGY", "geometryAsset" to "regions/../hidden",
            "geometrySha256" to "bad", "manifestSha256" to "bad", "geometryBytes" to 0L,
            "geometryBytes" to (PackedRegionBoundaryLookup.MAX_BYTES + 1), "geometryBytes" to "132",
            "transformLibraryVersion" to "", "transformOperation" to " ",
            "transformExpectedAccuracyMeters" to "NaN", "transformExpectedAccuracyMeters" to "1e0",
            "transformExpectedAccuracyMeters" to "-1", "transformExpectedAccuracyMeters" to "101",
            "transformExpectedAccuracyMeters" to 1L,
        )
        bad.forEach { rejects { storedDecode(stored() + it) } }
    }

    @Test fun extractedPendingJsonPreservesTheExistingBytesAndThirteenFields() {
        val manifest = manifest()
        val entry = manifest.regions.single()
        // Deliberately spell out the old RoomRegionInstaller representation as a compatibility fixture.
        val old = mapOf("policyStatus" to "pending", "datasetVersion" to "synthetic-dataset",
            "coordinateReferenceSystem" to "EPSG:4326", "sourceCoordinateReferenceSystem" to "EPSG:5179",
            "manifestSha256" to "a".repeat(64), "geometryAsset" to "regions/synthetic/99999.pgeo",
            "geometrySha256" to "b".repeat(64), "geometryBytes" to 132L, "geometryStatus" to "ready",
            "geometryReasonCode" to null, "transformLibraryVersion" to "synthetic-library",
            "transformOperation" to "Synthetic transform only", "transformExpectedAccuracyMeters" to "1.0")
        assertEquals(old, RegionGeometryMetadata.pendingPolicy(manifest, entry))
        assertEquals(13, old.size)
        assertEquals(StrictJson.stringify(old), RegionGeometryMetadata.pendingPolicyJson(manifest, entry))
    }

    @Test fun geometryComparisonIsIndependentOfReviewButCannotIgnoreMissingNullFields() {
        val manifest = manifest()
        val entry = manifest.regions.single()
        val pending = RegionGeometryMetadata.pendingPolicy(manifest, entry)
        assertTrue(RegionGeometryMetadata.matchesPolicyGeometry(pending, manifest, entry))
        assertTrue(RegionGeometryMetadata.matchesPolicyGeometry(stored(), manifest, entry))
        assertTrue(RegionGeometryMetadata.matchesPolicyGeometry(pending + ("policyStatus" to "revoked"), manifest, entry))
        assertFalse(RegionGeometryMetadata.matchesPolicyGeometry(pending - "geometryReasonCode", manifest, entry))
        for (key in RegionGeometryMetadata.geometryKeys)
            assertFalse(RegionGeometryMetadata.matchesPolicyGeometry(pending + (key to "tampered"), manifest, entry))
    }

    @Test fun revisionIdentityContainsOnlyExistingImmutableColumns() {
        val manifest = manifest()
        assertEquals(mapOf("region_revision_id" to "synthetic-r1", "region_id" to "synthetic-region",
            "code" to "99999", "dataset_version" to "synthetic-dataset", "name" to "SYNTHETIC ONLY",
            "source_revision_id" to "synthetic-source-r1"), RegionGeometryMetadata.revisionIdentity(manifest, manifest.regions.single()))
        val quarantined = manifest.regions.single().copy(status = "quarantined", reasonCode = "INVALID_TOPOLOGY")
        val pending = RegionGeometryMetadata.pendingPolicy(manifest, quarantined)
        assertEquals("pending", pending["policyStatus"])
        assertEquals("quarantined", pending["geometryStatus"])
        assertEquals("INVALID_TOPOLOGY", pending["geometryReasonCode"])
    }

    private fun decode(value: Map<String, Any?> = root()) = ReviewedRegionPolicyCodec.decode(StrictJson.encode(value), 3_000)
    private fun decodeText(value: String) = ReviewedRegionPolicyCodec.decode(value.toByteArray(), 3_000)
    private fun decodePolicy(key: String, value: Any?) = decode(root() + ("policy" to (policy() + (key to value))))
    private fun stored(): Map<String, Any?> {
        val manifest = manifest()
        @Suppress("UNCHECKED_CAST")
        return StrictJson.parse(ReviewedRegionPolicyCodec.storedPolicyJson(manifest, manifest.regions.single(), decode()).toByteArray()) as Map<String, Any?>
    }
    private fun storedDecode(value: Map<String, Any?>) = ReviewedRegionPolicyCodec.decodeStored(StrictJson.encode(value), "synthetic-r1", 3_000)
    private fun rejects(action: () -> Unit) {
        try { action(); fail("invalid region policy accepted") }
        catch (failure: RegionPolicyException) { assertEquals("INVALID_REGION_POLICY", failure.code); assertEquals(failure.code, failure.message) }
    }
    private fun root(): Map<String, Any?> = mapOf("formatVersion" to 1L, "regionRevisionId" to "synthetic-r1",
        "manifestSha256" to "a".repeat(64), "geometrySha256" to "b".repeat(64), "policy" to policy(),
        "verifiedAt" to 2_000L, "reviewBasis" to "Synthetic boundary review only", "evidenceSha256" to "c".repeat(64))
    private fun policy(): Map<String, Any?> = mapOf("datasetVersion" to "synthetic-dataset", "consecutiveSamples" to 3L,
        "minConfirmationDurationMs" to 2_000L, "boundaryMarginMeters" to 5L, "qualityAdapterVersion" to "synthetic-quality-only",
        "coordinateReferenceSystem" to "EPSG:4326")
    private fun manifest() = RegionDatasetManifest("a".repeat(64), "synthetic-dataset",
        RegionDatasetSource("synthetic-source-r1", "synthetic-source", "synthetic", "https://example.invalid/source",
            "SYNTHETIC ONLY", 500, "Source effective date unknown", 1_000, "d".repeat(64),
            "https://creativecommons.org/licenses/by/2.0/kr/", "Synthetic attribution", "Synthetic source review only"),
        RegionDatasetTransform("synthetic-library", "Synthetic transform only", 1.0, "1.0"),
        listOf(RegionDatasetEntry("synthetic-region", "synthetic-r1", "99999", "SYNTHETIC ONLY",
            "regions/synthetic/99999.pgeo", "b".repeat(64), 132, 1, 5,
            PackedRegionBounds(127.0, 37.0, 128.0, 38.0), "ready", null)))
    private fun changedManifest(sourceTime: Long = 1_000, entry: RegionDatasetEntry = manifest().regions.single()): RegionDatasetManifest {
        val original = manifest()
        return RegionDatasetManifest(original.fingerprint, original.datasetVersion,
            original.source.copy(verifiedAt = sourceTime), original.transform, listOf(entry))
    }
}
