package com.petasos.app.platform.android.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.petasos.app.generation.*
import com.petasos.app.platform.android.summary.SummaryBackground
import com.petasos.app.platform.android.music.BundledMusicCatalog
import com.petasos.app.platform.android.content.BundledStoryCatalog
import com.petasos.app.platform.android.location.BundledRegionCatalog
import com.petasos.app.platform.android.location.AndroidLocationSource
import com.petasos.app.platform.android.location.LocationAccess
import com.petasos.app.music.MusicTrackInfo
import com.petasos.app.platform.automotive.CarPolicySource
import com.petasos.app.platform.automotive.VehiclePowerPolicy
import com.petasos.app.platform.media.Media3RuntimeMusic
import com.petasos.app.platform.media.CoordinatorSessionCommands
import com.petasos.app.platform.pleos.speech.PleosRuntimeSpeech
import com.petasos.app.playback.*
import com.petasos.app.runtime.*
import com.petasos.app.storage.*
import com.petasos.app.records.*
import com.petasos.app.capability.location.LocationEvent
import com.petasos.app.capability.location.LocationOperation
import com.petasos.app.capability.location.LocationSource
import com.petasos.app.capability.navigation.NavigationAvailability
import com.petasos.app.capability.navigation.NavigationEvent
import com.petasos.app.capability.navigation.NavigationRouteState
import com.petasos.app.location.*
import com.petasos.app.planning.*
import com.petasos.app.planning.auxiliary.AuxiliaryRouteClient
import com.petasos.app.planning.auxiliary.AuxiliaryRouteSession
import com.petasos.app.platform.pleos.location.PleosLocationSource
import com.petasos.app.platform.pleos.navigation.PleosNavigationSource
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One service-owned runtime; constructing/browsing the host never creates a trip or starts audio. */
class PetasosSessionHost(
    context: Context,
    private val routeForecasts: RegionalRouteForecastSource? = null,
) : CoordinatorSessionCommands, AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val queue = AndroidRuntimeQueue()
    private val fence = AtomicLong()
    private var store: RoomRuntimePersistence? = null
    private val musicCatalog = BundledMusicCatalog(this.context)
    private val regionCatalog = BundledRegionCatalog(this.context)
    @Volatile private var musicTracks: List<MusicTrackInfo> = emptyList()
    private var musicPlanning: AutomaticMusicPlaybackBridge? = null
    @Volatile private var runtime: PlaybackRuntime? = null
    private var generation: GenerationClient? = null
    private var auxiliaryRouteClient: AuxiliaryRouteClient? = null
    @Volatile private var auxiliaryRoutes: AuxiliaryRouteSession? = null
    private var pendingResumeTurnId: String? = null // Worker owned; consumed once by the next interaction resume.
    private val storyPreparation = AtomicReference<StoryPreparation?>(null)
    private val tripEnding = AtomicReference<TripEnding?>(null)
    @Volatile private var recoverableTripId: String? = null
    private var speech: PleosRuntimeSpeech? = null
    private var music: Media3RuntimeMusic? = null
    @Volatile private var selected: String? = null
    private var pendingSelection = false // Worker-owned explicit setMediaItem intent, distinct from retained metadata.
    @Volatile private var catalog: List<InstalledStoryItem> = emptyList()
    @Volatile private var disposed = false
    @Volatile private var released = false
    @Volatile private var visibleForTalk = false
    @Volatile private var visibleForLocation = false
    @Volatile private var locationConsentEnabled = false
    private val locationConsentAuthority = LocationConsentAuthority()
    @Volatile private var power = VehiclePowerPolicy.unavailable()
    @Volatile private var latest: PlaybackSnapshot? = null
    private var listener: (() -> Unit)? = null
    private val uiListeners = linkedSetOf<() -> Unit>()
    private var locationStore: RoomLocationStore? = null
    private var navigationStore: RoomNavigationStore? = null
    private var recorder: LocationRecorder? = null
    // An initial quality cap, not a field-verified RegionPolicy. Unreviewed policies still cannot collect.
    private val locationNormalizer = LocationNormalizer(AndroidLocationMeaningAdapter(maximumAccuracyMeters = 100.0))
    @Volatile private var locationSource: LocationSource? = null
    @Volatile private var navigationSource: PleosNavigationSource? = null
    private var locationTimer: RuntimeCancellation? = null
    @Volatile private var planning: AutomaticStoryPlaybackBridge? = null
    private var regionalPlanner: RegionalStoryPlanner? = null
    @Volatile private var regionalTarget: RegionalStoryTarget? = null
    private var lookahead: PlaybackLookahead? = null
    private var planningBoundary = PlanningBoundaryMemory()
    private var storyTiming: RoomStoryTimingStore? = null
    @Volatile private var automaticGeneration: AutomaticStoryGeneration? = null
    @Volatile private var automaticGenerationPlan: AutomaticGenerationPlan? = null
    @Volatile private var sourceSessionId: String? = null
    private var clockScopeId = ""
    private var routeRevision = 0L
    private var routeDependency: Long? = null
    private var observedNavigationRouteState = NavigationRouteState.UNKNOWN
    private val navigationIngressRevision = AtomicLong()
    private var committedNavigationIngressRevision = 0L
    private var currentRegionId: String? = null
    @Volatile private var confirmedRegionAuthority: ConfirmedRegionAuthority? = null
    private var locationValid = false
    private var latestReceivedElapsed: Long? = null
    private var committedLocationElapsed: Long? = null
    private var committedLocation: RecordedLocation? = null
    private var locationFreshUntilElapsedMs: Long? = null
    @Volatile private var locationInputAuthority: LocationInputAuthority? = null
    private var committedLocationAuthority: () -> Boolean = { false }
    private val policy = CarPolicySource(context, onUx = {}, onPower = { updated ->
        power = updated
        if (updated.audioBlocked) cancelStoryPreparation()
        val live = runtime
        if (updated.audioBlocked) live?.dispatch(PlaybackEvent.Block(PlaybackBlocker.POWER_AUDIO))
        else live?.dispatch(PlaybackEvent.Unblock(PlaybackBlocker.POWER_AUDIO))
        if (updated.microphoneBlocked) cancelVisibleInput()
    })

    init { main.post { if (!disposed) policy.start() } }

    override fun setListener(value: (() -> Unit)?) { checkMain(); listener = value }
    fun addUiListener(value: () -> Unit) { checkMain(); uiListeners += value }
    fun removeUiListener(value: () -> Unit) { checkMain(); uiListeners -= value }
    /** Separate SpeechService 4.2.10 logs recognition payloads even in ON_DEVICE mode; live input remains disabled. */
    fun supportsLiveInput(): Boolean = false
    fun isPreparingStory(): Boolean = storyPreparation.get() != null
    fun canPrepareStory(): Boolean = !disposed && !isEndingTrip() && visibleForTalk && !power.audioBlocked &&
        !isPreparingStory() && readyForStoryPreparation(runtime?.snapshot())
    override fun snapshot(): PlaybackSnapshot? = latest
    override fun selectedVersionId(): String? = latest?.checkpoint?.story?.versionId ?: selected
    override fun selectedTitle(): String = catalog.firstOrNull { it.versionId == selectedVersionId() }?.title ?: "Petasos"
    override fun musicTitle(assetId: String): String = musicTracks.firstOrNull { it.assetId == assetId }?.title ?: "음악"
    override fun musicArtist(assetId: String): String = musicTracks.firstOrNull { it.assetId == assetId }?.artist.orEmpty()
    fun hasMusic(): Boolean = musicTracks.isNotEmpty()
    fun musicCredits(): ListenableFuture<String> = work {
        listOf(musicCatalog.creditsText(), regionCatalog.creditsText()).joinToString("\n\n")
    }

    fun browse(): ListenableFuture<List<InstalledStoryItem>> = work {
        val persistence = persistence()
        RoomMediaCatalog(persistence).items().also { catalog = it }
    }

    /** Selection prepares metadata only. External URIs and arbitrary model IDs are never accepted. */
    override fun select(versionId: String): ListenableFuture<Void?> {
        cancelStoryPreparation()
        return work {
            val entries = RoomMediaCatalog(persistence()).items()
            require(entries.any { it.versionId == versionId })
            selected = versionId
            pendingSelection = true
            catalog = entries
            changed()
            null
        }
    }

    override fun play(): ListenableFuture<Void?> {
        if (isEndingTrip()) return unavailableRecords()
        cancelStoryPreparation()
        val epoch = fence.get()
        return work {
            if (epoch != fence.get() || isEndingTrip()) return@work null
            val active = runtime
            if (active != null) {
                val state = active.snapshot()
                val version = explicitStoryVersionToPrepare(selected.takeIf { pendingSelection }, state.checkpoint, state.musicCheckpoint, pendingSelection)
                if (version != null) active.playInstalledStory(version)
                else active.dispatch(PlaybackEvent.Play)
            } else createRuntime(epoch, play = true)
            pendingSelection = false
            null
        }
    }

    override fun stop(): ListenableFuture<Void?> {
        fence.incrementAndGet()
        auxiliaryRoutes?.revoke()
        val cancelledPreparation = cancelStoryPreparation()
        // Stop ingress invalidates queued SDK starts before the worker handles another command.
        if (!cancelledPreparation) runtime?.dispatch(PlaybackEvent.Stop)
        return work { null }
    }
    override fun next(): ListenableFuture<Void?> {
        if (isEndingTrip()) return unavailableRecords()
        cancelStoryPreparation()
        return work { if (!isEndingTrip()) runtime?.dispatch(PlaybackEvent.Next); null }
    }
    override fun previous(): ListenableFuture<Void?> {
        if (isEndingTrip()) return unavailableRecords()
        cancelStoryPreparation()
        return work { if (!isEndingTrip()) runtime?.dispatch(PlaybackEvent.Previous); null }
    }

    /** Explicit parked-UI action. Only an installed public story supplies evidence and identity. */
    fun generateAndPlayStory(versionId: String): ListenableFuture<Void?> {
        checkMain()
        val job = StoryPreparation(versionId, fence.get())
        if (!canPrepareStory() || !storyPreparation.compareAndSet(null, job)) {
            job.future.setException(IllegalStateException("STORY_PREPARATION_UNAVAILABLE"))
            return job.future
        }
        revokeAutomaticPlanning()
        changed()
        queue.execute {
            try {
                check(preparationCurrent(job))
                val entries = RoomMediaCatalog(persistence()).items()
                check(entries.any { it.versionId == versionId })
                selected = versionId
                pendingSelection = false
                catalog = entries
                if (runtime == null) createRuntime(job.fence, play = false)
                // The runtime queues openSession during construction. Prepare only after its commit.
                queue.execute { prepareStory(job) }
            } catch (_: Exception) { finishStoryPreparation(job, success = false) }
        }
        return job.future
    }

    private fun prepareStory(job: StoryPreparation) {
        try {
            check(preparationCurrent(job))
            val live = runtime ?: error("RUNTIME_UNAVAILABLE")
            val authority = live.captureStoryPlaybackAuthority()
            val state = live.snapshot()
            val request = OperationToken(state.sessionId, state.cancelEpoch, UUID.randomUUID().toString())
            val current = { preparationCurrent(job) && runtime === live && selected == job.versionId &&
                live.isStoryPlaybackAuthorityCurrent(authority) }
            val storage = persistence().storyGenerationStore()
            // Explicit regeneration may revisit the selected public facts; automatic planning may not.
            val prepared = storage.prepare(request, job.versionId, emptyList(), isRuntimeCurrent = current)
                ?: error("STORY_PREPARATION_UNAVAILABLE")
            val handle = (generation ?: error("GENERATION_UNAVAILABLE")).submitStory(request, prepared.context) { result ->
                queue.execute {
                    try {
                        check(current())
                        val value = (result as? GenerationOutcome.Success)?.value ?: error("GENERATION_UNAVAILABLE")
                        val installed = storage.install(prepared, value) ?: error("STORY_INSTALL_UNAVAILABLE")
                        // Installing changes the content fingerprint; only runtime authority survives it.
                        check(current())
                        catalog = RoomMediaCatalog(persistence()).items()
                        selected = installed.versionId
                        pendingSelection = false
                        // Keep cancellation live until the queued runtime has accepted the play intent.
                        live.playInstalledStory(installed.versionId, authority) { accepted ->
                            finishStoryPreparation(job, success = accepted && !disposed && runtime === live &&
                                job.fence == fence.get() && visibleForTalk && !power.audioBlocked &&
                                storyPreparation.get() === job)
                        }
                    } catch (_: Exception) { finishStoryPreparation(job, success = false) }
                }
            }
            job.attach(handle)
        } catch (_: Exception) { finishStoryPreparation(job, success = false) }
    }

    private fun preparationCurrent(job: StoryPreparation): Boolean = storyPreparation.get() === job &&
        !disposed && job.fence == fence.get() && visibleForTalk && !power.audioBlocked &&
        readyForStoryPreparation(runtime?.snapshot())

    private fun readyForStoryPreparation(state: PlaybackSnapshot?): Boolean = state == null ||
        (!state.closed && !state.playIntent && !state.interactionIntent && state.blockers.isEmpty() &&
            state.mode in setOf(PlaybackMode.IDLE, PlaybackMode.PAUSED) && state.outputPhase == AdapterPhase.READY &&
            state.inputPhase !in setOf(AdapterPhase.ACTIVE, AdapterPhase.STOPPING) && state.pendingPlaybackCommits == 0)

    private fun finishStoryPreparation(job: StoryPreparation, success: Boolean) {
        if (!storyPreparation.compareAndSet(job, null)) return
        job.finish(success)
        changed()
    }

    private fun cancelStoryPreparation(): Boolean {
        revokeAutomaticPlanning()
        val job = storyPreparation.getAndSet(null) ?: return false
        fence.incrementAndGet()
        // Revoke the runtime authority too, including a result queued just before cancellation.
        runtime?.dispatch(PlaybackEvent.Stop)
        job.finish(false)
        changed()
        return true
    }

    private class StoryPreparation(val versionId: String, val fence: Long) {
        val future: SettableFuture<Void?> = SettableFuture.create()
        private var finished = false
        private var handle: RuntimeCancellation? = null
        @Synchronized fun attach(value: RuntimeCancellation) {
            if (finished) { try { value.cancel() } catch (_: Exception) { } }
            else handle = value
        }
        @Synchronized fun finish(success: Boolean) {
            if (finished) return
            finished = true
            val active = handle; handle = null
            if (!success) { try { active?.cancel() } catch (_: Exception) { } }
            if (success) future.set(null)
            else future.setException(IllegalStateException("STORY_PREPARATION_UNAVAILABLE"))
        }
    }

    /** Same-process Activity hook only. No MediaSession custom command exposes this capability. */
    fun setTalkUiVisible(visible: Boolean) {
        checkMain()
        visibleForTalk = visible
        if (!visible) { cancelStoryPreparation(); cancelVisibleInput() }
    }

    /** Activity visibility is independent of parked UI restrictions and microphone eligibility.
     * This stage has no location foreground service or background-location permission. */
    fun setLocationUiVisible(visible: Boolean) {
        checkMain()
        if (visibleForLocation == visible || disposed) return
        visibleForLocation = visible
        if (!visible) revokeLocationCapture()
        queue.execute {
            if (disposed) return@execute
            if (!visible) {
                // Visibility loss is an observed reception gap, not a vehicle suspend or trip end.
                try { recorder?.receive(LocationEvent.Failure(LocationOperation.UPDATES, "ActivityNotVisible")) }
                finally { stopLocationRecording() }
            } else {
                val state = latest
                if (!isEndingTrip() && state != null && !state.closed && sourceSessionId == state.sessionId)
                    updateLocationRecording(state.tripId, state.sessionId)
            }
        }
    }

    /** Main-thread ingress revocation precedes queued storage work and provider teardown callbacks. */
    private fun revokeLocationCapture() {
        locationInputAuthority?.invalidate()
        auxiliaryRoutes?.revoke()
        revokeAutomaticPlanning()
        val source = locationSource
        locationSource = null
        source?.close()
    }

    private fun locationAccessAllowed(): Boolean = !disposed && visibleForLocation && !locationConsentAuthority.isWithdrawn() && locationConsentEnabled &&
        LocationAccess.hasRequiredPermissions(context)

    fun talk(): ListenableFuture<Void?> {
        checkMain()
        revokeAutomaticPlanning()
        val epoch = fence.get()
        return work {
            if (!mayCapture() || epoch != fence.get()) throw IllegalStateException("MICROPHONE_UNAVAILABLE")
            if (runtime == null) createRuntime(epoch, play = false)
            if (!mayCapture() || epoch != fence.get() || runtime == null) throw IllegalStateException("MICROPHONE_UNAVAILABLE")
            runtime!!.dispatch(PlaybackEvent.Talk)
            null
        }
    }

    private fun mayCapture(): Boolean = supportsLiveInput() && !isEndingTrip() && visibleForTalk && !power.microphoneBlocked &&
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun cancelVisibleInput() {
        val state = latest ?: return
        if (state.mode in setOf(PlaybackMode.TALK_PENDING, PlaybackMode.PROMPT, PlaybackMode.LISTENING)) {
            runtime?.dispatch(PlaybackEvent.CancelInteraction)
        }
    }

    private fun createRuntime(epoch: Long, play: Boolean) {
        val persistence = persistence()
        val recovery = persistence.sessionRecovery()
        val candidate = recovery.currentTripCandidate()
        var storyCheckpoint: StoryCheckpoint? = null
        var musicCheckpoint: MusicCheckpoint? = null
        when (val saved = candidate?.checkpoint) {
            is RecoveryCheckpoint.Story -> try {
                val story = persistence.prepareStory(saved.versionId)
                val index = story.paragraphIds.indexOf(saved.paragraphId)
                if (story.storyId == saved.storyId && index >= 0) storyCheckpoint = StoryCheckpoint(story, index)
            } catch (_: RuntimeContentUnavailable) { }
            is RecoveryCheckpoint.Music -> try {
                val track = persistence.prepareMusic(saved.assetId)
                if (track.durationMs == null || saved.positionMs < track.durationMs) musicCheckpoint = MusicCheckpoint(track, saved.positionMs)
            } catch (_: RuntimeContentUnavailable) { }
            else -> Unit
        }
        val entries = RoomMediaCatalog(persistence).items()
        catalog = entries
        val requested = selected?.takeIf { id -> pendingSelection && entries.any { it.versionId == id } }
        if (epoch != fence.get() || disposed || isEndingTrip()) return
        // No selectable installed content means no empty automatic trip is created.
        if (play && requested == null && entries.isEmpty() && storyCheckpoint == null && musicCheckpoint == null &&
            musicCatalog.automaticCandidates().isEmpty()) throw RuntimeContentUnavailable()
        val tripId = candidate?.tripId ?: UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        clockScopeId = UUID.randomUUID().toString()
        val hierarchy = try { regionCatalog.readHierarchy() } catch (_: Exception) { ReviewedRegionHierarchy.NONE }
        regionalPlanner = RegionalStoryPlanner(sessionId, clockScopeId, hierarchy = hierarchy)
        lookahead = PlaybackLookahead()
        planningBoundary = PlanningBoundaryMemory()
        storyTiming = RoomStoryTimingStore(persistence)
        val locations = RoomLocationStore(persistence.database, regionCatalog.load(persistence.database), System::currentTimeMillis, ownsDatabase = false)
        locationStore = locations
        navigationStore = RoomNavigationStore(persistence.database, ownsDatabase = false)
        val automaticStories = RoomAutomaticStoryStore(persistence.database, System::currentTimeMillis, hierarchy = hierarchy)
        val landmarkStories = RoomLandmarkCandidates(persistence.database)
        val contentPlanner = ContentPlanner(clockScopeId)
        val landmarkSession = LandmarkCandidateSession(contentPlanner, sessionId, clockScopeId)
        val planner = AutomaticStoryPlaybackBridge(contentPlanner, { region, excluded ->
            val available = automaticStories.candidates(region, excluded)
            val target = regionalTarget?.takeIf { it.regionId == region }
            val seed = available.firstOrNull { it.reason == CandidateReason.CURRENT_REGION }
            val plan = if (target != null && seed != null) AutomaticGenerationPlan(
                "${target.sessionId}:${target.planningRevision}:${seed.story.storyId}", seed.story.versionId,
                seed.regionId!!, target.routeDependency, target.regionId, target.storyScope?.hierarchyKey,
            ) else null
            automaticGenerationPlan = plan
            automaticGeneration?.update(plan)
            // Generation only prepares content. Playback still passes through the ordinary offer boundary.
            if (automaticGeneration?.isPending == true) available.filter { it.reason == CandidateReason.INTEREST }
            else available
        }, object : PlannedStoryOutput {
            override fun offer(story: PreparedStory, offerId: String) { runtime?.dispatch(PlaybackEvent.OfferStory(story, offerId)) }
            override fun withdraw(storyId: String, versionId: String) { runtime?.dispatch(PlaybackEvent.ClearOfferedStory(storyId, versionId)) }
        }, SystemClock::elapsedRealtime, candidateCurrent = automaticStories::isCurrent,
            targetCurrent = { regionalPlanner?.isCurrent(it, SystemClock.elapsedRealtime()) == true },
            targetPlayable = { regionalPlanner?.isPlayableNow(it, SystemClock.elapsedRealtime()) == true },
            landmarkSession = landmarkSession,
            landmarkCandidates = landmarkStories::candidates,
            landmarkCurrent = { landmarkStories.isCurrent(it) },
            requireRegionalTarget = true)
        planning = planner
        musicPlanning = AutomaticMusicPlaybackBridge(musicCatalog::automaticCandidates, object : PlannedMusicOutput {
            override fun offer(track: PreparedMusic) { runtime?.dispatch(PlaybackEvent.OfferMusic(track)) }
            override fun withdraw(assetId: String) { runtime?.dispatch(PlaybackEvent.ClearOfferedMusic(assetId)) }
        })
        val bridge = object : RuntimePersistence by persistence {
            private val outputReferences = mutableMapOf<OperationToken, OutputReference>()
            override fun registerOutput(attempt: OperationToken, reference: OutputReference, turnId: String?) {
                persistence.registerOutput(attempt, reference, turnId)
                outputReferences[attempt] = reference
            }
            override fun markOutputStarted(attempt: OperationToken) {
                persistence.markOutputStarted(attempt)
                outputReferences[attempt]?.let { lookahead?.outputStarted(attempt, it, SystemClock.elapsedRealtime()) }
            }
            override fun openSession(tripId: String, sessionId: String) {
                if (candidate == null) persistence.openSession(tripId, sessionId)
                else recovery.openResumedSession(candidate, sessionId)
                // The session row is committed before any location/navigation callback can arrive.
                try { startSessionSources(tripId, sessionId) }
                catch (_: Exception) {
                    // An optional sensor/lookup failure must not falsely report that the session insert failed.
                    try { stopSessionSources() } catch (_: Exception) { invalidateLocation() }
                }
            }
            override fun commitPlayback(effect: PlaybackEffect.CommitPlayback, snapshot: PlaybackSnapshot) {
                persistence.commitPlayback(effect, snapshot)
                outputReferences.remove(effect.attempt)
                // Query after the completed attempt is committed so equivalent facts cannot remain queued.
                if (effect.outcome == AttemptOutcome.COMPLETED) refreshPlanning()
            }
            override fun freezeTurn(effect: PlaybackEffect.FreezeTurn) {
                persistence.freezeTurn(effect)
                pendingResumeTurnId = effect.turnId.takeIf { effect.hasCommittedUtterance }
                if (effect.hasCommittedUtterance) SummaryBackground.enqueueFrozen(context, effect.turnId)
            }
            override fun closeSession(sessionId: String) {
                stopSessionSources()
                persistence.closeSession(sessionId)
                if (disposed) queue.execute { releaseResources() }
            }
        }
        val configurations = DeviceGenerationConfigStore(context)
        val client = GenerationClient(configurations, configurations)
        generation = client
        if (routeForecasts == null) {
            auxiliaryRoutes?.close(); auxiliaryRouteClient?.close()
            try {
                val routeClient = AuxiliaryRouteClient(configurations)
                auxiliaryRouteClient = routeClient
                auxiliaryRoutes = AuxiliaryRouteSession(routeClient,
                    { regionCatalog.loadPolyline(persistence.database) },
                    regionCatalog.polylineAuthorizer(persistence.database), queue, AndroidRuntimeClock,
                    changed = { refreshPlanning() })
            } catch (_: Exception) {
                auxiliaryRoutes = null
                auxiliaryRouteClient?.close(); auxiliaryRouteClient = null
            }
        }
        automaticGeneration = AutomaticStoryGeneration(
            generator = AutomaticStoryGenerator { plan, authorized, complete ->
                val state = runtime?.snapshot()
                val request = state?.let { OperationToken(it.sessionId, it.cancelEpoch, UUID.randomUUID().toString()) }
                val storage = persistence.storyGenerationStore()
                val seed = automaticStories.candidates(plan.passageRegionId).firstOrNull { it.story.versionId == plan.seedVersionId && it.regionId == plan.regionId }
                val prepared = if (request != null && seed != null && authorized())
                    storage.prepare(request, plan.seedVersionId, emptyList(), plan.routeRevision, authorized) else null
                if (prepared == null || prepared.context.regionId != plan.regionId) {
                    complete(null)
                    RuntimeCancellation { }
                } else client.submitStory(request!!, prepared.context) { result ->
                    queue.execute {
                        val installed = try {
                            if (!authorized() || !automaticStories.isCurrent(seed!!, plan.passageRegionId)) null
                            else (result as? GenerationOutcome.Success)?.value?.let { storage.install(prepared, it) }
                        } catch (_: Exception) { null }
                        if (installed != null) catalog = RoomMediaCatalog(persistence).items()
                        complete(installed?.versionId)
                    }
                }
            },
            enqueue = { queue.execute(it) },
            planCurrent = { plan ->
                val state = runtime?.snapshot()
                val target = regionalTarget
                !disposed && !isEndingTrip() && storyPreparation.get() == null &&
                    automaticGenerationPlan == plan && state?.playIntent == true &&
                    !state.interactionIntent && state.blockers.isEmpty() && !state.closed &&
                    target != null && target.regionId == plan.passageRegionId && target.allowsStoryRegion(plan.regionId) &&
                    target.storyScope?.hierarchyKey == plan.hierarchyKey &&
                    regionalPlanner?.isCurrent(target, SystemClock.elapsedRealtime()) == true &&
                    currentRegionEvidence(target)
            },
            onChanged = { queue.execute { refreshPlanning(); changed() } },
        )
        val inputOutput = PleosRuntimeSpeech(context, inputAllowed = ::mayCapture)
        speech = inputOutput
        val musicOutput = Media3RuntimeMusic(context)
        music = musicOutput
        lateinit var live: PlaybackRuntime
        val apiResume = ApiRuntimeResumePlanner(client) { effect ->
            val turnId = pendingResumeTurnId
            pendingResumeTurnId = null
            persistence.resumeGenerationContext(effect, turnId) {
                live.isResumeCurrent(effect.request) && effect.musicCandidates.all {
                    try { persistence.prepareMusic(it.track.assetId) == it.track }
                    catch (_: Exception) { false }
                }
            }
        }
        val resume = object : RuntimeResumePlanner {
            override fun plan(effect: PlaybackEffect.PlanResume, callback: (RuntimeResumeSelection) -> Unit): RuntimeCancellation {
                if (effect.afterInteraction) return apiResume.plan(effect, callback)
                pendingResumeTurnId = null
                return CheckpointRuntimeResumePlanner().plan(effect, callback)
            }
        }
        live = PlaybackRuntime(
            PlaybackCoordinator(tripId, sessionId, restoredCheckpoint = storyCheckpoint, restoredMusicCheckpoint = musicCheckpoint,
                restoredMusicRequiredBeforeAutomaticStory = candidate?.requireMusicBeforeAutomaticStory == true),
            queue, AndroidRuntimeClock, bridge, inputOutput,
            generation = ApiRuntimeGeneration(client) { request -> persistence.answerContext(request) { live.isGenerationCurrent(request) } },
            music = musicOutput,
            contentAuthority = planner,
            resumePlanner = ContentCheckedResumePlanner(delegate = resume, storyCurrent = { story ->
                try { persistence.prepareStory(story.versionId) == story } catch (_: RuntimeContentUnavailable) { false }
            }, musicCurrent = { track ->
                try { persistence.prepareMusic(track.assetId) == track } catch (_: RuntimeContentUnavailable) { false }
            }),
            observer = object : RuntimeObserver {
                override fun onTransition(transition: PlaybackTransition) {
                    latest = transition.snapshot
                    lookahead?.onTransition(transition, SystemClock.elapsedRealtime())
                    musicPlanning?.onTransition(transition)
                    // Preserve the selected permit while the exact automatic start is being prepared.
                    // Actual Started will consume it and provide the new story's duration horizon.
                    if (transition.effects.none { it is PlaybackEffect.StartOutput && it.automaticOfferId != null }) refreshPlanning()
                    changed()
                }
                override fun onAutomaticStoryStarted(offerId: String, storyId: String, versionId: String) {
                    planning?.storyStarted(offerId, versionId)
                    // The SDK may have started just before a new position replaced its old offer.
                    // Runtime reports only a durable, current actual start: exclude that story too.
                    planning?.storyStartedWithoutOffer(storyId)
                }
                override fun onStoryStarted(storyId: String, versionId: String) {
                    planning?.storyStartedWithoutOffer(storyId)
                }
                override fun onStorySkipped(storyId: String) {
                    planning?.storySkipped(storyId)
                }
                override fun onIssue(issue: RuntimeIssue) { changed() }
            },
        )
        runtime = live
        latest = live.snapshot()
        if (epoch != fence.get() || disposed) { live.dispatch(PlaybackEvent.Stop); return }
        if (power.audioBlocked) live.dispatch(PlaybackEvent.Block(PlaybackBlocker.POWER_AUDIO))
        val version = requested
        if (play) {
            if (version != null && version != storyCheckpoint?.story?.versionId) live.playInstalledStory(version)
            else live.dispatch(PlaybackEvent.Play)
        }
        changed()
    }

    private fun persistence(): RoomRuntimePersistence {
        check(!disposed)
        SummaryBackground.ensureRecovered(context)
        return store ?: RoomRuntimePersistence(context, musicCatalog = musicCatalog).also {
            // Install reviewed public seeds once on the worker, before any catalog read. A rejected
            // bundle leaves existing content/settings available and grants no trip or location consent.
            BundledStoryCatalog(context).load(it.database)
            store = it
            musicTracks = try { musicCatalog.credits().map { credit -> credit.track } } catch (_: Exception) { emptyList() }
        }
    }

    fun readRecordingSettings(): ListenableFuture<VehicleRecordingSettings> = work {
        RoomVehiclePreferenceStore(persistence().database).readRecordingSettings()
    }

    fun readPreferences(): ListenableFuture<VehiclePreferences> = work {
        RoomVehiclePreferenceStore(persistence().database).readSettings()
    }

    fun setLocationEnabled(enabled: Boolean, expectedVersion: Long?): ListenableFuture<VehicleRecordingSettings> {
        checkMain()
        if (!visibleForTalk) return unavailableRecords()
        revokeAutomaticPlanning()
        val consentRequest = locationConsentAuthority.request(enabled)
        if (!enabled) { locationConsentEnabled = false; revokeLocationCapture() }
        return work {
            try {
                check(visibleForTalk && (!enabled || !isEndingTrip()))
                val settings = RoomVehiclePreferenceStore(persistence().database).setLocationEnabled(enabled, expectedVersion)
                // An old DB read or earlier ON save must not undo a later main-thread OFF request.
                if (enabled) locationConsentAuthority.confirmEnabled(consentRequest)
                val state = latest
                if (state != null && !state.closed && sourceSessionId == state.sessionId) updateLocationRecording(state.tripId, state.sessionId)
                settings
            } finally { refreshPlanning(); changed() }
        }
    }

    fun setInterestKeywords(keywords: List<String>, expectedVersion: Long?): ListenableFuture<VehiclePreferences> {
        checkMain()
        if (!visibleForTalk) return unavailableRecords()
        val copy = keywords.toList()
        revokeAutomaticPlanning()
        return work {
            try {
                check(visibleForTalk && !isEndingTrip())
                RoomVehiclePreferenceStore(persistence().database).setKeywords(copy, expectedVersion)
            } finally { refreshPlanning(); changed() }
        }
    }

    fun listTrips(): ListenableFuture<List<TripResultHeader>> = work {
        val storage = persistence()
        recoverableTripId = try { storage.sessionRecovery().currentTripCandidate()?.tripId } catch (_: Exception) { null }
        RoomTripResultsStore(storage.database).listTrips()
    }

    fun readTrip(tripId: String): ListenableFuture<TripResultsSnapshot?> = work {
        RoomTripResultsStore(persistence().database).readTrip(tripId)
    }

    fun endableTripId(): String? = runtime?.snapshot()?.tripId ?: recoverableTripId
    fun isEndingTrip(): Boolean = tripEnding.get() != null
    fun isTripEndPending(): Boolean = tripEnding.get()?.future?.isDone == false

    /** Only the parked same-process UI can end a trip. Standard Media Stop keeps the trip open. */
    fun endTrip(tripId: String): ListenableFuture<ClosedTrip> {
        checkMain()
        val previous = tripEnding.get()
        if (previous != null && previous.tripId == tripId && !previous.future.isDone) return previous.future
        if (disposed || !visibleForTalk || tripId != endableTripId() || (previous != null && previous.tripId != tripId))
            return unavailableRecords()
        val ending = TripEnding(tripId)
        if (!tripEnding.compareAndSet(previous, ending)) return unavailableRecords()
        fence.incrementAndGet()
        cancelStoryPreparation()
        sourceSessionId = null // End intent immediately fences both sensor streams, even before an audio ACK.
        queue.execute {
            try { stopSessionSources() } catch (_: Exception) { changed() }
            // A failed flush keeps the recorder and open segment for closeSession's retry.
        }
        // close fences output starts synchronously; success is delivered only after durable session close.
        val live = runtime
        if (live != null) live.close { closed -> finishTripEnd(ending, live, closed) }
        else queue.execute {
            // Runtime construction may have passed its worker gate just before end ingress.
            val created = runtime
            if (created == null) finishTripEnd(ending, null, true)
            else created.close { closed -> finishTripEnd(ending, created, closed) }
        }
        queue.schedule(10_000) {
            if (tripEnding.get() === ending && !ending.future.isDone) {
                ending.future.setException(IllegalStateException("TRIP_END_UNCONFIRMED"))
                changed() // Keep the end latch and preserve the unconfirmed records; retry remains explicit.
            }
        }
        changed()
        return ending.future
    }

    private fun finishTripEnd(ending: TripEnding, live: PlaybackRuntime?, sessionClosed: Boolean) {
        if (disposed || tripEnding.get() !== ending) return
        try {
            check(sessionClosed)
            val storage = persistence()
            val candidate = if (live == null) storage.sessionRecovery().currentTripCandidate() else null
            val sessionId = live?.snapshot()?.sessionId ?: candidate?.takeIf { it.tripId == ending.tripId }?.previousSessionId
                ?: error("TRIP_END_STALE")
            val closed = storage.tripLifecycleStore().endTrip(ending.tripId, sessionId, candidate?.tripRevision)
            generation?.close(); generation = null
            auxiliaryRoutes?.close(); auxiliaryRoutes = null
            auxiliaryRouteClient?.close(); auxiliaryRouteClient = null
            speech = null; music = null // Runtime close already released the audio adapters.
            locationStore?.close(); locationStore = null
            navigationStore?.close(); navigationStore = null
            planning = null; pendingResumeTurnId = null
            musicPlanning?.clear(); musicPlanning = null
            runtime = null; latest = null; recoverableTripId = null
            pendingSelection = false
            tripEnding.compareAndSet(ending, null)
            ending.future.set(closed)
        } catch (_: Exception) {
            ending.future.setException(IllegalStateException("TRIP_END_UNCONFIRMED"))
        }
        changed()
    }

    private class TripEnding(val tripId: String) {
        val future: SettableFuture<ClosedTrip> = SettableFuture.create()
    }
    private fun <T> unavailableRecords(): ListenableFuture<T> = SettableFuture.create<T>().also {
        it.setException(IllegalStateException("RECORDS_OPERATION_UNAVAILABLE"))
    }

    /** Same-process hook after a real preference save; no controller command can grant location consent. */
    fun locationSettingsChanged(): ListenableFuture<Void?> {
        revokeAutomaticPlanning()
        if (!LocationAccess.hasRequiredPermissions(context)) {
            locationInputAuthority?.invalidate()
        }
        return work {
            try {
                val state = latest
                if (!isEndingTrip() && state != null && !state.closed && sourceSessionId == state.sessionId) updateLocationRecording(state.tripId, state.sessionId)
                null
            } finally { refreshPlanning() }
        }
    }

    private fun startSessionSources(tripId: String, sessionId: String) {
        if (disposed || isEndingTrip()) return
        sourceSessionId = sessionId
        routeRevision = navigationStore?.readRevision(sessionId) ?: 0
        routeDependency = null
        observedNavigationRouteState = NavigationRouteState.UNKNOWN
        committedNavigationIngressRevision = navigationIngressRevision.incrementAndGet()
        invalidateLocation()
        val source = PleosNavigationSource(context, routeRevision)
        navigationSource = source
        main.post {
            if (!disposed && sourceSessionId == sessionId && navigationSource === source) {
                source.setAuxiliaryInputSink {
                    auxiliaryRoutes?.revoke()
                    revokeAutomaticPlanning()
                    queue.execute { if (sourceSessionId == sessionId && navigationSource === source) refreshPlanning() }
                }
                source.start { event ->
                    val changesAuthority = event is NavigationEvent.Changed || event is NavigationEvent.Failure ||
                        event is NavigationEvent.Availability && event.state != NavigationAvailability.READY &&
                        event.state != NavigationAvailability.CONNECTING
                    val ingressRevision = if (!disposed && sourceSessionId == sessionId && navigationSource === source &&
                        changesAuthority) navigationIngressRevision.incrementAndGet().also {
                            auxiliaryRoutes?.revoke(); revokeAutomaticPlanning()
                        }
                    else null
                    queue.execute {
                        if (!disposed && sourceSessionId == sessionId && navigationSource === source) {
                            if (ingressRevision != null) committedNavigationIngressRevision = ingressRevision
                            try {
                                when (event) {
                                    is NavigationEvent.Changed -> {
                                        if (navigationStore?.apply(sessionId, event.snapshot) == true) {
                                            routeRevision = event.snapshot.revision
                                            routeDependency = event.snapshot.observedRouteDependency
                                            observedNavigationRouteState = event.snapshot.observedRouteState
                                        } else {
                                            routeDependency = null
                                            observedNavigationRouteState = NavigationRouteState.UNKNOWN
                                        }
                                        refreshPlanning()
                                    }
                                    is NavigationEvent.Availability -> if (event.state != NavigationAvailability.READY && event.state != NavigationAvailability.CONNECTING) {
                                        routeDependency = null
                                        observedNavigationRouteState = NavigationRouteState.UNKNOWN
                                        refreshPlanning()
                                    }
                                    is NavigationEvent.Failure -> {
                                        routeDependency = null
                                        observedNavigationRouteState = NavigationRouteState.UNKNOWN
                                        refreshPlanning()
                                    }
                                    is NavigationEvent.ReadReceived -> Unit
                                }
                            } catch (_: Exception) {
                                routeDependency = null
                                observedNavigationRouteState = NavigationRouteState.UNKNOWN
                                refreshPlanning(); changed()
                            }
                        }
                    }
                }
            }
        }
        // Explicit location preference and the platform permission are independent gates.
        updateLocationRecording(tripId, sessionId)
    }

    private fun updateLocationRecording(tripId: String, sessionId: String) {
        val locations = locationStore ?: return
        locationConsentEnabled = locations.isExplicitlyEnabled()
        val enabled = locationAccessAllowed()
        if (!enabled) { stopLocationRecording(); return }
        if (recorder != null && locationSource != null) return
        locationInputAuthority?.invalidate()
        recorder?.stop()
        val inputAuthority = LocationInputAuthority()
        locationInputAuthority = inputAuthority
        // Worker-owned identities survive batching/retry. Receipt milliseconds are not unique.
        var recordingPermit: LocationInputAuthority.ReceiptPermit? = null
        val pendingPermits = mutableMapOf<String, LocationInputAuthority.ReceiptPermit?>()
        val recordingStore = object : LocationRecordingStore by locations {
            override fun append(segmentId: String, points: List<RecordedLocation>): LocationCommit {
                val committed = locations.append(segmentId, points)
                val last = points.last()
                val permit = pendingPermits[last.pointId]
                val received = last.receivedAtElapsedMs
                committedLocationAuthority = { inputAuthority.canUse(permit, received) }
                committedLocationElapsed = last.receivedAtElapsedMs
                locationFreshUntilElapsedMs = LocationFreshness.deadlineElapsedMs(last)
                locationValid = locationSource != null && last.quality == LocationQuality.VALID &&
                    last.receivedAtElapsedMs == latestReceivedElapsed && locationFreshUntilElapsedMs != null &&
                    committedLocationAuthority()
                // Only the latest exact receipt can authorize movement. The landmark session compares
                // committed points across batches; an approach entirely inside one batch may be missed.
                committedLocation = last.takeIf { locationValid }
                confirmedRegionAuthority?.invalidate()
                confirmedRegionAuthority = committed.confirmedRegion?.takeIf {
                    locationValid && it.lastPointId == last.pointId
                }?.let(::ConfirmedRegionAuthority)
                currentRegionId = confirmedRegionAuthority?.confirmation?.regionId
                pendingPermits.clear() // A failed append retains the exact point permits for retry.
                refreshPlanning()
                return committed
            }
        }
        val recording = LocationRecorder(recordingStore, locationNormalizer, {
            UUID.randomUUID().toString().also { pendingPermits[it] = recordingPermit }
        },
            clockScopeId, RECEIPT_GAP_CANDIDATE_MS, provider = "android-location-manager", routeRevision = { routeRevision })
        recorder = recording
        recording.begin(tripId, sessionId)
        val source = PleosAuthorizedLocationSource(
            accessSourceFactory = { PleosLocationSource(context) },
            measurementSourceFactory = { access ->
                AndroidLocationSource(context, AndroidLocationSource.preferredProvider(context) ?: "fused", access)
            },
            externalAccessAllowed = { locationAccessAllowed() && sourceSessionId == sessionId },
            elapsedMs = SystemClock::elapsedRealtime,
            schedule = { delay, action ->
                val task = Runnable(action)
                main.postDelayed(task, delay)
                val cancel: () -> Unit = { main.removeCallbacks(task) }
                cancel
            },
            guardFreshnessMs = RECEIPT_GAP_CANDIDATE_MS,
        )
        locationSource = source
        main.post {
            if (locationAccessAllowed() && sourceSessionId == sessionId && locationSource === source) source.start { event ->
                val doubtful = when (event) {
                    is LocationEvent.SampleReceived -> try {
                        locationNormalizer.normalize(event.sample, event.kind, "quality-check", "android-location-manager", clockScopeId)
                            .quality != LocationQuality.VALID
                    } catch (_: Exception) { true }
                    is LocationEvent.NoLocation, is LocationEvent.Failure, LocationEvent.PermissionDenied -> true
                    is LocationEvent.Availability -> !event.supported
                    else -> false
                }
                val permit = if (event is LocationEvent.SampleReceived)
                    inputAuthority.record(event.sample.receivedAtElapsedRealtimeMillis, !doubtful)
                else { if (doubtful) inputAuthority.invalidate(); null }
                if (doubtful && !disposed && sourceSessionId == sessionId && locationSource === source && recorder === recording)
                    { auxiliaryRoutes?.revoke(); revokeAutomaticPlanning() }
                queue.execute {
                    if (locationAccessAllowed() && sourceSessionId == sessionId && recorder === recording && locationSource === source) {
                        if (event is LocationEvent.SampleReceived) latestReceivedElapsed = event.sample.receivedAtElapsedRealtimeMillis
                        // A fresh doubtful/no-location event revokes the old automatic offer immediately.
                        if (doubtful) invalidateLocation()
                        recordingPermit = permit
                        try { recording.receive(event) }
                        catch (_: Exception) { pauseLocationSourceAfterFailure() }
                        finally { recordingPermit = null }
                    }
                }
            }
        }
        scheduleLocationTick(sessionId)
    }

    private fun scheduleLocationTick(sessionId: String) {
        locationTimer?.cancel()
        locationTimer = queue.schedule(LocationRecorder.BATCH_MILLIS) {
            if (disposed || sourceSessionId != sessionId || recorder == null) return@schedule
            try {
                if (locationStore?.isExplicitlyEnabled() != true || !locationAccessAllowed()) {
                    stopLocationRecording()
                    return@schedule
                }
                recorder?.tick(SystemClock.elapsedRealtime())
                val received = committedLocationElapsed
                val now = SystemClock.elapsedRealtime()
                val freshUntil = locationFreshUntilElapsedMs
                if (received == null || now < received || now - received > RECEIPT_GAP_CANDIDATE_MS ||
                    freshUntil == null || now > freshUntil) invalidateLocation()
                else refreshPlanning()
            } catch (_: Exception) { pauseLocationSourceAfterFailure() }
            if (recorder != null) scheduleLocationTick(sessionId)
        }
    }

    private fun refreshPlanning() {
        val state = latest ?: return
        if (disposed || state.closed || sourceSessionId != state.sessionId) {
            planningBoundary = PlanningBoundaryMemory()
            regionalTarget = null; automaticGenerationPlan = null; automaticGeneration?.update(null)
            planning?.clear(); return
        }
        try {
            val now = SystemClock.elapsedRealtime()
            val allowOffer = state.playIntent && state.blockers.isEmpty()
            if (!allowOffer) planningBoundary = PlanningBoundaryMemory()
            val observedBoundary = if (allowOffer) lookahead?.nextStoryStartElapsedMs(state, now, musicPlanning?.offeredDurationMs()) {
                storyTiming?.paragraphDurations(it)
            } else null
            val boundary = if (allowOffer) planningBoundary.resolve(state, observedBoundary, now,
                lookahead?.awaitingActualStart() == true) else null
            val locationAuthority = committedLocationAuthority
            val navigationRevision = committedNavigationIngressRevision
            // Capture this committed input, never a mutable latest permit. Main-thread revocation
            // remains effective even if an older queued refresh runs before its worker callback.
            val inputAuthority = { locationAuthority() && navigationIngressRevision.get() == navigationRevision }
            auxiliaryRoutes?.update(state.sessionId, clockScopeId, routeDependency, navigationSource?.auxiliaryInput(),
                committedLocation, inputAuthority, allowOffer && locationValid && locationAccessAllowed(),
                requestAllowed = { locationAccessAllowed() && !isEndingTrip() && sourceSessionId == state.sessionId &&
                    navigationIngressRevision.get() == navigationRevision })
            val forecast = routeForecasts?.forecast(state.sessionId, clockScopeId, routeDependency, now)
                ?: auxiliaryRoutes?.forecast(state.sessionId, clockScopeId, routeDependency, now)
            val planningInputAuthority = {
                inputAuthority() && (forecast == null || routeForecasts != null || auxiliaryRoutes?.isCurrent(forecast) == true)
            }
            // A raw position remains usable by independently reviewed landmarks when this region's
            // policy/source is withdrawn. Never issue a new regional candidate from a stale ID.
            val locations = locationStore
            val regionAuthority = confirmedRegionAuthority?.takeIf { permit ->
                locations != null && permit.recheck(locations::isConfirmedRegionCurrent)
            }
            currentRegionId = regionAuthority?.confirmation?.regionId
            val regionPermit = { regionAuthority?.isCurrent() == true }
            val regionEvidence = {
                locations != null && regionAuthority?.recheck(locations::isConfirmedRegionCurrent) == true
            }
            regionalTarget = regionalPlanner?.update(RegionalPlanningInput(state.sessionId, clockScopeId,
                routeDependency, now, committedLocationElapsed, locationValid, currentRegionId,
                state.interactionIntent, boundary, forecast,
                locationFreshUntilElapsedMs, planningInputAuthority,
                currentRegionPolicyKey = regionAuthority?.confirmation?.policySourceKey,
                currentRegionAuthority = regionPermit))
            // Pauses/blockers suppress offers, but passing a landmark during them is still terminal.
            // Only explicit observed inactivity allows current-position landmark selection. An active
            // route requires a reviewed route/landmark membership port, which is not available yet.
            val landmarkRoute = if (observedNavigationRouteState == NavigationRouteState.INACTIVE)
                LandmarkRouteContext.CurrentPosition else LandmarkRouteContext.Unavailable
            planning?.update(PlanningContext(currentRegionId, routeDependency, now, clockScopeId,
                locationValid, state.interactionIntent, committedLocationElapsed, regionalTarget,
                locationFreshUntilElapsedMs, planningInputAuthority, currentRegionAuthority = regionPermit,
                currentRegionEvidenceCurrent = regionEvidence), committedLocation, landmarkRoute, allowOffer)
        } catch (_: Exception) {
            automaticGenerationPlan = null; automaticGeneration?.update(null); regionalTarget = null
            locationValid = false
            committedLocation = null
            committedLocationAuthority = { false }
            confirmedRegionAuthority?.invalidate()
            planning?.clear()
            // Optional location lookup failure must not turn a committed playback transaction into a false failure.
            changed()
        }
    }

    private fun revokeAutomaticPlanning() {
        automaticGenerationPlan = null
        automaticGeneration?.revoke()
        planning?.revokeAuthority()
    }

    private fun invalidateLocation() {
        auxiliaryRoutes?.revoke()
        locationValid = false; currentRegionId = null; locationFreshUntilElapsedMs = null
        committedLocation = null
        committedLocationAuthority = { false }
        confirmedRegionAuthority?.invalidate()
        confirmedRegionAuthority = null
        // Region/landmark offers lose authority immediately; explicit interest matching needs no location.
        refreshPlanning()
    }

    /** Automatic generation invokes this off main before sending/accepting/installing its result. */
    private fun currentRegionEvidence(target: RegionalStoryTarget): Boolean {
        if (target.routeDependency != null) return true // A reviewed forecast owns its independent region proof.
        val authority = confirmedRegionAuthority ?: return false
        val locations = locationStore ?: return false
        val confirmation = authority.confirmation
        return confirmation.regionId == target.regionId && confirmation.policySourceKey == target.regionPolicyKey &&
            authority.recheck(locations::isConfirmedRegionCurrent)
    }

    private fun pauseLocationSourceAfterFailure() {
        locationInputAuthority?.invalidate()
        invalidateLocation()
        val source = locationSource
        locationSource = null
        main.post { source?.close() }
        // Retain pending point IDs. The bounded periodic flush may recover, but SDK capture does not restart implicitly.
        changed()
    }

    private fun stopLocationRecording() {
        locationInputAuthority?.invalidate()
        locationInputAuthority = null
        invalidateLocation()
        locationTimer?.cancel(); locationTimer = null
        val source = locationSource
        locationSource = null
        main.post { source?.close() }
        recorder?.stop(suspended = disposed)
        recorder = null
        latestReceivedElapsed = null; committedLocationElapsed = null
    }

    private fun stopSessionSources() {
        auxiliaryRoutes?.revoke()
        navigationIngressRevision.incrementAndGet()
        revokeAutomaticPlanning()
        sourceSessionId = null // Fence both SDK streams before flush or main-thread teardown.
        val navigation = navigationSource
        navigationSource = null
        main.post { navigation?.close() }
        routeDependency = null
        observedNavigationRouteState = NavigationRouteState.UNKNOWN
        stopLocationRecording()
    }
    private fun changed() { main.post { if (!released) { listener?.invoke(); uiListeners.toList().forEach { it() } } } }
    private fun <T> work(action: () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        if (disposed) { future.setException(IllegalStateException("SESSION_CLOSED")); return future }
        queue.execute {
            try { if (disposed) throw IllegalStateException("SESSION_CLOSED"); future.set(action()) }
            catch (_: Exception) { future.setException(IllegalStateException("SESSION_OPERATION_UNAVAILABLE")); changed() }
        }
        return future
    }

    override fun close() {
        checkMain()
        if (disposed) return
        disposed = true
        auxiliaryRoutes?.revoke()
        tripEnding.get()?.future?.setException(IllegalStateException("TRIP_END_UNCONFIRMED"))
        cancelStoryPreparation()
        visibleForTalk = false
        visibleForLocation = false
        locationConsentEnabled = false
        fence.incrementAndGet()
        policy.close()
        val live = runtime
        if (live != null) live.close()
        else queue.execute {
            // Construction may have passed its first guard before close read the volatile runtime.
            val created = runtime
            if (created == null) releaseResources() else created.close()
        }
        // Bound process-owned cleanup without marking an unconfirmed session closed in Room.
        queue.schedule(3_000) { releaseResources() }
    }
    private fun releaseResources() {
        if (released) return
        try { stopSessionSources() } catch (_: Exception) { /* Keep unconfirmed DB state for next recovery. */ }
        released = true
        musicPlanning?.clear(); musicPlanning = null
        automaticGeneration?.revoke(); automaticGeneration = null; automaticGenerationPlan = null
        auxiliaryRoutes?.close(); auxiliaryRoutes = null; auxiliaryRouteClient?.close(); auxiliaryRouteClient = null
        speech?.close(); music?.close(); generation?.close(); locationStore?.close(); navigationStore?.close(); store?.close()
        queue.close()
    }
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
    companion object {
        /** Conservative development candidate aligned with LOC freshness; real movement validation remains pending. */
        private const val RECEIPT_GAP_CANDIDATE_MS = 10_000L
    }
}
