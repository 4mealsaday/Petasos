package com.petasos.app.planning

import com.petasos.app.location.LandmarkApproach
import com.petasos.app.location.LandmarkApproachAssessment
import com.petasos.app.location.LandmarkApproachState
import com.petasos.app.location.LocationFreshness
import com.petasos.app.location.RecordedLocation
import java.util.concurrent.atomic.AtomicReference

/**
 * Serialized host-worker state. Owns one encounter, not a second playback queue. ContentPlanner
 * remains the authority for AVAILABLE/CONSUMED/EXPIRED. The host must call update during interaction
 * and at paragraph/track boundaries, then pass stillApproaching to its single planner chooser.
 */
class LandmarkCandidateSession(
    private val planner: ContentPlanner,
    private val sessionId: String,
    private val clockScopeId: String,
) {
    init { require(sessionId.isNotBlank() && clockScopeId.isNotBlank()) }

    private class Encounter(val offer: LandmarkOffer, val routeKey: String) {
        var terminalAt: Long? = null
        var terminalMeasurement: RecordedLocation? = null
        var outsideAfterCooldown: RecordedLocation? = null
    }

    private class Movement(val candidate: LandmarkStoryCandidate, val routeKey: String) {
        val evidence = LandmarkMovementEvidence(candidate)
    }

    private val encounters = mutableMapOf<String, Encounter>()
    private val movements = mutableMapOf<String, Movement>()
    private var active: Encounter? = null
    private var activePermit = false
    private val currentPermit = AtomicReference<LandmarkOffer?>()
    private var sequence = 0L
    private var watermarkMeasurement: RecordedLocation? = null
    private var observedWatermark: Long? = null
    private var receiptWatermark: Long? = null
    private var lastElapsedMs: Long? = null

    fun update(
        candidates: List<LandmarkStoryCandidate>,
        point: RecordedLocation?,
        context: PlanningContext,
        route: LandmarkRouteContext,
    ): LandmarkOffer? {
        currentPermit.set(null)
        return try {
            val offer = updateOnWorker(candidates, point, context, route)
            currentPermit.set(offer)
            offer
        } catch (failure: Exception) {
            movements.clear()
            retireActive(context.elapsedMs)
            throw failure
        }
    }

    private fun updateOnWorker(
        candidates: List<LandmarkStoryCandidate>,
        point: RecordedLocation?,
        context: PlanningContext,
        route: LandmarkRouteContext,
    ): LandmarkOffer? {
        val contextCurrent = currentContext(point, context) &&
            (lastElapsedMs == null || context.elapsedMs >= lastElapsedMs!!)
        lastElapsedMs = maxOf(lastElapsedMs ?: context.elapsedMs, context.elapsedMs)
        var newMeasurement = false
        var ordered = contextCurrent
        if (!contextCurrent) {
            movements.clear()
        } else {
            val current = checkNotNull(point)
            val observed = checkNotNull(current.observedAtEpochMs)
            val receipt = current.receivedAtElapsedMs
            val previousObserved = observedWatermark
            val previousReceipt = receiptWatermark
            if (previousObserved == null || (observed > previousObserved && receipt > checkNotNull(previousReceipt) &&
                    current.pointId != watermarkMeasurement?.pointId)) {
                observedWatermark = observed
                receiptWatermark = receipt
                watermarkMeasurement = current
                newMeasurement = true
            } else if (observed != previousObserved || receipt < checkNotNull(previousReceipt) ||
                current.latitude != watermarkMeasurement?.latitude || current.longitude != watermarkMeasurement?.longitude ||
                current.adapterVersion != watermarkMeasurement?.adapterVersion || current.accuracyMeters != watermarkMeasurement?.accuracyMeters) {
                // Never rewind the movement history using a late callback or a backwards clock.
                movements.clear()
                ordered = false
            } else {
                // A cache callback is not movement, but a subsequent callback cannot go backwards
                // in receipt order relative to it either.
                receiptWatermark = maxOf(receiptWatermark!!, receipt)
            }
        }

        synchronizeTerminal(context.elapsedMs)
        val assessments = movementAssessments(candidates, point, context, route, newMeasurement, ordered)
        active?.let { encounter ->
            val candidate = encounter.offer.candidate
            val available = movements[candidate.placeId]?.candidate == candidate &&
                candidates.singleOrNull { it.placeId == candidate.placeId } == candidate
            val assessment = if (!ordered) null else if (newMeasurement) assessments[candidate.placeId]?.assessment
                else assess(candidate, null, point, context)
            activePermit = available && routeKey(candidate, context, route) == encounter.routeKey &&
                assessment?.state in setOf(LandmarkApproachState.APPROACHING, LandmarkApproachState.HOLDING)
            if (!activePermit) retireActive(context.elapsedMs)
        }
        planner.update(context, ::stillApproaching)
        synchronizeTerminal(context.elapsedMs)

        // Expiry is irreversible for its ID. A separate encounter requires a later physical exit
        // after cooldown and then a new, independently measured approach. Timer/talk/route changes
        // and callback IDs never satisfy either part of this sequence.
        if (newMeasurement && ordered) {
            for (candidate in candidates) {
                val encounter = encounters[candidate.placeId] ?: continue
                val terminalAt = encounter.terminalAt ?: continue
                // A delayed callback cannot turn an outside measurement taken during cooldown
                // into a later physical exit. Preserve the adapter's original measurement age.
                val measurementElapsed = point?.let { LocationFreshness.deadlineElapsedMs(it) }
                    ?.minus(LocationFreshness.DEFAULT_FRESHNESS_MS) ?: continue
                if (!sameGeometryPolicy(encounter.offer.candidate, candidate) ||
                    context.elapsedMs < terminalAt || context.elapsedMs - terminalAt < candidate.policy.cooldownMs ||
                    measurementElapsed < terminalAt || measurementElapsed - terminalAt < candidate.policy.cooldownMs ||
                    !laterThan(point, encounter.terminalMeasurement)) continue
                if (assess(candidate, null, point, context).state == LandmarkApproachState.OUTSIDE) {
                    encounter.outsideAfterCooldown = point
                }
            }
        }

        if (active == null && newMeasurement && ordered) {
            val eligible = candidates.mapNotNull { candidate ->
                val key = routeKey(candidate, context, route) ?: return@mapNotNull null
                val movement = assessments[candidate.placeId] ?: return@mapNotNull null
                val assessment = movement.assessment
                if (assessment.state != LandmarkApproachState.APPROACHING) return@mapNotNull null
                val old = encounters[candidate.placeId]
                if (old != null) {
                    val outside = old.outsideAfterCooldown ?: return@mapNotNull null
                    if (old.terminalAt == null || !sameGeometryPolicy(old.offer.candidate, candidate) ||
                        !atOrAfter(movement.baseline, outside) || !laterThan(point, outside)) return@mapNotNull null
                }
                Triple(candidate, key, checkNotNull(assessment.distance).maximumMeters)
            }.sortedWith(compareBy<Triple<LandmarkStoryCandidate, String, Double>> { it.third }
                .thenBy { it.first.placeId }.thenBy { it.first.story.storyId })
            for ((candidate, key) in eligible) {
                check(sequence < Long.MAX_VALUE)
                val offer = LandmarkOffer("landmark:$sessionId:$clockScopeId:${++sequence}", candidate,
                    (route as? LandmarkRouteContext.OnRoute)?.revision)
                if (!planner.offer(StoryCandidate(offer.candidateId, candidate.story, CandidateReason.LANDMARK,
                        regionId = null, routeDependency = offer.routeDependency, expiresAtElapsedMs = null))) continue
                val encounter = Encounter(offer, key)
                active = encounter
                activePermit = true
                encounters[candidate.placeId] = encounter
                break
            }
        }
        return active?.offer
    }

    /** Cheap worker-owned predicate; ContentPlanner separately checks current input freshness. */
    fun stillApproaching(candidate: StoryCandidate): Boolean = activePermit &&
        candidate.reason == CandidateReason.LANDMARK && active?.offer?.let {
            it.candidateId == candidate.candidateId && it.candidate.story == candidate.story &&
                it.routeDependency == candidate.routeDependency
        } == true

    /** Read-only final-thread permit. The bridge additionally checks its exact input/time authority. */
    fun isCurrent(offer: LandmarkOffer): Boolean = currentPermit.get() === offer

    /** Invoke only after the actual SDK Started acknowledgement for the exact automatic offer. */
    fun consume(candidateId: String, elapsedMs: Long) {
        require(elapsedMs >= 0)
        val encounter = active?.takeIf { it.offer.candidateId == candidateId } ?: return
        currentPermit.set(null)
        planner.consumed(candidateId)
        markTerminal(encounter, elapsedMs)
    }

    /** Withdraw authority without erasing terminal/cooldown history. This does not end a clock scope. */
    fun clear(elapsedMs: Long) {
        require(elapsedMs >= 0)
        currentPermit.set(null)
        movements.clear()
        retireActive(elapsedMs)
    }

    private fun movementAssessments(
        candidates: List<LandmarkStoryCandidate>, point: RecordedLocation?, context: PlanningContext,
        route: LandmarkRouteContext, newMeasurement: Boolean, ordered: Boolean,
    ): Map<String, LandmarkMovementAssessment> {
        if (!ordered || point == null) {
            movements.clear()
            return emptyMap()
        }
        // An ambiguous duplicate place, changed policy/source/story, or route boundary starts a new
        // run. We never borrow observations from before the candidate had current authority.
        val current = candidates.groupBy { it.placeId }.mapNotNull { (placeId, rows) ->
            val candidate = rows.singleOrNull() ?: return@mapNotNull null
            val key = routeKey(candidate, context, route) ?: return@mapNotNull null
            placeId to (candidate to key)
        }.toMap()
        movements.entries.removeAll { (placeId, movement) ->
            val bound = current[placeId]
            bound == null || bound.first != movement.candidate || bound.second != movement.routeKey
        }
        if (!newMeasurement) return emptyMap()
        return current.mapValues { (placeId, bound) ->
            movements.getOrPut(placeId) { Movement(bound.first, bound.second) }
                .evidence.observe(point, context.elapsedMs, clockScopeId)
        }
    }

    private fun synchronizeTerminal(elapsedMs: Long) {
        val encounter = active ?: return
        if (planner.state(encounter.offer.candidateId) != CandidateState.AVAILABLE) markTerminal(encounter, elapsedMs)
    }

    private fun retireActive(elapsedMs: Long) {
        currentPermit.set(null)
        val encounter = active ?: return
        planner.expire(encounter.offer.candidateId)
        markTerminal(encounter, elapsedMs)
    }

    private fun markTerminal(encounter: Encounter, elapsedMs: Long) {
        currentPermit.set(null)
        movements.remove(encounter.offer.candidate.placeId)
        if (encounter.terminalAt == null) {
            encounter.terminalAt = maxOf(elapsedMs, lastElapsedMs ?: elapsedMs)
            encounter.terminalMeasurement = watermarkMeasurement
        }
        activePermit = false
        active = null
    }

    private fun currentContext(point: RecordedLocation?, context: PlanningContext): Boolean {
        if (point == null || context.clockScopeId != clockScopeId || point.clockScopeId != clockScopeId || !context.locationValid ||
            point.receivedAtElapsedMs != context.locationReceivedElapsedMs ||
            !LocationFreshness.isFresh(context.locationReceivedElapsedMs, context.locationFreshUntilElapsedMs,
                context.elapsedMs, LocationFreshness.DEFAULT_FRESHNESS_MS) ||
            !(try { context.locationAuthority() } catch (_: Exception) { false })) return false
        val deadline = LocationFreshness.deadlineElapsedMs(point) ?: return false
        return context.locationFreshUntilElapsedMs!! <= deadline
    }

    private fun assess(candidate: LandmarkStoryCandidate, previous: RecordedLocation?, point: RecordedLocation?, context: PlanningContext): LandmarkApproachAssessment =
        LandmarkApproach.assess(candidate.latitude, candidate.longitude, candidate.policy, previous, point, context.elapsedMs, clockScopeId)

    private fun routeKey(candidate: LandmarkStoryCandidate, context: PlanningContext, route: LandmarkRouteContext): String? = when (route) {
        LandmarkRouteContext.CurrentPosition -> "current_position".takeIf { context.routeRevision == null }
        LandmarkRouteContext.Unavailable -> null
        is LandmarkRouteContext.OnRoute -> "route:${route.revision}:${route.evidenceKey}".takeIf {
            context.routeRevision == route.revision && candidate.placeId in route.eligiblePlaceIds
        }
    }

    private fun sameGeometryPolicy(first: LandmarkStoryCandidate, second: LandmarkStoryCandidate): Boolean =
        first.placeId == second.placeId && first.latitude == second.latitude && first.longitude == second.longitude &&
            first.coordinateSourceRevisionId == second.coordinateSourceRevisionId && first.policy == second.policy

    private fun laterThan(point: RecordedLocation?, earlier: RecordedLocation?): Boolean =
        point != null && earlier != null && LandmarkApproach.distinctInOrder(earlier, point)

    private fun atOrAfter(point: RecordedLocation?, earlier: RecordedLocation): Boolean = point != null &&
        point.clockScopeId == earlier.clockScopeId && point.observedAtEpochMs != null && earlier.observedAtEpochMs != null &&
        point.observedAtEpochMs >= earlier.observedAtEpochMs && point.receivedAtElapsedMs >= earlier.receivedAtElapsedMs
}
