package com.petasos.app.presentation

import org.junit.Assert.*
import org.junit.Test

class TripPresentationMapperTest {
    @Test fun endingTripKeepsConsentWithdrawalButNeverEnablesConsentOrInterestEdits() {
        val on = TripPresentationMapper.map(ready.copy(endingTrip = true))
        assertTrue(on.locationToggleEnabled)
        assertFalse(on.interestsEnabled)
        assertFalse(on.permissionEnabled)
        assertFalse(TripPresentationMapper.map(ready.copy(endingTrip = true, locationEnabled = false)).locationToggleEnabled)
    }

    @Test fun unavailableControlsHidePermissionAndDisableAllActionsEvenWithLoadedData() {
        val state = TripPresentationMapper.map(ready.copy(available = false))
        assertFalse(state.locationToggleEnabled)
        assertFalse(state.permissionVisible)
        assertFalse(state.permissionEnabled)
        assertFalse(state.interestsEnabled)
        assertFalse(state.refreshEnabled)
        assertFalse(state.tripSelectionEnabled)
        assertFalse(state.endTripEnabled)
    }

    @Test fun permissionStageIsInformationalAndLoadingOrInFlightRequestsCannotEnablePermissionAction() {
        val pleos = TripPresentationMapper.map(ready.copy(hasPleosPermission = false))
        assertEquals(LocationPermissionStage.PLEOS, pleos.permissionStage)
        assertTrue(pleos.permissionVisible)
        val android = TripPresentationMapper.map(ready)
        assertEquals(LocationPermissionStage.ANDROID, android.permissionStage)
        assertFalse(TripPresentationMapper.map(ready.copy(hasLocationPermission = true)).permissionVisible)
        assertFalse(TripPresentationMapper.map(ready.copy(requestingPermission = true)).permissionEnabled)
        val loading = TripPresentationMapper.map(ready.copy(locationEnabled = null))
        assertFalse(loading.locationToggleEnabled)
        assertFalse(loading.permissionVisible)
        assertFalse(loading.interestsEnabled)
        assertTrue(loading.refreshEnabled) // Read-only history remains independently available.
    }

    @Test fun savingDisablesWritesWhilePreservingHistoryReads() {
        val state = TripPresentationMapper.map(ready.copy(saving = true))
        assertFalse(state.locationToggleEnabled)
        assertFalse(state.permissionEnabled)
        assertFalse(state.interestsEnabled)
        assertFalse(state.endTripEnabled)
        assertTrue(state.refreshEnabled)
        assertTrue(state.tripSelectionEnabled)
    }

    @Test fun endActionFollowsSelectedEndableTripAndPendingAcknowledgment() {
        assertTrue(TripPresentationMapper.map(ready).endTripEnabled)
        assertFalse(TripPresentationMapper.map(ready.copy(selectedTripIsEndable = false)).endTripEnabled)
        assertFalse(TripPresentationMapper.map(ready.copy(tripEndPending = true)).endTripEnabled)
        assertFalse(TripPresentationMapper.map(ready.copy(hasTrips = false, selectedTripIsEndable = false)).tripSelectionEnabled)
    }

    private val ready = TripPresentationInput(true, false, true, false, true, false, false, true, false, true)
}
