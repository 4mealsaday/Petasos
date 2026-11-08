package com.petasos.app.planning

import com.petasos.app.playback.OutputReference
import com.petasos.app.playback.PlaybackEffect
import com.petasos.app.playback.PlaybackSnapshot
import com.petasos.app.playback.PlaybackTransition
import com.petasos.app.playback.PreparedMusic

/** Current reviewed catalog metadata. The runtime independently verifies rights and bytes before output. */
data class AutomaticMusicCandidate(val track: PreparedMusic, val eligibilityKey: String) {
    init { require(eligibilityKey.isNotBlank()) }
    override fun toString(): String = "AutomaticMusicCandidate(content=redacted, eligibility=redacted)"
}

interface PlannedMusicOutput {
    fun offer(track: PreparedMusic)
    fun withdraw(assetId: String)
}

/**
 * Session/worker-owned, single queued track. The coordinator alone decides when it can play.
 * Catalog order is deterministic; after selection the next entry is prepared, wrapping at the end.
 * A one-track catalog may therefore produce a new attempt after natural completion, never player repeat.
 */
class AutomaticMusicPlaybackBridge(
    private val candidates: () -> List<AutomaticMusicCandidate>,
    private val output: PlannedMusicOutput,
) {
    private var sessionId: String? = null
    private var observedRevision = -1L
    private var eligible = false
    private var offered: AutomaticMusicCandidate? = null
    private var lastSelectedAssetId: String? = null

    /** The exact queued track, rather than an arbitrary catalog duration, determines the gap. */
    fun offeredDurationMs(): Long? = offered?.track?.durationMs

    /** Called before runtime effects, so a selected offer is consumed before its successor is queued. */
    fun onTransition(transition: PlaybackTransition) {
        val state = transition.snapshot
        useSession(state)
        if (state.stateRevision <= observedRevision) return
        observedRevision = state.stateRevision
        val selected = transition.effects.filterIsInstance<PlaybackEffect.StartOutput>()
            .mapNotNull { it.reference as? OutputReference.MusicTrack }.lastOrNull()
        if (selected != null) {
            lastSelectedAssetId = selected.assetId
            if (offered?.track?.assetId == selected.assetId) offered = null
        }
        val canOffer = canOffer(state)
        val needsLookup = canOffer && (!eligible || selected != null)
        eligible = canOffer
        if (!canOffer) withdraw()
        else if (needsLookup) supply(state)
    }

    /** Explicit catalog refresh only. Position/progress callbacks must use onTransition instead. */
    fun refresh(state: PlaybackSnapshot) {
        useSession(state)
        if (state.stateRevision < observedRevision) return
        observedRevision = state.stateRevision
        eligible = canOffer(state)
        if (eligible) supply(state) else withdraw()
    }

    fun clear() {
        withdraw()
        sessionId = null
        observedRevision = -1
        eligible = false
        lastSelectedAssetId = null
    }

    private fun useSession(state: PlaybackSnapshot) {
        if (sessionId != state.sessionId) {
            clear()
            sessionId = state.sessionId
        }
    }

    private fun canOffer(state: PlaybackSnapshot): Boolean = !state.closed && state.blockers.isEmpty() &&
        (state.playIntent || state.interactionIntent)

    private fun supply(state: PlaybackSnapshot) {
        val available = try {
            val values = candidates().toList()
            // Ambiguous IDs do not authorize whichever entry happened to occur first.
            val counts = values.groupingBy { it.track.assetId }.eachCount()
            values.filter { counts[it.track.assetId] == 1 }
        } catch (_: Exception) { emptyList() }
        val previous = lastSelectedAssetId ?: state.musicCheckpoint?.track?.assetId
        val previousIndex = available.indexOfFirst { it.track.assetId == previous }
        val next = if (available.isEmpty()) null else available[(previousIndex + 1) % available.size]
        if (next == offered) return
        withdraw()
        offered = next
        if (next != null) output.offer(next.track)
    }

    private fun withdraw() {
        val previous = offered ?: return
        offered = null // Reentrant/queued observer notifications cannot withdraw twice.
        output.withdraw(previous.track.assetId)
    }
}
