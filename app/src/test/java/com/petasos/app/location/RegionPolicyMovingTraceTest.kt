package com.petasos.app.location

import com.petasos.app.capability.location.AndroidLocationMetadata
import com.petasos.app.capability.location.LocationEvent
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.generation.StrictJson
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Public NGII cross-sections, independent GEOS truth and explicit synthetic Android errors.
 * No road/path-provider data, real observation, Room database, card issuance or policy approval.
 * The existing stationary/invalid/discontinuous harness remains a separate required companion.
 */
class RegionPolicyMovingTraceTest {
    @Test fun movingCandidateMatrixMeasuresOracleErrorsPassRejectionAndConfirmationDelay() {
        val dataset = Dataset()
        val candidates = mutableListOf<Map<String, Any?>>()
        val successfulRegions = mutableSetOf<String>()
        for (count in listOf(2, 3, 5)) for (duration in listOf(0L, 1_000L, 2_000L)) for (margin in listOf(5L, 10L, 20L)) {
            val policy = RegionPolicy(dataset.manifest.datasetVersion, count, duration, margin.toDouble(),
                AndroidLocationMeaningAdapter.VERSION, "EPSG:4326")
            var falseCards = 0
            var prematureCards = 0
            var bounceCards = 0
            var movingMisses = 0
            var shortAccepted = 0
            val delays = mutableListOf<Long>()
            val perRegion = DEMO_LEAF_CODES.map { code ->
                val moving = mutableListOf<List<Any?>>()
                val short = mutableListOf<List<Any?>>()
                var regionBounceCards = 0
                dataset.traces.filter { it.code == code }.forEach { trace ->
                    val result = run(dataset, trace, policy)
                    falseCards += result.falseCard
                    prematureCards += result.prematureCard
                    when (trace.kind) {
                        "moving_dwell" -> {
                            if (result.delayMs == null) movingMisses++ else {
                                delays += result.delayMs
                                successfulRegions += code
                            }
                            moving += listOf(trace.hz, trace.accuracy, result.delayMs, result.proofCount)
                        }
                        "short_pass" -> {
                            if (result.delayMs != null) shortAccepted++
                            short += listOf(trace.hz, trace.accuracy, result.delayMs, result.proofCount)
                        }
                        "outside_boundary_bounce" -> if (result.confirmed) {
                            bounceCards++
                            regionBounceCards++
                        }
                        else -> error("UNKNOWN_SYNTHETIC_TRACE_KIND")
                    }
                }
                linkedMapOf("code" to code, "moving" to moving, "shortPass" to short,
                    "outsideBounceCardCount" to regionBounceCards)
            }
            candidates += linkedMapOf("consecutiveSamples" to count, "minConfirmationDurationMs" to duration,
                "boundaryMarginMeters" to margin, "falseTargetCardCount" to falseCards,
                "prematureTargetCardCount" to prematureCards, "outsideBounceCardCount" to bounceCards,
                "movingMissCount" to movingMisses, "shortPassAcceptedCount" to shortAccepted,
                "movingDelayMs" to percentiles(delays), "regions" to perRegion)
        }
        val report = linkedMapOf<String, Any?>(
            "formatVersion" to 1, "scope" to "PUBLIC_NGII_MOVING_POLICY_CANDIDATES_NOT_APPROVED",
            "fixtureSha256" to FIXTURE_SHA, "manifestSha256" to MANIFEST_SHA, "hierarchySha256" to HIERARCHY_SHA,
            "datasetVersion" to dataset.manifest.datasetVersion, "generatorSha256" to dataset.generatorSha,
            "qualityAdapterVersion" to AndroidLocationMeaningAdapter.VERSION,
            "transformAccuracyDeductedMeters" to dataset.manifest.transform.expectedAccuracyMeters.toString(),
            "selectionUnderTest" to "SHORTEST_CONTINUOUS_SUFFIX_MIN_COUNT_AND_DURATION_MAX_100",
            "candidateCount" to candidates.size, "traceCountPerCandidate" to dataset.traces.size,
            "trialCount" to candidates.size * dataset.traces.size, "coordinates" to dataset.coordinates.size,
            "geometryQueries" to dataset.geometryQueries, "maximumCachedCoordinates" to MAX_COORDINATES,
            "oracleInteriorComparisons" to dataset.oracleInteriorComparisons,
            "traceOutcomeColumns" to listOf("rateHz", "accuracyMeters", "confirmationDelayMsOrNull", "proofPointCount"),
            "movingTrialsPerCandidate" to 81, "shortPassTrialsPerCandidate" to 81, "bounceTrialsPerCandidate" to 81,
            "falseTargetCardDefinition" to "A first target proof contains any independent true point outside that target",
            "prematureTargetCardDefinition" to "A first target card precedes the required count or duration of continuous true target samples",
            "shortPassAcceptanceMeaning" to "Tradeoff only; a valid true short passage is not automatically a false card",
            "delayOrigin" to "First sampled independent true target membership, not a continuously interpolated crossing time",
            "approvedPolicyCount" to 0, "policyAssetsModified" to false, "productDatabaseUsed" to false,
            "networkUsed" to false, "deviceLocationUsed" to false, "naverRouteUsed" to false,
            "limits" to listOf(
                "One selected boundary cross-section per exact leaf revision; not all borders",
                "Synthetic cross-sections are not roads, legal access paths or real-driving trajectories",
                "Android accuracy is statistical; bounded synthetic errors are not a field guarantee",
                "Receipt and measurement clocks coincide here; stale/gap/reversal tests remain in the companion harness",
                "A miss or short-pass rejection is retained as a candidate tradeoff, not hidden by changing the trace",
                "Room/source-rights races and actual callback quality remain separate acceptance checks"),
            "candidates" to candidates,
        )
        writeOptionalReport(StrictJson.encode(report))
        assertEquals(6_561, candidates.size * dataset.traces.size)
        assertEquals("Every target must have a successful positive control", DEMO_LEAF_CODES.toSet(), successfulRegions)
        assertEquals("No synthetic proof may contradict independent true membership", 0,
            candidates.sumOf { it["falseTargetCardCount"] as Int })
        assertEquals("No synthetic target may be confirmed prematurely", 0,
            candidates.sumOf { it["prematureTargetCardCount"] as Int })
        assertEquals("An outside-only path must never earn a target card", 0,
            candidates.sumOf { it["outsideBounceCardCount"] as Int })
    }

    private fun run(dataset: Dataset, trace: Trace, policy: RegionPolicy): Trial {
        val store = MemoryStore(dataset, trace, policy)
        var id = 0
        val recorder = LocationRecorder(store, LocationNormalizer(AndroidLocationMeaningAdapter(100.0)),
            { "moving-synthetic-" + (++id) }, "moving-synthetic-clock", 10_000, "synthetic-android")
        recorder.begin("moving-synthetic-trip", "moving-synthetic-session")
        trace.samples.forEach { row ->
            val coordinate = dataset.coordinates[row.measuredIndex]
            val time = START_MS + row.elapsedMs
            val metadata = AndroidLocationMetadata("gps", time * 1_000_000, time * 1_000_000,
                trace.accuracy.toDouble(), false, null)
            recorder.receive(LocationEvent.SampleReceived(LocationReadingKind.UPDATE, LocationSample(coordinate.latitude,
                coordinate.longitude, Double.NaN, Float.NaN, Float.NaN, time, time, time, metadata)))
        }
        recorder.stop()
        assertEquals("Every explicit synthetic observation must reach the recorder once", trace.samples.size, store.inserted)
        assertEquals("Continuous moving trace must keep one recording segment", 1, store.opened)
        assertEquals(1, store.finished)
        return store.result
    }

    private data class Trial(val confirmed: Boolean = false, val delayMs: Long? = null, val proofCount: Int = 0,
                             val falseCard: Int = 0, val prematureCard: Int = 0)
    private data class Sample(val elapsedMs: Long, val trueIndex: Int, val measuredIndex: Int)
    private data class Trace(val code: String, val revision: String, val kind: String, val hz: Int,
                             val accuracy: Int, val samples: List<Sample>)
    private data class Oracle(val code: String?, val clearanceMm: Long)

    private class MemoryStore(private val dataset: Dataset, private val trace: Trace, private val policy: RegionPolicy) :
        LocationRecordingStore {
        private val rows = mutableListOf<RegionPoint>()
        private val sampleByTime = trace.samples.withIndex().associateBy { START_MS + it.value.elapsedMs }
        var result = Trial()
        var inserted = 0
        var opened = 0
        var finished = 0
        override fun openSegment(tripId: String, sessionId: String, segmentId: String) { opened++; check(opened == 1) }
        override fun append(segmentId: String, points: List<RecordedLocation>): LocationCommit {
            points.forEach { point ->
                val sample = sampleByTime.getValue(point.receivedAtElapsedMs)
                check(sample.index == inserted)
                rows += RegionPoint(inserted.toLong(), point)
                inserted++
                if (rows.size > RegionConfirmation.MAX_PROOF_POINTS) rows.removeAt(0)
                if (!result.confirmed) {
                    val proof = RegionConfirmation.proof(trace.revision, policy, rows, dataset.boundary)
                    if (proof != null) {
                        val proofIndices = proof.map { sampleByTime.getValue(it.location.receivedAtElapsedMs).index }
                        check(proofIndices == ((sample.index - proof.size + 1)..sample.index).toList())
                        val falseCard = proofIndices.any { dataset.oracle[trace.samples[it].trueIndex].code != trace.code }
                        val trueSuffix = trace.samples.subList(0, sample.index + 1).asReversed()
                            .takeWhile { dataset.oracle[it.trueIndex].code == trace.code }
                        val premature = trueSuffix.size < policy.consecutiveSamples ||
                            trueSuffix.first().elapsedMs - trueSuffix.last().elapsedMs < policy.minConfirmationDurationMs
                        val firstTrue = trace.samples.firstOrNull { dataset.oracle[it.trueIndex].code == trace.code }?.elapsedMs
                        result = Trial(true, firstTrue?.let { sample.value.elapsedMs - it }, proof.size,
                            if (falseCard) 1 else 0, if (premature) 1 else 0)
                    }
                }
            }
            return LocationCommit(points.size, 0, emptySet(), null)
        }
        override fun finishSegment(segmentId: String, reason: String, interrupted: Boolean) { finished++; rows.clear() }
    }

    private class Dataset {
        val manifest = RegionDatasetManifestCodec.decode(File(ASSETS, DATASET + "/manifest.json").readBytes(), MANIFEST_SHA)
        private val entries = manifest.regions.associateBy { it.code }
        private val byRevision = manifest.regions.associateBy { it.regionRevisionId }
        private val fixtureBytes = checkNotNull(javaClass.getResourceAsStream("/regions/ngii-policy-crossings-v1.json"))
            .use { it.readBytes() }
        private val document = run {
            check(RegionDatasetManifestCodec.sha256(fixtureBytes) == FIXTURE_SHA)
            StrictJson.parseDecimalDocument(fixtureBytes, MAX_FIXTURE_BYTES) as Map<*, *>
        }
        val generatorSha = document["generatorSha256"] as String
        val coordinates = (document["coordinates"] as List<*>).map { row ->
            val values = row as List<*>
            BoundaryCoordinate((values[0] as String).toDouble(), (values[1] as String).toDouble())
        }
        val oracle = (document["oracle"] as List<*>).map { row ->
            val values = row as List<*>
            Oracle(values[0] as String?, values[1] as Long)
        }
        private val hierarchy = ReviewedRegionHierarchyCodec.decode(File(ASSETS, "regions/reviewed-hierarchy-v1.json")
            .readBytes(), HIERARCHY_SHA, manifest)
        private val packed = PackedRegionBoundaryLookup(manifest.regions.filter { it.status == "ready" }.map {
            PackedRegionBoundaryMetadata(it.regionRevisionId, manifest.datasetVersion, it.geometrySha256,
                it.geometryBytes, it.ringCount, it.pointCount, it.bounds)
        }, open = { File(ASSETS, byRevision.getValue(it.regionRevisionId).geometryAsset).inputStream() },
            quarantinedBounds = manifest.regions.filter { it.status == "quarantined" }.map { it.bounds })
        private val adjusted = RegionBoundaryLookup { latitude, longitude -> packed.containing(latitude, longitude).map {
            it.copy(interiorDistanceMeters = maxOf(0.0, it.interiorDistanceMeters - manifest.transform.expectedAccuracyMeters))
        } }
        private val selected = HierarchicalRegionBoundaryLookup(adjusted, hierarchy)
        private val cache = mutableMapOf<Pair<Double, Double>, List<RegionContainment>>()
        var geometryQueries = 0
            private set
        var oracleInteriorComparisons = 0
            private set
        val boundary = object : RegionBoundaryLookup {
            override val selectionKey = selected.selectionKey
            override fun isSelectionCurrent(regionRevisionId: String) = selected.isSelectionCurrent(regionRevisionId)
            override fun containing(latitude: Double, longitude: Double): List<RegionContainment> =
                cache.getOrPut(latitude to longitude) {
                    check(cache.size < MAX_COORDINATES)
                    geometryQueries++
                    selected.containing(latitude, longitude)
                }
        }
        val traces: List<Trace>
        init {
            check(document["formatVersion"] == 1L && document["scope"] == SCOPE)
            check(document["manifestSha256"] == MANIFEST_SHA && document["hierarchySha256"] == HIERARCHY_SHA)
            check(document["datasetVersion"] == manifest.datasetVersion && document["transformAccuracyMeters"] == "1")
            check(document["approvedPolicyCount"] == 0L && document["networkUsed"] == false &&
                document["deviceLocationUsed"] == false && document["naverResponseStored"] == false)
            check(coordinates.size in 1..MAX_COORDINATES && coordinates.size == oracle.size)
            val regions = document["regions"] as List<*>
            check(regions.map { (it as Map<*, *>)["code"] } == DEMO_LEAF_CODES)
            traces = regions.flatMap { value ->
                val region = value as Map<*, *>
                val code = region["code"] as String
                val entry = entries.getValue(code)
                check(entry.status == "ready" && entry.regionRevisionId == region["regionRevisionId"] &&
                    entry.geometrySha256 == region["geometrySha256"])
                val neighbor = entries.getValue(region["neighborCode"] as String)
                check(neighbor.status == "ready" && neighbor.code != code &&
                    neighbor.regionRevisionId == region["neighborRegionRevisionId"])
                (region["traces"] as List<*>).map { item ->
                    val trace = item as Map<*, *>
                    Trace(code, entry.regionRevisionId, trace["kind"] as String, (trace["rateHz"] as Long).toInt(),
                        (trace["accuracyMeters"] as Long).toInt(), (trace["samples"] as List<*>).map { sample ->
                            val fields = sample as List<*>
                            check(fields.size == 3)
                            Sample(fields[0] as Long, (fields[1] as Long).toInt(), (fields[2] as Long).toInt())
                        }).also { parsed ->
                            check(parsed.hz in listOf(1, 2, 5) && parsed.accuracy in listOf(3, 10, 30))
                            val end = when (parsed.kind) { "moving_dwell" -> 22_000L; "short_pass" -> 8_000L
                                "outside_boundary_bounce" -> 6_000L; else -> error("UNKNOWN_TRACE_KIND") }
                            check(parsed.samples.map { it.elapsedMs } == (0L..end step (1_000L / parsed.hz)).toList())
                            check(parsed.samples.all { it.trueIndex in coordinates.indices && it.measuredIndex in coordinates.indices })
                            if (parsed.kind == "outside_boundary_bounce") {
                                check(parsed.samples.all { oracle[it.trueIndex].code == neighbor.code })
                                val measuredCodes = parsed.samples.map { oracle[it.measuredIndex].code }.toSet()
                                check(code in measuredCodes && neighbor.code in measuredCodes)
                            }
                        }
                }
            }
            check(traces.size == 243)
            // Compare engines away from roundoff and the separately deducted 1 m transform allowance.
            // Border cases are still replayed; a near-border oracle result cannot authorize a card.
            coordinates.forEachIndexed { index, coordinate ->
                val expected = oracle[index]
                if (expected.clearanceMm > 2_000 && expected.code != null) {
                    val actual = boundary.containing(coordinate.latitude, coordinate.longitude)
                    assertEquals("Independent canonical leaf mismatch at synthetic fixture index " + index,
                        entries.getValue(expected.code).regionRevisionId, actual.singleOrNull()?.regionRevisionId)
                    oracleInteriorComparisons++
                }
            }
        }
    }

    private fun percentiles(values: List<Long>): Map<String, Any?> {
        val sorted = values.sorted()
        fun at(numerator: Int): Long? = if (sorted.isEmpty()) null else sorted[((sorted.size - 1) * numerator) / 100]
        return linkedMapOf("count" to sorted.size, "min" to sorted.firstOrNull(), "p50" to at(50),
            "p95" to at(95), "max" to sorted.lastOrNull())
    }

    private fun writeOptionalReport(bytes: ByteArray) {
        val destination = System.getenv("PETASOS_REGION_POLICY_MOVING_REPORT") ?: return
        val root = generateSequence(ASSETS) { it.parentFile }.drop(4).first().canonicalFile
        val output = File(destination).canonicalFile
        check(output.toPath().startsWith(File(root, "artifacts").canonicalFile.toPath()) && output.extension == "json")
        val parent = checkNotNull(output.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        output.writeBytes(bytes + byteArrayOf(10))
    }

    companion object {
        private const val START_MS = 100_000L
        private const val MAX_COORDINATES = 8_192
        private const val MAX_FIXTURE_BYTES = 1_048_576
        private const val DATASET = "regions/ngii-2026-09-21-v1"
        private const val SCOPE = "PUBLIC_NGII_SYNTHETIC_CROSSINGS_NOT_ROADS_NOT_OBSERVED_LOCATIONS_NOT_APPROVED_POLICIES"
        private const val FIXTURE_SHA = "08533135821e4adb969e4bf94ed6b68bc45f526f965b5b9f0840f5600449c8d3"
        private const val MANIFEST_SHA = "3e2117159aa65c6c8f69826f534a8065d1247497039827d1b638f77d580f1bf9"
        private const val HIERARCHY_SHA = "cf428d289dd504b3b8d1d481ea4e80f789a1db7e531ebd06ba749422a3971e5b"
        private val DEMO_LEAF_CODES = listOf("11140", "41461", "41463", "41465", "41550", "44131", "44133", "30110", "30200")
        private val ASSETS = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .first { File(it, DATASET + "/manifest.json").isFile }.canonicalFile
    }
}
