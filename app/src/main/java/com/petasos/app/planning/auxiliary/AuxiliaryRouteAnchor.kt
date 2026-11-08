package com.petasos.app.planning.auxiliary

import com.petasos.app.generation.StrictJson
import com.petasos.app.location.AndroidLocationMeaningAdapter
import com.petasos.app.location.BoundaryCoordinate
import com.petasos.app.location.LocationFreshness
import com.petasos.app.location.RecordedLocation
import com.petasos.app.location.polylineDigest
import com.petasos.app.location.polylineIdentity
import com.petasos.app.planning.RegionPassage
import com.petasos.app.planning.ReviewedRouteForecast
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

data class AuxiliaryRouteAnchorContext(
    val sessionId: String,
    val clockScopeId: String,
    val routeRevision: Long,
    val requestGeneration: Long,
) {
    init {
        require(polylineIdentity(sessionId) && polylineIdentity(clockScopeId) && routeRevision >= 0 && requestGeneration >= 0) {
            "Invalid auxiliary anchor context"
        }
    }
    override fun toString() = "AuxiliaryRouteAnchorContext([redacted])"
}

/**
 * Memory-only auxiliary forecast, never proof of the Pleos-selected route or a region visit.
 * update belongs on a serialized projection worker (up to 200,000 segments); current is a cheap
 * immutable snapshot read. The caller's authority binds the exact location ingress receipt, current
 * request/navigation generation, consent/visibility and current geometry/source rights.
 *
 * Android accuracy and elapsed uncertainty are 68th-percentile estimates, not hard error bounds:
 * https://developer.android.com/reference/android/location/Location
 * Interval intersection is conservative WITHIN that accuracy/ETA model. Provider guide times remain
 * predictions, and no distance-proportional time interpolation or collection evidence is produced.
 */
class AuxiliaryRouteAnchor(
    val context: AuxiliaryRouteAnchorContext,
    private val geometry: AuxiliaryRouteGeometry,
    private val timeline: AuxiliaryRegionTimeline,
    private val sourceObservedAtElapsedMs: Long,
    private val sourceFreshUntilElapsedMs: Long,
    private val maximumAccuracyMeters: Double = 100.0,
) {
    private data class Observation(
        val point: RecordedLocation,
        val measuredNanos: Long,
        val measuredElapsedMs: Long,
        val earliestMeasuredMs: Long,
        val latestMeasuredMs: Long,
        val freshUntil: Long,
    ) {
        override fun toString() = "AuxiliaryAnchorObservation([redacted])"
    }
    private class Snapshot(val forecast: ReviewedRouteForecast, val updatedAt: Long, val authority: () -> Boolean)
    private val matcher: AuxiliaryRouteAnchorGeometry
    private val latest = AtomicReference<Snapshot?>()
    private val invalidated = AtomicBoolean(false)
    private var previous: Observation? = null
    private var progressFloor = 0.0
    @Volatile private var lastUpdateElapsedMs: Long? = null
    /** Only a new route/anchor may clear definite divergence. Ambiguity alone does not request a route. */
    @Volatile var needsNewRoute: Boolean = false
        private set
    private val visitPrefix: String

    init {
        require(sourceObservedAtElapsedMs >= 0 && sourceFreshUntilElapsedMs > sourceObservedAtElapsedMs &&
            maximumAccuracyMeters.isFinite() && maximumAccuracyMeters > 0 &&
            geometry.routeKey == timeline.routeKey && geometry.totalDurationMs == timeline.totalDurationMs &&
            auxiliaryTimingUsable(geometry)) { "Invalid auxiliary anchor input" }
        val offsets = LongArray(geometry.guides.size + 1)
        geometry.guides.forEachIndexed { index, guide -> offsets[index + 1] = Math.addExact(offsets[index], guide.durationMs) }
        require(timeline.windows.all {
            it.endGuideIndex < geometry.guides.size && offsets[it.startGuideIndex] == it.startsAtOffsetMs &&
                offsets[it.endGuideIndex + 1] == it.endsAtOffsetMs
        }) { "Auxiliary timeline does not match geometry" }
        matcher = AuxiliaryRouteAnchorGeometry(geometry)
        visitPrefix = polylineDigest(listOf("auxiliary-anchor-v1", context.sessionId, context.clockScopeId,
            context.routeRevision.toString(), context.requestGeneration.toString(), geometry.routeKey, timeline.geometryEvidenceKey))
    }

    /** A later fresh measurement can replace an uncertain one; an old cached measurement cannot. */
    @Synchronized
    fun update(point: RecordedLocation, authority: () -> Boolean, nowElapsedMs: Long): ReviewedRouteForecast? {
        if (invalidated.get() || needsNewRoute || !allowed(authority)) return null
        if (!sourceCurrent(nowElapsedMs) || lastUpdateElapsedMs?.let { nowElapsedMs < it } == true) {
            invalidate(); return null
        }
        lastUpdateElapsedMs = nowElapsedMs
        val observation = observation(point, nowElapsedMs) ?: run { latest.set(null); return null }
        val before = previous
        if (before != null) {
            if (point == before.point) return current(context, nowElapsedMs)
            if (observation.measuredNanos <= before.measuredNanos || point.pointId == before.point.pointId ||
                point.receivedAtElapsedMs <= before.point.receivedAtElapsedMs) {
                latest.set(null); return null
            }
        }
        // Retire the previous exact-point permit before doing nontrivial path work.
        latest.set(null)
        val match = matcher.match(BoundaryCoordinate(checkNotNull(point.longitude), checkNotNull(point.latitude)),
            checkNotNull(point.accuracyMeters), progressFloor)
        if (invalidated.get() || !allowed(authority)) return null
        val phase = when (match) {
            is AuxiliaryRouteMatch.Found -> match.phase
            AuxiliaryRouteMatch.Ambiguous -> return null
            AuxiliaryRouteMatch.OffPath, AuxiliaryRouteMatch.Backtracked -> { needsNewRoute = true; return null }
        }
        // Progress remains observed even when broad guide timing leaves no usable future window.
        progressFloor = phase.lowerProgress
        previous = observation
        val forecast = try { anchor(observation, phase, nowElapsedMs) } catch (_: ArithmeticException) { null } ?: return null
        if (invalidated.get() || !allowed(authority)) return null
        val snapshot = Snapshot(forecast, nowElapsedMs, authority)
        latest.set(snapshot)
        if (invalidated.get() || !allowed(authority)) { latest.compareAndSet(snapshot, null); return null }
        return forecast
    }

    /** No geometry scan or clock conversion here. Expired/withdrawn snapshots never regain authority. */
    fun current(currentContext: AuxiliaryRouteAnchorContext, nowElapsedMs: Long): ReviewedRouteForecast? {
        if (currentContext != context || !sourceCurrent(nowElapsedMs)) { invalidate(); return null }
        if (invalidated.get() || needsNewRoute) return null
        val snapshot = latest.get() ?: return null
        if (nowElapsedMs < snapshot.updatedAt || nowElapsedMs >= snapshot.forecast.validUntilElapsedMs ||
            !allowed(snapshot.authority)) {
            latest.compareAndSet(snapshot, null)
            return null
        }
        return snapshot.forecast.takeIf { latest.get() === snapshot && !invalidated.get() }
    }

    fun invalidate() { invalidated.set(true); latest.set(null) }

    private fun sourceCurrent(now: Long): Boolean = now >= sourceObservedAtElapsedMs && now < sourceFreshUntilElapsedMs

    private fun observation(point: RecordedLocation, now: Long): Observation? {
        return try {
            if (point.clockScopeId != context.clockScopeId || point.routeRevision != context.routeRevision ||
                !polylineIdentity(point.pointId) || point.adapterVersion != AndroidLocationMeaningAdapter.VERSION ||
                point.sourceTimeUnit != "epoch_ms" || point.accuracyMeters == null ||
                point.accuracyMeters > maximumAccuracyMeters || point.latitude == null || point.longitude == null ||
                point.latitude !in 30.0..45.0 || point.longitude !in 120.0..135.0) null else {
                val deadline = LocationFreshness.deadlineElapsedMs(point) ?: return null
                if (!LocationFreshness.isFresh(point.receivedAtElapsedMs, deadline, now, LocationFreshness.DEFAULT_FRESHNESS_MS)) return null
                val confidence = StrictJson.parse(point.providerConfidenceJson.toByteArray(Charsets.UTF_8)) as? Map<*, *> ?: return null
                val age = AndroidLocationMeaningAdapter.restoredAgeMs(confidence, point.receivedAtElapsedMs) ?: return null
                if (age != point.measurementAgeAtReceiptMs) return null
                val raw = confidence["androidLocation"] as? Map<*, *> ?: return null
                if (point.provider != AndroidLocationMeaningAdapter.PROVIDER_PREFIX + (raw["provider"] as? String ?: return null) ||
                    (raw["horizontalAccuracyMeters"] as? String)?.toDoubleOrNull() != point.accuracyMeters) return null
                val measuredNanos = raw["elapsedRealtimeNanos"] as? Long ?: return null
                val measured = measuredNanos / 1_000_000
                val uncertainty = when (val value = raw["elapsedRealtimeUncertaintyNanos"]) {
                    null -> 0L
                    is String -> ceil((value.toDoubleOrNull() ?: return null) / 1_000_000.0).toLong()
                    else -> return null
                }
                val earliest = Math.subtractExact(measured, uncertainty)
                val latest = minOf(point.receivedAtElapsedMs, Math.addExact(measured, uncertainty))
                if (earliest < 0 || latest < earliest) null else Observation(point, measuredNanos, measured, earliest, latest, deadline)
            }
        } catch (_: Exception) { null }
    }

    private fun anchor(observation: Observation, phase: AuxiliaryRoutePhase, now: Long): ReviewedRouteForecast? {
        val until = minOf(sourceFreshUntilElapsedMs, observation.freshUntil)
        if (until <= now) return null
        // origin ∈ [earliest measurement - latest guide phase, latest measurement - earliest phase].
        val earliestOrigin = Math.subtractExact(observation.earliestMeasuredMs, phase.endsAtOffsetMs)
        val latestOrigin = Math.subtractExact(observation.latestMeasuredMs, phase.startsAtOffsetMs)
        val passages = timeline.windows.mapNotNull { window ->
            val start = maxOf(0L, Math.addExact(latestOrigin, window.startsAtOffsetMs))
            val end = Math.addExact(earliestOrigin, window.endsAtOffsetMs)
            if (end <= start || end <= now) null else RegionPassage(
                "aux-" + polylineDigest(listOf(visitPrefix, window.visitId)), window.region.regionId, start, end)
        }
        if (passages.isEmpty()) return null
        return ReviewedRouteForecast(context.sessionId, context.clockScopeId, context.routeRevision,
            observation.measuredElapsedMs, until, passages)
    }

    private fun allowed(authority: () -> Boolean): Boolean = try { authority() } catch (_: Exception) { false }

    override fun toString() = "AuxiliaryRouteAnchor([redacted])"
}
