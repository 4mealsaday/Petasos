package com.petasos.app.presentation.records

import org.junit.Assert.*
import org.junit.Test

class RecordedPathMapperTest {
    @Test fun continuousObservedSamplesBecomeOnePathWithoutAddingAnyPoints() {
        val input = listOf(sample(1), sample(2), sample(3))
        val result = RecordedPathMapper.map(input)
        assertEquals(1, result.size)
        assertEquals(input.map { it.coordinate }, result.single().points)
    }

    @Test fun invalidAndMissingSamplesNeverJoinTheSurroundingGoodFixes() {
        listOf(sample(2).copy(qualityValid = false), sample(2).copy(coordinate = null),
            sample(2).copy(coordinate = MapCoordinate(Double.NaN, 127.0)),
            sample(2).copy(coordinate = MapCoordinate(90.0, 127.0))).forEach { gap ->
            val result = RecordedPathMapper.map(listOf(sample(1), gap, sample(3)))
            assertEquals(2, result.size)
            assertTrue(result.all { it.points.size == 1 })
        }
    }

    @Test fun receiptOrMeasurementGapsDuplicatesAndSequenceLossBreakPaths() {
        val one = sample(1)
        val two = sample(2)
        listOf(two.copy(receivedElapsedMs = one.receivedElapsedMs + 10_001),
            two.copy(observedAt = one.observedAt!! + 10_001), two.copy(receivedElapsedMs = one.receivedElapsedMs),
            two.copy(observedAt = one.observedAt), two.copy(sequence = 3), two.copy(observedAt = null)
        ).forEach { gap -> assertEquals(2, RecordedPathMapper.map(listOf(one, gap)).size) }
        assertEquals(1, RecordedPathMapper.map(listOf(one, two.copy(receivedElapsedMs = one.receivedElapsedMs + 10_000,
            observedAt = one.observedAt!! + 10_000))).size)
    }

    @Test fun tripSegmentRouteClockAndProviderChangesBreakPaths() {
        val two = sample(2)
        listOf(two.copy(tripId = "other"), two.copy(segmentId = "other"), two.copy(routeRevision = 2),
            two.copy(clockScopeId = "other"), two.copy(provider = "other"), two.copy(providerEpoch = "new")
        ).forEach { gap -> assertEquals(2, RecordedPathMapper.map(listOf(sample(1), gap)).size) }
    }

    @Test fun emptyOrInvalidInputProducesNoFabricatedRoute() {
        assertTrue(RecordedPathMapper.map(emptyList()).isEmpty())
        assertTrue(RecordedPathMapper.map(listOf(sample(1).copy(qualityValid = false))).isEmpty())
        assertEquals("MapCoordinate([redacted])", MapCoordinate(37.0, 127.0).toString())
        assertEquals("RecordedMapSample([redacted])", sample(1).toString())
        assertEquals("RecordedPath([redacted])", RecordedPathMapper.map(listOf(sample(1))).single().toString())
    }

    private fun sample(sequence: Long) = RecordedMapSample("trip", "segment", sequence, "clock", sequence * 1_000,
        sequence * 1_000, 1, true, MapCoordinate(37.0 + sequence / 10_000.0, 127.0), "android", "provider-epoch")
}
