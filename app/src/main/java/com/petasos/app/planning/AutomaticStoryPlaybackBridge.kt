package com.petasos.app.planning

import com.petasos.app.location.LocationFreshness
import com.petasos.app.location.RecordedLocation
import com.petasos.app.playback.OutputReference
import com.petasos.app.playback.PreparedStory
import com.petasos.app.runtime.RuntimeContentAuthority
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Host maps these to Coordinator offer/withdraw commands on the same serialized runtime queue. */
interface PlannedStoryOutput {
    fun offer(story: PreparedStory, offerId: String)
    fun withdraw(storyId: String, versionId: String)
}

/**
 * One automatic offer across ordinary and landmark stories. The coordinator owns continuation,
 * playback timing and the music gap. Updates observe movement even while output is suppressed.
 * Mutations share the host's worker queue; final read-only authority checks may run elsewhere.
 */
class AutomaticStoryPlaybackBridge(
    private val planner: ContentPlanner,
    private val candidates: (String?, Set<String>) -> List<AutomaticStoryCandidate>,
    private val output: PlannedStoryOutput,
    private val elapsedTime: () -> Long,
    private val candidateCurrent: (AutomaticStoryCandidate, String?) -> Boolean,
    private val maximumLocationAgeMs: Long = LocationFreshness.DEFAULT_FRESHNESS_MS,
    private val targetCurrent: (RegionalStoryTarget) -> Boolean = { false },
    private val targetPlayable: (RegionalStoryTarget) -> Boolean = { false },
    private val landmarkSession: LandmarkCandidateSession? = null,
    private val landmarkCandidates: (Set<String>) -> List<LandmarkStoryCandidate> = { emptyList() },
    private val landmarkCurrent: (LandmarkStoryCandidate) -> Boolean = { false },
    /** Duration-aware Host waits for a regional target; standalone current-region adapters may omit it. */
    private val requireRegionalTarget: Boolean = false,
) : RuntimeContentAuthority {
    init { require(maximumLocationAgeMs > 0) }

    private sealed interface Entry {
        val id: String
        val story: PreparedStory
        data class Ordinary(override val id: String, val candidate: AutomaticStoryCandidate, val targetKey: String?) : Entry {
            override val story get() = candidate.story
        }
        data class Landmark(val offer: LandmarkOffer) : Entry {
            override val id get() = offer.candidateId
            override val story get() = offer.candidate.story
        }
    }
    private data class Authority(val offerId: String, val entry: Entry, val context: PlanningContext)
    private val authority = AtomicReference<Authority?>()
    private val excludedStoryIds = mutableSetOf<String>()
    private var inventory = emptyList<Entry.Ordinary>()
    private var activeLandmark: LandmarkOffer? = null
    private var offered: Entry? = null

    fun update(
        context: PlanningContext,
        point: RecordedLocation? = null,
        route: LandmarkRouteContext = LandmarkRouteContext.Unavailable,
        allowOffer: Boolean = true,
    ) {
        val region = selectionRegion(context)
        // Region selection and actual location quality are different gates: landmarks need no region target.
        val checkedContext = context.copy(currentRegionId = freshRegion(context), locationValid = freshLocation(context))
        val eligible = try {
            val excluded = Collections.unmodifiableSet(excludedStoryIds.toSet())
            activeLandmark = landmarkSession?.update(
                landmarkCandidates(excluded).filter { it.story.storyId !in excluded }, point, checkedContext, route)
            // Starts and explicit skips are session exclusions, applied before the store's result cap.
            candidates(region, excluded).filter { candidate ->
                candidate.story.storyId !in excludedStoryIds &&
                    (candidate.reason == CandidateReason.INTEREST || eligibleRegion(context, candidate.regionId))
            }.sortedBy { if (it.reason == CandidateReason.CURRENT_REGION) 0 else 1 }
                .distinctBy { it.story.storyId }
        } catch (failure: Exception) {
            clear()
            throw failure
        }
        val updated = eligible.map { candidate ->
            val targetKey = context.regionalTarget?.takeIf { candidate.reason == CandidateReason.CURRENT_REGION }
                ?.let { "${it.sessionId}:${it.clockScopeId}:${it.planningRevision}" }
            inventory.firstOrNull { it.candidate == candidate && it.targetKey == targetKey }
                ?: Entry.Ordinary(UUID.randomUUID().toString(), candidate, targetKey)
        }
        val retainedIds = updated.map { it.id }.toSet()
        inventory.filter { it.id !in retainedIds }.forEach { planner.expire(it.id) }
        inventory = updated
        inventory.forEach { entry ->
            val predicted = entry.candidate.reason == CandidateReason.CURRENT_REGION && context.regionalTarget != null
            planner.offer(StoryCandidate(entry.id, entry.story,
                if (predicted) CandidateReason.UPCOMING_REGION else entry.candidate.reason, entry.candidate.regionId,
                routeDependency = if (predicted) context.regionalTarget?.routeDependency else null, expiresAtElapsedMs = null))
        }

        val selectedId = if (allowOffer) planner.chooseAtBoundary(checkedContext) {
            landmarkSession?.stillApproaching(it) == true
        }?.candidateId else null
        val next: Entry? = activeLandmark?.takeIf { it.candidateId == selectedId }?.let { Entry.Landmark(it) }
            ?: inventory.firstOrNull { it.id == selectedId }
        val previous = offered
        if (next?.id == previous?.id) {
            val current = authority.get()
            if (current != null) {
                // A main-thread ingress can revoke concurrently; never restore its old token.
                authority.compareAndSet(current, current.copy(context = checkedContext))
                return
            }
            if (next == null) return
        }
        authority.set(null) // Revoke before a withdrawal can wait in the runtime queue.
        if (previous != null) output.withdraw(previous.story.storyId, previous.story.versionId)
        offered = next
        if (next != null) {
            val offerId = UUID.randomUUID().toString()
            authority.set(Authority(offerId, next, checkedContext))
            output.offer(next.story, offerId)
        }
    }

    /** Only the exact offer acknowledged by the actual SDK start can consume this session's automatic story. */
    fun storyStarted(offerId: String, versionId: String) {
        val current = authority.get() ?: return
        if (current.offerId != offerId || current.entry.story.versionId != versionId) return
        excludedStoryIds += current.entry.story.storyId
        if (current.entry is Entry.Landmark) {
            landmarkSession?.consume(current.entry.id, elapsedTime())
            activeLandmark = null
        } else planner.consumed(current.entry.id)
        inventory.filter { it.story.storyId in excludedStoryIds }.forEach { planner.expire(it.id) }
        inventory = inventory.filterNot { it.story.storyId in excludedStoryIds }
        authority.set(null)
        offered = null
    }

    /** Explicit playback and already-authorized continuation cannot become this session's next automatic replay. */
    fun storyStartedWithoutOffer(storyId: String) { excludeFromAutomatic(storyId) }

    /** Accepted Next is an explicit skip, even before SDK Started; it never asserts completed listening. */
    fun storySkipped(storyId: String) { excludeFromAutomatic(storyId) }

    private fun excludeFromAutomatic(storyId: String) {
        excludedStoryIds += storyId
        inventory.filter { it.story.storyId == storyId }.forEach { planner.consumed(it.id) }
        inventory = inventory.filterNot { it.story.storyId == storyId }
        if (activeLandmark?.candidate?.story?.storyId == storyId) {
            landmarkSession?.clear(elapsedTime())
            activeLandmark = null
        }
        val pending = offered?.takeIf { it.story.storyId == storyId } ?: return
        authority.set(null)
        offered = null
        // Explicit starts/skips do not consume a queued automatic candidate in the core.
        output.withdraw(pending.story.storyId, pending.story.versionId)
    }

    fun clear() {
        authority.set(null)
        landmarkSession?.clear(elapsedTime())
        activeLandmark = null
        offered?.let { output.withdraw(it.story.storyId, it.story.versionId) }
        offered = null
        inventory.forEach { planner.expire(it.id) }
        inventory = emptyList()
    }

    /** Thread-safe ingress fence only. It neither queries Room nor stops an already started output. */
    fun revokeAuthority() { authority.set(null) }

    override fun isAutomaticStoryCurrent(offerId: String, reference: OutputReference.StoryParagraph): Boolean {
        val current = authority.get() ?: return false
        if (!matches(current, offerId, reference)) return false
        if (current.entry is Entry.Ordinary && current.entry.candidate.reason == CandidateReason.CURRENT_REGION &&
            current.context.regionalTarget?.routeDependency == null &&
            !(try { current.context.currentRegionEvidenceCurrent() } catch (_: Exception) { false })) {
            authority.compareAndSet(current, null)
            return false
        }
        val permitted = try {
            when (val entry = current.entry) {
                is Entry.Ordinary -> candidateCurrent(entry.candidate, selectionRegion(current.context))
                is Entry.Landmark -> landmarkCurrent(entry.offer.candidate)
            }
        } catch (_: Exception) { false }
        // Location/route/preferences/Stop ingress can revoke while the evidence lookup is in progress.
        return permitted && authority.get() === current && matches(current, offerId, reference)
    }

    /** Cheap final main-thread check after the worker's evidence lookup and immediately before SDK output. */
    override fun isAutomaticStoryOffered(offerId: String, reference: OutputReference.StoryParagraph): Boolean {
        val current = authority.get() ?: return false
        return matches(current, offerId, reference) && authority.get() === current
    }

    private fun matches(current: Authority, offerId: String, reference: OutputReference.StoryParagraph): Boolean {
        val entry = current.entry
        if (current.offerId != offerId || current.context.userSpeaking || entry.story.storyId != reference.storyId ||
            entry.story.versionId != reference.versionId || reference.paragraphId !in entry.story.paragraphIds) return false
        return when (entry) {
            is Entry.Ordinary -> entry.candidate.reason == CandidateReason.INTEREST ||
                eligibleRegion(current.context, entry.candidate.regionId) &&
                (current.context.regionalTarget?.let { try { targetPlayable(it) } catch (_: Exception) { false } } ?: true)
            is Entry.Landmark -> freshLocation(current.context) && landmarkSession?.isCurrent(entry.offer) == true
        }
    }

    private fun selectionRegion(context: PlanningContext): String? {
        if (!freshLocation(context) || requireRegionalTarget && context.regionalTarget == null) return null
        val target = context.regionalTarget ?: return freshRegion(context)
        if (target.routeDependency == null && !regionAuthorized(context)) return null
        return target.regionId.takeIf { try { targetCurrent(target) } catch (_: Exception) { false } }
    }

    private fun eligibleRegion(context: PlanningContext, storyRegionId: String?): Boolean {
        val selected = selectionRegion(context) ?: return false
        val target = context.regionalTarget
        return if (target != null && target.regionId == selected) target.allowsStoryRegion(storyRegionId)
        else selected == storyRegionId
    }

    private fun freshRegion(context: PlanningContext): String? {
        if (!freshLocation(context) || !regionAuthorized(context)) return null
        return context.currentRegionId?.takeIf { it.isNotBlank() }
    }

    private fun regionAuthorized(context: PlanningContext): Boolean =
        try { context.currentRegionAuthority() } catch (_: Exception) { false }

    private fun freshLocation(context: PlanningContext): Boolean = context.locationValid &&
        LocationFreshness.isFresh(context.locationReceivedElapsedMs, context.locationFreshUntilElapsedMs,
            elapsedTime(), maximumLocationAgeMs) &&
            try { context.locationAuthority() } catch (_: Exception) { false }
}
