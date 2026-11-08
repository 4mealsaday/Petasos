package com.petasos.app.planning.auxiliary

import com.petasos.app.capability.navigation.NavigationAuxiliaryInput
import com.petasos.app.location.BoundaryCoordinate
import com.petasos.app.location.AndroidLocationMeaningAdapter
import com.petasos.app.location.LocationFreshness
import com.petasos.app.location.RecordedLocation
import com.petasos.app.location.RegionPolylineLookup
import com.petasos.app.location.PolylineRegion
import com.petasos.app.location.polylineIdentity
import com.petasos.app.planning.RegionalRouteForecastSource
import com.petasos.app.planning.ReviewedRouteForecast
import com.petasos.app.runtime.RuntimeCancellation
import com.petasos.app.runtime.RuntimeClock
import com.petasos.app.runtime.RuntimeQueue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

fun interface AuxiliaryRouteLookup {
    fun submit(request: AuxiliaryRouteRequest, current: () -> Boolean, complete: (AuxiliaryRouteOutcome) -> Unit): RuntimeCancellation
}

/**
 * One auxiliary forecast per live playback session. Owner methods run on the runtime queue;
 * revoke is safe at SDK/Activity ingress. Network and detailed geometry never run on that queue.
 * Raw routes remain in memory and are never collection evidence or a claim about Pleos's road path.
 */
class AuxiliaryRouteSession internal constructor(
    private val lookup: AuxiliaryRouteLookup,
    private val geometry: () -> RegionPolylineLookup,
    private val evidenceCurrent: (AuxiliaryRegionTimeline) -> Boolean,
    private val owner: RuntimeQueue,
    private val clock: RuntimeClock,
    private val changed: () -> Unit,
    private val project: ((() -> Unit) -> Unit),
    private val closeProjection: () -> Unit,
) : RegionalRouteForecastSource, AutoCloseable {
    constructor(client: AuxiliaryRouteClient, geometry: () -> RegionPolylineLookup,
                evidenceCurrent: (AuxiliaryRegionTimeline) -> Boolean, owner: RuntimeQueue,
                clock: RuntimeClock, changed: () -> Unit) : this(
        AuxiliaryRouteLookup(client::submit), geometry, evidenceCurrent, owner, clock, changed, projectionExecutor())

    private constructor(lookup: AuxiliaryRouteLookup, geometry: () -> RegionPolylineLookup,
                        evidenceCurrent: (AuxiliaryRegionTimeline) -> Boolean, owner: RuntimeQueue,
                        clock: RuntimeClock, changed: () -> Unit, worker: ThreadPoolExecutor) :
        this(lookup, geometry, evidenceCurrent, owner, clock, changed, { task -> worker.execute(task) }, { worker.shutdownNow() })

    private class Job(val context: AuxiliaryRouteAnchorContext, val inputKey: String,
                      val startedAt: Long, val expiresAt: Long, val navigation: NavigationAuxiliaryInput,
                      val requestAllowed: () -> Boolean) {
        val active = AtomicBoolean(true)
        val handle = AtomicReference<RuntimeCancellation?>()
        @Volatile var anchor: AuxiliaryRouteAnchor? = null
        var timeline: AuxiliaryRegionTimeline? = null // Owner worker only.
        val evidenceAllowed = AtomicBoolean(true)
        fun attach(value: RuntimeCancellation) {
            handle.set(value)
            if (!active.get()) handle.getAndSet(null)?.cancel()
        }
        fun revoke() {
            active.set(false)
            anchor?.invalidate()
            handle.getAndSet(null)?.cancel()
        }
    }
    private class Observation(val point: RecordedLocation, val authority: () -> Boolean)
    private val active = AtomicReference<Job?>()
    private val observation = AtomicReference<Observation?>()
    private var generation = 0L
    private var lastScheduledPoint: String? = null
    private var retryAt = 0L
    @Volatile private var closed = false

    /** Called with the exact committed location permit, never a synthetic point or cached position. */
    fun update(sessionId: String, clockScopeId: String, routeRevision: Long?, navigation: NavigationAuxiliaryInput?,
               point: RecordedLocation?, authority: () -> Boolean, enabled: Boolean,
               requestAllowed: () -> Boolean) {
        val now = clock.elapsedRealtimeMs()
        if (closed || !enabled || point == null || navigation == null || routeRevision == null ||
            navigation.routeRevision != routeRevision || !navigation.isCurrent() || !safe(requestAllowed) ||
            !polylineIdentity(sessionId) || !polylineIdentity(clockScopeId) || routeRevision < 0 ||
            !usablePoint(point, clockScopeId, routeRevision, now)) {
            clear(); return
        }
        val observed = Observation(point, authority)
        observation.set(observed)
        var job = active.get()
        if (job != null && (!job.active.get() || job.context.sessionId != sessionId || job.context.clockScopeId != clockScopeId ||
                job.context.routeRevision != routeRevision || job.inputKey != navigation.inputKey ||
                now < job.startedAt || now >= job.expiresAt || !job.navigation.isCurrent() ||
                job.anchor?.needsNewRoute == true && now >= retryAt)) {
            clear(); observation.set(observed); job = null
        }
        if (job == null) {
            if (now < retryAt || !safe(authority)) return
            generation = Math.addExact(generation, 1)
            val context = AuxiliaryRouteAnchorContext(sessionId, clockScopeId, routeRevision, generation)
            val current = Job(context, navigation.inputKey, now, Math.addExact(now, SOURCE_FRESHNESS_MS), navigation, requestAllowed)
            active.set(current)
            lastScheduledPoint = null
            retryAt = Math.addExact(now, RETRY_INTERVAL_MS)
            val request = AuxiliaryRouteRequest(BoundaryCoordinate(checkNotNull(point.longitude), checkNotNull(point.latitude)),
                navigation.destination, navigation.waypoints)
            current.attach(lookup.submit(request, { current(current) }) { outcome ->
                owner.execute {
                    if (!current(current)) return@execute
                    val response = (outcome as? AuxiliaryRouteOutcome.Success)?.value
                    if (response?.timingUsable != true) { fail(current); return@execute }
                    // Snapshot database-backed rights on the owner. The detailed geometry worker
                    // uses only assets/memory; accept and forecast recheck live rights on the owner.
                    val rawLookup = try { geometry() } catch (_: Exception) { fail(current); return@execute }
                    val guardedLookup = object : RegionPolylineLookup {
                        override val evidenceKey: String get() = if (current(current)) rawLookup.evidenceKey else ""
                        override fun containing(path: List<BoundaryCoordinate>): PolylineRegion? =
                            if (current(current)) rawLookup.containing(path) else null
                    }
                    project {
                        val prepared = try {
                            if (!current(current)) null else AuxiliaryRegionTimelineProjector(guardedLookup).project(response.geometry)?.let { timeline ->
                                timeline to AuxiliaryRouteAnchor(context, response.geometry, timeline, current.startedAt, current.expiresAt)
                            }
                        } catch (_: Exception) { null }
                        owner.execute accept@{
                            if (!current(current)) { prepared?.second?.invalidate(); return@accept }
                            val timeline = prepared?.first
                            if (timeline == null || !safe { evidenceCurrent(timeline) }) {
                                prepared?.second?.invalidate(); fail(current); return@accept
                            }
                            current.timeline = timeline
                            current.anchor = prepared.second
                            scheduleObservation(current)
                        }
                    }
                }
            })
        } else scheduleObservation(job)
    }

    private fun scheduleObservation(job: Job) {
        val sample = observation.get() ?: return
        val anchor = job.anchor ?: return
        if (lastScheduledPoint == sample.point.pointId || !current(job)) return
        lastScheduledPoint = sample.point.pointId
        project {
            if (!current(job) || !safe(sample.authority)) return@project
            val permit = { current(job) && safe(sample.authority) }
            anchor.update(sample.point, permit, clock.elapsedRealtimeMs())
            owner.execute { if (active.get() === job && job.active.get()) changed() }
        }
    }

    private fun current(job: Job): Boolean {
        val now = clock.elapsedRealtimeMs()
        return !closed && active.get() === job && job.active.get() && job.navigation.isCurrent() &&
            job.evidenceAllowed.get() && now in job.startedAt until job.expiresAt && safe(job.requestAllowed)
    }

    override fun forecast(sessionId: String, clockScopeId: String, routeRevision: Long?, nowElapsedMs: Long): ReviewedRouteForecast? {
        val job = active.get() ?: return null
        if (!current(job) || job.context.sessionId != sessionId || job.context.clockScopeId != clockScopeId ||
            job.context.routeRevision != routeRevision) return null
        val timeline = job.timeline ?: return null
        if (!safe { evidenceCurrent(timeline) }) {
            job.evidenceAllowed.set(false); job.revoke(); return null
        }
        return job.anchor?.current(job.context, nowElapsedMs)
    }

    /** Cheap final-use guard; forecast() performs the database recheck on the owner worker. */
    fun isCurrent(forecast: ReviewedRouteForecast): Boolean {
        val job = active.get() ?: return false
        return current(job) && job.anchor?.current(job.context, clock.elapsedRealtimeMs()) === forecast
    }

    private fun fail(job: Job) {
        if (active.compareAndSet(job, null)) {
            job.revoke(); lastScheduledPoint = null
            changed()
        }
    }

    /** Immediate revocation does not query a database or wait for a geometry/network worker. */
    fun revoke() {
        observation.set(null)
        active.getAndSet(null)?.revoke()
    }
    private fun clear() { revoke(); lastScheduledPoint = null }
    override fun close() { closed = true; clear(); closeProjection() }

    companion object {
        // Refresh bounds for an auxiliary estimate, not accuracy guarantees or visit-policy values.
        const val SOURCE_FRESHNESS_MS = 60_000L
        const val RETRY_INTERVAL_MS = 10_000L
        private fun safe(check: () -> Boolean) = try { check() } catch (_: Exception) { false }
        private fun usablePoint(point: RecordedLocation, clockScopeId: String, routeRevision: Long, now: Long): Boolean {
            if (point.clockScopeId != clockScopeId || point.routeRevision != routeRevision ||
                !polylineIdentity(point.pointId) || point.adapterVersion != AndroidLocationMeaningAdapter.VERSION ||
                point.sourceTimeUnit != "epoch_ms" || point.latitude == null || point.longitude == null ||
                point.latitude !in 30.0..45.0 || point.longitude !in 120.0..135.0 ||
                point.accuracyMeters == null || point.accuracyMeters > 100.0) return false
            val deadline = LocationFreshness.deadlineElapsedMs(point)
            return LocationFreshness.isFresh(point.receivedAtElapsedMs, deadline, now, LocationFreshness.DEFAULT_FRESHNESS_MS)
        }
        private fun projectionExecutor() = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(1), { task -> Thread(task, "petasos-route-projection").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardOldestPolicy())
    }
}
