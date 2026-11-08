package com.petasos.app.planning

import com.petasos.app.playback.PlaybackEffect
import com.petasos.app.playback.PreparedMusic
import com.petasos.app.playback.PreparedStory
import com.petasos.app.runtime.*
import java.util.concurrent.atomic.AtomicBoolean

/** A planner may select only Coordinator-supplied candidates that still pass installed-content checks. */
class ContentCheckedResumePlanner(
    private val delegate: RuntimeResumePlanner = CheckpointRuntimeResumePlanner(),
    private val storyCurrent: (PreparedStory) -> Boolean,
    private val musicCurrent: (PreparedMusic) -> Boolean,
) : RuntimeResumePlanner {
    override fun plan(effect: PlaybackEffect.PlanResume, callback: (RuntimeResumeSelection) -> Unit): RuntimeCancellation {
        val filtered = effect.copy(candidates = effect.candidates.filter { storyCurrent(it.story) },
            musicCandidates = effect.musicCandidates.filter { musicCurrent(it.track) })
        val cancelled = AtomicBoolean()
        val completed = AtomicBoolean()
        val handle = delegate.plan(filtered) selection@{ selected ->
            if (cancelled.get() || !completed.compareAndSet(false, true)) return@selection
            val story = selected.checkpoint?.takeIf { it in filtered.candidates && storyCurrent(it.story) }
            val music = selected.musicCheckpoint?.takeIf { it in filtered.musicCandidates && musicCurrent(it.track) }
            val invalid = (selected.checkpoint != null && story == null) || (selected.musicCheckpoint != null && music == null) ||
                (story != null && music != null)
            callback(if (invalid) RuntimeResumeSelection() else RuntimeResumeSelection(story, music))
        }
        return RuntimeCancellation { cancelled.set(true); handle.cancel() }
    }
}
