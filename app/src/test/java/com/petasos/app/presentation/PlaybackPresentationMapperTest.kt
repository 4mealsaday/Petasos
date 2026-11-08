package com.petasos.app.presentation

import org.junit.Assert.*
import org.junit.Test

class PlaybackPresentationMapperTest {
    @Test fun initialStateHasNoInteractionOrInferredMicrophoneCapability() {
        val state = map(PlaybackPresentationInput())
        assertEquals(ParkedPresentationGate.UNKNOWN, state.vehicleGate)
        assertEquals(PlaybackPresentationStatus.CONNECTING, state.status)
        assertEquals(PlaybackPresentationNotice.READY, state.notice)
        assertAllDisabled(state)
    }

    @Test fun vehicleRestrictionOrLostBrowserDominatesStaleAvailableFlags() {
        for (gate in listOf(ParkedPresentationGate.UNKNOWN, ParkedPresentationGate.RESTRICTED)) {
            val state = map(ready.copy(vehicleGate = gate))
            assertEquals(gate, state.vehicleGate)
            assertAllDisabled(state)
        }
        assertAllDisabled(map(ready.copy(connected = false)))
        val pausedActivity = map(ready.copy(interactionAvailable = false))
        assertEquals(ParkedPresentationGate.PARKED, pausedActivity.vehicleGate)
        assertAllDisabled(pausedActivity)
    }

    @Test fun failuresBlockersAndTripEndKeepTheirExistingStatusPriority() {
        val all = ready.copy(connectionFailed = true, connected = false,
            session = session(PlaybackPresentationMode.MUSIC, blocked = true),
            endingTrip = true, tripEndPending = true, preparingStory = true)
        assertEquals(PlaybackPresentationStatus.CONNECTION_UNAVAILABLE, map(all).status)
        assertEquals(PlaybackPresentationStatus.CONNECTING, map(all.copy(connectionFailed = false)).status)
        val connected = all.copy(connectionFailed = false, connected = true)
        assertEquals(PlaybackPresentationStatus.BLOCKED, map(connected).status)
        val unblocked = connected.copy(session = session(PlaybackPresentationMode.MUSIC))
        assertEquals(PlaybackPresentationStatus.TRIP_ENDING, map(unblocked).status)
        assertEquals(PlaybackPresentationStatus.TRIP_END_UNCONFIRMED, map(unblocked.copy(tripEndPending = false)).status)
        assertEquals(PlaybackPresentationStatus.PREPARING_STORY, map(unblocked.copy(endingTrip = false)).status)
    }

    @Test fun loadedEmptyCatalogDoesNotHideExistingPausedOrMusicSession() {
        val empty = ready.copy(hasStories = false, hasMusic = false, hasSelectedStory = false)
        assertEquals(PlaybackPresentationStatus.NO_CONTENT, map(empty.copy(session = null)).status)
        assertEquals(PlaybackPresentationStatus.NO_CONTENT, map(empty.copy(session = session(PlaybackPresentationMode.IDLE))).status)
        assertEquals(PlaybackPresentationStatus.PAUSED, map(empty.copy(session = session(PlaybackPresentationMode.PAUSED))).status)
        assertEquals(PlaybackPresentationStatus.MUSIC, map(empty.copy(session = session(PlaybackPresentationMode.MUSIC))).status)
        assertEquals(PlaybackPresentationStatus.IDLE, map(empty.copy(session = null, catalogLoaded = false)).status)
    }

    @Test fun everyPlaybackModeMapsWithoutDependingOnRuntimeSnapshotTypes() {
        val expected = mapOf(
            PlaybackPresentationMode.IDLE to PlaybackPresentationStatus.IDLE,
            PlaybackPresentationMode.STORY to PlaybackPresentationStatus.STORY,
            PlaybackPresentationMode.MUSIC to PlaybackPresentationStatus.MUSIC,
            PlaybackPresentationMode.TALK_PENDING to PlaybackPresentationStatus.TALK_PENDING,
            PlaybackPresentationMode.PROMPT to PlaybackPresentationStatus.PROMPT,
            PlaybackPresentationMode.LISTENING to PlaybackPresentationStatus.LISTENING,
            PlaybackPresentationMode.GENERATING to PlaybackPresentationStatus.GENERATING,
            PlaybackPresentationMode.ANSWERING to PlaybackPresentationStatus.ANSWERING,
            PlaybackPresentationMode.RESUMING to PlaybackPresentationStatus.RESUMING,
            PlaybackPresentationMode.PAUSED to PlaybackPresentationStatus.PAUSED,
        )
        assertEquals(PlaybackPresentationMode.entries.toSet(), expected.keys)
        expected.forEach { (mode, status) -> assertEquals(status, map(ready.copy(session = session(mode))).status) }
    }

    @Test fun musicOnlyCatalogCanPlayWithoutStorySelectionOrRegeneration() {
        val state = map(ready.copy(hasStories = false, hasMusic = true, hasSelectedStory = false, session = null))
        assertTrue(state.playEnabled)
        assertTrue(state.stopEnabled)
        assertFalse(state.selectionEnabled)
        assertFalse(state.newStoryEnabled)
        assertFalse(state.nextEnabled)
        assertFalse(state.previousEnabled)
    }

    @Test fun commandBitsRemainIndependentAndNavigationRequiresAnExistingSession() {
        val noCommands = map(ready.copy(commands = PlaybackPresentationCommands()))
        assertFalse(noCommands.playEnabled)
        assertFalse(noCommands.stopEnabled)
        assertFalse(noCommands.nextEnabled)
        assertFalse(noCommands.previousEnabled)
        assertTrue(noCommands.selectionEnabled)
        assertTrue(noCommands.newStoryEnabled)
        assertTrue(noCommands.talkEnabled)
        assertFalse(map(ready.copy(commands = ready.commands.copy(play = false))).playEnabled)
        assertFalse(map(ready.copy(commands = ready.commands.copy(stop = false))).stopEnabled)
        assertFalse(map(ready.copy(commands = ready.commands.copy(next = false))).nextEnabled)
        assertFalse(map(ready.copy(commands = ready.commands.copy(previous = false))).previousEnabled)
        assertTrue(map(ready).nextEnabled)
        assertTrue(map(ready).previousEnabled)
        assertFalse(map(ready.copy(session = null)).nextEnabled)
        assertFalse(map(ready.copy(session = null)).previousEnabled)
    }

    @Test fun preparationAndTripEndKeepStopAvailableWithoutInventingNewTalkRules() {
        for (pending in listOf(ready.copy(preparingStory = true), ready.copy(endingTrip = true))) {
            val state = map(pending.copy(canPrepareStory = false))
            assertTrue(state.stopEnabled)
            assertFalse(state.playEnabled)
            assertFalse(state.selectionEnabled)
            assertFalse(state.nextEnabled)
            assertFalse(state.previousEnabled)
            assertFalse(state.newStoryEnabled)
            // Existing Talk presentation is separately governed by live capability/session mode.
            assertTrue(state.talkEnabled)
        }
    }

    @Test fun talkRespectsCapabilityPermissionRequestSessionClosureBlockersAndModes() {
        assertTrue(map(ready.copy(session = null)).talkEnabled)
        assertFalse(map(ready.copy(liveInputSupported = null)).talkEnabled)
        assertFalse(map(ready.copy(liveInputSupported = false)).talkEnabled)
        assertFalse(map(ready.copy(requestingMicrophone = true)).talkEnabled)
        assertFalse(map(ready.copy(session = session(PlaybackPresentationMode.STORY, closed = true))).talkEnabled)
        assertFalse(map(ready.copy(session = session(PlaybackPresentationMode.STORY, blocked = true))).talkEnabled)
        val inputModes = setOf(PlaybackPresentationMode.IDLE, PlaybackPresentationMode.PAUSED, PlaybackPresentationMode.STORY,
            PlaybackPresentationMode.MUSIC, PlaybackPresentationMode.TALK_PENDING, PlaybackPresentationMode.PROMPT,
            PlaybackPresentationMode.LISTENING)
        PlaybackPresentationMode.entries.forEach { mode ->
            assertEquals(mode in inputModes, map(ready.copy(session = session(mode))).talkEnabled)
        }
    }

    @Test fun unsupportedMicrophoneOverridesOnlyReadyNoticeAndNeverHidesExplicitOutcome() {
        assertEquals(PlaybackPresentationNotice.READY, map(ready.copy(liveInputSupported = null)).notice)
        assertEquals(PlaybackPresentationNotice.READY, map(ready.copy(liveInputSupported = true)).notice)
        assertEquals(PlaybackPresentationNotice.MICROPHONE_VERIFICATION_PENDING, map(ready.copy(liveInputSupported = false)).notice)
        for (notice in PlaybackPresentationNotice.entries.filter { it != PlaybackPresentationNotice.READY }) {
            assertEquals(notice, map(ready.copy(liveInputSupported = false, notice = notice)).notice)
        }
    }

    @Test fun blockedStatusDoesNotReplaceLiveMediaCommandAvailabilityWithNewAuthority() {
        val state = map(ready.copy(session = session(PlaybackPresentationMode.STORY, blocked = true), canPrepareStory = false))
        assertEquals(PlaybackPresentationStatus.BLOCKED, state.status)
        assertTrue(state.stopEnabled)
        assertTrue(state.playEnabled)
        assertTrue(state.nextEnabled)
        assertFalse(state.talkEnabled)
        // The adapter still rechecks real permissions and available commands when clicked.
    }

    private fun map(input: PlaybackPresentationInput) = PlaybackPresentationMapper.map(input)
    private fun session(mode: PlaybackPresentationMode, closed: Boolean = false, blocked: Boolean = false) =
        PlaybackPresentationSession(mode, closed, blocked)
    private fun assertAllDisabled(state: PlaybackPresentationState) {
        assertFalse(state.selectionEnabled || state.playEnabled || state.stopEnabled || state.nextEnabled ||
            state.previousEnabled || state.newStoryEnabled || state.talkEnabled)
    }
    private val ready = PlaybackPresentationInput(
        vehicleGate = ParkedPresentationGate.PARKED, interactionAvailable = true, connected = true,
        session = session(PlaybackPresentationMode.STORY), catalogLoaded = true,
        hasStories = true, hasSelectedStory = true, canPrepareStory = true, liveInputSupported = true,
        commands = PlaybackPresentationCommands(play = true, stop = true, next = true, previous = true),
    )
}
