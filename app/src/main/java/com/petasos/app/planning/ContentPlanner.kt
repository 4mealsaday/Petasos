package com.petasos.app.planning

import com.petasos.app.location.LocationFreshness
import com.petasos.app.playback.PreparedStory

enum class CandidateReason { CURRENT_REGION, UPCOMING_REGION, INTEREST, LANDMARK, CHECKPOINT }
enum class CandidateState { AVAILABLE, CONSUMED, EXPIRED }

data class StoryCandidate(
    val candidateId: String,
    val story: PreparedStory,
    val reason: CandidateReason,
    val regionId: String?,
    /** Null means current-position mode. GPS changes never change this revision. */
    val routeDependency: Long?,
    val expiresAtElapsedMs: Long?,
) {
    init {
        require(candidateId.isNotBlank())
        require(routeDependency == null || routeDependency >= 0)
        require(expiresAtElapsedMs == null || expiresAtElapsedMs >= 0)
    }
}

/** Region-gate defaults preserve caller-reviewed ports; the production Host supplies both explicit gates. */
data class PlanningContext(
    val currentRegionId: String?,
    val routeRevision: Long?,
    val elapsedMs: Long,
    val clockScopeId: String,
    val locationValid: Boolean,
    val userSpeaking: Boolean,
    val locationReceivedElapsedMs: Long? = null,
    /** A prediction never changes the observed currentRegionId or proves arrival. */
    val regionalTarget: RegionalStoryTarget? = null,
    /** Inclusive measurement-age deadline. Receipt alone never proves a fresh measurement. */
    val locationFreshUntilElapsedMs: Long? = null,
    /** Bound to the exact committed input; a later callback cannot reopen this context's permit. */
    val locationAuthority: () -> Boolean = { true },
    /** Memory-only authority for the confirmed administrative region, independent of raw position. */
    val currentRegionAuthority: () -> Boolean = { true },
    /** Off-main evidence lookup; only the playback bridge's final worker check may invoke it. */
    val currentRegionEvidenceCurrent: () -> Boolean = { true },
) {
    init {
        require(elapsedMs >= 0 && clockScopeId.isNotBlank()); require(routeRevision == null || routeRevision >= 0)
        require(locationReceivedElapsedMs == null || locationReceivedElapsedMs >= 0)
        require(locationFreshUntilElapsedMs == null || locationFreshUntilElapsedMs >= 0)
    }
}

/** No playback effects. The caller invokes at paragraph/track boundaries and validates content again before use. */
class ContentPlanner(private val clockScopeId: String) {
    private val candidates = linkedMapOf<String, StoryCandidate>()
    private val terminal = mutableMapOf<String, CandidateState>()
    private var landmarkId: String? = null

    fun offer(candidate: StoryCandidate): Boolean {
        if (candidate.candidateId in terminal) return false
        val existing = candidates[candidate.candidateId]
        if (existing != null) return existing == candidate
        if (candidate.reason == CandidateReason.LANDMARK && landmarkId != null) return false
        candidates[candidate.candidateId] = candidate.copy(story = candidate.story.copy(paragraphIds = candidate.story.paragraphIds.toList()))
        if (candidate.reason == CandidateReason.LANDMARK) landmarkId = candidate.candidateId
        return true
    }

    /** On every location/route/input event, expire passed or invalid landmarks, including while speaking. */
    fun update(context: PlanningContext, landmarkStillApproaching: (StoryCandidate) -> Boolean = { false }) {
        candidates.values.toList().forEach { candidate ->
            val expired = context.clockScopeId != clockScopeId ||
                (candidate.expiresAtElapsedMs != null && context.elapsedMs >= candidate.expiresAtElapsedMs) ||
                (candidate.routeDependency != null && candidate.routeDependency != context.routeRevision) ||
                (candidate.reason == CandidateReason.LANDMARK && (!freshLocation(context) || !landmarkStillApproaching(candidate)))
            if (expired) retire(candidate.candidateId, CandidateState.EXPIRED)
        }
    }

    /** Plain region changes update the next choice, never interrupt an already playing story. */
    fun chooseAtBoundary(context: PlanningContext, landmarkStillApproaching: (StoryCandidate) -> Boolean = { false }): StoryCandidate? {
        update(context, landmarkStillApproaching)
        if (context.userSpeaking || context.clockScopeId != clockScopeId) return null
        return candidates.values.firstOrNull { it.reason == CandidateReason.LANDMARK } ?:
            candidates.values.firstOrNull { it.reason == CandidateReason.CHECKPOINT } ?:
            candidates.values.filter { freshLocation(context) &&
                (context.regionalTarget?.routeDependency != null || regionAuthorized(context)) && it.reason == CandidateReason.UPCOMING_REGION &&
                context.regionalTarget?.allowsStoryRegion(it.regionId) == true }
                .minWithOrNull(compareBy<StoryCandidate> {
                    context.regionalTarget?.storyScope?.eligibleStoryRegionIds?.indexOf(it.regionId) ?: 0
                }.thenBy { it.story.storyId }) ?:
            candidates.values.firstOrNull { freshLocation(context) && regionAuthorized(context) && it.reason == CandidateReason.CURRENT_REGION &&
                it.regionId != null && it.regionId == context.currentRegionId } ?:
            candidates.values.firstOrNull { it.reason == CandidateReason.INTEREST }
    }

    fun consumed(candidateId: String) { if (candidateId in candidates) retire(candidateId, CandidateState.CONSUMED) }
    fun expire(candidateId: String) { if (candidateId in candidates) retire(candidateId, CandidateState.EXPIRED) }
    fun state(candidateId: String): CandidateState? = terminal[candidateId] ?: if (candidateId in candidates) CandidateState.AVAILABLE else null

    private fun freshLocation(context: PlanningContext): Boolean = context.locationValid &&
        LocationFreshness.isFresh(context.locationReceivedElapsedMs, context.locationFreshUntilElapsedMs,
            context.elapsedMs, LocationFreshness.DEFAULT_FRESHNESS_MS) &&
            try { context.locationAuthority() } catch (_: Exception) { false }

    private fun regionAuthorized(context: PlanningContext): Boolean =
        try { context.currentRegionAuthority() } catch (_: Exception) { false }

    private fun retire(id: String, state: CandidateState) {
        candidates.remove(id)
        terminal[id] = state
        if (landmarkId == id) landmarkId = null
    }
}
