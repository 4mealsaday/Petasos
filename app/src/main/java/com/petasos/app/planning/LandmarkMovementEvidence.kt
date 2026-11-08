package com.petasos.app.planning

import com.petasos.app.location.LandmarkApproach
import com.petasos.app.location.LandmarkApproachAssessment
import com.petasos.app.location.LandmarkApproachState
import com.petasos.app.location.RecordedLocation

/** The actual earlier observation that proves direction, not an interpolated or averaged point. */
internal data class LandmarkMovementAssessment(
    val assessment: LandmarkApproachAssessment,
    val baseline: RecordedLocation? = null,
)

/**
 * A bounded, continuous observation window for one exact candidate and route identity. Adjacent
 * accuracy ranges may overlap at high sample rates; an older still-fresh observation can establish
 * the same distance-range separation without weakening LandmarkApproach's rule. All comparisons
 * use the existing measurement-age deadline (10 seconds), not renewed callback receipt times.
 *
 * Definite recession against ANY retained observation wins over net approach from a farther one.
 * It ends that observation run, so a turn cannot be hidden by an old distant baseline. Stationary
 * bounded jitter keeps overlapping ranges and establishes neither direction. Accuracy remains a
 * statistical measurement, not a promised bound on real-world error.
 */
internal class LandmarkMovementEvidence(private val candidate: LandmarkStoryCandidate) {
    private val points = ArrayDeque<RecordedLocation>()

    fun observe(point: RecordedLocation, elapsedMs: Long, clockScopeId: String): LandmarkMovementAssessment {
        val current = assess(null, point, elapsedMs, clockScopeId)
        if (current.state == LandmarkApproachState.UNAVAILABLE) {
            points.clear()
            return LandmarkMovementAssessment(current)
        }
        val last = points.lastOrNull()
        if (last != null && (!LandmarkApproach.distinctInOrder(last, point) ||
                !LandmarkApproach.usable(last, candidate.policy, elapsedMs, clockScopeId))) {
            // A missing timer must not let an admitted offer bridge an observation gap. This fresh
            // point may start the next run, but cannot retain or create authority on this update.
            points.clear()
            points.addLast(point)
            return LandmarkMovementAssessment(LandmarkApproachAssessment(LandmarkApproachState.UNAVAILABLE, current.distance))
        }
        // Actual measurement-age deadlines need not share callback ordering. Keep only the fresh
        // suffix after the last expired point; never select a baseline across an expired interior.
        val lastExpired = points.indexOfLast { !LandmarkApproach.usable(it, candidate.policy, elapsedMs, clockScopeId) }
        repeat(lastExpired + 1) { points.removeFirst() }
        if (current.state == LandmarkApproachState.OUTSIDE) {
            // Only the most recent outside observation is useful for the next entry. In particular,
            // no older inside movement survives a physical exit.
            points.clear()
            points.addLast(point)
            return LandmarkMovementAssessment(current)
        }
        var approaching: LandmarkMovementAssessment? = null
        for (earlier in points) {
            val compared = assess(earlier, point, elapsedMs, clockScopeId)
            if (compared.state == LandmarkApproachState.RECEDING) {
                points.clear()
                points.addLast(point)
                return LandmarkMovementAssessment(compared, earlier)
            }
            if (compared.state == LandmarkApproachState.APPROACHING) {
                // Prefer the latest sufficient baseline, including after the exact cooldown exit.
                approaching = LandmarkMovementAssessment(compared, earlier)
            }
        }
        points.addLast(point)
        while (points.size > MAX_POINTS) points.removeFirst()
        return approaching ?: LandmarkMovementAssessment(current)
    }

    private fun assess(previous: RecordedLocation?, point: RecordedLocation, elapsedMs: Long, clockScopeId: String) =
        LandmarkApproach.assess(candidate.latitude, candidate.longitude, candidate.policy,
            previous, point, elapsedMs, clockScopeId)

    companion object {
        // Work/memory bound, not a location-policy threshold or a required sample count.
        const val MAX_POINTS = 64
    }
}
