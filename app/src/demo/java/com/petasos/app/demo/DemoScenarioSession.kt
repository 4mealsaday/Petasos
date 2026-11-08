package com.petasos.app.demo

import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.location.LocationFreshness
import com.petasos.app.planning.AutomaticMusicCandidate
import com.petasos.app.planning.AutomaticMusicPlaybackBridge
import com.petasos.app.planning.AutomaticStoryPlaybackBridge
import com.petasos.app.planning.ContentPlanner
import com.petasos.app.planning.PlannedMusicOutput
import com.petasos.app.planning.PlannedStoryOutput
import com.petasos.app.planning.PlanningContext
import com.petasos.app.planning.PlaybackLookahead
import com.petasos.app.planning.RegionPassage
import com.petasos.app.planning.RegionalPlanningInput
import com.petasos.app.planning.RegionalStoryPlanner
import com.petasos.app.planning.RegionalStoryTarget
import com.petasos.app.planning.ReviewedRouteForecast
import com.petasos.app.playback.OutputReference
import com.petasos.app.playback.OperationToken
import com.petasos.app.playback.PlaybackCoordinator
import com.petasos.app.playback.PlaybackEffect
import com.petasos.app.playback.PlaybackEvent
import com.petasos.app.playback.PlaybackMode
import com.petasos.app.playback.PlaybackSnapshot
import com.petasos.app.playback.PlaybackTransition
import com.petasos.app.playback.PreparedMusic
import com.petasos.app.playback.PreparedStory
import com.petasos.app.runtime.PlaybackRuntime
import com.petasos.app.runtime.RuntimeCancellation
import com.petasos.app.runtime.RuntimeIssue
import com.petasos.app.runtime.RuntimeObserver
import com.petasos.app.runtime.RuntimePersistence
import com.petasos.app.storage.RoomAutomaticStoryStore
import com.petasos.app.storage.RoomStoryTimingStore
import java.util.Collections

/**
 * Worker-owned, silent execution of public fixtures through the product runtime and Room stores.
 * The forecast, location observations and output clock are synthetic; no vehicle data, SDK, Host,
 * network, real audio or product storage participates. Reading a projection never supplies input.
 * Caller owns fixture deletion, and may delete only after [close] acknowledges durable quiescence.
 */
internal class DemoScenarioSession(
    private val fixture: DemoFixtureStore,
    private val currentGate: () -> Boolean = { false },
) {
    private val owner = Thread.currentThread()
    private val time = fixture.clock
    private val store = fixture.store
    private val wallEpoch = System.currentTimeMillis()
    private val inventory = RoomAutomaticStoryStore(store.database,
        { wallEpoch + time.now - DemoFixtureStore.START_MS }, hierarchy = fixture.hierarchy)
    private val timings = RoomStoryTimingStore(store)
    private val planner = RegionalStoryPlanner(SESSION, fixture.clockScopeId, hierarchy = fixture.hierarchy)
    private val lookahead = PlaybackLookahead()
    private val references = mutableMapOf<OperationToken, OutputReference>()
    private val starts = mutableListOf<String>()
    private val automaticStarts = mutableListOf<String>()
    private val observations = mutableListOf<RuntimeCancellation>()
    private var expiry: RuntimeCancellation? = null
    private var refresh: RuntimeCancellation? = null
    private var refreshPending = false
    private var firstRequested = false
    private var closing = false
    private var closeAcknowledged = false
    private var failure: DemoScenarioFailure? = null
    private var route = DemoRouteChoice.ANSEONG
    private var routeRevision = 1L
    private var routeObservedAt = DemoFixtureStore.START_MS
    private var observationAt: Long? = null
    private var observationRegion: String? = null
    private var target: RegionalStoryTarget? = null
    private var offeredStoryId: String? = null
    private lateinit var runtime: PlaybackRuntime
    private val speech = DemoSilentSpeech(time, ::paragraphDuration, ::outputAllowed, ::gateLost)
    private val music = DemoSilentMusic(time, ::outputAllowed, ::gateLost)
    private val storyBridge = AutomaticStoryPlaybackBridge(ContentPlanner(fixture.clockScopeId),
        inventory::candidates, object : PlannedStoryOutput {
            override fun offer(story: PreparedStory, offerId: String) {
                offeredStoryId = story.storyId
                runtime.dispatch(PlaybackEvent.OfferStory(story, offerId))
            }
            override fun withdraw(storyId: String, versionId: String) {
                if (offeredStoryId == storyId) offeredStoryId = null
                runtime.dispatch(PlaybackEvent.ClearOfferedStory(storyId, versionId))
            }
        }, { time.now }, inventory::isCurrent,
        targetCurrent = { planner.isCurrent(it, time.now) },
        targetPlayable = { planner.isPlayableNow(it, time.now) }, requireRegionalTarget = true)
    private val musicBridge = AutomaticMusicPlaybackBridge({
        listOf(AutomaticMusicCandidate(fixture.musicCatalog.prepare(DemoFixtureStore.MUSIC_ID), "demo-silent-review"))
    }, object : PlannedMusicOutput {
        override fun offer(track: PreparedMusic) { runtime.dispatch(PlaybackEvent.OfferMusic(track)) }
        override fun withdraw(assetId: String) { runtime.dispatch(PlaybackEvent.ClearOfferedMusic(assetId)) }
    })

    init {
        check(time.now == DemoFixtureStore.START_MS) { "DEMO_NEW_CLOCK_REQUIRED" }
        val first = store.prepareStory(FIRST_VERSION)
        check(first.storyId == FIRST_STORY && timings.paragraphDurations(first) == listOf(34_000L)) {
            "DEMO_PUBLIC_STORY_CHANGED"
        }
        val persistence = object : RuntimePersistence by store {
            override fun registerOutput(attempt: OperationToken, reference: OutputReference, turnId: String?) {
                store.registerOutput(attempt, reference, turnId)
                references[attempt] = reference
            }
            override fun markOutputStarted(attempt: OperationToken) {
                store.markOutputStarted(attempt)
                val reference = references.getValue(attempt)
                if (reference is OutputReference.StoryParagraph) starts += reference.storyId
                lookahead.outputStarted(attempt, reference, time.now)
                requestRefresh()
            }
            override fun commitPlayback(effect: PlaybackEffect.CommitPlayback, snapshot: PlaybackSnapshot) {
                store.commitPlayback(effect, snapshot)
                references.remove(effect.attempt)
            }
        }
        runtime = PlaybackRuntime(PlaybackCoordinator(TRIP, SESSION), time, time, persistence, speech,
            music = music, contentAuthority = storyBridge, observer = object : RuntimeObserver {
                override fun onTransition(transition: PlaybackTransition) {
                    lookahead.onTransition(transition, time.now)
                    musicBridge.onTransition(transition)
                    requestRefresh()
                }
                override fun onIssue(issue: RuntimeIssue) { failure = DemoScenarioFailure.RUNTIME_UNAVAILABLE }
                override fun onAutomaticStoryStarted(offerId: String, storyId: String, versionId: String) {
                    automaticStarts += storyId
                    offeredStoryId = null
                    storyBridge.storyStarted(offerId, versionId)
                    storyBridge.storyStartedWithoutOffer(storyId)
                }
                override fun onStoryStarted(storyId: String, versionId: String) {
                    storyBridge.storyStartedWithoutOffer(storyId)
                }
                override fun onStorySkipped(storyId: String) { storyBridge.storySkipped(storyId) }
            })
        // Session provisioning must commit before the first observation can enqueue a Stop.
        time.drain()
        // Finite fixture observations, not a timer which perpetually renews a real location lease.
        for (offset in 0L..OBSERVATION_END_OFFSET_MS step OBSERVATION_PERIOD_MS) {
            observations += time.scheduleAt(DemoFixtureStore.START_MS + offset, DemoEventPriority.OBSERVATION) {
                if (!closing && route != DemoRouteChoice.OBSERVATION_GAP) receiveObservation()
            }
        }
        time.drain()
    }

    fun play(): Boolean = command {
        if (!firstRequested) { firstRequested = true; runtime.playInstalledStory(FIRST_VERSION) }
        else runtime.dispatch(PlaybackEvent.Play)
    }

    /** Stop remains available while the interaction gate is closed, including during cleanup. */
    fun stop(): Boolean {
        checkOwner()
        if (closeAcknowledged) return true
        return runCommand {
            storyBridge.revokeAuthority()
            runtime.dispatch(PlaybackEvent.Stop)
            requestRefresh()
        }
    }

    fun next(): Boolean = command { runtime.dispatch(PlaybackEvent.Next) }
    fun previous(): Boolean = command { runtime.dispatch(PlaybackEvent.Previous) }

    fun advanceBy(deltaMs: Long): Boolean {
        checkOwner()
        if (!canCommand()) return false
        if (deltaMs !in 0..300_000L) return false
        return runCommand { time.advanceBy(deltaMs) }
    }

    fun changeRoute(choice: DemoRouteChoice): Boolean = command {
        if (route != choice) {
            // Revoke synchronously: an old offer cannot survive until deferred planner work runs.
            storyBridge.revokeAuthority()
            target = null
            route = choice
            routeRevision = Math.addExact(routeRevision, 1)
            routeObservedAt = time.now
            requestRefresh()
        }
    }

    /** Activity/owner calls this on gate loss; clock advancement is not needed to stop outputs. */
    fun reconcileGate(): Boolean {
        checkOwner()
        val allowed = gateOpen()
        if (!allowed) stop()
        return allowed
    }

    fun snapshot(): DemoScenarioSnapshot {
        checkOwner()
        val state = runtime.snapshot()
        val storyId = state.checkpoint?.story?.storyId.takeIf {
            state.mode != PlaybackMode.MUSIC && state.musicCheckpoint == null
        }
        val currentTarget = target?.takeIf { !closing && planner.isCurrent(it, time.now) }
        return DemoScenarioSnapshot(time.now, state.mode, state.playIntent, closeAcknowledged,
            route, routeRevision, currentTarget?.planningRevision,
            observationRegion.takeIf { observationFresh() }, currentTarget?.regionId,
            currentTarget?.earliestStartElapsedMs, offeredStoryId, storyId, storyId?.let(::publicTitle),
            music.positionMs() ?: state.musicCheckpoint?.positionMs,
            count("SELECT count(*) FROM collection_record WHERE collection_type='story'"),
            count("SELECT count(*) FROM playback_attempt WHERE kind='story' AND state='completed'"),
            count("SELECT count(*) FROM playback_attempt WHERE kind='music' AND state='completed'"),
            count("SELECT count(*) FROM playback_attempt WHERE kind='music' AND state='interrupted'"),
            Collections.unmodifiableList(starts.toList()), Collections.unmodifiableList(automaticStarts.toList()), failure,
            observationRegion.takeIf { observationFresh() }?.let(::publicRegionName),
            currentTarget?.regionId?.let(::publicRegionName), offeredStoryId?.let(::publicTitle))
    }

    /** Does not close/delete the Room fixture. False must preserve its database and encryption keys. */
    fun close(): Boolean {
        checkOwner()
        if (closeAcknowledged) return true
        closing = true
        observations.forEach { it.cancel() }
        observations.clear()
        expiry?.cancel()
        refresh?.cancel()
        refreshPending = false
        storyBridge.revokeAuthority()
        var acknowledged: Boolean? = null
        return try {
            runtime.close { acknowledged = it }
            time.drain()
            if (acknowledged != true || !runtime.snapshot().closed) false
            else {
                storyBridge.clear()
                musicBridge.clear()
                time.drain()
                speech.close()
                music.close()
                target = null
                offeredStoryId = null
                closeAcknowledged = true
                time.close()
                true
            }
        } catch (_: Exception) {
            failure = DemoScenarioFailure.EXECUTION_UNAVAILABLE
            false
        }
    }

    private fun command(action: () -> Unit): Boolean {
        checkOwner()
        if (!canCommand()) return false
        return runCommand { action(); requestRefresh() }
    }

    private fun canCommand(): Boolean {
        if (closing || closeAcknowledged || failure != null) return false
        if (!gateOpen()) { stop(); return false }
        return true
    }

    private fun runCommand(action: () -> Unit): Boolean = try {
        action()
        time.drain()
        failure == null
    } catch (_: Exception) {
        failure = DemoScenarioFailure.EXECUTION_UNAVAILABLE
        false
    }

    private fun gateOpen(): Boolean = try { currentGate() } catch (_: Exception) { false }
    private fun outputAllowed(): Boolean = !closing && failure == null && gateOpen()

    private fun gateLost() {
        storyBridge.revokeAuthority()
        runtime.dispatch(PlaybackEvent.Stop)
        requestRefresh()
    }

    private fun receiveObservation() {
        // This event is an explicit synthetic input. It is not called by refresh, Play or snapshot.
        observationAt = time.now
        observationRegion = fixture.regions.getValue(if (time.now < CROSSING_MS) "11140" else nextRegionCode())
        expiry?.cancel()
        val receipt = time.now
        expiry = time.scheduleAt(receipt + FRESHNESS_MS + 1, DemoEventPriority.TIMER) {
            if (observationAt == receipt) {
                storyBridge.revokeAuthority()
                target = null
                requestRefresh()
            }
        }
        if (!gateOpen()) gateLost()
        requestRefresh()
    }

    private fun observationFresh(): Boolean = LocationFreshness.isFresh(
        observationAt, observationAt?.plus(FRESHNESS_MS), time.now, FRESHNESS_MS)

    private fun requestRefresh() {
        if (closing || refreshPending) return
        refreshPending = true
        refresh = time.scheduleAt(time.now, DemoEventPriority.PLANNING) {
            refreshPending = false
            if (!closing && failure == null) refreshPlan()
        }
    }

    private fun refreshPlan() {
        if (!gateOpen() && runtime.snapshot().playIntent) gateLost()
        val state = runtime.snapshot()
        val revision = routeRevision
        val currentRegion = observationRegion
        val receipt = observationAt
        val valid = observationFresh() && gateOpen()
        val locationAuthority = { !closing && gateOpen() && routeRevision == revision && observationFresh() }
        val regionAuthority = { locationAuthority() && observationRegion == currentRegion }
        val boundary = lookahead.nextStoryStartElapsedMs(state, time.now,
            musicBridge.offeredDurationMs(), timings::paragraphDurations)
        val forecast = ReviewedRouteForecast(SESSION, fixture.clockScopeId, revision, routeObservedAt,
            maxOf(routeObservedAt + 1, DemoFixtureStore.START_MS + FORECAST_END_OFFSET_MS), listOf(
                RegionPassage("demo-seoul-$revision", fixture.regions.getValue("11140"),
                    DemoFixtureStore.START_MS, CROSSING_MS),
                RegionPassage("demo-next-$revision", fixture.regions.getValue(nextRegionCode()),
                    CROSSING_MS, DemoFixtureStore.START_MS + OBSERVATION_END_OFFSET_MS),
            ))
        target = planner.update(RegionalPlanningInput(SESSION, fixture.clockScopeId, revision, time.now,
            receipt, valid, currentRegion, state.interactionIntent, boundary, forecast,
            locationFreshUntilElapsedMs = receipt?.plus(FRESHNESS_MS),
            locationAuthority = locationAuthority, currentRegionAuthority = regionAuthority))
        storyBridge.update(PlanningContext(currentRegion, revision, time.now, fixture.clockScopeId,
            valid, state.interactionIntent, receipt, target,
            locationFreshUntilElapsedMs = receipt?.plus(FRESHNESS_MS),
            locationAuthority = locationAuthority, currentRegionAuthority = regionAuthority),
            allowOffer = valid && state.playIntent && state.blockers.isEmpty())
    }

    private fun nextRegionCode(): String = if (route == DemoRouteChoice.YUSEONG) "30200" else "41550"

    private fun paragraphDuration(attempt: OperationToken): Long {
        val reference = references[attempt] as? OutputReference.StoryParagraph
            ?: error("DEMO_STORY_REFERENCE_REQUIRED")
        val story = store.prepareStory(reference.versionId)
        val index = story.paragraphIds.indexOf(reference.paragraphId)
        check(index >= 0) { "DEMO_PARAGRAPH_UNAVAILABLE" }
        return checkNotNull(timings.paragraphDurations(story)) { "DEMO_DURATION_UNAVAILABLE" }[index]
    }

    private fun publicTitle(storyId: String): String? = store.database.openHelper.readableDatabase.query(
        SimpleSQLiteQuery("SELECT title FROM story WHERE story_id=?", arrayOf(storyId)),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun publicRegionName(regionId: String): String? = store.database.openHelper.readableDatabase.query(
        SimpleSQLiteQuery("SELECT name FROM region WHERE region_id=?", arrayOf(regionId)),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun count(statement: String): Long = store.database.openHelper.readableDatabase.query(statement)
        .use { check(it.moveToFirst()); it.getLong(0) }

    private fun checkOwner() { check(Thread.currentThread() === owner) { "DEMO_OWNER_REQUIRED" } }

    companion object {
        const val FIRST_STORY = "03849d89-1640-5603-be8b-9835d371ed02"
        const val FIRST_VERSION = "543141ea-4065-5167-a130-1e4f2d0cc104"
        private const val TRIP = "demo-synthetic-trip"
        private const val SESSION = "demo-synthetic-session"
        private const val CROSSING_MS = DemoFixtureStore.START_MS + 40_000L
        private const val OBSERVATION_PERIOD_MS = 1_000L
        private const val OBSERVATION_END_OFFSET_MS = 180_000L
        private const val FORECAST_END_OFFSET_MS = 200_000L
        private const val FRESHNESS_MS = 10_000L
    }
}
