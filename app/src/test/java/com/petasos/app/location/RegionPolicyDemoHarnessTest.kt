package com.petasos.app.location

import com.petasos.app.capability.location.AndroidLocationMetadata
import com.petasos.app.capability.location.LocationEvent
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.generation.StrictJson
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/**
 * Demo-policy investigation only. Real pinned administrative geometry with synthetic Android
 * observations; no policy approval, product database, network, GPS injection or route ETA claim.
 * The in-memory store mirrors the bounded same-segment proof selection; it is not a Room audit.
 */
class RegionPolicyDemoHarnessTest {
    @Test fun candidateMatrixReportsFalseCardsDelayAndMissesAgainstPinnedDemoRegions() {
        val fixture = Dataset()
        val report = mutableListOf<Map<String, Any?>>()
        var negativeTrials = 0L
        var positiveTrials = 0L
        for (count in listOf(2, 3, 5)) for (duration in listOf(0L, 1_000L, 2_000L)) for (margin in listOf(5L, 10L, 20L)) {
            val candidate = RegionPolicy(fixture.manifest.datasetVersion, count, duration, margin.toDouble(),
                AndroidLocationMeaningAdapter.VERSION, "EPSG:4326")
            var falseCards = 0L
            val failures = mutableListOf<String>()
            val perRegion = mutableListOf<Map<String, Any?>>()
            for (code in DEMO_LEAF_CODES) {
                val entry = fixture.entries.getValue(code)
                val interior = fixture.interior.getValue(code)
                assertEquals("Independent interior oracle must select the expected leaf $code", entry.regionRevisionId,
                    fixture.boundary.containing(interior.latitude, interior.longitude).single().regionRevisionId)
                val samplesByRate = mutableListOf<Map<String, Any?>>()
                for (hz in listOf(1, 2, 5)) {
                    val events = samples(interior, 6 * hz + 1, 1_000L / hz)
                    val result = run(candidate, entry.regionRevisionId, fixture.boundary, events)
                    val fixedCount = run(candidate, entry.regionRevisionId, fixture.boundary, events, fixedCountWindow = true)
                    positiveTrials++
                    // This fixture has sufficient true residence time and all samples are deep
                    // inside. A miss is reported, not hidden by lowering the candidate duration.
                    samplesByRate += linkedMapOf("hz" to hz, "sampleCount" to events.size,
                        "miss" to (result.firstConfirmationMs == null),
                        "confirmationDelayMs" to result.firstConfirmationMs?.minus(START_MS),
                        "fixedCountWindowMiss" to (fixedCount.firstConfirmationMs == null),
                        "fixedCountWindowDelayMs" to fixedCount.firstConfirmationMs?.minus(START_MS))
                }
                val edge = fixture.edge(code)
                val negatives = negativeCases(interior, edge, count)
                for ((caseId, events) in negatives) {
                    val result = run(candidate, entry.regionRevisionId, fixture.boundary, events)
                    negativeTrials++
                    if (result.cardCount != 0L) {
                        falseCards += result.cardCount
                        failures += "$code:$caseId"
                    }
                }
                perRegion += linkedMapOf("code" to code, "rates" to samplesByRate,
                    "negativeCases" to negatives.size)
            }
            report += linkedMapOf("consecutiveSamples" to count, "minConfirmationDurationMs" to duration,
                "boundaryMarginMeters" to margin, "falseCardCount" to falseCards,
                "failedNegativeCases" to failures, "regions" to perRegion)
        }
        val document = linkedMapOf<String, Any?>(
            "formatVersion" to 1, "scope" to "DEMO_POLICY_CANDIDATES_ONLY_NOT_APPROVED",
            "datasetVersion" to fixture.manifest.datasetVersion, "manifestSha256" to MANIFEST_SHA,
            "hierarchySha256" to HIERARCHY_SHA, "qualityAdapterVersion" to AndroidLocationMeaningAdapter.VERSION,
            "oracle" to "pinned independent GEOS interior points plus exact packed boundary vertices",
            "oracleSha256" to fixture.oracleSha256, "fixtureVersion" to "region-policy-demo-v2",
            "transformAccuracyDeductedMeters" to fixture.manifest.transform.expectedAccuracyMeters.toString(),
            "pairedBaseline" to "FIXED_N_WINDOW_WITH_CURRENT_VALIDATOR_AND_SAME_ADJUSTED_GEOMETRY",
            "candidateCount" to report.size, "demoLeafCodes" to DEMO_LEAF_CODES,
            "positiveTrials" to positiveTrials, "negativeTrials" to negativeTrials,
            "falseCardDefinition" to "Target-region confirmation on an invalid, discontinuous or not-proven-interior synthetic trace",
            "geometryQueries" to fixture.geometryQueries, "maximumCachedCoordinates" to MAX_CACHED_POINTS,
            "selectionUnderTest" to "SHORTEST_CONTINUOUS_SUFFIX_MIN_COUNT_AND_DURATION_MAX_100",
            "syntheticReceiptRatesHz" to listOf(1, 2, 5), "receiptGapMs" to GAP_MS,
            "approvedPolicyCount" to 0, "networkUsed" to false, "productDatabaseUsed" to false,
            "naverRouteUsed" to false, "cardCountsAreSynthetic" to true,
            "policyAssetsModified" to false, "geometryIsMemoizedOnlyForSyntheticImmutableFixtures" to true,
            "limits" to listOf("No real-driving accuracy claim", "No actual route ETA or visit evidence",
                "Bounded-error cases assume the supplied synthetic error is within the reported radius",
                "Android reported accuracy is statistical; this harness does not promise zero field errors",
                "Room uniqueness, SQL transactions and source-rights races remain separate tests"),
            "candidates" to report,
        )
        writeOptionalReport(StrictJson.encode(document))
        assertEquals("No bounded-error/invalid/discontinuous trace may produce a candidate card", 0L,
            report.sumOf { it["falseCardCount"] as Long })
        // All candidates must detect these sufficiently long, deep-interior positive controls.
        report.forEach { row ->
            @Suppress("UNCHECKED_CAST") val regions = row["regions"] as List<Map<String, Any?>>
            regions.forEach { region ->
                @Suppress("UNCHECKED_CAST") val rates = region["rates"] as List<Map<String, Any?>>
                assertTrue("Positive control failed for ${region["code"]}", rates.none { it["miss"] == true })
            }
        }
    }

    private fun negativeCases(interior: BoundaryCoordinate, edge: BoundaryCoordinate, count: Int): Map<String, List<LocationEvent>> {
        val beforeGap = samples(interior, count - 1, 1_000)
        val afterGap = samples(interior, count - 1, 1_000, START_MS + 30_000)
        // A 0.00001-degree latitude perturbation is < 1.2 m anywhere; 5 m reported accuracy
        // contains the exact true boundary point for both signs. No independent containment
        // inference is needed to declare that the true point has not entered this region.
        val bounce = List(31) { index -> sample(BoundaryCoordinate(edge.longitude,
            edge.latitude + if (index % 2 == 0) 0.00001 else -0.00001), START_MS + index * 200L, accuracy = 5.0) }
        val invalidMiddle = samples(interior, count, 1_000).toMutableList().apply {
            this[count / 2] = sample(interior, START_MS + (count / 2) * 1_000L, missingMetadata = true)
        }
        return linkedMapOf(
            "exact_boundary" to samples(edge, 7, 1_000, accuracy = 0.0),
            "bounded_error_boundary_bounce" to bounce,
            "single_sample_short_pass" to (samples(interior, 1, 1_000) + LocationEvent.NoLocation(LocationReadingKind.UPDATE)),
            "invalid_middle_not_filtered" to invalidMiddle,
            "all_stale_measurements" to List(7) { sample(interior, START_MS + it * 1_000L, ageMs = 20_000) },
            "cached_measurement_repeated" to List(7) { sample(interior, START_MS + it * 1_000L, ageMs = it * 1_000L) },
            "inaccurate_measurements" to samples(interior, 7, 1_000, accuracy = 101.0),
            "mock_measurements" to List(7) { sample(interior, START_MS + it * 1_000L, mock = true) },
            "explicit_no_location_gap" to (beforeGap + LocationEvent.NoLocation(LocationReadingKind.UPDATE) + afterGap),
            "receipt_gap_without_callback" to (beforeGap + afterGap),
            "receipt_time_reversal" to (beforeGap + samples(interior, count - 1, 1_000, START_MS - 30_000)),
        )
    }

    private fun run(policy: RegionPolicy, revision: String, boundary: RegionBoundaryLookup,
                    events: List<LocationEvent>, fixedCountWindow: Boolean = false): Trial {
        val store = MemoryStore(policy, revision, boundary, fixedCountWindow)
        var id = 0
        val recorder = LocationRecorder(store, LocationNormalizer(AndroidLocationMeaningAdapter(100.0)),
            { "synthetic-${++id}" }, "demo-clock", GAP_MS, "synthetic-android")
        recorder.begin("synthetic-trip", "synthetic-session")
        events.forEach(recorder::receive)
        recorder.stop()
        return Trial(store.cardCount, store.firstConfirmationMs)
    }

    private data class Trial(val cardCount: Long, val firstConfirmationMs: Long?)
    private class MemoryStore(private val policy: RegionPolicy, private val revision: String,
                              private val boundary: RegionBoundaryLookup,
                              private val fixedCountWindow: Boolean) : LocationRecordingStore {
        private val rows = mutableMapOf<String, MutableList<RegionPoint>>()
        private val sequences = mutableMapOf<String, Long>()
        var cardCount = 0L
            private set
        var firstConfirmationMs: Long? = null
            private set
        override fun openSegment(tripId: String, sessionId: String, segmentId: String) {
            check(rows.put(segmentId, mutableListOf()) == null)
            sequences[segmentId] = 0L
        }
        override fun append(segmentId: String, points: List<RecordedLocation>): LocationCommit {
            val segment = rows.getValue(segmentId)
            points.forEach { point ->
                val sequence = sequences.getValue(segmentId)
                sequences[segmentId] = sequence + 1
                segment += RegionPoint(sequence, point)
                if (segment.size > RegionConfirmation.MAX_PROOF_POINTS) segment.removeAt(0)
                if (cardCount == 0L && RegionConfirmation.confirmed(revision, policy,
                        if (fixedCountWindow) segment.takeLast(policy.consecutiveSamples) else segment, boundary)) {
                    cardCount = 1
                    firstConfirmationMs = point.receivedAtElapsedMs
                }
            }
            return LocationCommit(points.size, 0, emptySet(), null)
        }
        override fun finishSegment(segmentId: String, reason: String, interrupted: Boolean) {
            rows.remove(segmentId)
            sequences.remove(segmentId)
        }
    }

    private class Dataset {
        val manifest = RegionDatasetManifestCodec.decode(File(ASSETS, "$DATASET/manifest.json").readBytes(), MANIFEST_SHA)
        val entries = manifest.regions.associateBy { it.code }
        private val oracleBytes = checkNotNull(javaClass.getResourceAsStream("/regions/ngii-d7a3751bd89f-oracle.tsv"))
            .use { it.readBytes() }
        val oracleSha256 = RegionDatasetManifestCodec.sha256(oracleBytes)
        val interior = oracleBytes.toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
                val fields = line.split('\t')
                check(fields.size == 7)
                fields[0] to BoundaryCoordinate(fields[5].toDouble(), fields[6].toDouble())
            }.toMap()
        private val hierarchy = ReviewedRegionHierarchyCodec.decode(File(ASSETS, "regions/reviewed-hierarchy-v1.json")
            .readBytes(), HIERARCHY_SHA, manifest)
        private val byRevision = manifest.regions.associateBy { it.regionRevisionId }
        private val raw = PackedRegionBoundaryLookup(manifest.regions.filter { it.status == "ready" }.map {
            PackedRegionBoundaryMetadata(it.regionRevisionId, manifest.datasetVersion, it.geometrySha256,
                it.geometryBytes, it.ringCount, it.pointCount, it.bounds)
        }, open = { File(ASSETS, byRevision.getValue(it.regionRevisionId).geometryAsset).inputStream() },
            quarantinedBounds = manifest.regions.filter { it.status == "quarantined" }.map { it.bounds })
        private val adjusted = RegionBoundaryLookup { latitude, longitude -> raw.containing(latitude, longitude).map {
            it.copy(interiorDistanceMeters = maxOf(0.0, it.interiorDistanceMeters - manifest.transform.expectedAccuracyMeters))
        } }
        private val selected = HierarchicalRegionBoundaryLookup(adjusted, hierarchy)
        private val cache = mutableMapOf<Pair<Double, Double>, List<RegionContainment>>()
        var geometryQueries = 0
            private set
        val boundary = object : RegionBoundaryLookup {
            override val selectionKey = selected.selectionKey
            override fun isSelectionCurrent(regionRevisionId: String) = selected.isSelectionCurrent(regionRevisionId)
            override fun containing(latitude: Double, longitude: Double): List<RegionContainment> =
                cache.getOrPut(latitude to longitude) {
                    check(cache.size < MAX_CACHED_POINTS)
                    geometryQueries++
                    selected.containing(latitude, longitude)
                }
        }
        private val edgeCache = mutableMapOf<String, BoundaryCoordinate>()
        fun edge(code: String): BoundaryCoordinate = edgeCache.getOrPut(code) {
            val entry = entries.getValue(code)
            check(entry.status == "ready")
            val file = File(ASSETS, entry.geometryAsset)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(8_192)
                while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == entry.geometrySha256)
            DataInputStream(file.inputStream().buffered()).use { input ->
                check(input.readLong() == 0x5054524730303031L)
                check(input.readInt() == entry.ringCount && input.readInt() == entry.pointCount)
                repeat(4) { input.readDouble() }
                check(input.readInt() >= 4)
                BoundaryCoordinate(input.readDouble(), input.readDouble())
            }
        }
    }

    private fun writeOptionalReport(bytes: ByteArray) {
        val destination = System.getenv("PETASOS_REGION_POLICY_REPORT") ?: return
        val root = ASSETS.parentFile.parentFile.parentFile.parentFile.canonicalFile
        val output = File(destination).canonicalFile
        val artifacts = File(root, "artifacts").canonicalFile
        check(output.toPath().startsWith(artifacts.toPath()) && output.extension == "json")
        check(output.parentFile.isDirectory || output.parentFile.mkdirs())
        output.writeBytes(bytes + byteArrayOf(10))
    }

    companion object {
        private const val START_MS = 100_000L
        private const val GAP_MS = 10_000L
        private const val MAX_CACHED_POINTS = 128
        private const val DATASET = "regions/ngii-2026-09-21-v1"
        private const val MANIFEST_SHA = "3e2117159aa65c6c8f69826f534a8065d1247497039827d1b638f77d580f1bf9"
        private const val HIERARCHY_SHA = "cf428d289dd504b3b8d1d481ea4e80f789a1db7e531ebd06ba749422a3971e5b"
        private val DEMO_LEAF_CODES = listOf("11140", "41461", "41463", "41465", "41550", "44131", "44133", "30110", "30200")
        private val ASSETS = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .first { File(it, "$DATASET/manifest.json").isFile }.canonicalFile
        private fun samples(point: BoundaryCoordinate, count: Int, intervalMs: Long,
                            startMs: Long = START_MS, accuracy: Double = 3.0): List<LocationEvent> =
            List(count) { sample(point, startMs + it * intervalMs, accuracy) }
        private fun sample(point: BoundaryCoordinate, receipt: Long, accuracy: Double = 3.0,
                           ageMs: Long = 0, mock: Boolean = false, missingMetadata: Boolean = false): LocationEvent {
            val measured = receipt - ageMs
            val metadata = if (missingMetadata) null else AndroidLocationMetadata("gps", measured * 1_000_000,
                receipt * 1_000_000, accuracy, mock, null)
            return LocationEvent.SampleReceived(LocationReadingKind.UPDATE, LocationSample(point.latitude,
                point.longitude, Double.NaN, Float.NaN, Float.NaN, measured, receipt, receipt, metadata))
        }
    }
}
