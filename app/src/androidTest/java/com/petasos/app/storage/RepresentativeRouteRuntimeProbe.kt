package com.petasos.app.storage

import android.app.Instrumentation
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.music.MusicCatalog
import com.petasos.app.platform.android.content.BundledStoryCatalog
import com.petasos.app.platform.android.location.BundledRegionCatalog
import com.petasos.app.planning.*
import com.petasos.app.playback.*
import com.petasos.app.runtime.*
import java.util.ArrayDeque
import java.util.UUID

/**
 * One integrated scenario, not a vehicle/SDK or generated-story acceptance test.
 * APK-pinned public stories and their real Room bindings run through the product planners/runtime.
 * Route windows, observation freshness, clock and silent output callbacks are explicitly synthetic.
 * No Activity, MediaService, SDK, audio player, microphone, network or product database is opened.
 */
internal object RepresentativeRouteRuntimeProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "representative-route-runtime-" + UUID.randomUUID() + ".db"
        val clock = ScenarioQueue()
        val epoch = System.currentTimeMillis()
        val musicCatalog = MusicCatalog { id ->
            check(id == MUSIC_ID)
            // This test-only source is consumed only by SilentMusic. It is never a playable URI.
            RuntimeMusicSource(MUSIC_ID, "synthetic://no-audio", MUSIC_DURATION)
        }
        try {
            RoomRuntimePersistence(StorageDatabase.open(context, name),
                PayloadCipher(AndroidPayloadKeys("com.petasos.app.tests.$name")),
                { epoch + clock.now - START }, { clock.now }, CLOCK, musicCatalog).use { store ->
                BundledStoryCatalog(context).loadVerified(store.database)
                val catalog = BundledRegionCatalog(context)
                val manifest = catalog.readManifest()
                val regions = manifest.regions.associate { it.code to it.regionId }
                val hierarchy = catalog.readHierarchy(manifest)
                val inventory = RoomAutomaticStoryStore(store.database, { epoch }, hierarchy = hierarchy)
                val timings = RoomStoryTimingStore(store)
                val planner = RegionalStoryPlanner(SESSION, CLOCK, hierarchy = hierarchy)
                val lookahead = PlaybackLookahead()
                val speech = SilentSpeech()
                val music = SilentMusic()
                val references = mutableMapOf<OperationToken, OutputReference>()
                val starts = mutableListOf<OutputReference>()
                val issues = mutableListOf<RuntimeIssue>()
                val offers = mutableListOf<Offered>()
                val acceptedAutomatic = mutableListOf<Offered>()
                var target: RegionalStoryTarget? = null
                var routeRevision = 1L
                lateinit var runtime: PlaybackRuntime
                lateinit var storyBridge: AutomaticStoryPlaybackBridge
                val sql = store.database.openHelper.writableDatabase
                fun count(statement: String, vararg values: Any?): Long =
                    sql.query(SimpleSQLiteQuery(statement, values)).use { check(it.moveToFirst()); it.getLong(0) }
                fun storyAttempts(storyId: String) = count("""
                    SELECT count(*) FROM playback_attempt a
                    JOIN paragraph p ON p.paragraph_id=a.paragraph_id
                    JOIN story_version v ON v.story_version_id=p.story_version_id
                    WHERE a.kind='story' AND v.story_id=?
                """.trimIndent(), storyId)
                storyBridge = AutomaticStoryPlaybackBridge(ContentPlanner(CLOCK), inventory::candidates,
                    object : PlannedStoryOutput {
                        override fun offer(story: PreparedStory, offerId: String) {
                            offers += Offered(story, offerId, checkNotNull(target))
                            runtime.dispatch(PlaybackEvent.OfferStory(story, offerId))
                        }
                        override fun withdraw(storyId: String, versionId: String) {
                            runtime.dispatch(PlaybackEvent.ClearOfferedStory(storyId, versionId))
                        }
                    }, { clock.now }, inventory::isCurrent,
                    targetCurrent = { planner.isCurrent(it, clock.now) },
                    targetPlayable = { planner.isPlayableNow(it, clock.now) }, requireRegionalTarget = true)
                val musicBridge = AutomaticMusicPlaybackBridge(
                    { listOf(AutomaticMusicCandidate(musicCatalog.prepare(MUSIC_ID), "synthetic-music-review")) },
                    object : PlannedMusicOutput {
                        override fun offer(track: PreparedMusic) { runtime.dispatch(PlaybackEvent.OfferMusic(track)) }
                        override fun withdraw(assetId: String) { runtime.dispatch(PlaybackEvent.ClearOfferedMusic(assetId)) }
                    })
                val persistence = object : RuntimePersistence by store {
                    override fun registerOutput(attempt: OperationToken, reference: OutputReference, turnId: String?) {
                        // Even the preparation of the second story must follow the durable music boundary.
                        if (reference is OutputReference.StoryParagraph && reference.storyId == FINAL_STORY) {
                            check(count("SELECT count(*) FROM playback_attempt WHERE kind='music' AND state='completed'") == 1L)
                        }
                        store.registerOutput(attempt, reference, turnId)
                        references[attempt] = reference
                    }
                    override fun markOutputStarted(attempt: OperationToken) {
                        store.markOutputStarted(attempt)
                        val reference = references.getValue(attempt)
                        starts += reference
                        lookahead.outputStarted(attempt, reference, clock.now)
                    }
                    override fun commitPlayback(effect: PlaybackEffect.CommitPlayback, snapshot: PlaybackSnapshot) {
                        store.commitPlayback(effect, snapshot)
                        references.remove(effect.attempt)
                    }
                }
                runtime = PlaybackRuntime(PlaybackCoordinator(TRIP, SESSION), clock, RuntimeClock { clock.now },
                    persistence, speech, music = music, contentAuthority = storyBridge,
                    observer = object : RuntimeObserver {
                        override fun onTransition(transition: PlaybackTransition) {
                            lookahead.onTransition(transition, clock.now)
                            musicBridge.onTransition(transition)
                        }
                        override fun onIssue(issue: RuntimeIssue) { issues += issue }
                        override fun onAutomaticStoryStarted(offerId: String, storyId: String, versionId: String) {
                            val offered = offers.single { it.id == offerId }
                            check(offered.story.storyId == storyId && offered.story.versionId == versionId)
                            check(planner.isCurrent(offered.target, clock.now))
                            acceptedAutomatic += offered
                            storyBridge.storyStarted(offerId, versionId)
                            storyBridge.storyStartedWithoutOffer(storyId)
                        }
                        override fun onStoryStarted(storyId: String, versionId: String) {
                            storyBridge.storyStartedWithoutOffer(storyId)
                        }
                    })
                fun drain() { clock.drain(); check(issues.isEmpty()) { "representative_runtime_issue" } }
                fun replan(): RegionalStoryTarget? {
                    val state = runtime.snapshot()
                    val first = regions.getValue("11140")
                    val next = regions.getValue(if (routeRevision == 1L) "41550" else "30200")
                    val current = if (clock.now < CROSSING) first else next
                    val boundary = lookahead.nextStoryStartElapsedMs(state, clock.now,
                        musicBridge.offeredDurationMs(), timings::paragraphDurations)
                    val forecast = ReviewedRouteForecast(SESSION, CLOCK, routeRevision, START, START + 200_000,
                        listOf(RegionPassage("synthetic-seoul-$routeRevision", first, START, CROSSING),
                            RegionPassage("synthetic-next-$routeRevision", next, CROSSING, START + 180_000)))
                    target = planner.update(RegionalPlanningInput(SESSION, CLOCK, routeRevision, clock.now,
                        clock.now, true, current, state.interactionIntent, boundary, forecast,
                        locationFreshUntilElapsedMs = clock.now + 10_000))
                    storyBridge.update(PlanningContext(current, routeRevision, clock.now, CLOCK, true,
                        state.interactionIntent, clock.now, target, locationFreshUntilElapsedMs = clock.now + 10_000),
                        allowOffer = state.playIntent && state.blockers.isEmpty())
                    drain()
                    return target
                }
                try {
                    drain()
                    check(count("SELECT count(*) FROM preference") == 0L)
                    val first = store.prepareStory(FIRST_VERSION)
                    check(first.storyId == FIRST_STORY && first.paragraphIds.size == 1)
                    val storyDuration = checkNotNull(timings.paragraphDurations(first)).single()
                    check(storyDuration == 34_000L) // Pinned public asset, never overwritten to fit the scenario.
                    runtime.playInstalledStory(first.versionId); drain()
                    val initial = checkNotNull(replan())
                    val oldOffer = offers.last()
                    check(initial.regionId == regions.getValue("41550") && initial.routeDependency == 1L)
                    check(initial.earliestStartElapsedMs == START + storyDuration + MUSIC_DURATION)
                    check(oldOffer.story.storyId == STALE_STORY && oldOffer.target.planningRevision == initial.planningRevision)
                    check(starts.filterIsInstance<OutputReference.StoryParagraph>().map { it.storyId } == listOf(FIRST_STORY))
                    mark("representative_public_story_length_plus_music_selects_exact_future_region_and_story")

                    clock.advanceTo(START + storyDuration - 1); replan()
                    clock.advanceTo(START + storyDuration); speech.complete(); drain(); replan()
                    check(runtime.snapshot().mode == PlaybackMode.MUSIC && music.startPositions == listOf(0L))
                    check(storyAttempts(STALE_STORY) == 0L && storyAttempts(FINAL_STORY) == 0L)
                    clock.advanceTo(START + storyDuration + 2_000); music.position(2_000); drain(); replan()
                    runtime.dispatch(PlaybackEvent.Stop); drain(); replan()
                    check(!runtime.snapshot().playIntent && runtime.snapshot().outputPhase == AdapterPhase.READY)
                    check(runtime.snapshot().musicCheckpoint?.positionMs == 2_000L)
                    check(count("SELECT count(*) FROM playback_attempt WHERE kind='music' AND state='interrupted' AND position_ms=2000") == 1L)
                    check(!storyBridge.isAutomaticStoryOffered(oldOffer.id, oldOffer.reference()))

                    routeRevision = 2L
                    check(replan() == null) // Rerouting while stopped never restores playback intent.
                    clock.advanceTo(clock.now + 1_000); drain()
                    check(music.startPositions == listOf(0L) && storyAttempts(FINAL_STORY) == 0L)
                    runtime.dispatch(PlaybackEvent.Play); drain()
                    check(music.startPositions == listOf(0L, 2_000L))
                    val rerouted = checkNotNull(replan())
                    val finalOffer = offers.last()
                    check(rerouted.regionId == regions.getValue("30200") && rerouted.routeDependency == 2L)
                    check(rerouted.planningRevision > initial.planningRevision)
                    check(rerouted.earliestStartElapsedMs == START + storyDuration + MUSIC_DURATION + 1_000)
                    check(finalOffer.story.storyId == FINAL_STORY && finalOffer.id != oldOffer.id)
                    check(!planner.isCurrent(initial, clock.now) && !storyBridge.isAutomaticStoryCurrent(oldOffer.id, oldOffer.reference()))
                    check(storyAttempts(STALE_STORY) == 0L && storyAttempts(FINAL_STORY) == 0L)
                    mark("representative_stop_preserves_music_position_reroute_revokes_old_plan_and_resume_replans")

                    clock.advanceTo(rerouted.earliestStartElapsedMs - 1)
                    music.position(MUSIC_DURATION - 1); drain()
                    check(checkNotNull(replan()).planningRevision == rerouted.planningRevision)
                    check(storyAttempts(FINAL_STORY) == 0L)
                    clock.advanceTo(rerouted.earliestStartElapsedMs)
                    music.complete(); drain()
                    check(runtime.snapshot().mode == PlaybackMode.STORY)
                    check(runtime.snapshot().checkpoint?.story?.storyId == FINAL_STORY)
                    check(acceptedAutomatic.single().id == finalOffer.id)
                    check(acceptedAutomatic.single().target.planningRevision == rerouted.planningRevision)
                    check(starts.filterIsInstance<OutputReference.StoryParagraph>().map { it.storyId } == listOf(FIRST_STORY, FINAL_STORY))
                    check(storyAttempts(STALE_STORY) == 0L && storyAttempts(FINAL_STORY) == 1L)
                    check(count("SELECT count(*) FROM playback_attempt WHERE kind='music' AND state='completed' AND position_ms=?", MUSIC_DURATION) == 1L)
                    mark("representative_music_terminal_commit_precedes_exact_rerouted_public_story_start")

                    runtime.dispatch(PlaybackEvent.Stop); drain()
                    check(count("SELECT count(*) FROM playback_attempt WHERE state IN ('prepared','playing')") == 0L)
                    check(count("SELECT count(*) FROM collection_record WHERE collection_type='region'") == 0L)
                    check(listOf("preference", "recording_segment", "route_point", "conversation_turn", "generation_request")
                        .all { count("SELECT count(*) FROM " + it) == 0L })
                    sql.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) }
                    mark("representative_disposable_room_no_location_voice_generation_or_product_record_input")
                } finally {
                    runtime.close(); clock.drain()
                    check(runtime.snapshot().closed)
                    storyBridge.clear(); musicBridge.clear(); clock.drain()
                }
            }
        } finally { SyntheticStorageFixture.cleanup(context, name) }
    }

    private data class Offered(val story: PreparedStory, val id: String, val target: RegionalStoryTarget) {
        fun reference() = OutputReference.StoryParagraph(story.storyId, story.versionId, story.paragraphIds.first())
    }

    /** Explicit clock advancement; no sleeps, real output, timer threads or accelerated completion. */
    private class ScenarioQueue : RuntimeQueue {
        var now = START; private set
        private val tasks = ArrayDeque<() -> Unit>()
        private data class Timer(val due: Long, val task: () -> Unit, var active: Boolean = true)
        private val timers = mutableListOf<Timer>()
        override fun execute(task: () -> Unit) { check(tasks.size < 1_000); tasks.addLast(task) }
        override fun schedule(delayMs: Long, task: () -> Unit): RuntimeCancellation {
            check(delayMs >= 0 && timers.size < 1_000)
            val timer = Timer(now + delayMs, task); timers += timer
            return RuntimeCancellation { timer.active = false }
        }
        fun advanceTo(value: Long) { check(value >= now); now = value; drain() }
        fun drain() {
            var handled = 0
            while (true) {
                timers.filter { !it.active }.toList().forEach { timers.remove(it) }
                val due = timers.filter { it.due <= now }.sortedBy { it.due }
                due.forEach { timers.remove(it); execute(it.task) }
                if (tasks.isEmpty()) return
                check(++handled <= 4_000) { "representative_queue_not_quiescent" }
                tasks.removeFirst().invoke()
            }
        }
    }

    private class SilentSpeech : RuntimeSpeech {
        private var active: Pair<OperationToken, (RuntimeOutputEvent) -> Unit>? = null
        override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) {
            check(active == null); active = attempt to callback; callback(RuntimeOutputEvent.STARTED)
        }
        fun complete() { val item = checkNotNull(active); active = null; item.second(RuntimeOutputEvent.COMPLETED) }
        override fun stopOutput(attempt: OperationToken) {
            val item = checkNotNull(active); check(item.first == attempt); active = null; item.second(RuntimeOutputEvent.STOPPED)
        }
        override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) = error("representative_input_forbidden")
        override fun stopInput(input: OperationToken) = error("representative_input_forbidden")
        override fun close() { check(active == null) }
    }

    private class SilentMusic : RuntimeMusic {
        private data class Slot(val attempt: OperationToken, var position: Long, val callback: (RuntimeMusicEvent) -> Unit)
        private var active: Slot? = null
        val startPositions = mutableListOf<Long>()
        override fun startOutput(attempt: OperationToken, source: RuntimeMusicSource, positionMs: Long, callback: (RuntimeMusicEvent) -> Unit) {
            check(active == null && source.assetId == MUSIC_ID && source.uri == "synthetic://no-audio")
            active = Slot(attempt, positionMs, callback); startPositions += positionMs; callback(RuntimeMusicEvent.Started)
        }
        fun position(value: Long) {
            val item = checkNotNull(active); check(value in item.position..MUSIC_DURATION)
            item.position = value; item.callback(RuntimeMusicEvent.Position(value))
        }
        fun complete() { val item = checkNotNull(active); active = null; item.callback(RuntimeMusicEvent.Completed(MUSIC_DURATION)) }
        override fun stopOutput(attempt: OperationToken) {
            val item = checkNotNull(active); check(item.attempt == attempt); active = null; item.callback(RuntimeMusicEvent.Stopped(item.position))
        }
        override fun close() { check(active == null) }
    }

    private const val START = 100_000L
    private const val CROSSING = START + 40_000
    private const val MUSIC_DURATION = 10_000L
    private const val MUSIC_ID = "synthetic-silent-boundary-track"
    private const val CLOCK = "representative-synthetic-clock"
    private const val TRIP = "representative-synthetic-trip"
    private const val SESSION = "representative-synthetic-session"
    private const val FIRST_STORY = "03849d89-1640-5603-be8b-9835d371ed02"
    private const val FIRST_VERSION = "543141ea-4065-5167-a130-1e4f2d0cc104"
    private const val STALE_STORY = "07a9db6f-b2a5-5bbe-91ff-68e905e86125"
    private const val FINAL_STORY = "666b77d3-aba4-5ea6-b9fc-1a4c6556eef5"
}
