package com.petasos.app.location

import com.petasos.app.generation.StrictJson
import org.junit.Assert.*
import org.junit.Test

class RegionDatasetManifestTest {
    @Test fun reviewedPinDecodesReadyGeometryWithoutGrantingPolicy() {
        val value = decode(fixture())
        assertEquals("test-region-dataset-v1", value.datasetVersion)
        assertEquals("ready", value.regions.single().status)
        assertEquals(1.0, value.transform.expectedAccuracyMeters, 0.0)
        assertEquals("DBF metadata only; effective date unknown", value.source.sourceUpdatedRaw)
    }

    @Test fun unknownReviewPinAndModifiedBytesFail() {
        val bytes = StrictJson.encode(fixture())
        rejects { RegionDatasetManifestCodec.decode(bytes, "0".repeat(64)) }
        rejects { RegionDatasetManifestCodec.decode(bytes + byteArrayOf(32), RegionDatasetManifestCodec.sha256(bytes)) }
    }

    @Test fun unknownFieldsAndWrongCoordinateSystemsFail() {
        rejects { decode(fixture() + ("extra" to true)) }
        rejects { decode(fixture() + ("coordinateReferenceSystem" to "EPSG:5179")) }
        rejects { decode(fixture() + ("sourceCoordinateReferenceSystem" to "EPSG:4326")) }
        rejects { decode(fixture() + ("policyStatus" to "verified")) }
    }

    @Test fun unsafeAssetAndSourceUrlsFail() {
        for (asset in listOf("regions/../geometry.bin", "/regions/data/geometry.bin", "regions/data/../geometry.bin", "https://example.invalid/geometry"))
            rejects { decode(withRegion("geometryAsset" to asset)) }
        val original = fixture()
        @Suppress("UNCHECKED_CAST") val source = original["source"] as Map<String, Any?>
        rejects { decode(original + ("source" to (source + ("sourceUrl" to "https://example.invalid/data?api_key=secret")))) }
        rejects { decode(original + ("source" to (source + ("licenseUrl" to "https://example.invalid/license")))) }
    }

    @Test fun readyAndQuarantineCannotChangeMeaningSilently() {
        rejects { decode(withRegion("reasonCode" to "INVALID_TOPOLOGY")) }
        rejects { decode(withRegion("status" to "quarantined")) }
        rejects { decode(withRegion("status" to "quarantined", "reasonCode" to "UNKNOWN")) }
        val value = decode(withRegion("status" to "quarantined", "reasonCode" to "INVALID_TOPOLOGY"))
        assertEquals("quarantined", value.regions.single().status)
    }

    @Test fun countLengthAndCoordinateLimitsAreEnforcedBeforeAnyGeometryRead() {
        rejects { decode(withRegion("geometryBytes" to 131L)) }
        rejects { decode(withRegion("ringCount" to 0L)) }
        rejects { decode(withRegion("pointCount" to 3L)) }
        rejects { decode(withRegion("bounds" to listOf("127", "37", "126", "38"))) }
        rejects { decode(withRegion("bounds" to listOf("119", "37", "128", "38"))) }
        rejects { decode(withRegion("bounds" to listOf("127", "37", "1.28e2", "38"))) }
        rejects { decode(withRegion("bounds" to listOf(127L, 37L, 128L, 38L))) }
    }

    @Test fun duplicateCodesIdsAndAssetNamesFail() {
        val row = region()
        rejects { decode(fixture() + ("regions" to listOf(row, row))) }
        rejects { decode(fixture() + ("regions" to listOf(row,
            row + mapOf("regionId" to "another", "regionRevisionId" to "another-r1", "geometryAsset" to "regions/test/another.bin")))) }
    }

    @Test fun transformationUncertaintyMustBeFiniteNonnegativeDecimalString() {
        @Suppress("UNCHECKED_CAST") val original = fixture()["transform"] as Map<String, Any?>
        for (accuracy in listOf("-1", "NaN", "1e0", "101", 1L))
            rejects { decode(fixture() + ("transform" to (original + ("expectedAccuracyMeters" to accuracy)))) }
    }

    @Test fun listsAndDebugTextDoNotExposeOrAllowChangingReviewedManifest() {
        val value = decode(fixture())
        try { (value.regions as MutableList<RegionDatasetEntry>).clear(); fail("manifest became mutable") }
        catch (_: UnsupportedOperationException) { }
        assertEquals("RegionDatasetManifest([redacted])", value.toString())
        assertEquals("RegionDatasetEntry([redacted])", value.regions.single().toString())
    }

    @Test fun oversizedManifestAndMalformedDataFailWithFixedCode() {
        rejects { RegionDatasetManifestCodec.decode(ByteArray(RegionDatasetManifestCodec.MAX_MANIFEST_BYTES + 1), "0".repeat(64)) }
        val bytes = "private invalid payload".toByteArray()
        rejects { RegionDatasetManifestCodec.decode(bytes, RegionDatasetManifestCodec.sha256(bytes)) }
    }

    private fun decode(value: Map<String, Any?>): RegionDatasetManifest {
        val bytes = StrictJson.encode(value)
        return RegionDatasetManifestCodec.decode(bytes, RegionDatasetManifestCodec.sha256(bytes))
    }
    private fun rejects(action: () -> Unit) {
        try { action(); fail("invalid manifest accepted") }
        catch (failure: RegionDatasetException) { assertEquals("INVALID_REGION_DATASET", failure.code) }
    }
    private fun withRegion(vararg fields: Pair<String, Any?>): Map<String, Any?> = fixture() + ("regions" to listOf(region() + fields))
    private fun region(): Map<String, Any?> = mapOf("regionId" to "test-region", "regionRevisionId" to "test-region-r1",
        "code" to "11650", "name" to "SYNTHETIC TEST ONLY", "geometryAsset" to "regions/test/11650.bin",
        "geometrySha256" to "1".repeat(64), "geometryBytes" to 132L, "ringCount" to 1L, "pointCount" to 5L,
        "bounds" to listOf("127", "37", "128", "38"), "status" to "ready", "reasonCode" to null)
    private fun fixture(): Map<String, Any?> = mapOf("formatVersion" to 1L, "datasetVersion" to "test-region-dataset-v1",
        "coordinateReferenceSystem" to "EPSG:4326", "sourceCoordinateReferenceSystem" to "EPSG:5179", "policyStatus" to "pending",
        "transform" to mapOf("libraryVersion" to "test-only", "operation" to "Synthetic fixture only", "expectedAccuracyMeters" to "1"),
        "source" to mapOf("sourceRevisionId" to "test-region-source-r1", "sourceId" to "test-region-source", "provider" to "synthetic",
            "sourceUrl" to "https://example.invalid/geometry", "title" to "SYNTHETIC TEST ONLY", "fetchedAt" to 1L,
            "sourceUpdatedRaw" to "DBF metadata only; effective date unknown", "verifiedAt" to 1L, "contentHash" to "2".repeat(64),
            "licenseUrl" to "https://creativecommons.org/licenses/by/2.0/kr/", "attribution" to "Synthetic fixture only",
            "reviewBasis" to "Synthetic metadata; no actual source rights claimed"), "regions" to listOf(region()))
}
