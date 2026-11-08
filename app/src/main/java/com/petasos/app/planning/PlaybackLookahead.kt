package com.petasos.app.planning

import com.petasos.app.playback.*

/**
 * Worker-owned projection of the next automatic story boundary, never a playback timer.
 * Register every transition before asking for a projection. Only an actual, durably accepted
 * output-start callback calls [outputStarted]; StartOutput merely identifies the pending attempt.
 * Estimates reaching zero do not complete outputs or advance checkpoints.
 */
class PlaybackLookahead {
    private data class ActiveOutput(
        val attempt: OperationToken,
        val reference: OutputReference,
        val requestedAtMs: Long,
        var startedAtMs: Long? = null,
        var musicPositionMs: Long? = null,
        var musicPositionAtMs: Long? = null,
    )

    private var latest: PlaybackSnapshot? = null
    private var lastElapsedMs = -1L
    private var active: ActiveOutput? = null

    /** StartOutput was requested, but its actual Started callback has not been accepted yet. */
    fun awaitingActualStart(): Boolean = active?.startedAtMs == null && active != null &&
        latest?.outputPhase == AdapterPhase.ACTIVE

    fun onTransition(transition: PlaybackTransition, nowElapsedMs: Long) {
        val state = transition.snapshot
        val before = latest
        if (nowElapsedMs < 0 || nowElapsedMs < lastElapsedMs) {
            active = null // A missed transition cannot leave an earlier attempt's clock authoritative.
            return
        }
        if (before != null && (state.sessionId != before.sessionId || state.tripId != before.tripId ||
                state.stateRevision <= before.stateRevision)) return
        latest = state
        lastElapsedMs = nowElapsedMs
        transition.effects.forEach { effect ->
            when (effect) {
                is PlaybackEffect.StartOutput -> {
                    if (effect.attempt.sessionId == state.sessionId) {
                        active = ActiveOutput(effect.attempt, effect.reference, nowElapsedMs)
                    }
                }
                is PlaybackEffect.StopOutput -> if (active?.attempt == effect.attempt) active = null
                is PlaybackEffect.CommitPlayback -> if (active?.attempt == effect.attempt) active = null
                else -> Unit
            }
        }
        if (state.closed || state.outputPhase != AdapterPhase.ACTIVE) active = null
        val output = active ?: return
        val reference = output.reference as? OutputReference.MusicTrack ?: return
        val checkpoint = state.musicCheckpoint?.takeIf { it.track.assetId == reference.assetId } ?: return
        if (output.startedAtMs != null && checkpoint.positionMs != output.musicPositionMs) {
            // A new actual position supersedes the old anchor. Never add elapsed since Started again.
            output.musicPositionMs = checkpoint.positionMs
            output.musicPositionAtMs = nowElapsedMs
        }
    }

    fun outputStarted(attempt: OperationToken, reference: OutputReference, nowElapsedMs: Long) {
        val output = active ?: return
        if (output.attempt != attempt || output.reference != reference || output.startedAtMs != null ||
            nowElapsedMs < lastElapsedMs || nowElapsedMs < output.requestedAtMs || latest?.outputPhase != AdapterPhase.ACTIVE) return
        output.startedAtMs = nowElapsedMs
        lastElapsedMs = nowElapsedMs
        if (reference is OutputReference.MusicTrack) {
            output.musicPositionMs = latest?.musicCheckpoint?.takeIf { it.track.assetId == reference.assetId }
                ?.positionMs ?: reference.positionMs
            output.musicPositionAtMs = nowElapsedMs
        }
    }

    /**
     * Durations are ordered by the exact PreparedStory paragraph IDs and must all be positive.
     * Returns null while the interaction/adapter/commit is unsettled, a duration is unknown, or
     * an output is overdue. The caller must then wait for new evidence and replan, not use an old ETA.
     */
    fun nextStoryStartElapsedMs(
        state: PlaybackSnapshot,
        nowElapsedMs: Long,
        nextMusicDurationMs: Long?,
        durations: (PreparedStory) -> List<Long>?,
    ): Long? {
        if (state != latest || nowElapsedMs < lastElapsedMs || nowElapsedMs < 0 ||
            !state.playIntent || state.interactionIntent || state.turnId != null || state.closed ||
            state.blockers.isNotEmpty() || state.pendingPlaybackCommits != 0 || state.inputPhase != AdapterPhase.READY) return null
        if (state.mode == PlaybackMode.IDLE && state.outputPhase == AdapterPhase.READY && active == null &&
            state.checkpoint?.hasNext != true && state.musicCheckpoint == null && !state.musicRequiredBeforeAutomaticStory) {
            return nowElapsedMs
        }
        if (state.outputPhase != AdapterPhase.ACTIVE) return null
        val output = active ?: return null
        val startedAt = output.startedAtMs ?: return null
        if (nowElapsedMs < startedAt) return null
        val remaining = when (state.mode) {
            PlaybackMode.STORY -> {
                val checkpoint = state.checkpoint?.takeIf { it.hasNext } ?: return null
                val reference = output.reference as? OutputReference.StoryParagraph ?: return null
                if (reference.storyId != checkpoint.story.storyId || reference.versionId != checkpoint.story.versionId ||
                    reference.paragraphId != checkpoint.story.paragraphIds[checkpoint.paragraphIndex]) return null
                val values = try { durations(checkpoint.story)?.toList() } catch (_: Exception) { null } ?: return null
                if (values.size != checkpoint.story.paragraphIds.size || values.any { it <= 0 }) return null
                val currentRemaining = values[checkpoint.paragraphIndex] - (nowElapsedMs - startedAt)
                if (currentRemaining <= 0) return null
                val music = nextMusicDurationMs?.takeIf { it > 0 } ?: return null
                var sum = currentRemaining
                for (index in checkpoint.paragraphIndex + 1 until values.size) sum = safeAdd(sum, values[index]) ?: return null
                safeAdd(sum, music) ?: return null
            }
            PlaybackMode.MUSIC -> {
                val checkpoint = state.musicCheckpoint ?: return null
                val reference = output.reference as? OutputReference.MusicTrack ?: return null
                if (reference.assetId != checkpoint.track.assetId) return null
                val duration = checkpoint.track.durationMs ?: return null
                val anchorPosition = output.musicPositionMs ?: return null
                val anchorTime = output.musicPositionAtMs ?: return null
                if (anchorPosition >= duration || nowElapsedMs < anchorTime) return null
                val value = duration - anchorPosition - (nowElapsedMs - anchorTime)
                if (value <= 0) return null
                value
            }
            else -> return null
        }
        return safeAdd(nowElapsedMs, remaining)
    }

    private fun safeAdd(left: Long, right: Long): Long? = if (right > Long.MAX_VALUE - left) null else left + right
}
