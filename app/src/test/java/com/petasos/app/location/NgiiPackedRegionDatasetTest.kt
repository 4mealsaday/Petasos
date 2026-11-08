package com.petasos.app.location

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression against the actual reviewed national dataset, not a small synthetic substitute.
 * The compact oracle records GEOS representative_point results from the offline compiler audit;
 * JVM containment is independently implemented and each ready binary is consumed in full.
 * Geometry remains on disk throughout this test, including Shinan's 2.4 million coordinates.
 */
class NgiiPackedRegionDatasetTest {
    @Test fun reviewedFiveDistrictsSelectOneLeafWithoutDeletingRawCityGeometry() {
        val manifest = manifest()
        val hierarchy = ReviewedRegionHierarchyCodec.decode(File(assetsRoot, "regions/reviewed-hierarchy-v1.json").readBytes(),
            "cf428d289dd504b3b8d1d481ea4e80f789a1db7e531ebd06ba749422a3971e5b", manifest)
        val lookup = catalogLookup(manifest)
        val wrapped = HierarchicalRegionBoundaryLookup(lookup, hierarchy)
        val oracle = oracle()
        val expected = mapOf("41461" to "41460", "41463" to "41460", "41465" to "41460", "44131" to "44130", "44133" to "44130")
        for ((child, parent) in expected) {
            val point = oracle.getValue(child)
            val childEntry = manifest.regions.single { it.code == child }
            val parentEntry = manifest.regions.single { it.code == parent }
            val raw = lookup.containing(point.latitude, point.longitude)
            assertEquals(child, setOf(childEntry.regionRevisionId, parentEntry.regionRevisionId), raw.map { it.regionRevisionId }.toSet())
            assertEquals(child, childEntry.regionRevisionId, wrapped.containing(point.latitude, point.longitude).single().regionRevisionId)
            assertEquals(listOf(childEntry.regionId, parentEntry.regionId), hierarchy.scope(childEntry.regionId).eligibleStoryRegionIds)
        }
        val unknown = oracle.getValue("41111")
        assertTrue(wrapped.containing(unknown.latitude, unknown.longitude).isEmpty()) // Suwon is deliberately not reviewed here.
    }

    @Test fun hierarchyCatalogRejectsWrongPinsNamesAndUnreadyRevision() {
        val manifest = manifest()
        val bytes = File(assetsRoot, "regions/reviewed-hierarchy-v1.json").readBytes()
        assertThrows(RegionDatasetException::class.java) { ReviewedRegionHierarchyCodec.decode(bytes, "0".repeat(64), manifest) }
        val changed = bytes.toString(Charsets.UTF_8).replace("\"처인구\"", "\"다른구\"").toByteArray()
        assertThrows(RegionDatasetException::class.java) {
            ReviewedRegionHierarchyCodec.decode(changed, RegionDatasetManifestCodec.sha256(changed), manifest)
        }
        val unready = RegionDatasetManifest(manifest.fingerprint, manifest.datasetVersion, manifest.source, manifest.transform,
            manifest.regions.map { if (it.code == "41461") it.copy(status = "quarantined", reasonCode = "INVALID_TOPOLOGY") else it })
        assertThrows(RegionDatasetException::class.java) {
            ReviewedRegionHierarchyCodec.decode(bytes, RegionDatasetManifestCodec.sha256(bytes), unready)
        }
    }

    @Test fun reviewedNationalInventoryRetainsEveryCodeAndThreeQuarantines() {
        val manifest = manifest()
        val oracle = oracle()
        assertEquals("ngii-d7a3751bd89f-wgs84-stream-v1", manifest.datasetVersion)
        assertEquals(SOURCE_SHA256, manifest.source.contentHash)
        assertEquals(1.0, manifest.transform.expectedAccuracyMeters, 0.0)
        assertEquals(264, oracle.size)
        assertEquals(264, manifest.regions.size)
        assertEquals(261, manifest.regions.count { it.status == "ready" })
        assertEquals(setOf("30140", "36110", "46110"),
            manifest.regions.filter { it.status == "quarantined" }.map { it.code }.toSet())
        assertEquals(9_994_396L, manifest.regions.sumOf { it.pointCount.toLong() })
        assertEquals(34_045, manifest.regions.sumOf { it.ringCount })
        assertEquals(161_140_180L, manifest.regions.sumOf { it.geometryBytes })
        assertEquals(mapOf("HJD005" to 78, "HJD006" to 82, "HJD007" to 104),
            oracle.values.groupingBy { it.classification }.eachCount())
        assertEquals(oracle.keys, manifest.regions.map { it.code }.toSet())
        assertEquals(manifest.regions.map { "${it.code}.pgeo" }.toSet(),
            File(assetsRoot, DATASET_DIRECTORY).listFiles()!!.filter { it.extension == "pgeo" }.map { it.name }.toSet())
        for (entry in manifest.regions) {
            val expected = oracle.getValue(entry.code)
            assertEquals(entry.code, expected.status, entry.status)
            assertEquals(entry.code, expected.ringCount, entry.ringCount)
            assertEquals(entry.code, expected.pointCount, entry.pointCount)
            assertEquals(entry.code, "kr-sigungu-${entry.code}", entry.regionId)
            assertEquals(entry.code, "${entry.regionId}-${manifest.datasetVersion}", entry.regionRevisionId)
            assertEquals(entry.code, entry.geometryBytes, File(assetsRoot, entry.geometryAsset).length())
            if (entry.status == "quarantined") assertEquals("INVALID_TOPOLOGY", entry.reasonCode)
            else assertNull(entry.reasonCode)
        }
    }

    @Test fun all261ReadyRegionsMatchIndependentInteriorOracleAfterFullStreamingValidation() {
        val manifest = manifest()
        val oracle = oracle()
        var scannedPoints = 0L
        var scannedRings = 0
        var validatedRegions = 0
        for (entry in manifest.regions.filter { it.status == "ready" }) {
            val point = oracle.getValue(entry.code)
            var opened = 0
            lateinit var stream: ObservedStream
            val lookup = PackedRegionBoundaryLookup(listOf(entry.metadata(manifest)), open = {
                opened++
                ObservedStream(File(assetsRoot, entry.geometryAsset).inputStream()).also { stream = it }
            })
            val matches = lookup.containing(point.latitude, point.longitude)
            assertEquals("Independent interior oracle failed for code ${entry.code}", 1, matches.size)
            val result = matches.single()
            assertEquals(entry.regionRevisionId, result.regionRevisionId)
            assertEquals(manifest.datasetVersion, result.datasetVersion)
            assertEquals("EPSG:4326", result.coordinateReferenceSystem)
            assertTrue("Invalid interior clearance for ${entry.code}",
                result.interiorDistanceMeters.isFinite() && result.interiorDistanceMeters > 0.0)
            assertEquals(entry.code, 1, opened)
            assertEquals("Full binary must be checked for ${entry.code}", entry.geometryBytes, stream.bytesRead)
            assertTrue("Binary must close for ${entry.code}", stream.closed)
            assertTrue("Only fixed-size read buffers are allowed", stream.largestReadRequest <= 8192)
            scannedPoints += entry.pointCount
            scannedRings += entry.ringCount
            validatedRegions++
        }
        assertEquals(261, validatedRegions)
        assertEquals(9_975_147L, scannedPoints)
        assertEquals(33_990, scannedRings)
    }

    @Test fun actualSuwonCityAndConstituentDistrictRemainAmbiguous() {
        val manifest = manifest()
        val point = oracle().getValue("41111") // GEOS interior of Jangan-gu, within Suwon.
        val lookup = catalogLookup(manifest)
        val codesByRevision = manifest.regions.associate { it.regionRevisionId to it.code }
        val matches = lookup.containing(point.latitude, point.longitude)
        assertEquals(setOf("41110", "41111"), matches.map { codesByRevision.getValue(it.regionRevisionId) }.toSet())
        assertTrue(matches.all { it.interiorDistanceMeters > 0.0 })
    }

    @Test fun eachActualQuarantineWithholdsCatalogMatchesBeforeOpeningAnyBinary() {
        val manifest = manifest()
        val oracle = oracle()
        val lookup = catalogLookup(manifest, open = { throw AssertionError("Quarantine must withhold before any open") })
        for (entry in manifest.regions.filter { it.status == "quarantined" }) {
            val point = oracle.getValue(entry.code)
            assertTrue(entry.code, lookup.containing(point.latitude, point.longitude).isEmpty())
        }
    }

    private fun catalogLookup(
        manifest: RegionDatasetManifest,
        open: ((PackedRegionBoundaryMetadata) -> InputStream)? = null,
    ): PackedRegionBoundaryLookup {
        val ready = manifest.regions.filter { it.status == "ready" }
        val assets = ready.associate { it.regionRevisionId to it.geometryAsset }
        return PackedRegionBoundaryLookup(ready.map { it.metadata(manifest) },
            open = open ?: { File(assetsRoot, assets.getValue(it.regionRevisionId)).inputStream() },
            quarantinedBounds = manifest.regions.filter { it.status == "quarantined" }.map { it.bounds })
    }

    private fun RegionDatasetEntry.metadata(manifest: RegionDatasetManifest) = PackedRegionBoundaryMetadata(
        regionRevisionId, manifest.datasetVersion, geometrySha256, geometryBytes, ringCount, pointCount, bounds,
    )

    private fun manifest(): RegionDatasetManifest = RegionDatasetManifestCodec.decode(
        File(assetsRoot, "$DATASET_DIRECTORY/manifest.json").readBytes(), MANIFEST_SHA256,
    )

    private fun oracle(): Map<String, Oracle> = checkNotNull(javaClass.getResourceAsStream(
        "/regions/ngii-d7a3751bd89f-oracle.tsv",
    )).bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val fields = line.split('\t')
            check(fields.size == 7)
            fields[0] to Oracle(fields[1], fields[2], fields[3].toInt(), fields[4].toInt(),
                fields[5].toDouble(), fields[6].toDouble())
        }.toList().also { check(it.map { row -> row.first }.toSet().size == it.size) }.toMap()
    }

    private data class Oracle(
        val status: String, val classification: String, val ringCount: Int, val pointCount: Int,
        val longitude: Double, val latitude: Double,
    )

    private class ObservedStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead = 0L
        var largestReadRequest = 0
        var closed = false
        override fun read(): Int = `in`.read().also { if (it >= 0) bytesRead++ }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            largestReadRequest = maxOf(largestReadRequest, length)
            return `in`.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
        }
        override fun close() {
            closed = true
            super.close()
        }
    }

    companion object {
        private const val DATASET_DIRECTORY = "regions/ngii-2026-09-21-v1"
        private const val SOURCE_SHA256 = "d7a3751bd89f8eabd129eff603b15a55e61afe472e5255a9202ec5a2b01fe6fa"
        private const val MANIFEST_SHA256 = "3e2117159aa65c6c8f69826f534a8065d1247497039827d1b638f77d580f1bf9"
        private val assetsRoot = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .first { File(it, "$DATASET_DIRECTORY/manifest.json").isFile }
    }
}
