package com.petasos.app.planning

import com.petasos.app.capability.location.AndroidLocationMetadata
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationSample
import com.petasos.app.content.ContentBundleCodec
import com.petasos.app.generation.StrictJson
import com.petasos.app.location.*
import com.petasos.app.playback.PreparedStory
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

/** Public official representative points + explicitly synthetic meridian movement and Android metadata.
 * No road geometry, actual driving, location callback, Room, playback or policy approval occurs here.
 */
class LandmarkCurrentPositionReviewTest {
    @Test fun candidateMatrixRetainsMissesAndMeasuresOpportunityAgainstIndependentGeodesicTruth() {
        val dataset = Dataset()
        val rows = mutableListOf<Map<String, Any?>>()
        var falseDirection = 0
        var falseRadius = 0
        var trials = 0
        val positivePlaces = mutableSetOf<String>()
        for (place in dataset.places) for (radius in RADII) for (cooldown in COOLDOWNS) {
            val outcomes = mutableListOf<List<Any?>>()
            for (hz in RATES) for (speed in SPEEDS) for (accuracy in ACCURACIES) for (noise in NOISES) {
                val result = moving(place, radius, cooldown, hz, speed, accuracy, noise)
                trials++
                falseDirection += result.falseDirection
                falseRadius += result.falseRadius
                if (result.firstOfferMs != null) positivePlaces += place.placeId
                outcomes += listOf(hz, speed, accuracy, noise, result.firstOfferMs,
                    result.leadBeforeClosestMs, result.firstTrueDistanceMeters, result.availableMs,
                    result.falseDirection, result.falseRadius, result.samples)
            }
            rows += linkedMapOf("placeId" to place.placeId, "storyId" to place.storyId,
                "approachRadiusMeters" to radius, "cooldownMs" to cooldown,
                "movingMissCount" to outcomes.count { it[4] == null }, "trials" to outcomes)
        }
        val report = linkedMapOf<String, Any?>(
            "formatVersion" to 1L, "scope" to "PUBLIC_SYNTHETIC_CURRENT_POSITION_CANDIDATES_NOT_APPROVED",
            "fixtureSha256" to FIXTURE_SHA, "assetSha256" to dataset.assetSha,
            "generatorSha256" to dataset.generatorSha, "coordinateReviewEvidenceSha256" to dataset.evidenceSha,
            "qualityAdapterVersion" to AndroidLocationMeaningAdapter.VERSION,
            "directionRule" to "decreasing_distance", "passedRule" to "increasing_distance_after_approach",
            "candidateCount" to rows.size, "trialCount" to trials, "falseDirectionOfferCount" to falseDirection,
            "falseRadiusOfferCount" to falseRadius, "oracleCoordinateCount" to dataset.places.sumOf { it.coordinates.size },
            "columns" to listOf("sampleRateHz", "speedMetersPerSecond", "accuracyMeters", "syntheticNoise",
                "firstOfferElapsedMsOrNull", "leadBeforeSampledClosestPointMsOrNull", "trueDistanceAtFirstOfferMetersOrNull",
                "availableDurationMsOrNull", "falseDirectionOffers", "falseRadiusOffers", "samples"),
            "approvedPolicyCount" to 0, "productDatabaseUsed" to false, "actualLocationUsed" to false,
            "networkUsed" to false, "roadGeometryUsed" to false,
            "limits" to listOf("Official POI coordinates are representative points, not road access or measured accuracy",
                "Synthetic WGS84 meridian cross-sections do not prove an accessible vehicle route",
                "Android accuracy is statistical; bounded synthetic noise is not a field guarantee",
                "Adjacent uncertainty ranges can overlap during real approach; misses remain visible",
                "Opportunity is measured before sampled closest point, not guaranteed audio start or completion",
                "Only explicit INACTIVE navigation allows CurrentPosition; ACTIVE membership is not inferred",
                "Separate guard tests and storage/rights tests must pass before any scoped review is selected"),
            "candidates" to rows,
        )
        writeOptionalReport(StrictJson.encode(report))
        assertEquals(648, trials)
        assertEquals(dataset.places.map { it.placeId }.toSet(), positivePlaces)
        assertEquals(0, falseDirection)
        assertEquals(0, falseRadius)
        // Report the conservative cadence limitation instead of silently tuning traces until all pass.
        assertTrue(rows.any { (it["movingMissCount"] as Int) > 0 })
    }

    @Test fun stationaryAndBoundaryNoiseActuallyVisitBothErrorSignsAndNeverCreateMovement() {
        val dataset = Dataset()
        for (place in dataset.places) for (radius in RADII) for (hz in RATES) for (accuracy in ACCURACIES) {
            for (trueOffset in listOf(radius / 2, radius, radius + accuracy + 1)) {
                val h = Harness(place, radius, COOLDOWNS.first())
                val visitedErrors = mutableSetOf<Int>()
                repeat(40) { index ->
                    val error = if (index % 2 == 0) -accuracy else accuracy
                    visitedErrors += error
                    val point = place.point(trueOffset + error, START + index * (1000L / hz), accuracy)
                    assertNull(h.update(point))
                }
                assertEquals(setOf(-accuracy, accuracy), visitedErrors)
            }
        }
    }

    @Test fun interactionExpiryStopAuthorityAndCooldownPreserveTerminalEncounterIdentity() {
        for (place in Dataset().places) for (radius in RADII) for (cooldown in COOLDOWNS) {
            // Receding from a real synthetic approach during speech permanently expires that offer.
            val talk = Harness(place, radius, cooldown)
            talk.update(place.point(radius * 4 / 5, START, 5))
            val second = place.point(radius / 2, START + 1000, 5)
            val old = checkNotNull(talk.update(second, speaking = true))
            assertNull(talk.planner.chooseAtBoundary(talk.context(second, speaking = true), talk.session::stillApproaching))
            val passed = place.point(radius * 7 / 10, START + 2000, 5)
            assertNull(talk.update(passed, speaking = true))
            assertEquals(CandidateState.EXPIRED, talk.planner.state(old.candidateId))
            assertNull(talk.update(passed, now = START + 2001))
            assertNull(talk.update(second, now = START + 2002))
            assertFalse(talk.session.isCurrent(old))

            // Timer expiry, missing quality, inventory withdrawal and explicit clear are terminal.
            for (reason in listOf("stale", "invalid", "authority", "inventory", "clear")) {
                val h = Harness(place, radius, cooldown)
                h.update(place.point(radius * 4 / 5, START, 5))
                val offer = checkNotNull(h.update(second))
                when (reason) {
                    "stale" -> assertNull(h.update(second, now = START + 11_001))
                    "invalid" -> assertNull(h.update(second.copy(quality = LocationQuality.UNKNOWN)))
                    "authority" -> assertNull(h.update(second, authority = false))
                    "inventory" -> assertNull(h.update(second, inventory = emptyList()))
                    "clear" -> h.session.clear(START + 1001)
                }
                assertEquals(CandidateState.EXPIRED, h.planner.state(offer.candidateId))
                assertFalse(h.session.isCurrent(offer))
                assertNull(h.update(place.point(radius / 3, START + cooldown + 12_000, 5)))
            }

            val rearm = Harness(place, radius, cooldown)
            rearm.update(place.point(radius * 4 / 5, START, 5))
            val first = checkNotNull(rearm.update(second))
            assertNull(rearm.update(passed))
            assertNull(rearm.update(place.point(radius + 100, START + 2500, 5)))
            assertNull(rearm.update(place.point(radius / 2, START + 3000, 5)))
            val afterCooldown = START + 2000 + cooldown + 1000
            // Receipt after cooldown cannot renew an outside measurement taken before cooldown.
            assertNull(rearm.update(place.point(radius + 100, afterCooldown, 5,
                measurementMs = START + 2000 + cooldown - 1000)))
            assertNull(rearm.update(place.point(radius / 2, afterCooldown + 1000, 5)))
            assertNull(rearm.update(place.point(radius + 100, afterCooldown + 2000, 5)))
            val renewed = checkNotNull(rearm.update(place.point(radius / 2, afterCooldown + 3000, 5)))
            assertNotEquals(first.candidateId, renewed.candidateId)
            assertEquals(CandidateState.EXPIRED, rearm.planner.state(first.candidateId))

            val hold = Harness(place, radius, cooldown)
            hold.update(place.point(radius * 4 / 5, START, 5))
            val held = checkNotNull(hold.update(second))
            val stationary = place.point(radius / 2, START + 2000, 5)
            assertEquals(held, hold.update(stationary))
            assertEquals(held.candidateId, hold.planner.chooseAtBoundary(hold.context(stationary), hold.session::stillApproaching)?.candidateId)
            hold.session.consume(held.candidateId, START + 2001) // Explicit synthetic Started ACK boundary only.
            assertEquals(CandidateState.CONSUMED, hold.planner.state(held.candidateId))
            assertNull(hold.update(place.point(radius / 3, START + 3000, 5)))
        }
    }

    @Test fun unknownOrActiveRouteAndDelayedRepeatedMeasurementCannotAuthorizeCurrentPosition() {
        for (place in Dataset().places) {
            for (route in listOf(LandmarkRouteContext.Unavailable, LandmarkRouteContext.CurrentPosition)) {
                val h = Harness(place, 300, 60000)
                val a = place.point(240, START, 5)
                val b = place.point(150, START + 1000, 5)
                assertNull(h.update(a, route = route, routeRevision = 7))
                assertNull(h.update(b, route = route, routeRevision = 7))
            }
            val h = Harness(place, 300, 60000)
            val a = place.point(240, START, 5)
            assertNull(h.update(a))
            val cached = place.point(240, START + 1000, 5, measurementMs = START)
            assertNull(h.update(cached))
            val stale = place.point(150, START + 12_000, 5, measurementMs = START + 1000)
            assertEquals(LocationQuality.STALE, stale.quality)
            assertNull(h.update(stale))
            assertNull(h.update(place.point(150, START + 13_000, 5)))
            assertNotNull(h.update(place.point(100, START + 14_000, 5)))
        }
    }

    private fun moving(place: Place, radius: Int, cooldown: Long, hz: Int, speed: Int, accuracy: Int, noise: String): Trial {
        val h = Harness(place, radius, cooldown)
        val step = speed / hz
        check(step > 0 && speed % hz == 0)
        val offsets = (800 downTo -800 step step).toList()
        val closestIndex = offsets.indices.minBy { abs(offsets[it]) }
        var firstOffer: Long? = null
        var firstDistance: Int? = null
        var terminalAt: Long? = null
        var falseDirection = 0
        var falseRadius = 0
        var seenId: String? = null
        offsets.forEachIndexed { index, truth ->
            val error = if (noise == "zero_error") 0 else if (index % 2 == 0) -accuracy else accuracy
            val at = START + index * (1000L / hz)
            val offered = h.update(place.point(truth + error, at, accuracy))
            if (offered != null && seenId == null) {
                seenId = offered.candidateId
                firstOffer = at
                firstDistance = abs(truth)
                if (index == 0 || abs(truth) >= abs(offsets[index - 1])) falseDirection++
                if (abs(truth) > radius) falseRadius++
            } else if (offered != null) check(offered.candidateId == seenId)
            if (seenId != null && offered == null && terminalAt == null) terminalAt = at
        }
        return Trial(firstOffer, firstOffer?.let { START + closestIndex * (1000L / hz) - it }, firstDistance,
            firstOffer?.let { (terminalAt ?: START + (offsets.size - 1) * (1000L / hz)) - it },
            falseDirection, falseRadius, offsets.size)
    }

    private data class Trial(val firstOfferMs: Long?, val leadBeforeClosestMs: Long?, val firstTrueDistanceMeters: Int?,
        val availableMs: Long?, val falseDirection: Int, val falseRadius: Int, val samples: Int)

    private class Harness(place: Place, radius: Int, cooldown: Long) {
        val planner = ContentPlanner(CLOCK)
        val session = LandmarkCandidateSession(planner, "synthetic-landmark-review", CLOCK)
        val candidates = listOf(LandmarkStoryCandidate(PreparedStory(place.storyId, place.versionId, place.paragraphIds),
            place.placeId, place.latitude, place.longitude, place.coordinateSourceRevisionId,
            LandmarkPolicy(radius.toDouble(), LandmarkPassedRule.INCREASING_DISTANCE_AFTER_APPROACH,
                LandmarkDirectionRule.DECREASING_DISTANCE, cooldown, "synthetic-candidate-$radius-$cooldown",
                AndroidLocationMeaningAdapter.VERSION), "synthetic-current-position-evidence-only"))
        fun context(point: RecordedLocation, speaking: Boolean = false, now: Long = point.receivedAtElapsedMs,
            routeRevision: Long? = null, authority: Boolean = true) = PlanningContext(null, routeRevision, now, CLOCK,
            point.quality == LocationQuality.VALID, speaking, point.receivedAtElapsedMs,
            locationFreshUntilElapsedMs = LocationFreshness.deadlineElapsedMs(point), locationAuthority = { authority })
        fun update(point: RecordedLocation, speaking: Boolean = false, now: Long = point.receivedAtElapsedMs,
            authority: Boolean = true, inventory: List<LandmarkStoryCandidate> = candidates,
            route: LandmarkRouteContext = LandmarkRouteContext.CurrentPosition, routeRevision: Long? = null) =
            session.update(inventory, point, context(point, speaking, now, routeRevision, authority), route)
    }

    private class Place(row: Map<*, *>) {
        val placeId = row["placeId"] as String
        val storyId = row["storyId"] as String
        val versionId = row["storyVersionId"] as String
        val paragraphIds = (row["paragraphIds"] as List<*>).map { it as String }
        val coordinateSourceRevisionId = row["coordinateSourceRevisionId"] as String
        val latitude = (row["latitude"] as String).toDouble()
        val longitude = (row["longitude"] as String).toDouble()
        val coordinates = (row["coordinates"] as List<*>).associate { item ->
            val values = item as List<*>
            (values[0] as Long).toInt() to ((values[1] as String).toDouble() to (values[2] as String).toDouble())
        }
        private val normalizer = LocationNormalizer(AndroidLocationMeaningAdapter(50.0))
        init {
            check(coordinates.keys == (-850..850).toSet())
            check(LandmarkPlaceMetadata(placeId, null, row["name"] as String, "[]", latitude, longitude,
                coordinateSourceRevisionId).fingerprint() == row["placeMetadataSha256"])
        }
        fun point(offset: Int, receiptMs: Long, accuracy: Int, measurementMs: Long = receiptMs): RecordedLocation {
            val coordinate = coordinates.getValue(offset)
            val metadata = AndroidLocationMetadata("gps", measurementMs * 1_000_000, receiptMs * 1_000_000,
                accuracy.toDouble(), false, 0.0)
            return normalizer.normalize(LocationSample(coordinate.first, coordinate.second, 0.0, 0f, 0f,
                EPOCH + measurementMs, EPOCH + receiptMs, receiptMs, metadata), LocationReadingKind.UPDATE,
                "synthetic-$receiptMs-$offset", "public-synthetic-android-metadata-only", CLOCK)
        }
    }

    private class Dataset {
        private val bytes = checkNotNull(javaClass.getResourceAsStream("/location/landmark-current-position-demo.json")).use { it.readBytes() }
        private val root = run {
            check(ContentBundleCodec.sha256(bytes) == FIXTURE_SHA)
            StrictJson.parse(bytes) as Map<*, *>
        }
        val assetSha = root["assetSha256"] as String
        val generatorSha = root["generatorSha256"] as String
        val evidenceSha = root["coordinateReviewEvidenceSha256"] as String
        val places = (root["places"] as List<*>).map { Place(it as Map<*, *>) }
        init {
            check(root["approvedPolicyCount"] == 0L && root["realDriving"] == false && root["realRoadGeometry"] == false &&
                root["actualLocationSamples"] == false && root["oracleInverseComparisons"] == 3402L)
            check(places.size == 2 && places.map { it.placeId }.distinct().size == 2)
        }
    }

    private fun writeOptionalReport(bytes: ByteArray) {
        val destination = System.getenv("PETASOS_LANDMARK_POLICY_REVIEW_REPORT") ?: return
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "spec/petasos-v0.8.sql").isFile }
        val output = File(destination).canonicalFile
        check(output.toPath().startsWith(File(root, "artifacts").canonicalFile.toPath()) && output.extension == "json")
        val parent = checkNotNull(output.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        check(output.createNewFile()) // Never silently replace prior review evidence.
        output.writeBytes(bytes + byteArrayOf(10))
    }

    private companion object {
        const val FIXTURE_SHA = "76939bfe69cb9975fbb486f060c3434ec416500c42c46b36b86b6b449bd2bfa4"
        const val CLOCK = "public-synthetic-landmark-clock"
        const val EPOCH = 1_700_000_000_000L
        const val START = 100_000L
        val RADII = listOf(150, 300, 500)
        val COOLDOWNS = listOf(60_000L, 180_000L, 300_000L)
        val SPEEDS = listOf(5, 15, 25)
        val ACCURACIES = listOf(5, 15, 30)
        val RATES = listOf(1, 5)
        val NOISES = listOf("zero_error", "alternating_radial_error")
    }
}
