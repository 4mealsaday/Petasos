package com.petasos.app.storage

import android.app.Instrumentation
import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.music.MusicCatalog
import com.petasos.app.playback.*
import com.petasos.app.runtime.RuntimeMusicSource
import java.util.UUID

/** Real Room/TX-02/TX-04 with scripted playback evidence. No SDK, audio, location or network. */
object RecoveryMusicGapProbe {
    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        Fixture(context).use { f ->
            f.completeStory(terminal = true)
            f.completePrompt()
            f.exec("UPDATE source_evidence SET review_status='draft'")
            val afterStory = f.recover(required = true)
            check(afterStory.checkpoint == RecoveryCheckpoint.Idle)
            check(f.text("SELECT last_completed_paragraph_id FROM playback_checkpoint") == "test-p2")
            f.exec("UPDATE source_evidence SET review_status='verified'")
            // A new process/session that never starts an output cannot erase the earlier obligation.
            f.resume(afterStory)
            f.saveIdle()
            val emptySession = f.recover(required = true)
            f.resume(emptySession)
            f.completeStory(terminal = false)
            val partialStory = f.recover(required = true)
            check(partialStory.checkpoint is RecoveryCheckpoint.Story)
            mark("recovery_music_gap_survives_completed_story_crash_empty_session_and_partial_story")

            f.resume(partialStory)
            f.completeStory(terminal = true)
            f.wall = 1 // Wall time moves backwards; only append order determines cadence.
            f.finishMusic(completed = true)
            val afterMusic = f.recover(required = false)
            check(afterMusic.checkpoint == RecoveryCheckpoint.Idle)
            check(f.text("SELECT last_completed_paragraph_id FROM playback_checkpoint") == "test-p2")
            f.resume(afterMusic)
            f.completeStory(terminal = false)
            val afterAnotherPartial = f.recover(required = false)
            f.resume(afterAnotherPartial)
            f.completeStory(terminal = true)
            f.recover(required = true)
            mark("recovery_only_durable_music_completion_releases_gap_and_later_final_story_restores_it")
        }

        Fixture(context).use { f ->
            f.prepareMusic(started = false)
            val prepared = f.recover(required = true)
            check(prepared.checkpoint == RecoveryCheckpoint.Idle)
            f.resume(prepared)
            f.prepareMusic(started = true)
            val interruptedByDeath = f.recover(required = true)
            f.resume(interruptedByDeath)
            f.finishMusic(completed = false)
            f.saveIdle() // Explicit skip removes the music checkpoint, not the cadence obligation.
            val skipped = f.recover(required = true)
            check(skipped.checkpoint == RecoveryCheckpoint.Idle)
            f.resume(skipped)
            f.finishMusic(completed = true)
            f.recover(required = false)
            mark("recovery_prepared_playing_and_skipped_music_cannot_claim_natural_completion")
        }

        Fixture(context).use { f ->
            // A saved pointer can describe a resume/selection. It is not an actual completed attempt.
            f.saveCompletedPointerWithoutPlayback()
            val pointerOnly = f.recover(required = false)
            f.resume(pointerOnly)
            f.completeStory(terminal = true)
            val beforeCompletion = f.recover(required = true)
            f.resume(beforeCompletion)
            val attempt = f.finishMusic(completed = true)
            f.exec("UPDATE playback_attempt SET ended_elapsed_ms=NULL WHERE attempt_id=?", attempt.id)
            val missingProof = f.recover(required = true)
            check(missingProof != beforeCompletion)
            f.exec("UPDATE playback_attempt SET ended_elapsed_ms=? WHERE attempt_id=?", f.elapsed, attempt.id)
            val repairedProof = checkNotNull(f.store.sessionRecovery().currentTripCandidate())
            check(!repairedProof.requireMusicBeforeAutomaticStory)
            try {
                f.store.sessionRecovery().openResumedSession(missingProof, "stale-resume")
                error("RECOVERY_CADENCE_STALE_CANDIDATE_ACCEPTED")
            } catch (_: StorageUnavailable) { }
            mark("recovery_checkpoint_pointer_is_not_completion_and_cadence_evidence_revalidates_candidate")

            // A different trip starts with no inherited music debt, even when the old one has it.
            f.resume(repairedProof)
            f.prepareMusic(started = false)
            f.recover(required = true)
            f.openDifferentTrip()
            f.recover(required = false)
            mark("recovery_music_gap_is_scoped_to_trip_and_never_grants_autoplay")
        }
    }

    private class Fixture(private val context: Context) : AutoCloseable {
        private val name = "recovery-music-gap-${UUID.randomUUID()}.db"
        var wall = 10_000L
        var elapsed = 100L
        private var sequence = 0
        private var revision = 0L
        private var tripId = "trip"
        private var sessionId = "session"
        private var checkpoint: StoryCheckpoint? = null
        val store = RoomRuntimePersistence(StorageDatabase.open(context, name),
            PayloadCipher(AndroidPayloadKeys("com.petasos.app.tests.$name")), { wall }, { elapsed },
            "synthetic-clock", MusicCatalog { id ->
                check(id == "synthetic-music")
                // Only registration checks this synthetic port; nothing opens or plays the URI.
                RuntimeMusicSource(id, "file:///synthetic-not-played.wav", 1_000)
            })
        private val story = SyntheticStorageFixture.install(store)

        init { store.openSession(tripId, sessionId) }

        fun completeStory(terminal: Boolean) {
            val index = if (terminal) story.paragraphIds.lastIndex else 0
            val token = token()
            val reference = OutputReference.StoryParagraph(story.storyId, story.versionId, story.paragraphIds[index])
            store.registerOutput(token, reference, null)
            store.markOutputStarted(token)
            elapsed += 10
            checkpoint = StoryCheckpoint(story, index + 1)
            store.commitPlayback(PlaybackEffect.CommitPlayback(token, reference, AttemptOutcome.COMPLETED, checkpoint),
                snapshot(checkpoint))
        }

        fun prepareMusic(started: Boolean): OperationToken {
            val token = token()
            store.registerOutput(token, OutputReference.MusicTrack("synthetic-music"), null)
            if (started) store.markOutputStarted(token)
            return token
        }

        fun completePrompt() {
            val token = token()
            val reference = OutputReference.FixedPrompt("talk-prompt")
            store.registerOutput(token, reference, null)
            store.markOutputStarted(token)
            elapsed += 10
            store.commitPlayback(PlaybackEffect.CommitPlayback(token, reference, AttemptOutcome.COMPLETED, checkpoint),
                snapshot(checkpoint))
        }

        fun finishMusic(completed: Boolean): OperationToken {
            val token = prepareMusic(started = true)
            elapsed += 10
            // Natural completion leaves no music pointer, even if the preceding story pointer remains.
            val nextMusic = if (completed) null else MusicCheckpoint(PreparedMusic("synthetic-music", 1_000), 250)
            store.commitPlayback(PlaybackEffect.CommitPlayback(token, OutputReference.MusicTrack("synthetic-music"),
                if (completed) AttemptOutcome.COMPLETED else AttemptOutcome.INTERRUPTED,
                checkpoint, nextMusic, if (completed) 1_000L else 250L), snapshot(checkpoint, nextMusic))
            return token
        }

        fun saveIdle() {
            checkpoint = null
            store.saveCheckpoint(snapshot(null))
        }

        fun saveCompletedPointerWithoutPlayback() {
            checkpoint = StoryCheckpoint(story, story.paragraphIds.size)
            store.saveCheckpoint(snapshot(checkpoint))
        }

        fun recover(required: Boolean): RecoveryCandidate {
            val recovery = store.sessionRecovery()
            val candidate = checkNotNull(recovery.recoverOnAppStart())
            check(candidate.requireMusicBeforeAutomaticStory == required)
            check(recovery.currentTripCandidate() == candidate)
            val restored = PlaybackCoordinator(candidate.tripId, "new-core",
                restoredMusicRequiredBeforeAutomaticStory = candidate.requireMusicBeforeAutomaticStory)
            check(!restored.snapshot().playIntent && !restored.snapshot().interactionIntent)
            check(restored.snapshot().outputPhase == AdapterPhase.READY && restored.snapshot().inputPhase == AdapterPhase.READY)
            check(restored.snapshot().musicRequiredBeforeAutomaticStory == required)
            return candidate
        }

        fun resume(candidate: RecoveryCandidate) {
            sessionId = "session-${++sequence}"
            revision = 0
            store.sessionRecovery().openResumedSession(candidate, sessionId)
        }

        fun openDifferentTrip() {
            tripId = "different-trip"
            sessionId = "different-session"
            revision = 0
            checkpoint = null
            store.openSession(tripId, sessionId)
        }

        fun exec(statement: String, vararg args: Any?) = store.database.openHelper.writableDatabase.execSQL(statement, args)
        fun text(statement: String): String? = store.database.openHelper.writableDatabase.query(SimpleSQLiteQuery(statement)).use {
            check(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }
        private fun token() = OperationToken(sessionId, 0, "attempt-${++sequence}")
        private fun snapshot(story: StoryCheckpoint?, music: MusicCheckpoint? = null) = PlaybackSnapshot(
            tripId, sessionId, PlaybackMode.IDLE, ++revision, 0, false, false, emptySet(),
            AdapterPhase.READY, AdapterPhase.READY, false, null, story, 0, false, music)
        override fun close() {
            store.close()
            SyntheticStorageFixture.cleanup(context, name)
        }
    }
}
