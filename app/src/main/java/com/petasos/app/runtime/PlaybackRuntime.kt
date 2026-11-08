package com.petasos.app.runtime

import com.petasos.app.playback.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Serial effect host. Persistence runs on the dedicated worker queue, outside the coordinator's
 * monitor. SDK and network callbacks are always queued and retain the exact operation identity.
 * This host owns no UI, Android API, database implementation or server configuration.
 */
class PlaybackRuntime(
    private val coordinator: PlaybackCoordinator,
    private val queue: RuntimeQueue,
    private val clock: RuntimeClock,
    private val persistence: RuntimePersistence,
    private val speech: RuntimeSpeech,
    private val generation: RuntimeGeneration = UnavailableRuntimeGeneration(),
    private val observer: RuntimeObserver = object : RuntimeObserver {},
    private val nextHandle: () -> String = { UUID.randomUUID().toString() },
    private val music: RuntimeMusic = UnavailableRuntimeMusic(),
    private val resumePlanner: RuntimeResumePlanner = CheckpointRuntimeResumePlanner(),
    private val contentAuthority: RuntimeContentAuthority = RuntimeContentAuthority { _, _ -> false },
) {
    /** A captured start permission, not a command. Its internals contain no content or transcript. */
    class StoryPlaybackAuthority internal constructor(
        private val owner: Any,
        private val sessionId: String,
        private val cancelEpoch: Long,
        private val fence: Long,
    ) {
        internal fun fenceIfCurrent(owner: Any, state: PlaybackSnapshot, currentFence: Long): Long? =
            fence.takeIf { this.owner === owner && this.sessionId == state.sessionId &&
                cancelEpoch == state.cancelEpoch && it == currentFence }
        override fun toString() = "StoryPlaybackAuthority([redacted])"
    }

    private class GenerationJob(val request: OperationToken, val attempt: Int) {
        var cancellation: RuntimeCancellation? = null
    }
    private class ResumeJob { var cancellation: RuntimeCancellation? = null }
    private data class GenerationAuthority(val request: OperationToken, val turnId: String, val attempt: Int, val fence: Long)
    private data class ResumeAuthority(val request: OperationToken, val fence: Long)
    private sealed interface ContentRead<out T> {
        class Value<T>(val value: T) : ContentRead<T>
        data object Rejected : ContentRead<Nothing>
        data object Failed : ContentRead<Nothing>
    }

    private val startsFence = AtomicLong()
    private val storyAuthorityOwner = Any()
    private val registeredTurns = mutableSetOf<String>()
    private val registeredOutputs = mutableSetOf<OperationToken>()
    private val rejectedOutputs = mutableSetOf<OperationToken>()
    private val liveOutputs = mutableSetOf<OperationToken>()
    private val speechStartPermissions = mutableMapOf<OperationToken, AtomicBoolean>()
    private val musicStartPermissions = mutableMapOf<OperationToken, AtomicBoolean>()
    private val musicReferences = mutableMapOf<OperationToken, OutputReference.MusicTrack>()
    private val stoppingOutputs = mutableSetOf<OperationToken>()
    private val liveInputs = mutableSetOf<OperationToken>()
    private val stoppedInputs = mutableSetOf<OperationToken>()
    private val pendingStarts = mutableSetOf<OperationToken>()
    private val timers = mutableMapOf<TimerToken, RuntimeCancellation>()
    private val requests = mutableMapOf<OperationToken, GenerationJob>()
    private val resumeRequests = mutableMapOf<OperationToken, ResumeJob>()
    private val texts = mutableMapOf<PayloadHandle, RuntimeText>()
    private val answers = mutableMapOf<PayloadHandle, RuntimeAnswer>()
    private var opened = false
    private var disposed = false
    private var closing = false
    private var storageFailed = false
    private var closeResult: Boolean? = null
    private var retrySessionClose = false
    private val closeCallbacks = mutableListOf<(Boolean) -> Unit>()
    @Volatile private var latest = coordinator.snapshot()
    @Volatile private var generationAuthority: GenerationAuthority? = null
    private val resumeAuthority = AtomicReference<ResumeAuthority?>(null)

    init {
        queue.execute {
            try {
                persistence.openSession(latest.tripId, latest.sessionId)
                opened = true
            } catch (_: Exception) {
                storageFailure()
            }
        }
    }

    /** Snapshot contains references/status only, never transcript or generated answer bodies. */
    fun snapshot(): PlaybackSnapshot = latest

    /** Inert capture for an asynchronous producer. Capturing never opens or starts anything. */
    fun captureStoryPlaybackAuthority(): StoryPlaybackAuthority {
        val fence = startsFence.get()
        val state = latest
        return StoryPlaybackAuthority(storyAuthorityOwner, state.sessionId, state.cancelEpoch, fence)
    }

    /** The producer may use this guard, but must pass the same authority to the final play call. */
    fun isStoryPlaybackAuthorityCurrent(authority: StoryPlaybackAuthority): Boolean {
        val state = latest
        return authority.fenceIfCurrent(storyAuthorityOwner, state, startsFence.get()) != null &&
            !state.closed && state.blockers.isEmpty() && state.mode in setOf(PlaybackMode.IDLE, PlaybackMode.PAUSED) &&
            state.outputPhase == AdapterPhase.READY && state.inputPhase == AdapterPhase.READY && state.pendingPlaybackCommits == 0
    }

    /** Resolve current installed content on the worker queue; a later stop invalidates preparation. */
    fun playInstalledStory(versionId: String) {
        require(versionId.isNotBlank())
        val fence = startsFence.get()
        queue.execute {
            if (!mayPrepareStory(fence)) return@execute
            // An explicit Play can follow an already ingressed Stop whose epoch update is still
            // queued. Capture that new session epoch on the worker, while retaining Play's ingress
            // fence so a later Stop still revokes it. Async results use the fixed-authority overload.
            prepareAndPlayStory(versionId, captureStoryPlaybackAuthority(), fence)
        }
    }

    /** An async result may never replace its captured fence with the fence at result-delivery time. */
    fun playInstalledStory(versionId: String, authority: StoryPlaybackAuthority) = playInstalledStory(versionId, authority) { }

    /** ACK means the core accepted this exact playback intent, not that SDK output has started. */
    fun playInstalledStory(versionId: String, authority: StoryPlaybackAuthority, onAccepted: (Boolean) -> Unit) {
        val delivered = AtomicBoolean()
        fun finish(accepted: Boolean) {
            if (delivered.compareAndSet(false, true)) onAccepted(accepted)
        }
        if (versionId.isBlank()) { finish(false); return }
        val fence = authority.fenceIfCurrent(storyAuthorityOwner, latest, startsFence.get())
        if (fence == null) { finish(false); return }
        try {
            queue.execute {
                val accepted = try { prepareAndPlayStory(versionId, authority, fence) }
                    catch (_: Exception) { false }
                finish(accepted)
            }
        } catch (_: Exception) { finish(false) }
    }

    private fun prepareAndPlayStory(versionId: String, authority: StoryPlaybackAuthority, fence: Long): Boolean {
        if (!mayPrepareStory(fence) || !isStoryPlaybackAuthorityCurrent(authority)) return false
        val story = when (val read = contentRead { persistence.prepareStory(versionId) }) {
            is ContentRead.Value -> read.value
            else -> return false
        }
        if (story.versionId != versionId || !mayPrepareStory(fence) || !isStoryPlaybackAuthorityCurrent(authority)) return false
        process(PlaybackEvent.PlayStory(story), fence)
        val state = latest
        // IDLE/PAUSED was a preparation precondition. After dispatch the same authority instead
        // needs to own the accepted playback intent, including its normal RESUMING phase.
        return !disposed && !closing && !storageFailed && !state.closed && state.blockers.isEmpty() &&
            state.playIntent && state.checkpoint?.story?.versionId == versionId &&
            authority.fenceIfCurrent(storyAuthorityOwner, state, startsFence.get()) == fence
    }

    fun playInstalledMusic(assetId: String) {
        require(assetId.isNotBlank())
        val fence = startsFence.get()
        queue.execute {
            if (!mayPrepareStory(fence)) return@execute
            val track = when (val read = contentRead { persistence.prepareMusic(assetId) }) {
                is ContentRead.Value -> read.value
                else -> return@execute
            }
            if (mayPrepareStory(fence)) process(PlaybackEvent.PlayMusic(track))
        }
    }

    /** Safe from network threads. A stop ingress revokes authority before its queued event runs. */
    fun isGenerationCurrent(request: RuntimeGenerationRequest): Boolean {
        val authority = generationAuthority ?: return false
        val state = latest
        return authority.request == request.request && authority.turnId == request.turnId &&
            authority.attempt == request.attemptNumber && startsFence.get() == authority.fence &&
            state.sessionId == request.request.sessionId && state.cancelEpoch == request.request.cancelEpoch &&
            state.turnId == request.turnId && state.mode == PlaybackMode.GENERATING && state.interactionIntent &&
            state.blockers.isEmpty() && !state.closed
    }

    /** HTTP threads may inspect authority without reading the worker-owned job map. */
    fun isResumeCurrent(request: OperationToken): Boolean {
        val authority = resumeAuthority.get() ?: return false
        val state = latest
        return authority.request == request && state.sessionId == request.sessionId &&
            state.cancelEpoch == request.cancelEpoch && state.mode == PlaybackMode.RESUMING && state.playIntent &&
            state.blockers.isEmpty() && !state.closed && resumeAuthority.get() === authority && startsFence.get() == authority.fence
    }

    fun dispatch(event: PlaybackEvent) {
        if (event == PlaybackEvent.Talk) {
            // An ignored repeated Talk must not fence a resumed track's natural completion.
            // Use the coordinator's same monitor for debounce + fence: its state cannot advance
            // to a different output between these reads. A rejected ingress stays rejected even
            // if a busy worker would process it after the debounce window has elapsed.
            synchronized(coordinator) {
                if (coordinator.isTalkDebounced(clock.elapsedRealtimeMs())) return
                val state = coordinator.snapshot()
                val replacesResume = state.mode == PlaybackMode.RESUMING && state.blockers.isEmpty() &&
                    state.outputPhase != AdapterPhase.STOPPING && state.inputPhase != AdapterPhase.STOPPING
                if ((state.mode == PlaybackMode.MUSIC && state.outputPhase == AdapterPhase.ACTIVE) || replacesResume) startsFence.incrementAndGet()
            }
            queue.execute { if (!disposed && !closing) process(event) }
            return
        }
        val state = latest
        val navigationStopsOutput = (event == PlaybackEvent.Next || event == PlaybackEvent.Previous) &&
            state.mode in setOf(PlaybackMode.STORY, PlaybackMode.MUSIC) && state.outputPhase == AdapterPhase.ACTIVE && state.blockers.isEmpty()
        val cancelStopsInteraction = event == PlaybackEvent.CancelInteraction && state.interactionIntent && state.mode != PlaybackMode.TALK_PENDING
        if (event == PlaybackEvent.Stop || event == PlaybackEvent.Suspend || event is PlaybackEvent.Block || navigationStopsOutput || cancelStopsInteraction) startsFence.incrementAndGet()
        queue.execute { if (!disposed && !closing) process(event) }
    }

    /**
     * Requests graceful suspension. Cleanup acknowledgments continue draining until CloseSession;
     * an unconfirmed microphone stop remains unconfirmed and keeps this session in closing state.
     */
    fun close() = close { }

    /** True acknowledges quiescence and durable session close. An unconfirmed adapter stays pending. */
    fun close(onClosed: (Boolean) -> Unit) {
        startsFence.incrementAndGet()
        val delivered = AtomicBoolean()
        val callback: (Boolean) -> Unit = { result -> if (delivered.compareAndSet(false, true)) onClosed(result) }
        try { queue.execute {
            if (closeResult == true) { callback(true); return@execute }
            closeCallbacks += callback
            if (retrySessionClose) { commitSessionClose(); return@execute }
            if (closeResult == false && closing) { completeClose(false); return@execute }
            if (disposed) { completeClose(false); return@execute }
            if (closing) return@execute
            closing = true
            process(PlaybackEvent.Suspend)
            timers.values.forEach { it.cancel() }
            timers.clear()
            requests.values.forEach { it.cancellation?.cancel() }
            requests.clear()
            resumeAuthority.set(null)
            resumeRequests.values.forEach { it.cancellation?.cancel() }
            resumeRequests.clear()
            generationAuthority = null
            texts.clear()
            answers.clear()
            if (latest.inputPhase == AdapterPhase.STOPPING) observer.onIssue(RuntimeIssue.INPUT_STOP_UNCONFIRMED)
            if (storageFailed) completeClose(false)
        } } catch (_: Exception) { callback(false) }
    }

    private fun completeClose(success: Boolean) {
        closeResult = success
        val callbacks = closeCallbacks.toList()
        closeCallbacks.clear()
        callbacks.forEach { callback -> try { callback(success) } catch (_: Exception) { /* Owner callback failure is not a close ACK. */ } }
    }

    private fun commitSessionClose() {
        if (!opened || (storageFailed && !retrySessionClose)) { completeClose(false); return }
        // Only this final write is retryable here. Earlier failed playback/turn writes cannot be
        // repaired by changing a session flag, even when every adapter has physically stopped.
        retrySessionClose = true
        val committed = try { persistence.closeSession(latest.sessionId); true }
            catch (_: Exception) { storageFailure(); false }
        try { speech.close() } catch (_: Exception) { observer.onIssue(RuntimeIssue.SPEECH_UNAVAILABLE) }
        try { music.close() } catch (_: Exception) { observer.onIssue(RuntimeIssue.MUSIC_UNAVAILABLE) }
        musicReferences.clear()
        if (committed) {
            retrySessionClose = false
            disposed = true
            completeClose(true)
        } else completeClose(false)
    }

    private fun process(event: PlaybackEvent, expectedStartFence: Long? = null) {
        processOne(event, expectedStartFence)
        // A rejected reference must be removed before a queued Stop/Suspend can save it.
        // Drain after this effect batch, on the same serial worker, without reentrant effects.
        while (!disposed) {
            val rejected = rejectedOutputs.firstOrNull() ?: break
            rejectedOutputs -= rejected
            processOne(PlaybackEvent.OutputNotRegistered(rejected))
        }
    }

    private fun processOne(event: PlaybackEvent, expectedStartFence: Long? = null) {
        if (disposed) return
        val transitionFence = expectedStartFence ?: startsFence.get()
        if (expectedStartFence != null && startsFence.get() != expectedStartFence) return
        val transition = coordinator.dispatch(event, clock.elapsedRealtimeMs())
        latest = transition.snapshot
        // Exclusion must reach the planner before its transition refresh can queue the
        // skipped story again, and before a synchronous adapter stop can acknowledge.
        transition.effects.filterIsInstance<PlaybackEffect.ExcludeAutomaticStory>()
            .forEach { observer.onStorySkipped(it.storyId) }
        // The turn row must exist before any prompt, input, final commit or generation for it.
        val turn = latest.turnId
        if (turn != null && turn !in registeredTurns && !storageFailed) {
            try {
                persistence.registerTurn(latest.tripId, latest.sessionId, turn)
                registeredTurns += turn
            } catch (_: Exception) {
                observer.onTransition(transition)
                storageFailure()
                return
            }
        }
        observer.onTransition(transition)
        transition.effects.forEach { execute(it, transitionFence) }
        if (event is PlaybackEvent.OutputNotRegistered) rejectedOutputs -= event.attempt
    }

    private fun execute(effect: PlaybackEffect, transitionFence: Long) {
        when (effect) {
            is PlaybackEffect.StartOutput -> startOutput(effect, transitionFence)
            is PlaybackEffect.StopOutput -> stopOutput(effect.attempt)
            is PlaybackEffect.RequestInput -> requestInput(effect)
            is PlaybackEffect.StopInput -> stopInput(effect.input)
            is PlaybackEffect.CommitPlayback -> {
                if (effect.attempt in rejectedOutputs) {
                    // The local rejection drain will remove this attempt; there is no DB row.
                } else {
                    val success = !storageFailed && effect.attempt in registeredOutputs && persist { persistence.commitPlayback(effect, latest) }
                    registeredOutputs -= effect.attempt
                    enqueue(PlaybackEvent.PlaybackCommitted(effect.attempt, success))
                }
            }
            is PlaybackEffect.PersistUtterance -> {
                val body = texts[effect.transientPayload]
                val stored = if (body == null || storageFailed) null else persistValue { persistence.persistUtterance(effect.commit, effect.turnId, body) }
                enqueue(PlaybackEvent.UtteranceCommitted(effect.commit, stored))
            }
            is PlaybackEffect.PersistAnswer -> {
                val body = answers[effect.answer.payload]
                if (body == null || storageFailed) enqueue(PlaybackEvent.AnswerCommitted(effect.commit, null))
                else when (val result = contentRead { persistence.persistAnswer(effect.commit, effect.turnId, body) }) {
                    is ContentRead.Value -> enqueue(PlaybackEvent.AnswerCommitted(effect.commit, result.value))
                    ContentRead.Rejected -> enqueue(PlaybackEvent.AnswerContentRejected(effect.commit))
                    ContentRead.Failed -> enqueue(PlaybackEvent.AnswerCommitted(effect.commit, null))
                }
            }
            is PlaybackEffect.GenerateAnswer -> generate(effect, transitionFence)
            is PlaybackEffect.ExcludeAutomaticStory -> Unit // Delivered before onTransition above.
            is PlaybackEffect.CancelRequest -> {
                resumeAuthority.get()?.takeIf { it.request == effect.request }?.let { resumeAuthority.compareAndSet(it, null) }
                requests.remove(effect.request)?.cancellation?.cancel()
                resumeRequests.remove(effect.request)?.cancellation?.cancel()
                if (generationAuthority?.request == effect.request) generationAuthority = null
            }
            is PlaybackEffect.FreezeTurn -> if (!storageFailed) persist { persistence.freezeTurn(effect) }
            is PlaybackEffect.SaveCheckpoint -> if (!storageFailed) persist { persistence.saveCheckpoint(latest) }
            is PlaybackEffect.PlanResume -> planResume(effect, transitionFence)
            is PlaybackEffect.ScheduleTimer -> {
                val timer = effect.timer
                timers.remove(timer)?.cancel()
                timers[timer] = queue.schedule((timer.dueAtMs - clock.elapsedRealtimeMs()).coerceAtLeast(0)) {
                    if (!disposed && timers.remove(timer) != null) process(PlaybackEvent.TimerExpired(timer))
                }
            }
            is PlaybackEffect.CancelTimer -> timers.remove(effect.timer)?.cancel()
            PlaybackEffect.CloseSession -> {
                commitSessionClose()
            }
        }
    }

    private fun startOutput(effect: PlaybackEffect.StartOutput, fence: Long) {
        if (storageFailed || !opened) {
            enqueue(PlaybackEvent.OutputFailed(effect.attempt))
            return
        }
        when (contentRead {
            persistence.registerOutput(effect.attempt, effect.reference, latest.turnId)
            registeredOutputs += effect.attempt
        }) {
            ContentRead.Rejected -> {
                rejectedOutputs += effect.attempt
                return
            }
            ContentRead.Failed -> { enqueue(PlaybackEvent.OutputFailed(effect.attempt)); return }
            is ContentRead.Value -> Unit
        }
        (effect.reference as? OutputReference.MusicTrack)?.let { musicReferences[effect.attempt] = it }
        pendingStarts += effect.attempt
        queue.execute outputStart@ {
            if (disposed || !pendingStarts.remove(effect.attempt)) return@outputStart
            if (closing || storageFailed || startsFence.get() != fence || latest.blockers.isNotEmpty()) {
                // This operation never reached the SDK, so quiescence is independently known.
                enqueue(knownOutputStopped(effect.attempt))
                return@outputStart
            }
            val musicReference = effect.reference as? OutputReference.MusicTrack
            if (musicReference != null) {
                startMusic(effect.attempt, musicReference, fence)
                return@outputStart
            }
            liveOutputs += effect.attempt
            val permission = AtomicBoolean(true)
            speechStartPermissions[effect.attempt] = permission
            val preparationRequested = AtomicBoolean()
            try {
                var startAcknowledged = false
                speech.startPreparedOutput(effect.attempt, prepare = prepare@ { callback ->
                    if (!preparationRequested.compareAndSet(false, true)) return@prepare
                    // A real adapter requests this only after Connected. Do not retain plaintext
                    // during connection or perform the current-rights DB read on the SDK thread.
                    queue.execute { callback(prepareSpeechOutput(effect, fence, permission)) }
                }) { result ->
                    queue.execute outputCallback@ {
                        if (disposed || effect.attempt !in liveOutputs) return@outputCallback
                        if (startsFence.get() != fence && (result == RuntimeOutputEvent.COMPLETED || result == RuntimeOutputEvent.STARTED)) return@outputCallback
                        if (result == RuntimeOutputEvent.STARTED) {
                            if (startAcknowledged || storageFailed || closing || effect.attempt in stoppingOutputs ||
                                latest.closed || latest.blockers.isNotEmpty() || latest.outputPhase != AdapterPhase.ACTIVE) return@outputCallback
                            startAcknowledged = true
                            if (!persist { persistence.markOutputStarted(effect.attempt) }) return@outputCallback
                            // Persistence may race a Stop/close ingress. A durable Started row alone
                            // cannot make an already revoked attempt consume an automatic offer.
                            if (disposed || closing || storageFailed || startsFence.get() != fence ||
                                effect.attempt !in liveOutputs || effect.attempt in stoppingOutputs ||
                                latest.closed || latest.blockers.isNotEmpty() || latest.outputPhase != AdapterPhase.ACTIVE) return@outputCallback
                            // Consume the exact actual start before OutputStarted's transition lets
                            // the host project the next region and replace the current offer token.
                            if (effect.reference is OutputReference.StoryParagraph) {
                                if (effect.automaticOfferId != null)
                                    observer.onAutomaticStoryStarted(effect.automaticOfferId, effect.reference.storyId, effect.reference.versionId)
                                else observer.onStoryStarted(effect.reference.storyId, effect.reference.versionId)
                            }
                            process(PlaybackEvent.OutputStarted(effect.attempt), fence)
                            return@outputCallback
                        }
                        if (result == RuntimeOutputEvent.FOCUS_LOST || result == RuntimeOutputEvent.FOCUS_TRANSIENT) {
                            process(PlaybackEvent.Block(if (result == RuntimeOutputEvent.FOCUS_LOST) PlaybackBlocker.FOCUS_LOST else PlaybackBlocker.FOCUS_TRANSIENT))
                            return@outputCallback
                        }
                        val event = when (result) {
                            RuntimeOutputEvent.COMPLETED -> PlaybackEvent.OutputDone(effect.attempt)
                            RuntimeOutputEvent.STOPPED -> PlaybackEvent.OutputStopped(effect.attempt)
                            RuntimeOutputEvent.FAILED -> PlaybackEvent.OutputFailed(effect.attempt)
                            else -> error("handled above")
                        }
                        // Failure does not prove output silence; retain it until a real stopped callback.
                        if (result == RuntimeOutputEvent.STOPPED || (result == RuntimeOutputEvent.COMPLETED && effect.attempt !in stoppingOutputs)) {
                            speechStartPermissions.remove(effect.attempt)?.set(false)
                            liveOutputs -= effect.attempt
                            stoppingOutputs -= effect.attempt
                        }
                        process(event)
                    }
                }
            } catch (_: Exception) {
                observer.onIssue(RuntimeIssue.SPEECH_UNAVAILABLE)
                enqueue(PlaybackEvent.OutputFailed(effect.attempt))
            }
        }
    }

    private fun prepareSpeechOutput(
        effect: PlaybackEffect.StartOutput,
        fence: Long,
        permission: AtomicBoolean,
    ): RuntimeSpeechPermit? {
        fun eligible() = !disposed && !closing && !storageFailed && permission.get() &&
            effect.attempt in liveOutputs && effect.attempt !in stoppingOutputs &&
            startsFence.get() == fence && latest.blockers.isEmpty()
        if (!eligible()) return null
        val body = when (val result = contentRead { persistence.loadOutput(effect.reference) }) {
            is ContentRead.Value -> result.value
            ContentRead.Rejected, ContentRead.Failed -> return null
        }
        if (!eligible()) return null
        val reference = effect.reference as? OutputReference.StoryParagraph
        val offerId = effect.automaticOfferId
        if (offerId != null && reference != null) {
            val current = try { contentAuthority.isAutomaticStoryCurrent(offerId, reference) } catch (_: Exception) { false }
            if (!current) { observer.onIssue(RuntimeIssue.CONTENT_UNAVAILABLE); return null }
        }
        if (!eligible()) return null
        return RuntimeSpeechPermit(body) {
            // SDK-thread reads are restricted to atomic/volatile state and the cheap offer guard.
            // Talk deliberately permits a currently finishing story paragraph despite epoch change.
            val state = latest
            permission.get() && startsFence.get() == fence && !state.closed && state.blockers.isEmpty() &&
                state.outputPhase == AdapterPhase.ACTIVE &&
                (offerId == null || reference == null || contentAuthority.isAutomaticStoryOffered(offerId, reference)) &&
                startsFence.get() == fence && permission.get()
        }
    }

    private fun stopOutput(attempt: OperationToken) {
        speechStartPermissions.remove(attempt)?.set(false)
        musicStartPermissions.remove(attempt)?.set(false)
        stoppingOutputs += attempt
        if (pendingStarts.remove(attempt) || attempt !in liveOutputs) {
            stoppingOutputs -= attempt
            enqueue(knownOutputStopped(attempt))
        } else try {
            if (attempt in musicReferences) music.stopOutput(attempt) else speech.stopOutput(attempt)
        } catch (_: Exception) {
            observer.onIssue(if (attempt in musicReferences) RuntimeIssue.MUSIC_UNAVAILABLE else RuntimeIssue.SPEECH_UNAVAILABLE)
        }
    }

    private fun knownOutputStopped(attempt: OperationToken) = PlaybackEvent.OutputStopped(attempt, musicReferences[attempt]?.positionMs)

    private fun startMusic(attempt: OperationToken, reference: OutputReference.MusicTrack, fence: Long) {
        liveOutputs += attempt
        val permission = AtomicBoolean(true)
        musicStartPermissions[attempt] = permission
        val preparationRequested = AtomicBoolean()
        var startAcknowledged = false
        try {
            music.startPreparedOutput(attempt, reference.positionMs, prepare = prepare@ { callback ->
                if (!preparationRequested.compareAndSet(false, true)) return@prepare
                // File hashing and current-rights reads stay on this worker. The adapter reserves
                // its main-thread slot first, then rechecks the returned permit before player start.
                queue.execute { callback(prepareMusicOutput(attempt, reference, fence, permission)) }
            }) { result ->
                queue.execute musicCallback@ {
                    if (disposed || attempt !in liveOutputs) return@musicCallback
                    if (startsFence.get() != fence && (result is RuntimeMusicEvent.Completed || result == RuntimeMusicEvent.Started)) return@musicCallback
                    when (result) {
                        RuntimeMusicEvent.Started -> {
                            if (startAcknowledged || attempt in stoppingOutputs) return@musicCallback
                            startAcknowledged = true
                            if (!storageFailed) persist { persistence.markOutputStarted(attempt) }
                            process(PlaybackEvent.OutputStarted(attempt))
                        }
                        is RuntimeMusicEvent.Position -> process(PlaybackEvent.MusicPosition(attempt, result.positionMs))
                        is RuntimeMusicEvent.Completed -> {
                            if (result.positionMs < 0) { process(PlaybackEvent.OutputFailed(attempt)); return@musicCallback }
                            if (attempt !in stoppingOutputs) {
                                musicStartPermissions.remove(attempt)?.set(false)
                                liveOutputs -= attempt
                                musicReferences -= attempt
                            }
                            process(PlaybackEvent.OutputDone(attempt, result.positionMs))
                        }
                        is RuntimeMusicEvent.Stopped -> {
                            if (result.positionMs < 0) { observer.onIssue(RuntimeIssue.MUSIC_UNAVAILABLE); return@musicCallback }
                            if (!startAcknowledged && attempt !in stoppingOutputs && startsFence.get() == fence && !storageFailed && !closing) {
                                // A current start rejected by the final source guard is a failure,
                                // not a completed song or permission to retry the same intent.
                                process(PlaybackEvent.OutputFailed(attempt))
                            }
                            musicStartPermissions.remove(attempt)?.set(false)
                            liveOutputs -= attempt
                            stoppingOutputs -= attempt
                            musicReferences -= attempt
                            process(PlaybackEvent.OutputStopped(attempt, result.positionMs))
                        }
                        RuntimeMusicEvent.Failed -> {
                            observer.onIssue(RuntimeIssue.MUSIC_UNAVAILABLE)
                            process(PlaybackEvent.OutputFailed(attempt))
                        }
                        RuntimeMusicEvent.FocusLost -> process(PlaybackEvent.Block(PlaybackBlocker.FOCUS_LOST))
                        RuntimeMusicEvent.FocusTransient -> process(PlaybackEvent.Block(PlaybackBlocker.FOCUS_TRANSIENT))
                    }
                }
            }
        } catch (_: Exception) {
            observer.onIssue(RuntimeIssue.MUSIC_UNAVAILABLE)
            enqueue(PlaybackEvent.OutputFailed(attempt))
        }
    }

    private fun prepareMusicOutput(
        attempt: OperationToken,
        reference: OutputReference.MusicTrack,
        fence: Long,
        permission: AtomicBoolean,
    ): RuntimeMusicPermit? {
        fun eligible() = !disposed && !closing && !storageFailed && permission.get() &&
            attempt in liveOutputs && attempt !in stoppingOutputs && startsFence.get() == fence && latest.blockers.isEmpty()
        if (!eligible()) return null
        val source = when (val result = contentRead {
            persistence.loadMusic(reference).also {
                if (it.assetId != reference.assetId || !it.isCurrent()) throw RuntimeContentUnavailable()
            }
        }) {
            is ContentRead.Value -> result.value
            ContentRead.Rejected -> { enqueue(PlaybackEvent.OutputFailed(attempt)); return null }
            ContentRead.Failed -> return null
        }
        if (!eligible()) return null
        return RuntimeMusicPermit(source) {
            val state = latest
            permission.get() && startsFence.get() == fence && !state.closed && state.blockers.isEmpty() &&
                state.sessionId == attempt.sessionId && state.cancelEpoch == attempt.cancelEpoch &&
                state.mode == PlaybackMode.MUSIC && state.playIntent && state.outputPhase == AdapterPhase.ACTIVE &&
                source.isCurrent() && startsFence.get() == fence && permission.get()
        }
    }

    private fun planResume(effect: PlaybackEffect.PlanResume, fence: Long) {
        if (disposed || closing || startsFence.get() != fence) return
        val job = ResumeJob()
        val authority = ResumeAuthority(effect.request, fence)
        resumeRequests.remove(effect.request)?.cancellation?.cancel()
        resumeRequests[effect.request] = job
        resumeAuthority.set(authority)
        if (!isResumeCurrent(effect.request)) {
            resumeAuthority.compareAndSet(authority, null)
            resumeRequests.remove(effect.request)
            return
        }
        try {
            val cancellation = resumePlanner.plan(effect) resumeResult@ { selection ->
                val current = isResumeCurrent(effect.request)
                if (!resumeAuthority.compareAndSet(authority, null) || !current) return@resumeResult
                queue.execute {
                    if (!disposed && resumeRequests[effect.request] === job && startsFence.get() == fence) {
                        resumeRequests.remove(effect.request)
                        process(PlaybackEvent.ResumePlanned(effect.request, selection.checkpoint, selection.musicCheckpoint), fence)
                    }
                }
            }
            if (resumeRequests[effect.request] === job) job.cancellation = cancellation else cancellation.cancel()
        } catch (_: Exception) {
            resumeAuthority.compareAndSet(authority, null)
            resumeRequests.remove(effect.request)
            // The core's bounded timer retains the fixed checkpoint fallback.
        }
    }

    private fun requestInput(effect: PlaybackEffect.RequestInput) {
        val fence = startsFence.get()
        pendingStarts += effect.input
        queue.execute inputStart@ {
            if (disposed || !pendingStarts.remove(effect.input)) return@inputStart
            if (storageFailed || startsFence.get() != fence || latest.blockers.isNotEmpty()) {
                enqueue(PlaybackEvent.InputStoppedConfirmed(effect.input))
                return@inputStart
            }
            liveInputs.clear()
            stoppedInputs.clear()
            liveInputs += effect.input
            try {
                speech.requestInput(effect.input) { result ->
                    queue.execute inputCallback@ {
                        if (disposed || effect.input !in liveInputs) return@inputCallback
                        if (startsFence.get() != fence && result != RuntimeInputEvent.StoppedConfirmed) return@inputCallback
                        when (result) {
                            RuntimeInputEvent.Ready -> process(PlaybackEvent.InputReady(effect.input))
                            RuntimeInputEvent.Started -> process(PlaybackEvent.InputStarted(effect.input))
                            RuntimeInputEvent.Ended -> process(PlaybackEvent.InputEnded(effect.input))
                            RuntimeInputEvent.Failed -> process(PlaybackEvent.InputFailed(effect.input))
                            RuntimeInputEvent.StoppedConfirmed -> {
                                stoppedInputs += effect.input
                                process(PlaybackEvent.InputStoppedConfirmed(effect.input))
                            }
                            is RuntimeInputEvent.Final -> {
                                val handle = PayloadHandle(nextHandle())
                                texts[handle] = result.text
                                try { process(PlaybackEvent.InputFinal(effect.input, handle)) } finally { texts.remove(handle) }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                observer.onIssue(RuntimeIssue.SPEECH_UNAVAILABLE)
                enqueue(PlaybackEvent.InputFailed(effect.input))
            }
        }
    }

    private fun stopInput(input: OperationToken) {
        if (pendingStarts.remove(input) || input !in liveInputs || input in stoppedInputs) {
            enqueue(PlaybackEvent.InputStoppedConfirmed(input))
        } else try {
            speech.stopInput(input)
        } catch (_: Exception) {
            observer.onIssue(RuntimeIssue.SPEECH_UNAVAILABLE)
        }
    }

    private fun generate(effect: PlaybackEffect.GenerateAnswer, fence: Long) {
        fun currentContext(): Boolean = !disposed && !closing && !storageFailed && startsFence.get() == fence &&
            latest.sessionId == effect.request.sessionId && latest.cancelEpoch == effect.request.cancelEpoch &&
            latest.turnId == effect.turnId && latest.mode == PlaybackMode.GENERATING && latest.interactionIntent &&
            latest.inputPhase == AdapterPhase.READY && latest.outputPhase == AdapterPhase.READY &&
            latest.pendingPlaybackCommits == 0 && latest.blockers.isEmpty() && !latest.closed
        // Retain the effect's original transition fence across persistence and observer
        // callbacks. Reading a final payload must not acquire a newer permission after Stop.
        if (!currentContext()) return
        val body = if (storageFailed) null else persistValue { persistence.loadUtterance(effect.storedPayload) }
        if (body == null) {
            enqueue(PlaybackEvent.AnswerFailed(effect.request, retryable = false))
            return
        }
        if (!currentContext()) return
        requests.remove(effect.request)?.cancellation?.cancel()
        val job = GenerationJob(effect.request, effect.attemptNumber)
        requests[effect.request] = job
        generationAuthority = GenerationAuthority(effect.request, effect.turnId, effect.attemptNumber, fence)
        val request = RuntimeGenerationRequest(effect.request, effect.turnId, effect.attemptNumber, body)
        queue.execute generationStart@ {
            if (!currentContext() || requests[effect.request] !== job || !isGenerationCurrent(request)) return@generationStart
            try {
                val command = ExplicitPlaybackCommandParser.parse(body.value)
                if (command != null) {
                    if (!currentContext() || requests[effect.request] !== job || !isGenerationCurrent(request)) return@generationStart
                    requests.remove(effect.request)
                    generationAuthority = null
                    process(PlaybackEvent.ExplicitCommandResolved(effect.request, command), fence)
                    return@generationStart
                }
                job.cancellation = generation.generate(request) { result ->
                    queue.execute generationCallback@ {
                        if (!currentContext() || requests[effect.request] !== job || !isGenerationCurrent(request)) return@generationCallback
                        requests.remove(effect.request)
                        generationAuthority = null
                        when (result) {
                            is RuntimeGenerationResult.Failure -> {
                                if (!result.retryable) observer.onIssue(RuntimeIssue.GENERATION_UNAVAILABLE)
                                process(PlaybackEvent.AnswerFailed(effect.request, result.retryable, result.retryAfterMs), fence)
                            }
                            is RuntimeGenerationResult.Success -> {
                                val handle = PayloadHandle(nextHandle())
                                answers[handle] = result.answer
                                try {
                                    process(PlaybackEvent.AnswerReceived(effect.request, ValidatedAnswer(handle, result.answer.paragraphs.map { it.key })), fence)
                                } finally { answers.remove(handle) }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                requests.remove(effect.request)
                generationAuthority = null
                observer.onIssue(RuntimeIssue.GENERATION_UNAVAILABLE)
                enqueue(PlaybackEvent.AnswerFailed(effect.request, retryable = false))
            }
        }
    }

    private fun enqueue(event: PlaybackEvent) = queue.execute { if (!disposed) process(event) }

    private fun mayPrepareStory(fence: Long): Boolean =
        !disposed && !closing && !storageFailed && opened && startsFence.get() == fence && !latest.closed &&
            latest.blockers.isEmpty() && latest.mode in setOf(PlaybackMode.IDLE, PlaybackMode.PAUSED) &&
            latest.outputPhase == AdapterPhase.READY && latest.inputPhase == AdapterPhase.READY && latest.pendingPlaybackCommits == 0

    private inline fun persist(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (_: Exception) {
        storageFailure()
        false
    }

    private inline fun <T> persistValue(action: () -> T): T? = try {
        action()
    } catch (_: Exception) {
        storageFailure()
        null
    }

    private inline fun <T> contentRead(action: () -> T): ContentRead<T> = try {
        ContentRead.Value(action())
    } catch (_: RuntimeContentUnavailable) {
        observer.onIssue(RuntimeIssue.CONTENT_UNAVAILABLE)
        ContentRead.Rejected
    } catch (_: Exception) {
        storageFailure()
        ContentRead.Failed
    }

    private fun storageFailure() {
        if (storageFailed) return
        storageFailed = true
        startsFence.incrementAndGet()
        observer.onIssue(RuntimeIssue.STORAGE_UNAVAILABLE)
        enqueue(PlaybackEvent.Block(PlaybackBlocker.STORAGE_UNAVAILABLE))
        if (closing) completeClose(false)
    }
}
