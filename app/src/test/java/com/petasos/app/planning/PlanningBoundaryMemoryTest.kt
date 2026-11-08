package com.petasos.app.planning

import com.petasos.app.playback.*
import org.junit.Assert.*
import org.junit.Test

class PlanningBoundaryMemoryTest {
    private val playing = PlaybackSnapshot("trip", "session", PlaybackMode.STORY, 1, 0, true, false,
        emptySet(), AdapterPhase.ACTIVE, AdapterPhase.READY, false, null, null, 0, false)
    private val committing = playing.copy(stateRevision = 2, outputPhase = AdapterPhase.READY, pendingPlaybackCommits = 1)

    @Test fun noPriorObservationMeansNoBoundaryEvenDuringAStartOrResume() {
        val memory = PlanningBoundaryMemory()
        assertNull(memory.resolve(playing, null, 0, startingOutput = true))
        assertNull(memory.resolve(committing, null, 1))
        assertNull(memory.resolve(playing.copy(mode = PlaybackMode.RESUMING, stateRevision = 3), null, 2))
    }

    @Test fun observedBoundaryReplacesTheEstimateAndRestoresACompletedGap() {
        val memory = PlanningBoundaryMemory()
        assertEquals(100_000L, memory.resolve(playing, 100_000, 0))
        assertEquals(100_000L, memory.resolve(committing, null, 1_000))
        assertEquals(95_000L, memory.resolve(playing.copy(stateRevision = 3), 95_000, 2_000))
        assertEquals(95_000L, memory.resolve(committing.copy(stateRevision = 4), null, 10_000))
    }

    @Test fun paragraphCommitThenRequestThenActualStartUsesOneBoundedMissingInterval() {
        val memory = PlanningBoundaryMemory()
        memory.resolve(playing, 90_000, 0)
        assertEquals(90_000L, memory.resolve(committing, null, 10_000))
        val starting = playing.copy(stateRevision = 3)
        assertEquals(90_000L, memory.resolve(starting, null, 12_000, startingOutput = true))
        assertEquals(90_000L, memory.resolve(starting, null, 14_999, startingOutput = true))
        assertNull(memory.resolve(starting, null, 15_000, startingOutput = true))
        assertNull(memory.resolve(starting, null, 16_000, startingOutput = true))
        assertEquals(92_000L, memory.resolve(starting, 92_000, 17_000))
    }

    @Test fun resumeGapCanBridgeButDoesNotRenewAtEachTick() {
        val memory = PlanningBoundaryMemory()
        memory.resolve(playing, 100_000, 0)
        val resuming = playing.copy(mode = PlaybackMode.RESUMING, stateRevision = 2, outputPhase = AdapterPhase.READY)
        assertEquals(100_000L, memory.resolve(resuming, null, 1_000))
        for (time in 2_000L..5_000L step 1_000) assertEquals(100_000L, memory.resolve(resuming, null, time))
        assertNull(memory.resolve(resuming, null, 6_000))
    }

    @Test fun heldBoundaryNeverPointsIntoThePast() {
        val memory = PlanningBoundaryMemory()
        memory.resolve(playing, 100, 0)
        assertEquals(200L, memory.resolve(committing, null, 200))
        assertEquals(300L, memory.resolve(committing, null, 300))
    }

    @Test fun startedOutputWithNoProjectionIsOverdueOrUnknownAndCannotBorrowTheOldBoundary() {
        val memory = PlanningBoundaryMemory()
        memory.resolve(playing, 90_000, 0)
        assertNull(memory.resolve(playing, null, 10_000, startingOutput = false))
        assertNull(memory.resolve(committing, null, 10_001))
    }

    @Test fun idleWithoutAnActualBoundaryCannotExtendThePreviousEstimate() {
        val memory = PlanningBoundaryMemory()
        memory.resolve(playing, 90_000, 0)
        val idle = playing.copy(mode = PlaybackMode.IDLE, outputPhase = AdapterPhase.READY, stateRevision = 2)
        assertNull(memory.resolve(idle, null, 100))
    }

    @Test fun interactionStopBlockerClosedAndInputActivityClearEvenAProvidedEstimate() {
        val ineligible = listOf(
            playing.copy(interactionIntent = true),
            playing.copy(turnId = "turn"),
            playing.copy(playIntent = false),
            playing.copy(blockers = setOf(PlaybackBlocker.FOCUS_TRANSIENT)),
            playing.copy(closed = true),
            playing.copy(mode = PlaybackMode.PAUSED),
            playing.copy(mode = PlaybackMode.TALK_PENDING),
            playing.copy(mode = PlaybackMode.GENERATING),
            playing.copy(inputPhase = AdapterPhase.ACTIVE),
        )
        ineligible.forEach { state ->
            val memory = PlanningBoundaryMemory()
            memory.resolve(playing, 90_000, 0)
            assertNull(memory.resolve(state.copy(stateRevision = 2), 100_000, 100, startingOutput = true))
            assertNull(memory.resolve(committing.copy(stateRevision = 3), null, 200))
        }
    }

    @Test fun cancelEpochOrOwnerChangesCannotCarryTheOldBoundaryIntoTheNewContext() {
        listOf(
            committing.copy(cancelEpoch = 1),
            committing.copy(sessionId = "new-session"),
            committing.copy(tripId = "new-trip"),
        ).forEach { state ->
            val memory = PlanningBoundaryMemory()
            memory.resolve(playing, 90_000, 0)
            assertNull(memory.resolve(state, null, 100))
            assertEquals(110_000L, memory.resolve(state, 110_000, 200))
        }
    }

    @Test fun aFreshObservationIsAcceptedAfterCancellationOnlyInAnEligibleContext() {
        val memory = PlanningBoundaryMemory()
        memory.resolve(playing, 90_000, 0)
        val newAttempt = playing.copy(cancelEpoch = 1, stateRevision = 2)
        assertEquals(120_000L, memory.resolve(newAttempt, 120_000, 100))
        assertEquals(120_000L, memory.resolve(newAttempt, null, 200, startingOutput = true))
    }

    @Test fun invalidObservationAndClockRegressionClearTheRememberedBoundary() {
        val memory = PlanningBoundaryMemory()
        memory.resolve(playing, 90_000, 100)
        assertNull(memory.resolve(committing, null, 50))
        assertNull(memory.resolve(committing, null, 150))
        memory.resolve(committing, 100_000, 200)
        assertNull(memory.resolve(committing, 100, 250))
        assertNull(memory.resolve(committing, null, 300))
    }

    @Test fun staleSnapshotCannotResurrectOrRenewRememberedTiming() {
        val memory = PlanningBoundaryMemory()
        memory.resolve(playing.copy(stateRevision = 10), 90_000, 0)
        assertNull(memory.resolve(committing, 100_000, 100))
        assertNull(memory.resolve(committing.copy(stateRevision = 11), null, 200))
    }

    @Test fun startingFlagOnlyBridgesAnActiveOutput() {
        val memory = PlanningBoundaryMemory()
        memory.resolve(playing, 90_000, 0)
        assertNull(memory.resolve(playing.copy(outputPhase = AdapterPhase.READY), null, 100, startingOutput = true))
    }
}
