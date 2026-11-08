package com.petasos.app.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** DATA-11 rule names are deliberately closed; an unknown rule cannot silently use a default. */
enum class LandmarkDirectionRule { DECREASING_DISTANCE }
enum class LandmarkPassedRule { INCREASING_DISTANCE_AFTER_APPROACH }

/** Thresholds and adapter identity are supplied by reviewed data, never production defaults. */
data class LandmarkPolicy(
    val approachRadiusMeters: Double,
    val passedRule: LandmarkPassedRule,
    val directionRule: LandmarkDirectionRule,
    val cooldownMs: Long,
    val policyVersion: String,
    val qualityAdapterVersion: String,
) {
    init {
        require(approachRadiusMeters.isFinite() && approachRadiusMeters > 0)
        require(cooldownMs >= 0)
        require(policyVersion.isNotBlank() && policyVersion.length <= 200)
        require(qualityAdapterVersion.isNotBlank() && qualityAdapterVersion.length <= 200)
    }
}

data class LandmarkDistanceRange(val minimumMeters: Double, val maximumMeters: Double)
enum class LandmarkApproachState { APPROACHING, HOLDING, OUTSIDE, RECEDING, UNAVAILABLE }
data class LandmarkApproachAssessment(val state: LandmarkApproachState, val distance: LandmarkDistanceRange?)

/**
 * Uses actual, meaning-verified positions only. It never reads raw SDK heading/confidence, predicts
 * arrival, or turns a route waypoint into a road. HOLDING can retain an admitted candidate but
 * cannot create one: a stationary/noisy/repeated measurement does not establish approach.
 */
object LandmarkApproach {
    fun assess(
        latitude: Double,
        longitude: Double,
        policy: LandmarkPolicy,
        previous: RecordedLocation?,
        current: RecordedLocation?,
        elapsedMs: Long,
        clockScopeId: String,
    ): LandmarkApproachAssessment {
        if (!validCoordinate(latitude, longitude) || !usable(current, policy, elapsedMs, clockScopeId)) {
            return LandmarkApproachAssessment(LandmarkApproachState.UNAVAILABLE, null)
        }
        val point = checkNotNull(current)
        val distance = distanceRange(latitude, longitude, point)
        if (distance.minimumMeters > policy.approachRadiusMeters) {
            return LandmarkApproachAssessment(LandmarkApproachState.OUTSIDE, distance)
        }
        // A location uncertainty envelope straddling the radius is not proven inside.
        if (distance.maximumMeters > policy.approachRadiusMeters) {
            return LandmarkApproachAssessment(LandmarkApproachState.UNAVAILABLE, distance)
        }
        if (previous == null) {
            return LandmarkApproachAssessment(LandmarkApproachState.HOLDING, distance)
        }
        if (!usable(previous, policy, elapsedMs, clockScopeId)) {
            return LandmarkApproachAssessment(LandmarkApproachState.UNAVAILABLE, distance)
        }
        if (!distinctInOrder(previous, point)) return LandmarkApproachAssessment(LandmarkApproachState.HOLDING, distance)
        val before = distanceRange(latitude, longitude, previous)
        val approaching = when (policy.directionRule) {
            LandmarkDirectionRule.DECREASING_DISTANCE -> distance.maximumMeters < before.minimumMeters
        }
        val receding = when (policy.passedRule) {
            LandmarkPassedRule.INCREASING_DISTANCE_AFTER_APPROACH -> distance.minimumMeters > before.maximumMeters
        }
        val state = when {
            approaching -> LandmarkApproachState.APPROACHING
            receding -> LandmarkApproachState.RECEDING
            else -> LandmarkApproachState.HOLDING
        }
        return LandmarkApproachAssessment(state, distance)
    }

    fun usable(point: RecordedLocation?, policy: LandmarkPolicy, elapsedMs: Long, clockScopeId: String): Boolean {
        if (point == null || point.clockScopeId != clockScopeId || point.adapterVersion != policy.qualityAdapterVersion) return false
        val deadline = LocationFreshness.deadlineElapsedMs(point) ?: return false
        return LocationFreshness.isFresh(point.receivedAtElapsedMs, deadline, elapsedMs, LocationFreshness.DEFAULT_FRESHNESS_MS)
    }

    /** Different callback IDs are insufficient when the SDK repeats the same measurement. */
    fun distinctInOrder(previous: RecordedLocation, current: RecordedLocation): Boolean =
        previous.pointId != current.pointId && previous.clockScopeId == current.clockScopeId &&
            previous.observedAtEpochMs != null && current.observedAtEpochMs != null &&
            current.observedAtEpochMs > previous.observedAtEpochMs &&
            current.receivedAtElapsedMs > previous.receivedAtElapsedMs

    private fun distanceRange(latitude: Double, longitude: Double, point: RecordedLocation): LandmarkDistanceRange {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(checkNotNull(point.latitude))
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(checkNotNull(point.longitude) - longitude)
        val haversine = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        val angle = 2 * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
        val accuracy = checkNotNull(point.accuracyMeters)
        // WGS84's global meridional/prime-vertical curvature extrema bound the unit-sphere
        // metric. A single mean Earth radius would hide its approximation at policy thresholds.
        return LandmarkDistanceRange(max(0.0, angle * MIN_CURVATURE_RADIUS_METERS - accuracy),
            angle * MAX_CURVATURE_RADIUS_METERS + accuracy)
    }

    private fun validCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0

    private const val MIN_CURVATURE_RADIUS_METERS = 6_335_439.0
    private const val MAX_CURVATURE_RADIUS_METERS = 6_399_594.0
}
