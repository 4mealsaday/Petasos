package com.petasos.app.playback

/** Pure runtime contract. Handles identify payloads held by the host; they never contain text. */
data class PayloadHandle(val id: String) {
    init { require(id.isNotBlank()) }
    override fun toString(): String = "PayloadHandle([redacted])"
}

data class PreparedStory(val storyId: String, val versionId: String, val paragraphIds: List<String>) {
    init {
        require(storyId.isNotBlank() && versionId.isNotBlank())
        require(paragraphIds.isNotEmpty() && paragraphIds.all { it.isNotBlank() })
        require(paragraphIds.distinct().size == paragraphIds.size)
    }
}

data class StoryCheckpoint(val story: PreparedStory, val paragraphIndex: Int) {
    init { require(paragraphIndex in 0..story.paragraphIds.size) }
    val hasNext: Boolean get() = paragraphIndex < story.paragraphIds.size
}

data class PreparedMusic(val assetId: String, val durationMs: Long? = null) {
    init { require(assetId.isNotBlank() && (durationMs == null || durationMs > 0)) }
}

data class MusicCheckpoint(val track: PreparedMusic, val positionMs: Long) {
    init { require(positionMs >= 0) }
}

/** Host must validate schema, sources and API metadata before constructing this value. */
data class ValidatedAnswer(val payload: PayloadHandle, val paragraphKeys: List<String>) {
    init {
        require(paragraphKeys.size in 1..8 && paragraphKeys.all { it.isNotBlank() })
        require(paragraphKeys.distinct().size == paragraphKeys.size)
    }
}

data class OperationToken(val sessionId: String, val cancelEpoch: Long, val id: String)
enum class PlaybackMode { IDLE, STORY, MUSIC, TALK_PENDING, PROMPT, LISTENING, GENERATING, ANSWERING, RESUMING, PAUSED }
enum class AdapterPhase { READY, ACTIVE, STOPPING, UNAVAILABLE }
enum class PlaybackBlocker { FOCUS_TRANSIENT, FOCUS_LOST, SERVICE_UNAVAILABLE, STORAGE_UNAVAILABLE, POWER_AUDIO, SUSPENDED }
enum class TimerKind { INPUT_READY, FIRST_SPEECH, MAX_INPUT, FINAL_GRACE, GENERATION, GENERATION_RETRY, RESUME }
data class TimerToken(val operation: OperationToken, val kind: TimerKind, val dueAtMs: Long)
enum class AttemptOutcome { COMPLETED, INTERRUPTED }
enum class TurnOutcome { CLOSED, CANCELLED, INTERRUPTED, FAILED }
/** Exact local commands resolved from a durably committed final utterance, never a model action. */
enum class ExplicitPlaybackCommand { STOP, CONTINUE, NEXT, REPLAY, MUSIC }

sealed interface OutputReference {
    data class StoryParagraph(val storyId: String, val versionId: String, val paragraphId: String) : OutputReference
    data class MusicTrack(val assetId: String, val positionMs: Long = 0) : OutputReference {
        init { require(assetId.isNotBlank() && positionMs >= 0) }
    }
    data class FixedPrompt(val assetId: String) : OutputReference
    data class AnswerParagraph(val turnId: String, val payload: PayloadHandle, val paragraphKey: String) : OutputReference
}

sealed interface PlaybackEvent {
    data class PlayStory(val story: PreparedStory) : PlaybackEvent
    data class PlayMusic(val track: PreparedMusic) : PlaybackEvent
    /** Already eligible automatic offers are deferred until an eligible content boundary. */
    data class OfferStory(val story: PreparedStory, val offerId: String = "${story.storyId}:${story.versionId}") : PlaybackEvent {
        init { require(offerId.isNotBlank()) }
    }
    data class ClearOfferedStory(val storyId: String, val versionId: String) : PlaybackEvent
    data class OfferMusic(val track: PreparedMusic) : PlaybackEvent
    data class ClearOfferedMusic(val assetId: String) : PlaybackEvent
    data object Next : PlaybackEvent
    data object Previous : PlaybackEvent
    data object Play : PlaybackEvent
    data object Talk : PlaybackEvent
    /** Cancel only the active interaction; preserve background listening intent. */
    data object CancelInteraction : PlaybackEvent
    data object Stop : PlaybackEvent
    data object Suspend : PlaybackEvent
    data class Block(val blocker: PlaybackBlocker) : PlaybackEvent
    data class Unblock(val blocker: PlaybackBlocker) : PlaybackEvent
    data class OutputDone(val attempt: OperationToken, val positionMs: Long? = null) : PlaybackEvent
    data class OutputStarted(val attempt: OperationToken) : PlaybackEvent
    /** Confirmed adapter quiescence, not merely a stop() invocation or a delay. */
    data class OutputStopped(val attempt: OperationToken, val positionMs: Long? = null) : PlaybackEvent
    data class MusicPosition(val attempt: OperationToken, val positionMs: Long) : PlaybackEvent
    data class OutputFailed(val attempt: OperationToken) : PlaybackEvent
    /** Registration was rejected: no durable attempt and no SDK invocation exist. Not a commit ACK. */
    data class OutputNotRegistered(val attempt: OperationToken) : PlaybackEvent
    data class PlaybackCommitted(val attempt: OperationToken, val success: Boolean) : PlaybackEvent
    data class InputReady(val input: OperationToken) : PlaybackEvent
    data class InputStarted(val input: OperationToken) : PlaybackEvent
    /** Speech-segment end only. This does NOT confirm the microphone has stopped. */
    data class InputEnded(val input: OperationToken) : PlaybackEvent
    data class InputFinal(val input: OperationToken, val transientPayload: PayloadHandle) : PlaybackEvent
    /** Only a host with independent physical-stop evidence may send this event. */
    data class InputStoppedConfirmed(val input: OperationToken) : PlaybackEvent
    data class InputFailed(val input: OperationToken) : PlaybackEvent
    data class UtteranceCommitted(val commit: OperationToken, val storedPayload: PayloadHandle?) : PlaybackEvent
    data class ExplicitCommandResolved(val request: OperationToken, val command: ExplicitPlaybackCommand) : PlaybackEvent
    data class AnswerReceived(val request: OperationToken, val answer: ValidatedAnswer) : PlaybackEvent
    data class AnswerFailed(val request: OperationToken, val retryable: Boolean, val retryAfterMs: Long = 0) : PlaybackEvent
    data class AnswerCommitted(val commit: OperationToken, val storedAnswer: ValidatedAnswer?) : PlaybackEvent
    /** A content permission check rejected this write; the database remains available. */
    data class AnswerContentRejected(val commit: OperationToken) : PlaybackEvent
    data class ResumePlanned(val request: OperationToken, val checkpoint: StoryCheckpoint?, val musicCheckpoint: MusicCheckpoint? = null) : PlaybackEvent
    data class TimerExpired(val timer: TimerToken) : PlaybackEvent
}

sealed interface PlaybackEffect {
    data class StartOutput(val attempt: OperationToken, val reference: OutputReference, val automaticOfferId: String? = null) : PlaybackEffect
    data class StopOutput(val attempt: OperationToken) : PlaybackEffect
    data class RequestInput(val input: OperationToken, val turnId: String) : PlaybackEffect
    data class StopInput(val input: OperationToken) : PlaybackEffect
    data class CommitPlayback(
        val attempt: OperationToken,
        val reference: OutputReference,
        val outcome: AttemptOutcome,
        val checkpoint: StoryCheckpoint?,
        val musicCheckpoint: MusicCheckpoint? = null,
        val outputPositionMs: Long? = null,
    ) : PlaybackEffect
    data class PersistUtterance(val commit: OperationToken, val turnId: String, val transientPayload: PayloadHandle) : PlaybackEffect
    data class GenerateAnswer(val request: OperationToken, val turnId: String, val storedPayload: PayloadHandle, val attemptNumber: Int) : PlaybackEffect
    data class CancelRequest(val request: OperationToken) : PlaybackEffect
    /** Session-only automatic exclusion; this does not fabricate completed listening history. */
    data class ExcludeAutomaticStory(val storyId: String) : PlaybackEffect
    data class PersistAnswer(val commit: OperationToken, val turnId: String, val answer: ValidatedAnswer) : PlaybackEffect
    data class FreezeTurn(val turnId: String, val outcome: TurnOutcome, val hasCommittedUtterance: Boolean, val completedAnswerKeys: List<String>) : PlaybackEffect
    data class PlanResume(
        val request: OperationToken,
        val candidates: List<StoryCheckpoint>,
        val musicCandidates: List<MusicCheckpoint> = emptyList(),
        val afterInteraction: Boolean = false,
    ) : PlaybackEffect
    data class ScheduleTimer(val timer: TimerToken) : PlaybackEffect
    data class CancelTimer(val timer: TimerToken) : PlaybackEffect
    data class SaveCheckpoint(val checkpoint: StoryCheckpoint?, val playIntent: Boolean, val musicCheckpoint: MusicCheckpoint? = null) : PlaybackEffect
    data object CloseSession : PlaybackEffect
}

data class PlaybackSnapshot(
    val tripId: String,
    val sessionId: String,
    val mode: PlaybackMode,
    val stateRevision: Long,
    val cancelEpoch: Long,
    val playIntent: Boolean,
    val interactionIntent: Boolean,
    val blockers: Set<PlaybackBlocker>,
    val outputPhase: AdapterPhase,
    val inputPhase: AdapterPhase,
    val inputAvailable: Boolean,
    val turnId: String?,
    val checkpoint: StoryCheckpoint?,
    val pendingPlaybackCommits: Int,
    val closed: Boolean,
    val musicCheckpoint: MusicCheckpoint? = null,
    /** Only a naturally completed, committed music attempt clears this automatic-story boundary. */
    val musicRequiredBeforeAutomaticStory: Boolean = false,
)

data class PlaybackTransition(val snapshot: PlaybackSnapshot, val effects: List<PlaybackEffect>, val ignoredReason: String? = null)
