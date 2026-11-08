package com.petasos.app.planning

import com.petasos.app.playback.*

/**
 * Bridges brief commit/start gaps using an already observed boundary. It never invents the first
 * boundary, extends an overdue started output, or carries timing across a cancellation/interaction.
 * Worker owned; all times use the session's monotonic clock.
 */
class PlanningBoundaryMemory {
    private data class Owner(val tripId: String, val sessionId: String, val cancelEpoch: Long)
    private var owner: Owner? = null
    private var lastRevision = -1L
    private var lastNowMs = -1L
    private var boundaryMs: Long? = null
    private var missingSinceMs: Long? = null

    /** [observedBoundaryMs] must be a fresh projection, never a previous return value of this method. */
    fun resolve(
        state: PlaybackSnapshot,
        observedBoundaryMs: Long?,
        nowMs: Long,
        startingOutput: Boolean = false,
    ): Long? {
        if (nowMs < 0 || nowMs < lastNowMs) { clearBoundary(); return null }
        lastNowMs = nowMs
        val currentOwner = Owner(state.tripId, state.sessionId, state.cancelEpoch)
        if (owner != currentOwner) {
            clearBoundary()
            owner = currentOwner
            lastRevision = state.stateRevision
        } else if (state.stateRevision < lastRevision) {
            clearBoundary()
            return null
        }
        lastRevision = state.stateRevision
        if (!state.playIntent || state.interactionIntent || state.turnId != null || state.closed ||
            state.blockers.isNotEmpty() || state.inputPhase != AdapterPhase.READY ||
            state.mode !in setOf(PlaybackMode.IDLE, PlaybackMode.STORY, PlaybackMode.MUSIC, PlaybackMode.RESUMING)) {
            clearBoundary()
            return null
        }
        if (observedBoundaryMs != null) {
            if (observedBoundaryMs < nowMs) { clearBoundary(); return null }
            boundaryMs = observedBoundaryMs
            missingSinceMs = null
            return observedBoundaryMs
        }
        val previous = boundaryMs ?: return null
        val benignGap = state.pendingPlaybackCommits > 0 || state.mode == PlaybackMode.RESUMING ||
            startingOutput && state.outputPhase == AdapterPhase.ACTIVE
        if (!benignGap) { clearBoundary(); return null }
        val firstMissing = missingSinceMs ?: nowMs.also { missingSinceMs = it }
        if (nowMs - firstMissing >= MAX_GAP_MS) { clearBoundary(); return null }
        return maxOf(nowMs, previous)
    }

    private fun clearBoundary() { boundaryMs = null; missingSinceMs = null }

    companion object { const val MAX_GAP_MS = 5_000L }
}
