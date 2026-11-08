package com.petasos.app.summary

import com.petasos.app.playback.PlaybackEffect
import com.petasos.app.runtime.RuntimePersistence

/** Enqueue only after the durable freeze. Startup scanning repairs an enqueue/process failure. */
class SummarySchedulingPersistence(
    private val delegate: RuntimePersistence,
    private val enqueueFrozenTurn: (String) -> Unit,
    private val onSchedulingFailure: () -> Unit = {},
) : RuntimePersistence by delegate {
    override fun freezeTurn(effect: PlaybackEffect.FreezeTurn) {
        delegate.freezeTurn(effect)
        if (effect.hasCommittedUtterance) {
            try { enqueueFrozenTurn(effect.turnId) }
            catch (_: Exception) { onSchedulingFailure() }
        }
    }
}
