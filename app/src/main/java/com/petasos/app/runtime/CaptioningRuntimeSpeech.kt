package com.petasos.app.runtime

import com.petasos.app.playback.OperationToken

/**
 * Memory-only view of the paragraph the speech adapter actually reports STARTED for.
 * This observer never fabricates an output event, replaces a permit, starts input, or writes text.
 * Hiding the UI permanently fences the current attempt's caption; showing it cannot replay a late
 * STARTED or restore hidden plaintext. A fresh output attempt can populate a visible caption.
 */
class CaptioningRuntimeSpeech(
    private val delegate: RuntimeSpeech,
    private val onCaptionChanged: () -> Unit = {},
) : RuntimeSpeech {
    private class Slot(val attempt: OperationToken, val prepared: Boolean, var eligible: Boolean) {
        var text: RuntimeText? = null
        var permit: RuntimeSpeechPermit? = null
        var resolved = false
        fun discard() { eligible = false; text = null; permit = null }
    }

    private val lock = Any()
    private var visible = false
    private var closed = false
    private var current: Slot? = null
    private var caption: RuntimeText? = null

    fun setVisible(value: Boolean) {
        val changed = synchronized(lock) {
            visible = value && !closed
            if (!visible) {
                current?.discard()
                clearCaption()
            } else false
        }
        if (changed) notifyChanged()
    }

    /** Recheck a prepared output's cheap current-authority guard even after STARTED. */
    fun currentCaption(): RuntimeText? {
        var changed = false
        val value = synchronized(lock) {
            val slot = current
            if (caption != null && (!visible || closed || slot == null || !slot.eligible ||
                    (slot.prepared && slot.permit?.isCurrent() != true))) {
                slot?.discard()
                changed = clearCaption()
            }
            caption
        }
        if (changed) notifyChanged()
        return value
    }

    override fun startOutput(attempt: OperationToken, text: RuntimeText, callback: (RuntimeOutputEvent) -> Unit) {
        val slot = begin(attempt, prepared = false)
        synchronized(lock) { if (current === slot && slot.eligible) slot.text = text }
        try { delegate.startOutput(attempt, text, observe(slot, callback)) }
        catch (failure: Exception) { discard(slot); throw failure }
    }

    override fun startPreparedOutput(
        attempt: OperationToken,
        prepare: ((RuntimeSpeechPermit?) -> Unit) -> Unit,
        callback: (RuntimeOutputEvent) -> Unit,
    ) {
        val slot = begin(attempt, prepared = true)
        try {
            delegate.startPreparedOutput(attempt, { accept ->
                prepare { permit ->
                    synchronized(lock) {
                        if (current === slot && !slot.resolved) {
                            slot.resolved = true
                            if (slot.eligible && visible && !closed && permit?.isCurrent() == true) {
                                slot.permit = permit; slot.text = permit.text
                            }
                        }
                    }
                    // Preserve the exact same permit and callback cardinality. The adapter owns
                    // resolution, final start permission and all physical output acknowledgments.
                    accept(permit)
                }
            }, observe(slot, callback))
        } catch (failure: Exception) { discard(slot); throw failure }
    }

    override fun stopOutput(attempt: OperationToken) {
        val changed = synchronized(lock) {
            if (current?.attempt == attempt) {
                current?.discard(); current = null; clearCaption()
            } else false
        }
        if (changed) notifyChanged()
        delegate.stopOutput(attempt)
    }

    override fun cancelFocusRecovery() = delegate.cancelFocusRecovery()

    override fun requestInput(input: OperationToken, callback: (RuntimeInputEvent) -> Unit) = delegate.requestInput(input, callback)
    override fun stopInput(input: OperationToken) = delegate.stopInput(input)

    override fun close() {
        val changed = synchronized(lock) {
            closed = true; visible = false; current?.discard(); current = null; clearCaption()
        }
        if (changed) notifyChanged()
        delegate.close()
    }

    override fun toString() = "CaptioningRuntimeSpeech([redacted])"

    private fun begin(attempt: OperationToken, prepared: Boolean): Slot {
        var changed = false
        val slot = synchronized(lock) {
            current?.discard()
            changed = clearCaption()
            Slot(attempt, prepared, visible && !closed).also { current = it }
        }
        if (changed) notifyChanged()
        return slot
    }

    private fun observe(slot: Slot, callback: (RuntimeOutputEvent) -> Unit): (RuntimeOutputEvent) -> Unit = { event ->
        val changed = synchronized(lock) {
            if (current !== slot) false
            else if (event == RuntimeOutputEvent.STARTED) {
                val allowed = slot.eligible && visible && !closed && (!slot.prepared || slot.permit?.isCurrent() == true)
                val next = if (allowed) slot.text else null
                if (!allowed) slot.discard()
                if (caption !== next) { caption = next; true } else false
            } else {
                slot.discard(); current = null; clearCaption()
            }
        }
        if (changed) notifyChanged()
        callback(event)
    }

    private fun discard(slot: Slot) {
        val changed = synchronized(lock) {
            slot.discard()
            if (current === slot) { current = null; clearCaption() } else false
        }
        if (changed) notifyChanged()
    }

    /** Caller holds lock. */
    private fun clearCaption(): Boolean {
        val changed = caption != null
        caption = null
        return changed
    }

    private fun notifyChanged() {
        // An unavailable UI observer must not interrupt the adapter's actual runtime callback.
        try { onCaptionChanged() } catch (_: Exception) { }
    }
}
