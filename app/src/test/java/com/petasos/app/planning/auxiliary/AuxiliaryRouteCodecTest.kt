package com.petasos.app.planning.auxiliary

import com.petasos.app.location.BoundaryCoordinate
import org.junit.Assert.*
import org.junit.Test

internal fun auxiliaryRouteWire(
    path: String = "[[127.0,37.0],[127.1,37.1],[127.2,37.2]]",
    guides: String = "[{\"startPointIndex\":0,\"endPointIndex\":1,\"durationMs\":1000,\"distanceMeters\":100}," +
        "{\"startPointIndex\":1,\"endPointIndex\":2,\"durationMs\":2000,\"distanceMeters\":200}]",
    duration: String = "3000", distance: String = "300", coverage: Boolean = true,
    totals: Boolean = true, usable: Boolean = true,
): String = """{"schemaVersion":"0.8","status":"ok","provider":"naver_maps","service":"directions5","sourceMode":"AUXILIARY","coordinateReferenceSystem":"EPSG:4326","activePleosRouteVerified":false,"collectionEvidence":false,"path":$path,"timingSegments":$guides,"totalDistanceMeters":$distance,"totalDurationMs":$duration,"timingCoverageComplete":$coverage,"timingTotalsMatch":$totals,"timingUsable":$usable}"""

internal fun auxiliaryErrorWire(code: String, retryable: Boolean = false): ByteArray =
    """{"schemaVersion":"0.8","requestId":null,"status":"error","code":"$code","retryable":$retryable,"retryAfterMs":null}""".toByteArray()

class AuxiliaryRouteCodecTest {
    @Test fun fixedWireDecodesDecimalCoordinatesAndImmutableTiming() {
        val response = decode(auxiliaryRouteWire())
        assertEquals(300L, response.totalDistanceMeters)
        assertEquals(3000L, response.geometry.totalDurationMs)
        assertEquals(BoundaryCoordinate(127.1, 37.1), response.geometry.path[1])
        assertEquals(AuxiliaryGuideInterval(0, 1, 1000, 100), response.geometry.guides[0])
        assertTrue(response.timingUsable)
        try { (response.geometry.path as MutableList<BoundaryCoordinate>).clear(); fail("Mutable path") } catch (_: UnsupportedOperationException) { }
        assertFalse(response.toString().contains("127"))
    }

    @Test fun keyNormalizesNumericSpellingButChangesWithGeometryAndTiming() {
        val original = auxiliaryRouteWire()
        val key = decode(original).geometry.routeKey
        assertEquals(key, decode(original.replace("127.0", "127").replace("37.0", "3.7e1")).geometry.routeKey)
        assertNotEquals(key, decode(original.replace("127.1", "127.15")).geometry.routeKey)
        assertNotEquals(key, decode(original.replace("1000", "1001").replace("3000", "3001")).geometry.routeKey)
        assertNotEquals(key, decode(auxiliaryRouteWire(distance = "301")).geometry.routeKey)
    }

    @Test fun exactKeysAndProviderAuthorityMarkersCannotBeChanged() {
        val original = auxiliaryRouteWire()
        listOf(original.replace("\"sourceMode\":\"AUXILIARY\"", "\"sourceMode\":\"ACTIVE\""),
            original.replace("\"activePleosRouteVerified\":false", "\"activePleosRouteVerified\":true"),
            original.replace("\"collectionEvidence\":false", "\"collectionEvidence\":true"),
            original.replace("naver_maps", "other-provider"), original.replace("directions5", "directions15"),
            original.replace("EPSG:4326", "EPSG:5179"), original.replace("\"0.8\"", "\"0.9\""),
            original.dropLast(1) + ",\"private\":true}",
            original.replace("\"status\":\"ok\"", "\"status\":\"ok\",\"status\":\"ok\"")).forEach(::reject)
    }

    @Test fun nonfiniteSwappedProjectedAndOutOfEnvelopeCoordinatesFail() {
        listOf("[[37,127],[37.1,127.1]]", "[[119.9,37],[127,37]]", "[[127,45.1],[127,37]]",
            "[[127,37],[1e999,37]]", "[[127,37],[NaN,37]]", "[[127,37],[true,37]]",
            "[[127,37],[\"127.1\",37]]", "[[127,37,0],[127.1,37.1]]").forEach {
            reject(auxiliaryRouteWire(path = it))
        }
    }

    @Test fun integerMetricsAndCapsMatchServerWithoutDecimalOrBooleanCoercion() {
        listOf("true", "3000.0", "3e3", "-1", "604800001", "0").forEach {
            reject(auxiliaryRouteWire(duration = it))
        }
        listOf("false", "300.0", "-1", "10000001", "0").forEach {
            reject(auxiliaryRouteWire(distance = it))
        }
        reject(auxiliaryRouteWire().replace("\"startPointIndex\":0", "\"startPointIndex\":0.0"))
        reject(auxiliaryRouteWire().replace("\"endPointIndex\":2", "\"endPointIndex\":3"))
        reject(auxiliaryRouteWire().replace("\"distanceMeters\":100", "\"distanceMeters\":10000001"))
    }

    @Test fun pointGuideAndRawByteBoundsAreCheckedBeforeElementConsumption() {
        reject(auxiliaryRouteWire(path = "[[127,37]]"))
        reject(auxiliaryRouteWire(path = List(AuxiliaryRouteGeometry.MAX_POINTS + 1) { "null" }.joinToString(",", "[", "]")))
        reject(auxiliaryRouteWire(guides = List(AuxiliaryRouteGeometry.MAX_GUIDES + 1) { "null" }.joinToString(",", "[", "]")))
        try { AuxiliaryRouteCodec.decode(ByteArray(AuxiliaryRouteCodec.MAX_RESPONSE_BYTES + 1)); fail("Oversized response") }
        catch (failure: AuxiliaryRouteException) { assertEquals("INVALID_AUXILIARY_ROUTE", failure.code) }
    }

    @Test fun missingTailAndProviderWithheldTimingAreKeptWithoutForecastPromotion() {
        val incomplete = decode(auxiliaryRouteWire(
            guides = "[{\"startPointIndex\":0,\"endPointIndex\":1,\"durationMs\":1000,\"distanceMeters\":100}]",
            coverage = false, totals = false, usable = false))
        assertFalse(incomplete.timingUsable)
        assertEquals(1, incomplete.geometry.guides.single().endPointIndex)
        assertFalse(decode(auxiliaryRouteWire(coverage = false, totals = false, usable = false)).timingUsable)
        assertTrue(decode(auxiliaryRouteWire(guides = "[]", coverage = false, totals = false, usable = false)).geometry.guides.isEmpty())
    }

    @Test fun claimedCoverageTotalsAndUsabilityAreIndependentlyChecked() {
        reject(auxiliaryRouteWire(duration = "3001"))
        reject(auxiliaryRouteWire(coverage = false))
        reject(auxiliaryRouteWire(totals = false))
        reject(auxiliaryRouteWire(guides = "[]"))
        reject(auxiliaryRouteWire().replace("\"startPointIndex\":1", "\"startPointIndex\":0"))
        reject(auxiliaryRouteWire(coverage = false, totals = false, usable = false)
            .replace("\"startPointIndex\":1", "\"startPointIndex\":2"))
        reject(auxiliaryRouteWire(path = "[[127,37],[127,37],[127,37]]", usable = false))
    }

    @Test fun stationaryOrZeroDurationTravelCannotClaimUsableTiming() {
        reject(auxiliaryRouteWire().replace("\"durationMs\":1000", "\"durationMs\":0").replace("\"totalDurationMs\":3000", "\"totalDurationMs\":2000"))
        reject(auxiliaryRouteWire(path = "[[127,37],[127,37],[127.2,37.2]]"))
        assertFalse(decode(auxiliaryRouteWire(path = "[[127,37],[127,37],[127.2,37.2]]", usable = false)).timingUsable)
    }

    @Test fun requestUsesOnlyFixedFieldsAndCopiesBoundedWaypoints() {
        val mutable = mutableListOf(BoundaryCoordinate(127.1, 37.1))
        val request = AuxiliaryRouteRequest(BoundaryCoordinate(127.0, 37.0), BoundaryCoordinate(127.2, 37.2), mutable)
        mutable.clear()
        assertEquals("{\"origin\":[127.0,37.0],\"destination\":[127.2,37.2],\"waypoints\":[[127.1,37.1]],\"option\":\"traoptimal\"}",
            AuxiliaryRouteCodec.encode(request).toString(Charsets.UTF_8))
        assertEquals(1, request.waypoints.size)
        assertFalse(request.toString().contains("127"))
        listOf<() -> Unit>(
            { AuxiliaryRouteRequest(BoundaryCoordinate(Double.NaN, 37.0), request.destination) },
            { AuxiliaryRouteRequest(request.origin, request.destination, List(6) { request.origin }) },
            { AuxiliaryRouteRequest(request.origin, request.destination, option = "PRIVATE_UNEXPECTED_OPTION") },
        ).forEach { build -> try { build(); fail("Invalid request") } catch (_: IllegalArgumentException) { } }
    }

    @Test fun errorSchemaAndStatusMustAgreeAndNeverExposeProviderText() {
        assertEquals(AuxiliaryRouteFailureKind.AUTHORIZATION, AuxiliaryRouteCodec.decodeFailure(401, auxiliaryErrorWire("UNAUTHORIZED")))
        assertEquals(AuxiliaryRouteFailureKind.TIMEOUT, AuxiliaryRouteCodec.decodeFailure(504, auxiliaryErrorWire("ROUTE_TIMEOUT", true)))
        assertEquals(AuxiliaryRouteFailureKind.UNAVAILABLE, AuxiliaryRouteCodec.decodeFailure(503, auxiliaryErrorWire("AUXILIARY_ROUTE_UNAVAILABLE")))
        listOf(503 to auxiliaryErrorWire("UNAUTHORIZED"), 401 to auxiliaryErrorWire("PRIVATE_PROVIDER_DIAGNOSTIC"),
            401 to auxiliaryErrorWire("UNAUTHORIZED").toString(Charsets.UTF_8).replace("null", "\"unexpected\"").toByteArray()).forEach { (status, bytes) ->
            try { AuxiliaryRouteCodec.decodeFailure(status, bytes); fail("Invalid error accepted") }
            catch (failure: AuxiliaryRouteException) { assertEquals("INVALID_AUXILIARY_ROUTE", failure.message) }
        }
    }

    private fun decode(value: String) = AuxiliaryRouteCodec.decode(value.toByteArray())
    private fun reject(value: String) {
        try { decode(value); fail("Invalid auxiliary response accepted") }
        catch (failure: AuxiliaryRouteException) { assertEquals("INVALID_AUXILIARY_ROUTE", failure.code) }
    }
}
