package com.petasos.app.planning

import com.petasos.app.location.LocationFreshness
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/** One passage through a region. Returning to the same region requires a different visitId. */
data class RegionPassage(
    val visitId: String,
    val regionId: String,
    val entersAtElapsedMs: Long,
    val exitsAtElapsedMs: Long,
) {
    init {
        require(visitId.isNotBlank() && regionId.isNotBlank())
        require(entersAtElapsedMs >= 0 && exitsAtElapsedMs > entersAtElapsedMs)
    }
}

/**
 * Produced only by a reviewed route/region/ETA adapter. These windows are predictions, never
 * received positions or collection evidence. All times belong to the supplied clock scope.
 */
class ReviewedRouteForecast(
    val sessionId: String,
    val clockScopeId: String,
    val routeRevision: Long,
    val observedAtElapsedMs: Long,
    val validUntilElapsedMs: Long,
    passages: List<RegionPassage>,
) {
    val passages: List<RegionPassage> = Collections.unmodifiableList(passages.toList())

    init {
        require(sessionId.isNotBlank() && clockScopeId.isNotBlank())
        require(routeRevision >= 0 && observedAtElapsedMs >= 0)
        require(validUntilElapsedMs > observedAtElapsedMs)
        require(this.passages.map { it.visitId }.toSet().size == this.passages.size)
        require(this.passages.zipWithNext().all { (first, second) ->
            first.exitsAtElapsedMs <= second.entersAtElapsedMs
        })
    }
}

/** The production Host supplies exact region review identity/authority; trusted pure callers may omit them. */
data class RegionalPlanningInput(
    val sessionId: String,
    val clockScopeId: String,
    val routeRevision: Long?,
    val elapsedMs: Long,
    val locationReceivedElapsedMs: Long?,
    val locationValid: Boolean,
    val currentRegionId: String?,
    val interactionActive: Boolean,
    /** Caller supplies the remaining story plus required music duration, or null if unknown. */
    val earliestStoryStartElapsedMs: Long?,
    val forecast: ReviewedRouteForecast?,
    /** Inclusive measurement-age deadline in clockScopeId; unknown freshness grants no authority. */
    val locationFreshUntilElapsedMs: Long? = null,
    /** Live ingress fence bound to this exact committed sample, independently of queued updates. */
    val locationAuthority: () -> Boolean = { true },
    /** Exact reviewed policy/source identity for current-position fallback, separate from forecasts. */
    val currentRegionPolicyKey: String? = null,
    /** Memory-only permit; policy evidence is refreshed by the host off the SDK/main thread. */
    val currentRegionAuthority: () -> Boolean = { true },
) {
    init {
        require(sessionId.isNotBlank() && clockScopeId.isNotBlank())
        require(routeRevision == null || routeRevision >= 0)
        require(elapsedMs >= 0)
        require(locationReceivedElapsedMs == null || locationReceivedElapsedMs >= 0)
        require(currentRegionId == null || currentRegionId.isNotBlank())
        require(earliestStoryStartElapsedMs == null || earliestStoryStartElapsedMs >= 0)
        require(locationFreshUntilElapsedMs == null || locationFreshUntilElapsedMs >= 0)
        require(currentRegionPolicyKey == null || currentRegionPolicyKey.isNotBlank())
    }
}

data class RegionalStoryTarget(
    val sessionId: String,
    val clockScopeId: String,
    val planningRevision: Long,
    val regionId: String,
    val routeDependency: Long?,
    val visitId: String?,
    val earliestStartElapsedMs: Long,
    /** Forecast/passage expiry; current-position fallback also expires through location freshness. */
    val expiresAtElapsedMs: Long?,
    /** A changed confirmation policy must create a new planning revision even for the same region. */
    val regionPolicyKey: String? = null,
)

/**
 * Chooses a generation target, not a playback command or an assertion that the car arrived.
 * update runs on the serialized runtime queue; isCurrent is an immutable cross-thread permit check.
 * Replanning never interrupts an output. The coordinator still owns paragraph/music boundaries.
 */
class RegionalStoryPlanner(
    private val sessionId: String,
    private val clockScopeId: String,
    private val maximumLocationAgeMs: Long = LocationFreshness.DEFAULT_FRESHNESS_MS,
    private val maximumPlanningHorizonMs: Long = 600_000,
    /** A preparation/playback allowance for a soon-upcoming region, not an assertion of arrival. */
    private val maximumApproachLeadMs: Long = 30_000,
) {
    init {
        require(sessionId.isNotBlank() && clockScopeId.isNotBlank())
        require(maximumLocationAgeMs > 0 && maximumPlanningHorizonMs > 0)
        require(maximumApproachLeadMs >= 0)
    }

    private data class Snapshot(val input: RegionalPlanningInput, val target: RegionalStoryTarget)
    private val latest = AtomicReference<Snapshot?>()
    private var revision = 0L
    private var lastUpdateElapsedMs: Long? = null

    fun update(input: RegionalPlanningInput): RegionalStoryTarget? {
        if (input.sessionId != sessionId || input.clockScopeId != clockScopeId) return withdraw()
        val previousTime = lastUpdateElapsedMs
        if (previousTime != null && input.elapsedMs < previousTime) return withdraw()
        lastUpdateElapsedMs = input.elapsedMs
        if (input.interactionActive || !freshLocation(input, input.elapsedMs)) return withdraw()
        val suppliedStart = input.earliestStoryStartElapsedMs ?: return withdraw()
        // A delayed caller cannot schedule in the past, and subtraction avoids overflowing now + cap.
        val earliest = maxOf(input.elapsedMs, suppliedStart)
        if (earliest - input.elapsedMs > maximumPlanningHorizonMs) return withdraw()

        val forecast = input.forecast?.takeIf {
            forecastCurrent(it, input, input.elapsedMs) && agreesWithCurrentRegion(it, input)
        }
        val selected = if (forecast != null) {
            val passage = forecast.passages.firstOrNull { it.exitsAtElapsedMs > earliest }
                ?: return withdraw()
            if (passage.entersAtElapsedMs > earliest &&
                passage.entersAtElapsedMs - earliest > maximumApproachLeadMs) return withdraw()
            if (maxOf(earliest, passage.entersAtElapsedMs) - input.elapsedMs > maximumPlanningHorizonMs) return withdraw()
            RegionalStoryTarget(sessionId, clockScopeId, 0, passage.regionId, forecast.routeRevision,
                passage.visitId, earliest, minOf(passage.exitsAtElapsedMs, forecast.validUntilElapsedMs))
        } else {
            // No reviewed projection: prepare only the actually confirmed current region.
            // This does not predict that the car will remain here until earliestStartElapsedMs.
            val region = input.currentRegionId?.takeIf { regionAuthorized(input) } ?: return withdraw()
            RegionalStoryTarget(sessionId, clockScopeId, 0, region, null, null, earliest, null, input.currentRegionPolicyKey)
        }
        val previous = latest.get()?.target
        val nextRevision = if (previous != null && sameDestination(previous, selected)) {
            previous.planningRevision
        } else {
            if (revision == Long.MAX_VALUE) return withdraw()
            ++revision
        }
        val target = selected.copy(planningRevision = nextRevision)
        latest.set(Snapshot(input, target))
        return target
    }

    /**
     * ETA-only changes keep a generation request useful. Its old times grant no permission:
     * validation always uses the latest accepted snapshot and forecast expiry instead.
     */
    fun isCurrent(target: RegionalStoryTarget, now: Long): Boolean {
        val snapshot = latest.get() ?: return false
        return current(snapshot, target, now) && latest.get() === snapshot
    }

    private fun current(snapshot: Snapshot, target: RegionalStoryTarget, now: Long): Boolean {
        val current = snapshot.target
        if (target.planningRevision != current.planningRevision || !sameDestination(target, current)) return false
        if (now < snapshot.input.elapsedMs || !freshLocation(snapshot.input, now)) return false
        if (current.expiresAtElapsedMs != null && now >= current.expiresAtElapsedMs) return false
        if (current.routeDependency != null) {
            val forecast = snapshot.input.forecast ?: return false
            if (!forecastCurrent(forecast, snapshot.input, now)) return false
        } else if (!regionAuthorized(snapshot.input) || snapshot.input.currentRegionId != current.regionId ||
            snapshot.input.currentRegionPolicyKey != current.regionPolicyKey) return false
        return true
    }

    /**
     * Preparation may look ahead through the remaining story/music. Output additionally requires
     * that this passage is current or within the reviewed approach allowance at the actual start.
     * The coordinator's real completion callbacks, never the estimated boundary, permit playback.
     */
    fun isPlayableNow(target: RegionalStoryTarget, now: Long): Boolean {
        val snapshot = latest.get() ?: return false
        if (!current(snapshot, target, now)) return false
        val current = snapshot.target
        if (current.routeDependency != null) {
            val passage = snapshot.input.forecast?.passages?.firstOrNull { it.visitId == current.visitId }
                ?: return false
            if (now >= passage.exitsAtElapsedMs || (passage.entersAtElapsedMs > now &&
                    passage.entersAtElapsedMs - now > maximumApproachLeadMs)) return false
        }
        return latest.get() === snapshot
    }

    private fun freshLocation(input: RegionalPlanningInput, now: Long): Boolean {
        if (!input.locationValid) return false
        return LocationFreshness.isFresh(input.locationReceivedElapsedMs, input.locationFreshUntilElapsedMs,
            now, maximumLocationAgeMs) && try { input.locationAuthority() } catch (_: Exception) { false }
    }

    private fun forecastCurrent(forecast: ReviewedRouteForecast, input: RegionalPlanningInput, now: Long): Boolean =
        forecast.sessionId == sessionId && forecast.clockScopeId == clockScopeId &&
            forecast.routeRevision == input.routeRevision && now >= forecast.observedAtElapsedMs &&
            now < forecast.validUntilElapsedMs

    /** An unchanged route revision cannot override a freshly confirmed deviation or traffic delay. */
    private fun agreesWithCurrentRegion(forecast: ReviewedRouteForecast, input: RegionalPlanningInput): Boolean {
        val actualRegion = input.currentRegionId?.takeIf { regionAuthorized(input) } ?: return true
        val expectedNow = forecast.passages.firstOrNull {
            it.entersAtElapsedMs <= input.elapsedMs && input.elapsedMs < it.exitsAtElapsedMs
        } ?: return true
        return expectedNow.regionId == actualRegion
    }

    private fun sameDestination(first: RegionalStoryTarget, second: RegionalStoryTarget): Boolean =
        first.sessionId == second.sessionId && first.clockScopeId == second.clockScopeId &&
            first.regionId == second.regionId && first.routeDependency == second.routeDependency &&
            first.visitId == second.visitId && first.regionPolicyKey == second.regionPolicyKey

    private fun regionAuthorized(input: RegionalPlanningInput): Boolean =
        try { input.currentRegionAuthority() } catch (_: Exception) { false }

    private fun withdraw(): RegionalStoryTarget? {
        latest.set(null)
        return null
    }
}
