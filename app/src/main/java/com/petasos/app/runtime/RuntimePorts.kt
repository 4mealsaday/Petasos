package com.petasos.app.runtime

import com.petasos.app.playback.*
import java.util.concurrent.atomic.AtomicBoolean

/** Plaintext is transient and deliberately excluded from diagnostic string representations. */
class RuntimeText(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = "RuntimeText([redacted])"
}

/** Transient worker-prepared text plus a cheap, thread-safe final SDK-start guard. */
class RuntimeSpeechPermit(val text: RuntimeText, private val current: () -> Boolean) {
    fun isCurrent(): Boolean = try { current() } catch (_: Exception) { false }
    override fun toString(): String = "RuntimeSpeechPermit([redacted])"
}

class RuntimeParagraph(val key: String, val text: RuntimeText, sourceRevisionIds: List<String> = emptyList()) {
    val sourceRevisionIds: List<String> = sourceRevisionIds.toList()
    init { require(key.isNotBlank()) }
    override fun toString(): String = "RuntimeParagraph([redacted])"
}

/** Only a provider which has validated the response schema, sources and request metadata may return this. */
class RuntimeAnswer(paragraphs: List<RuntimeParagraph>) {
    val paragraphs: List<RuntimeParagraph> = paragraphs.toList()
    init {
        require(this.paragraphs.size in 1..8)
        require(this.paragraphs.map { it.key }.distinct().size == this.paragraphs.size)
    }
    override fun toString(): String = "RuntimeAnswer([redacted])"
}

fun interface RuntimeCancellation { fun cancel() }

/** A dedicated serial worker queue; never the Android UI thread. Scheduled jobs re-enter this queue. */
interface RuntimeQueue {
    fun execute(task: () -> Unit)
    fun schedule(delayMs: Long, task: () -> Unit): RuntimeCancellation
}

fun interface RuntimeClock { fun elapsedRealtimeMs(): Long }

/** Expected content/permission denial, distinct from database or encryption failure. */
class RuntimeContentUnavailable(code: String = "content_unavailable") : Exception(safeCode(code)) {
    val code: String = message!!
    companion object {
        private fun safeCode(code: String): String = if (code in setOf(
            "content_unavailable", "story_reference_unavailable", "answer_source_unavailable",
        )) code else "content_unavailable"
    }
}

/**
 * Short synchronous worker-thread transactions. Returning means the operation was durably committed;
 * failures throw. No network calls or SDK calls belong inside these methods/transactions.
 */
interface RuntimePersistence {
    fun openSession(tripId: String, sessionId: String)
    /** Resolve an installed, currently eligible story version from durable storage. */
    fun prepareStory(versionId: String): PreparedStory = throw RuntimeContentUnavailable("story_reference_unavailable")
    fun prepareMusic(assetId: String): PreparedMusic = throw RuntimeContentUnavailable()
    fun loadMusic(reference: OutputReference.MusicTrack): RuntimeMusicSource = throw RuntimeContentUnavailable()
    fun registerTurn(tripId: String, sessionId: String, turnId: String)
    fun registerOutput(attempt: OperationToken, reference: OutputReference, turnId: String?)
    fun markOutputStarted(attempt: OperationToken)
    fun loadOutput(reference: OutputReference): RuntimeText
    fun loadUtterance(payload: PayloadHandle): RuntimeText
    fun persistUtterance(commit: OperationToken, turnId: String, text: RuntimeText): PayloadHandle
    fun persistAnswer(commit: OperationToken, turnId: String, answer: RuntimeAnswer): ValidatedAnswer
    fun commitPlayback(effect: PlaybackEffect.CommitPlayback, snapshot: PlaybackSnapshot)
    fun freezeTurn(effect: PlaybackEffect.FreezeTurn)
    fun saveCheckpoint(snapshot: PlaybackSnapshot)
    fun closeSession(sessionId: String)
}

enum class RuntimeOutputEvent { STARTED, COMPLETED, STOPPED, FAILED, FOCUS_LOST, FOCUS_TRANSIENT }

sealed interface RuntimeInputEvent {
    data object Ready : RuntimeInputEvent
    data object Started : RuntimeInputEvent
    data object Ended : RuntimeInputEvent
    class Final(val text: RuntimeText) : RuntimeInputEvent {
        override fun toString(): String = "Final([redacted])"
    }
    data object Failed : RuntimeInputEvent
    /** Independent physical input-stop evidence only; never synthesize from Ended, close or a delay. */
    data object StoppedConfirmed : RuntimeInputEvent
}

interface RuntimeSpeech {
    fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit)
    /**
     * Delayed SDK adapters request preparation only after connecting, then recheck the permit
     * immediately before speak. Preparation owns worker/DB dispatch; adapters never read storage.
     * The default preserves synchronous/scripted ports which have no separate connect boundary.
     */
    fun startPreparedOutput(
        attempt: OperationToken,
        prepare: ((RuntimeSpeechPermit?) -> Unit) -> Unit,
        callback: (RuntimeOutputEvent) -> Unit,
    ) {
        val resolved = AtomicBoolean()
        try {
            prepare { permit ->
                if (resolved.compareAndSet(false, true)) {
                    if (permit == null || !permit.isCurrent()) callback(RuntimeOutputEvent.STOPPED)
                    else try { startOutput(attempt, permit.text, callback) }
                    catch (_: Exception) { callback(RuntimeOutputEvent.FAILED) }
                }
            }
        } catch (_: Exception) {
            if (resolved.compareAndSet(false, true)) {
                callback(RuntimeOutputEvent.FAILED)
                callback(RuntimeOutputEvent.STOPPED) // No output request was submitted.
            }
        }
    }
    fun stopOutput(attempt: OperationToken)
    fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit)
    fun stopInput(input: OperationToken)
    /** Best-effort resource cleanup; this method is not a physical-stop acknowledgment. */
    fun close()
}

/** A locally installed, rights/hash-verified music asset. No private user recording is allowed. */
data class RuntimeMusicSource(
    val assetId: String,
    val uri: String,
    val durationMs: Long? = null,
    /** Immutable/atomic review identity and expiry only; never perform file or DB I/O here. */
    private val current: () -> Boolean = { true },
) {
    init { require(assetId.isNotBlank() && uri.isNotBlank() && (durationMs == null || durationMs > 0)) }
    fun isCurrent(): Boolean = try { current() } catch (_: Exception) { false }
    override fun toString(): String = "RuntimeMusicSource([redacted])"
}

/** Worker-verified source plus a cheap, thread-safe guard for the final player-start boundary. */
class RuntimeMusicPermit(val source: RuntimeMusicSource, private val current: () -> Boolean) {
    fun isCurrent(): Boolean = try { current() } catch (_: Exception) { false }
    override fun toString(): String = "RuntimeMusicPermit([redacted])"
}

sealed interface RuntimeMusicEvent {
    data object Started : RuntimeMusicEvent
    data class Position(val positionMs: Long) : RuntimeMusicEvent
    /** Only a natural end event (Media3 STATE_ENDED with repeat OFF) may produce Completed. */
    data class Completed(val positionMs: Long) : RuntimeMusicEvent
    /** Physical output stop with its captured position, not the return from stopOutput(). */
    data class Stopped(val positionMs: Long) : RuntimeMusicEvent
    data object Failed : RuntimeMusicEvent
    data object FocusLost : RuntimeMusicEvent
    data object FocusTransient : RuntimeMusicEvent
}

interface RuntimeMusic {
    fun startOutput(attempt: OperationToken, source: RuntimeMusicSource, positionMs: Long, callback: (RuntimeMusicEvent) -> Unit)
    /**
     * Asynchronous adapters reserve their slot before requesting worker preparation and check the
     * permit again immediately before starting their player. No DB or file I/O belongs in that
     * final guard. The default preserves synchronous/scripted ports without a separate SDK queue.
     */
    fun startPreparedOutput(
        attempt: OperationToken,
        positionMs: Long,
        prepare: ((RuntimeMusicPermit?) -> Unit) -> Unit,
        callback: (RuntimeMusicEvent) -> Unit,
    ) {
        val resolved = AtomicBoolean()
        try {
            prepare { permit ->
                if (resolved.compareAndSet(false, true)) {
                    if (permit == null || !permit.isCurrent()) callback(RuntimeMusicEvent.Stopped(positionMs.coerceAtLeast(0)))
                    else try { startOutput(attempt, permit.source, positionMs, callback) }
                    catch (_: Exception) { callback(RuntimeMusicEvent.Failed) }
                }
            }
        } catch (_: Exception) {
            if (resolved.compareAndSet(false, true)) {
                callback(RuntimeMusicEvent.Failed)
                callback(RuntimeMusicEvent.Stopped(positionMs.coerceAtLeast(0))) // No player request was submitted.
            }
        }
    }
    fun stopOutput(attempt: OperationToken)
    fun close()
}

class UnavailableRuntimeMusic : RuntimeMusic {
    override fun startOutput(attempt: OperationToken, source: RuntimeMusicSource, positionMs: Long, callback: (RuntimeMusicEvent) -> Unit) {
        callback(RuntimeMusicEvent.Failed)
        // No SDK or player was invoked; quiescence is known independently.
        callback(RuntimeMusicEvent.Stopped(positionMs))
    }
    override fun stopOutput(attempt: OperationToken) = Unit
    override fun close() = Unit
}

data class RuntimeResumeSelection(val checkpoint: StoryCheckpoint? = null, val musicCheckpoint: MusicCheckpoint? = null)

interface RuntimeResumePlanner {
    fun plan(effect: PlaybackEffect.PlanResume, callback: (RuntimeResumeSelection) -> Unit): RuntimeCancellation
}

/** Location/route/expiry authorization for an automatic offer at the first SDK start boundary. */
fun interface RuntimeContentAuthority {
    fun isAutomaticStoryCurrent(offerId: String, reference: OutputReference.StoryParagraph): Boolean
    /** SDK-thread guard: production implementations must override this without DB or network I/O. */
    fun isAutomaticStoryOffered(offerId: String, reference: OutputReference.StoryParagraph): Boolean =
        isAutomaticStoryCurrent(offerId, reference)
}

class CheckpointRuntimeResumePlanner : RuntimeResumePlanner {
    override fun plan(effect: PlaybackEffect.PlanResume, callback: (RuntimeResumeSelection) -> Unit): RuntimeCancellation {
        callback(if (effect.musicCandidates.isNotEmpty()) RuntimeResumeSelection(musicCheckpoint = effect.musicCandidates.first())
            else RuntimeResumeSelection(checkpoint = effect.candidates.firstOrNull()))
        return RuntimeCancellation { }
    }
}

class RuntimeGenerationRequest(
    val request: OperationToken,
    val turnId: String,
    val attemptNumber: Int,
    val utterance: RuntimeText,
) {
    override fun toString(): String = "RuntimeGenerationRequest([redacted])"
}

sealed interface RuntimeGenerationResult {
    class Success(val answer: RuntimeAnswer) : RuntimeGenerationResult {
        override fun toString(): String = "Success([redacted])"
    }
    data class Failure(val retryable: Boolean, val retryAfterMs: Long = 0) : RuntimeGenerationResult
}

interface RuntimeGeneration {
    fun generate(request: RuntimeGenerationRequest, callback: (RuntimeGenerationResult) -> Unit): RuntimeCancellation
}

/** Missing server configuration is an explicit failure, never a fabricated answer. */
class UnavailableRuntimeGeneration : RuntimeGeneration {
    override fun generate(request: RuntimeGenerationRequest, callback: (RuntimeGenerationResult) -> Unit): RuntimeCancellation {
        callback(RuntimeGenerationResult.Failure(retryable = false))
        return RuntimeCancellation { }
    }
}

enum class RuntimeIssue {
    STORAGE_UNAVAILABLE, CONTENT_UNAVAILABLE, SPEECH_UNAVAILABLE, MUSIC_UNAVAILABLE, GENERATION_UNAVAILABLE, INPUT_STOP_UNCONFIRMED,
}

interface RuntimeObserver {
    fun onTransition(transition: PlaybackTransition) { }
    fun onIssue(issue: RuntimeIssue) { }
    fun onAutomaticStoryStarted(offerId: String, storyId: String, versionId: String) { }
    /** Genuine start without a new automatic offer: explicit playback or an already-started story's continuation. */
    fun onStoryStarted(storyId: String, versionId: String) { }
    /** Accepted story Next, before transition refresh/effects. This is user intent, never listening completion. */
    fun onStorySkipped(storyId: String) { }
}
