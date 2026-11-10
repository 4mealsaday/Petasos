package com.petasos.app.playback

import java.util.UUID

/**
 * First, platform-independent v0.8 slice. The host executes effects outside this monitor and
 * dispatches acknowledgments afterwards. This class neither invents persistence success nor
 * maps SDK speech-segment events to physical stop confirmation. One instance is one session.
 * Content eligibility, route dependencies and durable recovery are supplied by the host.
 */
class PlaybackCoordinator(
    private val tripId: String,
    private val sessionId: String,
    private val nextId: () -> String = { UUID.randomUUID().toString() },
    restoredCheckpoint: StoryCheckpoint? = null,
    restoredMusicCheckpoint: MusicCheckpoint? = null,
    restoredMusicRequiredBeforeAutomaticStory: Boolean = false,
    /** Wait for the first spoken word after the mic opens. DEMO raises it for a human speaker. */
    private val firstSpeechMs: Long = 5_000,
) {
    private enum class Purpose { STORY, MUSIC, PROMPT, ANSWER, FAILURE, COMMAND_NOTICE }
    private enum class Navigation { NEXT, PREVIOUS }
    private data class Output(val token: OperationToken, val reference: OutputReference, val purpose: Purpose, var phase: AdapterPhase, val ownerTurnId: String?)
    private data class Input(
        val token: OperationToken,
        val turnId: String,
        var phase: AdapterPhase = AdapterPhase.ACTIVE,
        var ready: Boolean = false,
        var started: Boolean = false,
        var ended: Boolean = false,
        var finalSeen: Boolean = false,
    )
    private data class Turn(
        val id: String,
        var utterance: PayloadHandle? = null,
        var finalCommit: OperationToken? = null,
        var answerCommit: OperationToken? = null,
        var answer: ValidatedAnswer? = null,
        var answerIndex: Int = 0,
        val completedKeys: MutableList<String> = mutableListOf(),
        var closingOutcome: TurnOutcome? = null,
    )
    private data class PendingPlayback(val output: Output, val outcome: AttemptOutcome, val nextCheckpoint: StoryCheckpoint?, val nextMusicCheckpoint: MusicCheckpoint? = null)
    private data class Generation(val token: OperationToken, val turnId: String, val deadline: Long, var attempt: Int = 1, var waitingRetry: Boolean = false)

    private var mode = PlaybackMode.IDLE
    private var revision = 0L
    private var epoch = 0L
    private var lastTime = -1L
    private var lastTalkTime: Long? = null
    private var playIntent = false
    private var interactionIntent = false
    private var closed = false
    private var closingSession = false
    private var checkpoint = restoredCheckpoint?.ownedCopy()
    private var musicCheckpoint = restoredMusicCheckpoint
    private var offeredStory: PreparedStory? = null
    private var offeredStoryId: String? = null
    private var automaticStoryOfferId: String? = null
    private var offeredMusic: PreparedMusic? = null
    // RUN-05/07: after a story, one naturally completed track separates automatic stories.
    // A skipped/interrupted track or a late offer while IDLE cannot satisfy this boundary.
    private var musicRequiredBeforeAutomaticStory = restoredMusicRequiredBeforeAutomaticStory ||
        restoredMusicCheckpoint != null || restoredCheckpoint?.hasNext == false
    private var navigation: Pair<OperationToken, Navigation>? = null
    private val blockers = mutableSetOf<PlaybackBlocker>()
    private var output: Output? = null
    private var input: Input? = null
    private var currentTurnId: String? = null
    private val turns = mutableMapOf<String, Turn>()
    private val pendingPlayback = mutableMapOf<OperationToken, PendingPlayback>()
    private val timers = mutableMapOf<TimerKind, TimerToken>()
    private var generation: Generation? = null
    private var resumeRequest: OperationToken? = null
    private var resumeCandidate: StoryCheckpoint? = null
    private var resumeCandidates: List<StoryCheckpoint> = emptyList()
    private var resumeMusicCandidate: MusicCheckpoint? = null
    private var resumeAfterInteraction = false
    private var resumeResolved = false
    private var resumeSelection: StoryCheckpoint? = null
    private var resumeMusicSelection: MusicCheckpoint? = null
    private var failureNotice = false
    private var failureUnreachable = false
    private var commandNotice: String? = null
    private val emitted = mutableListOf<PlaybackEffect>()

    init { require(tripId.isNotBlank() && sessionId.isNotBlank()) }

    @Synchronized
    fun snapshot(): PlaybackSnapshot = PlaybackSnapshot(
        tripId, sessionId, mode, revision, epoch, playIntent, interactionIntent, blockers.toSet(),
        output?.phase ?: AdapterPhase.READY, input?.phase ?: AdapterPhase.READY,
        mode == PlaybackMode.LISTENING && input?.ready == true && input?.phase == AdapterPhase.ACTIVE && input?.finalSeen == false && input?.ended == false,
        currentTurnId, checkpoint?.ownedCopy(), pendingPlayback.size, closed, musicCheckpoint,
        musicRequiredBeforeAutomaticStory,
    )

    /** The runtime may reject a repeated button before revoking an SDK callback's authority. */
    @Synchronized
    internal fun isTalkDebounced(nowMs: Long): Boolean = lastTalkTime?.let { nowMs - it < 350 } == true

    /** nowMs is a monotonic elapsed time within this session, never a wall-clock timestamp. */
    @Synchronized
    fun dispatch(event: PlaybackEvent, nowMs: Long): PlaybackTransition {
        if (closed) return PlaybackTransition(snapshot(), emptyList(), "session_closed")
        if (closingSession && event !is PlaybackEvent.OutputStopped && event !is PlaybackEvent.InputStoppedConfirmed &&
            event !is PlaybackEvent.PlaybackCommitted && event !is PlaybackEvent.UtteranceCommitted && event !is PlaybackEvent.AnswerCommitted &&
            event !is PlaybackEvent.OutputNotRegistered && event !is PlaybackEvent.AnswerContentRejected) {
            return PlaybackTransition(snapshot(), emptyList(), "session_closing")
        }
        if (nowMs < 0 || nowMs < lastTime) return PlaybackTransition(snapshot(), emptyList(), "clock_regressed")
        lastTime = nowMs
        emitted.clear()
        val ignored = handle(event, nowMs)
        if (ignored == null) {
            pump(nowMs)
            finishClosingSession()
            revision++
        }
        return PlaybackTransition(snapshot(), emitted.toList(), ignored)
    }

    private fun handle(event: PlaybackEvent, now: Long): String? = when (event) {
        is PlaybackEvent.PlayStory -> {
            when {
                mode != PlaybackMode.IDLE && mode != PlaybackMode.PAUSED -> "play_requires_idle_or_paused"
                blockers.isNotEmpty() -> "blocked"
                output != null || input?.phase?.let { it != AdapterPhase.READY } == true || pendingPlayback.isNotEmpty() -> "playback_settling"
                else -> { checkpoint = StoryCheckpoint(event.story.ownedCopy(), 0); automaticStoryOfferId = null; musicCheckpoint = null; commandNotice = null; playIntent = true; beginResume(now); null }
            }
        }
        is PlaybackEvent.PlayMusic -> when {
            mode != PlaybackMode.IDLE && mode != PlaybackMode.PAUSED -> "play_requires_idle_or_paused"
            blockers.isNotEmpty() -> "blocked"
            output != null || input?.phase?.let { it != AdapterPhase.READY } == true || pendingPlayback.isNotEmpty() -> "playback_settling"
            else -> { musicCheckpoint = MusicCheckpoint(event.track, 0); checkpoint = null; automaticStoryOfferId = null; commandNotice = null; playIntent = true; beginResume(now); null }
        }
        is PlaybackEvent.OfferStory -> {
            offeredStory = event.story.ownedCopy()
            offeredStoryId = event.offerId
            if (maySelectAutomaticFromIdle()) selectFollowing(allowStory = true)
            null
        }
        is PlaybackEvent.ClearOfferedStory -> {
            if (offeredStory?.storyId != event.storyId || offeredStory?.versionId != event.versionId) "offer_not_found"
            else { offeredStory = null; offeredStoryId = null; null }
        }
        is PlaybackEvent.OfferMusic -> {
            offeredMusic = event.track
            if (maySelectAutomaticFromIdle()) selectFollowing(allowStory = true)
            null
        }
        is PlaybackEvent.ClearOfferedMusic -> {
            if (offeredMusic?.assetId != event.assetId) "offer_not_found"
            else { offeredMusic = null; null }
        }
        PlaybackEvent.Next -> navigate(Navigation.NEXT)
        PlaybackEvent.Previous -> navigate(Navigation.PREVIOUS)
        PlaybackEvent.Play -> when {
            mode != PlaybackMode.IDLE && mode != PlaybackMode.PAUSED -> "play_requires_idle_or_paused"
            blockers.isNotEmpty() -> "blocked"
            else -> {
                playIntent = true
                automaticStoryOfferId = null
                commandNotice = null
                if (checkpoint?.hasNext != true && musicCheckpoint == null) selectFollowing(allowStory = true)
                beginResume(now)
                null
            }
        }
        PlaybackEvent.Talk -> talk(now)
        PlaybackEvent.CancelInteraction -> cancelInteraction(now)
        PlaybackEvent.Stop -> { pause(manual = true, TurnOutcome.INTERRUPTED); null }
        PlaybackEvent.Suspend -> {
            blockers += PlaybackBlocker.SUSPENDED
            pause(manual = true, TurnOutcome.INTERRUPTED)
            closingSession = true
            null
        }
        is PlaybackEvent.Block -> if (!blockers.add(event.blocker)) "already_blocked" else {
            pause(manual = false, TurnOutcome.INTERRUPTED); null
        }
        is PlaybackEvent.Unblock -> if (!blockers.remove(event.blocker)) "not_blocked" else {
            if (blockers.isEmpty() && playIntent) beginResume(now)
            null
        }
        is PlaybackEvent.OutputDone -> outputDone(event.attempt, event.positionMs)
        is PlaybackEvent.OutputStarted -> {
            val active = output
            if (active?.token != event.attempt || active.phase != AdapterPhase.ACTIVE) "stale_output_start"
            else { if (active.purpose == Purpose.STORY) automaticStoryOfferId = null; null }
        }
        is PlaybackEvent.OutputStopped -> outputStopped(event)
        is PlaybackEvent.MusicPosition -> {
            val active = output
            if (active?.token != event.attempt || active.purpose != Purpose.MUSIC) "stale_music_position"
            else if (event.positionMs < 0) "invalid_music_position"
            else { musicCheckpoint = musicCheckpoint?.copy(positionMs = event.positionMs); null }
        }
        is PlaybackEvent.OutputFailed -> {
            if (output?.token != event.attempt) "stale_output" else if (output?.phase != AdapterPhase.ACTIVE) "output_already_stopping" else {
                blockers += PlaybackBlocker.SERVICE_UNAVAILABLE
                // A failed track must not restart from a late offer or a service recovery alone.
                pause(manual = output?.purpose == Purpose.MUSIC, TurnOutcome.FAILED)
                null
            }
        }
        is PlaybackEvent.OutputNotRegistered -> outputNotRegistered(event.attempt)
        is PlaybackEvent.PlaybackCommitted -> playbackCommitted(event)
        is PlaybackEvent.InputReady -> {
            val active = activeInput(event.input)
            when {
                active == null -> "stale_input"
                active.ready || active.started || active.finalSeen || active.ended -> "duplicate_input_ready"
                expired(TimerKind.INPUT_READY, now) -> { failInput(now); null }
                else -> { active.ready = true; cancelTimer(TimerKind.INPUT_READY); schedule(TimerKind.FIRST_SPEECH, now + firstSpeechMs); null }
            }
        }
        is PlaybackEvent.InputStarted -> {
            val active = activeInput(event.input)
            when {
                active == null -> "stale_input"
                active.started || active.finalSeen || active.ended -> "duplicate_input_started"
                expired(TimerKind.INPUT_READY, now) || expired(TimerKind.FIRST_SPEECH, now) -> { failInput(now); null }
                else -> {
                    active.ready = true; active.started = true
                    cancelTimer(TimerKind.INPUT_READY); cancelTimer(TimerKind.FIRST_SPEECH)
                    schedule(TimerKind.MAX_INPUT, now + 60_000); null
                }
            }
        }
        is PlaybackEvent.InputEnded -> {
            val active = activeInput(event.input)
            when {
                active == null -> "stale_input"
                active.ended -> "duplicate_input_ended"
                expired(TimerKind.INPUT_READY, now) || expired(TimerKind.FIRST_SPEECH, now) || expired(TimerKind.MAX_INPUT, now) -> { failInput(now); null }
                else -> {
                    active.ended = true
                    cancelTimer(TimerKind.INPUT_READY); cancelTimer(TimerKind.FIRST_SPEECH); cancelTimer(TimerKind.MAX_INPUT)
                    if (!active.finalSeen) schedule(TimerKind.FINAL_GRACE, now + 500)
                    null
                }
            }
        }
        is PlaybackEvent.InputFinal -> inputFinal(event, now)
        is PlaybackEvent.InputStoppedConfirmed -> {
            val active = input
            if (active?.token != event.input) "stale_input" else if (active.phase == AdapterPhase.READY) "duplicate_input_stop" else {
                active.phase = AdapterPhase.READY
                if (active.turnId != currentTurnId) input = null
                else if (!active.finalSeen && (expired(TimerKind.INPUT_READY, now) || expired(TimerKind.FIRST_SPEECH, now) ||
                    expired(TimerKind.MAX_INPUT, now) || expired(TimerKind.FINAL_GRACE, now))) failInput(now)
                else if (!active.finalSeen && !active.ended) {
                    active.ended = true
                    cancelInputTimers()
                    schedule(TimerKind.FINAL_GRACE, now + 500)
                }
                null
            }
        }
        is PlaybackEvent.InputFailed -> if (activeInput(event.input) == null) "stale_input" else { failInput(now); null }
        is PlaybackEvent.UtteranceCommitted -> utteranceCommitted(event, now)
        is PlaybackEvent.ExplicitCommandResolved -> explicitCommandResolved(event, now)
        is PlaybackEvent.AnswerReceived -> answerReceived(event, now)
        is PlaybackEvent.AnswerFailed -> answerFailed(event, now)
        is PlaybackEvent.AnswerCommitted -> answerCommitted(event)
        is PlaybackEvent.AnswerContentRejected -> answerContentRejected(event.commit)
        is PlaybackEvent.ResumePlanned -> {
            when {
                resumeRequest != event.request -> "stale_resume"
                expired(TimerKind.RESUME, now) -> { resolveResume(resumeCandidate, resumeMusicCandidate); null }
                event.checkpoint != null && event.musicCheckpoint != null -> "multiple_resume_targets"
                event.checkpoint != null && event.checkpoint !in resumeCandidates -> "candidate_not_offered"
                event.musicCheckpoint != null && event.musicCheckpoint != resumeMusicCandidate -> "candidate_not_offered"
                else -> { resolveResume(event.checkpoint, event.musicCheckpoint); null }
            }
        }
        is PlaybackEvent.TimerExpired -> timerExpired(event.timer, now)
    }

    private fun talk(now: Long): String? {
        if (blockers.isNotEmpty()) return "blocked"
        if (isTalkDebounced(now)) return "button_debounced"
        if (output?.phase == AdapterPhase.STOPPING || input?.phase == AdapterPhase.STOPPING) return "adapter_stopping"
        lastTalkTime = now
        if (mode in setOf(PlaybackMode.TALK_PENDING, PlaybackMode.PROMPT, PlaybackMode.LISTENING)) {
            invalidateRequests()
            interactionIntent = false
            closeTurn(TurnOutcome.CANCELLED)
            stopInput()
            if (output?.purpose == Purpose.STORY && output?.phase == AdapterPhase.ACTIVE) mode = PlaybackMode.STORY
            else { stopOutput(); beginResume(now, afterInteraction = true) }
            return null
        }
        invalidateRequests()
        closeTurn(TurnOutcome.INTERRUPTED)
        val turn = Turn(nextId())
        if (input?.phase == AdapterPhase.READY) input = null
        turns[turn.id] = turn
        currentTurnId = turn.id
        interactionIntent = true
        failureNotice = false
        commandNotice = null
        mode = if (output?.phase == AdapterPhase.ACTIVE && output?.purpose in setOf(Purpose.STORY, Purpose.ANSWER)) {
            PlaybackMode.TALK_PENDING
        } else PlaybackMode.PROMPT
        if (output?.purpose in setOf(Purpose.MUSIC, Purpose.COMMAND_NOTICE)) stopOutput()
        return null
    }

    private fun cancelInteraction(now: Long): String? {
        if (!interactionIntent && currentTurnId == null) return "no_active_interaction"
        invalidateRequests()
        interactionIntent = false
        failureNotice = false
        closeTurn(TurnOutcome.CANCELLED)
        stopInput()
        if (output?.purpose == Purpose.STORY && output?.phase == AdapterPhase.ACTIVE) mode = PlaybackMode.STORY
        else { stopOutput(); beginResume(now, afterInteraction = true) }
        return null
    }

    private fun outputDone(token: OperationToken, positionMs: Long?): String? {
        val active = output ?: return "stale_output"
        if (active.token != token) return "stale_output"
        if (active.phase != AdapterPhase.ACTIVE) return "output_stopping"
        if (active.purpose == Purpose.MUSIC && (positionMs == null || positionMs < 0)) return "music_position_required"
        output = null
        val next = if (active.purpose == Purpose.STORY) checkpoint?.let { it.copy(paragraphIndex = it.paragraphIndex + 1) } else checkpoint
        val nextMusic = if (active.purpose == Purpose.MUSIC) null else musicCheckpoint
        pendingPlayback[token] = PendingPlayback(active, AttemptOutcome.COMPLETED, next, nextMusic)
        emitted += PlaybackEffect.CommitPlayback(token, active.reference, AttemptOutcome.COMPLETED, next, nextMusic, positionMs)
        return null
    }

    private fun outputStopped(event: PlaybackEvent.OutputStopped): String? {
        val active = output ?: return "stale_output"
        if (active.token != event.attempt) return "stale_output"
        if (active.purpose == Purpose.MUSIC && (event.positionMs == null || event.positionMs < 0)) return "music_position_required"
        val unexpected = active.phase == AdapterPhase.ACTIVE
        if (active.purpose == Purpose.MUSIC) {
            musicCheckpoint = musicCheckpoint?.copy(positionMs = event.positionMs!!)
            pendingPlayback[active.token] = PendingPlayback(active, AttemptOutcome.INTERRUPTED, checkpoint, musicCheckpoint)
            emitted += PlaybackEffect.CommitPlayback(active.token, active.reference, AttemptOutcome.INTERRUPTED,
                checkpoint, musicCheckpoint, event.positionMs)
        } else if (unexpected) {
            pendingPlayback[active.token] = PendingPlayback(active, AttemptOutcome.INTERRUPTED, checkpoint, musicCheckpoint)
            emitted += PlaybackEffect.CommitPlayback(active.token, active.reference, AttemptOutcome.INTERRUPTED, checkpoint, musicCheckpoint)
        }
        output = null
        if (unexpected) pause(manual = false, TurnOutcome.INTERRUPTED)
        return null
    }

    private fun playbackCommitted(event: PlaybackEvent.PlaybackCommitted): String? {
        val committed = pendingPlayback.remove(event.attempt) ?: return "stale_playback_commit"
        if (!event.success) {
            blockers += PlaybackBlocker.STORAGE_UNAVAILABLE
            pause(manual = false, TurnOutcome.FAILED)
            flushClosingTurns()
            return null
        }
        if (committed.outcome == AttemptOutcome.COMPLETED) {
            when (committed.output.purpose) {
                Purpose.STORY -> {
                    checkpoint = committed.nextCheckpoint
                    if (checkpoint?.hasNext == false) musicRequiredBeforeAutomaticStory = true
                    if (mode == PlaybackMode.TALK_PENDING) mode = PlaybackMode.PROMPT
                }
                Purpose.MUSIC -> {
                    musicCheckpoint = committed.nextMusicCheckpoint
                    musicRequiredBeforeAutomaticStory = false
                    if (mode == PlaybackMode.MUSIC && playIntent) selectFollowing(allowStory = true)
                }
                Purpose.PROMPT -> if (mode == PlaybackMode.PROMPT && interactionIntent &&
                    committed.output.ownerTurnId == currentTurnId && committed.output.token.cancelEpoch == epoch) mode = PlaybackMode.LISTENING
                Purpose.ANSWER -> {
                    val reference = committed.output.reference as OutputReference.AnswerParagraph
                    turns[reference.turnId]?.let { it.completedKeys += reference.paragraphKey; it.answerIndex++ }
                    if (mode == PlaybackMode.TALK_PENDING) mode = PlaybackMode.PROMPT
                }
                Purpose.FAILURE -> failureNotice = false
                Purpose.COMMAND_NOTICE -> Unit // The notice never resumes background playback.
            }
        }
        navigation?.takeIf { it.first == event.attempt }?.let { (_, action) ->
            navigation = null
            if (action == Navigation.PREVIOUS) {
                if (committed.output.purpose == Purpose.STORY) checkpoint = checkpoint?.copy(paragraphIndex = 0)
                else musicCheckpoint = musicCheckpoint?.copy(positionMs = 0)
                if (mode != PlaybackMode.PAUSED) mode = PlaybackMode.RESUMING
            } else {
                val musicSkipped = committed.output.purpose == Purpose.MUSIC
                if (musicSkipped) musicCheckpoint = null else checkpoint = null
                if (mode != PlaybackMode.PAUSED) selectFollowing(allowStory = !musicSkipped)
            }
            // The interruption transaction records where the old attempt actually stopped;
            // persist the user's separate skip/restart selection after that commit succeeds.
            emitted += PlaybackEffect.SaveCheckpoint(checkpoint, playIntent, musicCheckpoint)
        }
        flushClosingTurns()
        return null
    }

    private fun outputNotRegistered(attempt: OperationToken): String? {
        val active = output?.takeIf { it.token == attempt }
        val pending = pendingPlayback[attempt]
        if (active == null && pending == null) return "stale_unregistered_output"
        if (pending?.outcome == AttemptOutcome.COMPLETED) return "output_already_completed"
        val rejected = active ?: pending!!.output
        pendingPlayback.remove(attempt)
        if (active != null) output = null
        // An unregistered story pointer may be malformed; never save it as an authorized checkpoint.
        if (rejected.purpose == Purpose.STORY) { checkpoint = null; automaticStoryOfferId = null }
        if (rejected.purpose == Purpose.MUSIC) musicCheckpoint = null
        if (navigation?.first == attempt) navigation = null
        pause(manual = rejected.purpose == Purpose.MUSIC, TurnOutcome.FAILED)
        flushClosingTurns()
        return null
    }

    private fun inputFinal(event: PlaybackEvent.InputFinal, now: Long): String? {
        val active = input ?: return "stale_input"
        val turn = currentTurn() ?: return "stale_input"
        if (active.token != event.input || active.turnId != turn.id || active.phase == AdapterPhase.STOPPING) return "stale_input"
        if (active.finalSeen) return "duplicate_final"
        if (!active.ready && !active.started && !active.ended) return "input_not_ready"
        if (expired(TimerKind.FINAL_GRACE, now) || expired(TimerKind.INPUT_READY, now) ||
            expired(TimerKind.FIRST_SPEECH, now) || expired(TimerKind.MAX_INPUT, now)) {
            failInput(now)
            return null
        }
        active.finalSeen = true
        cancelInputTimers()
        val token = token()
        turn.finalCommit = token
        emitted += PlaybackEffect.PersistUtterance(token, turn.id, event.transientPayload)
        return null
    }

    private fun utteranceCommitted(event: PlaybackEvent.UtteranceCommitted, now: Long): String? {
        val turn = turns.values.firstOrNull { it.finalCommit == event.commit } ?: return "stale_utterance_commit"
        turn.finalCommit = null
        if (event.storedPayload == null) {
            blockers += PlaybackBlocker.STORAGE_UNAVAILABLE
            pause(manual = false, TurnOutcome.FAILED)
        } else {
            turn.utterance = event.storedPayload
            if (turn.closingOutcome == null) { stopInput(); maybeGenerate(now) }
        }
        flushClosingTurns()
        return null
    }

    private fun answerReceived(event: PlaybackEvent.AnswerReceived, now: Long): String? {
        val request = generation ?: return "stale_generation"
        if (request.token != event.request) return "stale_generation"
        if (now >= request.deadline) { failGeneration(); return null }
        val turn = currentTurn() ?: return "stale_turn"
        generation = null
        cancelTimer(TimerKind.GENERATION); cancelTimer(TimerKind.GENERATION_RETRY)
        val commit = token()
        turn.answerCommit = commit
        emitted += PlaybackEffect.PersistAnswer(commit, turn.id, event.answer.copy(paragraphKeys = event.answer.paragraphKeys.toList()))
        return null
    }

    private fun explicitCommandResolved(event: PlaybackEvent.ExplicitCommandResolved, now: Long): String? {
        val request = generation ?: return "stale_generation"
        if (request.token != event.request || request.token.cancelEpoch != epoch || request.token.sessionId != sessionId) return "stale_generation"
        if (now >= request.deadline) { failGeneration(); return null }
        val turn = currentTurn() ?: return "stale_turn"
        val activeInput = input
        if (mode != PlaybackMode.GENERATING || !interactionIntent || request.turnId != turn.id || turn.closingOutcome != null) return "command_requires_current_turn"
        if (turn.utterance == null || turn.finalCommit != null || turn.answerCommit != null) return "command_requires_committed_utterance"
        if (activeInput?.turnId != turn.id || activeInput.phase != AdapterPhase.READY || !activeInput.finalSeen) return "command_requires_input_ready"
        if (output != null || pendingPlayback.isNotEmpty()) return "playback_settling"
        if (blockers.isNotEmpty()) return "blocked"

        invalidateRequests()
        interactionIntent = false
        input = null // Only the confirmed physical-stop path reaches this command boundary.
        failureNotice = false
        commandNotice = null
        automaticStoryOfferId = null
        closeTurn(TurnOutcome.CLOSED)
        playIntent = event.command != ExplicitPlaybackCommand.STOP
        when (event.command) {
            ExplicitPlaybackCommand.STOP -> mode = PlaybackMode.PAUSED
            ExplicitPlaybackCommand.CONTINUE -> {
                if (checkpoint?.hasNext != true && musicCheckpoint == null) selectFollowing(allowStory = true)
            }
            ExplicitPlaybackCommand.NEXT -> {
                val musicSkipped = musicCheckpoint != null
                if (musicSkipped) musicCheckpoint = null
                else {
                    checkpoint?.story?.storyId?.let(::excludeAutomaticStory)
                    checkpoint = null
                }
                selectFollowing(allowStory = !musicSkipped)
            }
            ExplicitPlaybackCommand.REPLAY -> {
                when {
                    musicCheckpoint != null -> musicCheckpoint = musicCheckpoint!!.copy(positionMs = 0)
                    checkpoint != null -> checkpoint = checkpoint!!.copy(paragraphIndex = 0)
                    else -> commandUnavailable("command-replay-unavailable")
                }
            }
            ExplicitPlaybackCommand.MUSIC -> {
                val music = musicCheckpoint ?: offeredMusic?.let { track ->
                    offeredMusic = null
                    MusicCheckpoint(track, 0)
                }
                if (music == null) commandUnavailable("command-music-unavailable")
                else { musicCheckpoint = music; checkpoint = null }
            }
        }
        // The runtime persists the closed user-only turn and selection before any new SDK start.
        emitted += PlaybackEffect.SaveCheckpoint(checkpoint, playIntent, musicCheckpoint)
        if (playIntent) beginResume(now, afterInteraction = false)
        return null
    }

    private fun commandUnavailable(promptId: String) {
        playIntent = false
        mode = PlaybackMode.PAUSED
        commandNotice = promptId
    }

    private fun answerFailed(event: PlaybackEvent.AnswerFailed, now: Long): String? {
        val request = generation ?: return "stale_generation"
        if (request.token != event.request) return "stale_generation"
        if (request.waitingRetry) return "retry_already_scheduled"
        val remaining = request.deadline - now
        if (!event.retryable || request.attempt >= 2 || event.retryAfterMs < 0 || event.retryAfterMs >= remaining) {
            failGeneration(event.unreachable || event.retryable)
        } else if (event.retryAfterMs == 0L) retryGeneration()
        else {
            request.waitingRetry = true
            schedule(TimerKind.GENERATION_RETRY, now + event.retryAfterMs)
        }
        return null
    }

    private fun answerCommitted(event: PlaybackEvent.AnswerCommitted): String? {
        val turn = turns.values.firstOrNull { it.answerCommit == event.commit } ?: return "stale_answer_commit"
        turn.answerCommit = null
        if (event.storedAnswer == null) {
            blockers += PlaybackBlocker.STORAGE_UNAVAILABLE
            pause(manual = false, TurnOutcome.FAILED)
        } else {
            turn.answer = event.storedAnswer.copy(paragraphKeys = event.storedAnswer.paragraphKeys.toList())
            if (turn.closingOutcome == null) mode = PlaybackMode.ANSWERING
        }
        flushClosingTurns()
        return null
    }

    private fun answerContentRejected(commit: OperationToken): String? {
        val turn = turns.values.firstOrNull { it.answerCommit == commit } ?: return "stale_answer_commit"
        turn.answerCommit = null
        if (turn.closingOutcome == null) failGeneration()
        flushClosingTurns()
        return null
    }

    private fun timerExpired(timer: TimerToken, now: Long): String? {
        if (timers[timer.kind] != timer) return "stale_timer"
        if (now < timer.dueAtMs) return "timer_not_due"
        timers.remove(timer.kind)
        when (timer.kind) {
            TimerKind.INPUT_READY, TimerKind.FIRST_SPEECH, TimerKind.MAX_INPUT, TimerKind.FINAL_GRACE -> failInput(now)
            TimerKind.GENERATION -> failGeneration()
            TimerKind.GENERATION_RETRY -> if (generation?.let { now < it.deadline } == true) retryGeneration() else failGeneration()
            TimerKind.RESUME -> resolveResume(resumeCandidate, resumeMusicCandidate)
        }
        return null
    }

    private fun pump(now: Long) {
        if (closed || blockers.isNotEmpty()) return
        maybeGenerate(now)
        if (output != null || input?.phase?.let { it != AdapterPhase.READY } == true || pendingPlayback.isNotEmpty()) return
        when (mode) {
            PlaybackMode.STORY -> if (playIntent) {
                val target = checkpoint
                if (target?.hasNext == true) startStory(target)
                else { selectFollowing(allowStory = true); startSelected() }
            }
            PlaybackMode.MUSIC -> if (playIntent) musicCheckpoint?.let(::startMusic) ?: run { mode = PlaybackMode.IDLE }
            PlaybackMode.PROMPT -> if (interactionIntent && currentTurn() != null) startOutput(Purpose.PROMPT, OutputReference.FixedPrompt("talk-prompt"))
            PlaybackMode.LISTENING -> if (interactionIntent && input == null) {
                val turn = currentTurn() ?: return
                val active = Input(token(), turn.id)
                input = active
                emitted += PlaybackEffect.RequestInput(active.token, turn.id)
                schedule(TimerKind.INPUT_READY, now + 3_000)
            }
            PlaybackMode.GENERATING -> if (failureNotice) startOutput(Purpose.FAILURE, OutputReference.FixedPrompt(if (failureUnreachable) "generation-failed" else "generation-misheard"))
                else if (generation == null && currentTurn()?.answerCommit == null) beginResume(now, afterInteraction = true)
            PlaybackMode.ANSWERING -> {
                val turn = currentTurn() ?: return
                val answer = turn.answer ?: return
                if (turn.answerIndex < answer.paragraphKeys.size) {
                    startOutput(Purpose.ANSWER, OutputReference.AnswerParagraph(turn.id, answer.payload, answer.paragraphKeys[turn.answerIndex]))
                } else { closeTurn(TurnOutcome.CLOSED); interactionIntent = false; input = null; beginResume(now, afterInteraction = true) }
            }
            PlaybackMode.RESUMING -> if (resumeResolved) {
                val selected = resumeSelection
                val selectedMusic = resumeMusicSelection
                resumeResolved = false
                if (playIntent && selectedMusic != null) { musicCheckpoint = selectedMusic; mode = PlaybackMode.MUSIC; startMusic(selectedMusic) }
                else if (playIntent && selected?.hasNext == true) { checkpoint = selected; mode = PlaybackMode.STORY; startStory(selected) }
                else if (playIntent && resumeCandidates.isEmpty() && resumeMusicCandidate == null) {
                    // A natural completion can commit during focus loss or an interaction. With
                    // no checkpoint offered to the planner this is fallback, not a model's wait.
                    selectFollowing(allowStory = true)
                    startSelected()
                }
                else mode = if (playIntent) PlaybackMode.IDLE else PlaybackMode.PAUSED
            } else if (resumeRequest == null) requestResume(now)
            PlaybackMode.PAUSED -> commandNotice?.let { promptId ->
                commandNotice = null
                startOutput(Purpose.COMMAND_NOTICE, OutputReference.FixedPrompt(promptId))
            }
            else -> Unit
        }
    }

    private fun maybeGenerate(now: Long) {
        val turn = currentTurn() ?: return
        val active = input ?: return
        if (mode != PlaybackMode.LISTENING || !interactionIntent || turn.utterance == null || active.phase != AdapterPhase.READY) return
        if (pendingPlayback.isNotEmpty() || output != null || blockers.isNotEmpty()) return
        mode = PlaybackMode.GENERATING
        val request = Generation(token(), turn.id, now + 15_000)
        generation = request
        emitted += PlaybackEffect.GenerateAnswer(request.token, turn.id, turn.utterance!!, 1)
        schedule(TimerKind.GENERATION, request.deadline)
    }

    private fun retryGeneration() {
        val request = generation ?: return
        val turn = currentTurn() ?: return
        request.attempt++
        request.waitingRetry = false
        emitted += PlaybackEffect.GenerateAnswer(request.token, turn.id, turn.utterance!!, request.attempt)
    }

    /** A deadline/timer expiry counts as an unreachable server; an explicit non-connection failure does not. */
    private fun failGeneration(unreachable: Boolean = true) {
        failureUnreachable = unreachable
        generation?.let { emitted += PlaybackEffect.CancelRequest(it.token) }
        generation = null
        cancelTimer(TimerKind.GENERATION); cancelTimer(TimerKind.GENERATION_RETRY)
        closeTurn(TurnOutcome.FAILED)
        interactionIntent = false
        input = null // Generation is entered only after confirmed input stop.
        failureNotice = true
        mode = PlaybackMode.GENERATING
    }

    private fun failInput(now: Long) {
        cancelInputTimers()
        stopInput()
        interactionIntent = false
        closeTurn(TurnOutcome.FAILED)
        beginResume(now, afterInteraction = true)
    }

    private fun beginResume(now: Long, afterInteraction: Boolean = false) {
        if (!playIntent || blockers.isNotEmpty()) { mode = PlaybackMode.PAUSED; return }
        mode = PlaybackMode.RESUMING
        resumeAfterInteraction = afterInteraction
        resumeRequest?.let { emitted += PlaybackEffect.CancelRequest(it) }
        cancelTimer(TimerKind.RESUME)
        resumeMusicCandidate = musicCheckpoint
        resumeCandidate = checkpoint?.takeIf { it.hasNext && resumeMusicCandidate == null }
        resumeCandidates = emptyList()
        resumeSelection = null
        resumeMusicSelection = null
        resumeResolved = false
        resumeRequest = null
        if (output == null && (input == null || input?.phase == AdapterPhase.READY) && pendingPlayback.isEmpty()) requestResume(now)
    }

    private fun requestResume(now: Long) {
        resumeMusicCandidate = musicCheckpoint
        resumeCandidate = checkpoint?.takeIf { it.hasNext && resumeMusicCandidate == null }
        resumeCandidates = resumeCandidate?.let { initial ->
            if (resumeAfterInteraction) (initial.paragraphIndex until initial.story.paragraphIds.size).take(20)
                .map { index -> initial.copy(paragraphIndex = index) }
            else listOf(initial)
        } ?: emptyList()
        val request = token()
        resumeRequest = request
        emitted += PlaybackEffect.PlanResume(request, resumeCandidates.map { it.ownedCopy() }, listOfNotNull(resumeMusicCandidate), resumeAfterInteraction)
        schedule(TimerKind.RESUME, now + 3_000)
    }

    private fun resolveResume(target: StoryCheckpoint?, musicTarget: MusicCheckpoint? = null) {
        resumeRequest?.let { emitted += PlaybackEffect.CancelRequest(it) }
        resumeRequest = null
        cancelTimer(TimerKind.RESUME)
        resumeSelection = target
        resumeMusicSelection = musicTarget
        resumeResolved = true
    }

    private fun pause(manual: Boolean, outcome: TurnOutcome) {
        navigation = null
        if (manual) playIntent = false
        interactionIntent = false
        failureNotice = false
        commandNotice = null
        invalidateRequests()
        stopOutput()
        stopInput()
        closeTurn(outcome)
        mode = PlaybackMode.PAUSED
        emitted += PlaybackEffect.SaveCheckpoint(checkpoint, playIntent, musicCheckpoint)
    }

    private fun invalidateRequests() {
        epoch++
        generation?.let { emitted += PlaybackEffect.CancelRequest(it.token) }
        resumeRequest?.let { emitted += PlaybackEffect.CancelRequest(it) }
        generation = null
        resumeRequest = null
        resumeCandidates = emptyList()
        resumeAfterInteraction = false
        resumeResolved = false
        resumeSelection = null
        resumeMusicSelection = null
        cancelTimer(TimerKind.GENERATION); cancelTimer(TimerKind.GENERATION_RETRY); cancelTimer(TimerKind.RESUME)
    }

    private fun stopOutput() {
        val active = output ?: return
        if (active.phase != AdapterPhase.ACTIVE) return
        active.phase = AdapterPhase.STOPPING
        emitted += PlaybackEffect.StopOutput(active.token)
        if (active.purpose != Purpose.MUSIC) {
            pendingPlayback[active.token] = PendingPlayback(active, AttemptOutcome.INTERRUPTED, checkpoint, musicCheckpoint)
            emitted += PlaybackEffect.CommitPlayback(active.token, active.reference, AttemptOutcome.INTERRUPTED, checkpoint, musicCheckpoint)
        }
    }

    private fun stopInput() {
        cancelInputTimers()
        val active = input ?: return
        if (active.phase == AdapterPhase.ACTIVE) {
            active.phase = AdapterPhase.STOPPING
            emitted += PlaybackEffect.StopInput(active.token)
        }
    }

    private fun closeTurn(outcome: TurnOutcome) {
        val turn = currentTurn() ?: return
        turn.closingOutcome = outcome
        currentTurnId = null
        flushClosingTurns()
    }

    private fun flushClosingTurns() {
        val completed = turns.values.filter { turn ->
            turn.closingOutcome != null &&
                turn.finalCommit == null && turn.answerCommit == null &&
                !(output?.phase == AdapterPhase.ACTIVE && (output?.reference as? OutputReference.AnswerParagraph)?.turnId == turn.id) &&
                pendingPlayback.values.none { (it.output.reference as? OutputReference.AnswerParagraph)?.turnId == turn.id }
        }
        completed.forEach { turn ->
            emitted += PlaybackEffect.FreezeTurn(turn.id, turn.closingOutcome!!, turn.utterance != null, turn.completedKeys.toList())
            turns.remove(turn.id)
        }
    }

    private fun startStory(target: StoryCheckpoint) = startOutput(
        Purpose.STORY,
        OutputReference.StoryParagraph(target.story.storyId, target.story.versionId, target.story.paragraphIds[target.paragraphIndex]),
        automaticStoryOfferId,
    )

    private fun startMusic(target: MusicCheckpoint) {
        musicRequiredBeforeAutomaticStory = true
        startOutput(Purpose.MUSIC, OutputReference.MusicTrack(target.track.assetId, target.positionMs))
    }

    private fun navigate(action: Navigation): String? {
        if (mode != PlaybackMode.STORY && mode != PlaybackMode.MUSIC) return "navigation_requires_content"
        if (blockers.isNotEmpty()) return "blocked"
        val active = output ?: return "playback_settling"
        if (active.phase != AdapterPhase.ACTIVE) return "adapter_stopping"
        if (action == Navigation.NEXT && active.purpose == Purpose.STORY) {
            (active.reference as OutputReference.StoryParagraph).storyId.let(::excludeAutomaticStory)
        }
        invalidateRequests()
        automaticStoryOfferId = null // Explicit navigation is a user-directed selection/replay.
        navigation = active.token to action
        interactionIntent = false
        stopOutput()
        mode = PlaybackMode.RESUMING
        return null
    }

    private fun excludeAutomaticStory(storyId: String) {
        if (offeredStory?.storyId == storyId) { offeredStory = null; offeredStoryId = null }
        emitted += PlaybackEffect.ExcludeAutomaticStory(storyId)
    }

    private fun selectFollowing(allowStory: Boolean) {
        val story = offeredStory.takeIf { allowStory && !musicRequiredBeforeAutomaticStory }
        val music = offeredMusic
        when {
            story != null -> {
                offeredStory = null; automaticStoryOfferId = offeredStoryId; offeredStoryId = null
                checkpoint = StoryCheckpoint(story, 0); musicCheckpoint = null; mode = PlaybackMode.STORY
            }
            music != null -> { offeredMusic = null; musicCheckpoint = MusicCheckpoint(music, 0); mode = PlaybackMode.MUSIC }
            else -> mode = PlaybackMode.IDLE
        }
    }

    /** A null selection among real resume candidates means wait; a new automatic offer cannot override it. */
    private fun maySelectAutomaticFromIdle(): Boolean = mode == PlaybackMode.IDLE && playIntent && blockers.isEmpty() &&
        checkpoint?.hasNext != true && musicCheckpoint == null

    private fun startSelected() {
        if (mode == PlaybackMode.STORY) checkpoint?.takeIf { it.hasNext }?.let(::startStory)
        else if (mode == PlaybackMode.MUSIC) musicCheckpoint?.let(::startMusic)
    }

    private fun startOutput(purpose: Purpose, reference: OutputReference, automaticOfferId: String? = null) {
        check(output == null && (input == null || input?.phase == AdapterPhase.READY) && pendingPlayback.isEmpty())
        val active = Output(token(), reference, purpose, AdapterPhase.ACTIVE, currentTurnId)
        output = active
        emitted += PlaybackEffect.StartOutput(active.token, reference, automaticOfferId)
    }

    private fun activeInput(token: OperationToken): Input? = input?.takeIf {
        it.token == token && it.turnId == currentTurnId && it.phase == AdapterPhase.ACTIVE
    }
    private fun currentTurn(): Turn? = currentTurnId?.let(turns::get)
    private fun finishClosingSession() {
        if (closingSession && output == null && (input == null || input?.phase == AdapterPhase.READY) && pendingPlayback.isEmpty() && turns.isEmpty()) {
            closed = true
            emitted += PlaybackEffect.CloseSession
        }
    }
    private fun token() = OperationToken(sessionId, epoch, nextId())
    private fun expired(kind: TimerKind, now: Long) = timers[kind]?.let { now >= it.dueAtMs } == true
    private fun schedule(kind: TimerKind, deadline: Long) {
        cancelTimer(kind)
        val timer = TimerToken(token(), kind, deadline)
        timers[kind] = timer
        emitted += PlaybackEffect.ScheduleTimer(timer)
    }
    private fun cancelTimer(kind: TimerKind) { timers.remove(kind)?.let { emitted += PlaybackEffect.CancelTimer(it) } }
    private fun cancelInputTimers() {
        listOf(TimerKind.INPUT_READY, TimerKind.FIRST_SPEECH, TimerKind.MAX_INPUT, TimerKind.FINAL_GRACE).forEach(::cancelTimer)
    }
    private fun PreparedStory.ownedCopy() = copy(paragraphIds = paragraphIds.toList())
    private fun StoryCheckpoint.ownedCopy() = copy(story = story.ownedCopy())
}
