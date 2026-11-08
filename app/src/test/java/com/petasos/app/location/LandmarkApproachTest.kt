package com.petasos.app.location

import org.junit.Assert.*
import org.junit.Test

class LandmarkApproachTest {
    private val policy = LandmarkPolicy(1_000.0, LandmarkPassedRule.INCREASING_DISTANCE_AFTER_APPROACH,
        LandmarkDirectionRule.DECREASING_DISTANCE, 2_000, "synthetic-v1", "synthetic-adapter")

    private fun point(elapsed: Long, longitude: Double, accuracy: Double = 1.0) = RecordedLocation(
        "p-$elapsed", "synthetic", null, null, "${EPOCH + elapsed}", "epoch_ms", EPOCH + elapsed,
        EPOCH + elapsed, elapsed, "clock", 37.0, longitude, "{}", accuracy, "{}", LocationQuality.VALID, "synthetic-adapter",
    )

    private fun assess(previous: RecordedLocation?, current: RecordedLocation, now: Long = current.receivedAtElapsedMs) =
        LandmarkApproach.assess(37.0, 127.0, policy, previous, current, now, "clock")

    @Test fun admitsOnlyDistinctMeasuredDecreaseInsideReviewedRadius() {
        val previous = point(1_000, 127.020)
        val current = point(2_000, 127.005)
        assertEquals(LandmarkApproachState.APPROACHING, assess(previous, current).state)
        assertEquals(LandmarkApproachState.HOLDING, assess(null, current).state)
        assertEquals(LandmarkApproachState.RECEDING, assess(current, point(3_000, 127.006)).state)
        assertEquals(LandmarkApproachState.OUTSIDE, assess(current, point(3_000, 127.020)).state)
    }

    @Test fun repeatedMeasurementWithNewCallbackIdDoesNotProveDirection() {
        val previous = point(1_000, 127.006)
        val duplicate = point(2_000, 127.004).copy(observedAtEpochMs = previous.observedAtEpochMs)
        assertEquals(LandmarkApproachState.HOLDING, assess(previous, duplicate).state)
        assertFalse(LandmarkApproach.distinctInOrder(previous, duplicate))
        assertFalse(LandmarkApproach.distinctInOrder(previous, point(1_000, 127.004).copy(pointId = "different")))
    }

    @Test fun positionNoiseCannotEstablishApproachAndBoundaryOverlapCannotAuthorize() {
        val previous = point(1_000, 127.005, accuracy = 50.0)
        val noisy = point(2_000, 127.0049, accuracy = 50.0)
        assertEquals(LandmarkApproachState.HOLDING, assess(previous, noisy).state)
        assertEquals(LandmarkApproachState.UNAVAILABLE,
            assess(previous, point(2_000, 127.005, accuracy = 600.0)).state)
    }

    @Test fun staleUnknownWrongAdapterAndWrongClockCannotAuthorize() {
        val previous = point(1_000, 127.006)
        val current = point(2_000, 127.004)
        assertEquals(LandmarkApproachState.UNAVAILABLE, assess(previous, current, 12_001).state)
        assertEquals(LandmarkApproachState.UNAVAILABLE, assess(previous, current.copy(quality = LocationQuality.UNKNOWN)).state)
        assertEquals(LandmarkApproachState.UNAVAILABLE, assess(previous, current.copy(adapterVersion = "other")).state)
        assertEquals(LandmarkApproachState.UNAVAILABLE, assess(previous, current.copy(clockScopeId = "other")).state)
    }

    @Test fun currentFreshPointAfterStalePreviousDoesNotProveApproach() {
        assertEquals(LandmarkApproachState.UNAVAILABLE, assess(point(1_000, 127.006), point(12_000, 127.004)).state)
    }

    @Test fun invalidPolicyHasNoSilentDefaults() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { radius ->
            try { policy.copy(approachRadiusMeters = radius); fail("invalid radius accepted") } catch (_: IllegalArgumentException) { }
        }
        try { policy.copy(cooldownMs = -1); fail("invalid cooldown accepted") } catch (_: IllegalArgumentException) { }
        try { policy.copy(qualityAdapterVersion = ""); fail("missing adapter accepted") } catch (_: IllegalArgumentException) { }
    }

    companion object { private const val EPOCH = 1_700_000_000_000L }
}
