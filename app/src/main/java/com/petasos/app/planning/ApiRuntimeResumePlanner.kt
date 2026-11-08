package com.petasos.app.planning

import com.petasos.app.generation.GenerationClient
import com.petasos.app.generation.GenerationOutcome
import com.petasos.app.generation.ResumeGenerationCandidate
import com.petasos.app.generation.ResumeGenerationContext
import com.petasos.app.generation.ValidatedResumePlan
import com.petasos.app.playback.OperationToken
import com.petasos.app.playback.PlaybackEffect
import com.petasos.app.runtime.CheckpointRuntimeResumePlanner
import com.petasos.app.runtime.RuntimeCancellation
import com.petasos.app.runtime.RuntimeResumePlanner
import com.petasos.app.runtime.RuntimeResumeSelection

/** Trusted host snapshot only; neither the model nor this context can add playback candidates. */
class ApiResumeContext(
    val contextSummary: String,
    val routeRevision: Long?,
    val isCurrent: () -> Boolean,
) {
    override fun toString(): String = "ApiResumeContext([redacted])"
}

internal fun interface ResumeGenerationSubmitter {
    fun submit(
        request: OperationToken,
        context: ResumeGenerationContext,
        callback: (GenerationOutcome<ValidatedResumePlan>) -> Unit,
    ): RuntimeCancellation
}

/**
 * Selects references only. Install as ContentCheckedResumePlanner's delegate so both API selection
 * and checkpoint fallback pass the same current-content checks. GenerationClient and the core keep
 * their existing three-second deadlines; this adapter never retries or extends either deadline.
 */
class ApiRuntimeResumePlanner internal constructor(
    private val submitter: ResumeGenerationSubmitter,
    private val contextSupplier: (PlaybackEffect.PlanResume) -> ApiResumeContext?,
) : RuntimeResumePlanner {
    constructor(client: GenerationClient, contextSupplier: (PlaybackEffect.PlanResume) -> ApiResumeContext?) :
        this(ResumeGenerationSubmitter(client::submitResume), contextSupplier)

    private val checkpointPlanner = CheckpointRuntimeResumePlanner()

    override fun plan(effect: PlaybackEffect.PlanResume, callback: (RuntimeResumeSelection) -> Unit): RuntimeCancellation {
        // Completed stories have no paragraph to authorize. Never synthesize another paragraph.
        val available = effect.copy(candidates = effect.candidates.filter { it.hasNext }, musicCandidates = effect.musicCandidates.toList())
        val candidates = available.candidates.map { checkpoint ->
            Candidate(ResumeGenerationCandidate(checkpoint.story.versionId,
                checkpoint.story.paragraphIds[checkpoint.paragraphIndex], "story"), RuntimeResumeSelection(checkpoint))
        } + available.musicCandidates.map { checkpoint ->
            Candidate(ResumeGenerationCandidate(checkpoint.track.assetId, null, "music"), RuntimeResumeSelection(musicCheckpoint = checkpoint))
        }
        val call = PendingSelection(callback)
        fun fallback(): RuntimeResumeSelection {
            var selected = RuntimeResumeSelection()
            checkpointPlanner.plan(available) { selected = it }
            return selected
        }

        if (candidates.isEmpty() || candidates.size > 20 ||
            candidates.map { it.wire.contentId to it.wire.paragraphId }.distinct().size != candidates.size) {
            call.complete { fallback() }
            return call
        }

        try {
            val context = contextSupplier(available)
            if (context == null || !context.isCurrent()) {
                call.complete { fallback() }
                return call
            }
            val generationContext = ResumeGenerationContext(context.contextSummary, candidates.map { it.wire },
                context.routeRevision, context.isCurrent)
            val handle = submitter.submit(available.request, generationContext) { outcome ->
                call.complete {
                    val selection = try {
                        if (outcome is GenerationOutcome.Success && context.isCurrent()) select(outcome.value, candidates) else null
                    } catch (_: Exception) { null }
                    selection ?: fallback()
                }
            }
            call.attach(handle)
        } catch (_: Exception) {
            // Missing trusted context, configuration failure, or synchronous submission failure.
            call.complete { fallback() }
        }
        return call
    }

    private fun select(plan: ValidatedResumePlan, candidates: List<Candidate>): RuntimeResumeSelection? {
        // REC-01/02 requires private transition text to be stored before output. That output path is
        // not connected here, so reject the whole unsupported result rather than silently omit it.
        if (plan.transitionText != null) return null
        if (plan.action == "wait") {
            return if (plan.targetContentId == null && plan.paragraphId == null) RuntimeResumeSelection() else null
        }
        if (plan.action !in setOf("resume", "skip", "switch")) return null
        return candidates.singleOrNull { it.wire.contentId == plan.targetContentId && it.wire.paragraphId == plan.paragraphId }?.selection
    }

    private class Candidate(val wire: ResumeGenerationCandidate, val selection: RuntimeResumeSelection)

    /** Cancellation marks this call first, including when the client calls back from cancel(). */
    private class PendingSelection(private val callback: (RuntimeResumeSelection) -> Unit) : RuntimeCancellation {
        private enum class State { ACTIVE, COMPLETE, CANCELLED }
        private val lock = Any()
        private var state = State.ACTIVE
        private var handle: RuntimeCancellation? = null

        fun complete(selection: () -> RuntimeResumeSelection) = synchronized(lock) {
            if (state != State.ACTIVE) return@synchronized
            state = State.COMPLETE
            handle = null
            // Delivery shares the cancel monitor: once cancel returns, no new callback can begin.
            callback(selection())
        }

        fun attach(cancellation: RuntimeCancellation) {
            val release = synchronized(lock) {
                if (state == State.ACTIVE) { handle = cancellation; false } else true
            }
            // A synchronous preparation callback can complete before submit returns its handle.
            if (release) cancelQuietly(cancellation)
        }

        override fun cancel() {
            val cancellation = synchronized(lock) {
                state = State.CANCELLED
                handle.also { handle = null }
            }
            cancellation?.let(::cancelQuietly)
        }

        private fun cancelQuietly(cancellation: RuntimeCancellation) {
            try { cancellation.cancel() } catch (_: Exception) { /* Local cancellation stays authoritative. */ }
        }
    }
}
