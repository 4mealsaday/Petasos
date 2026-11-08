package com.petasos.app.presentation

/** Values read by the product adapter; neither this model nor its mapper reads or changes settings. */
data class TripPresentationInput(
    val available: Boolean,
    val saving: Boolean,
    val locationEnabled: Boolean?,
    val hasLocationPermission: Boolean,
    val hasPleosPermission: Boolean,
    val endingTrip: Boolean,
    val requestingPermission: Boolean,
    val hasTrips: Boolean,
    val tripEndPending: Boolean,
    val selectedTripIsEndable: Boolean,
)

enum class LocationPermissionStage { PLEOS, ANDROID }

/** Display state only. Product commands must still check their current host, consent and lifecycle. */
data class TripPresentationState(
    val locationRecordingEnabled: Boolean,
    val locationToggleEnabled: Boolean,
    val permissionVisible: Boolean,
    val permissionEnabled: Boolean,
    val permissionStage: LocationPermissionStage,
    val interestsEnabled: Boolean,
    val refreshEnabled: Boolean,
    val tripSelectionEnabled: Boolean,
    val endTripEnabled: Boolean,
)

object TripPresentationMapper {
    fun map(input: TripPresentationInput): TripPresentationState {
        val editable = input.available && !input.saving && input.locationEnabled != null && !input.endingTrip
        return TripPresentationState(
            locationRecordingEnabled = input.locationEnabled == true,
            // Ending a trip must not prevent withdrawal of existing location consent.
            locationToggleEnabled = input.available && !input.saving && input.locationEnabled != null &&
                (!input.endingTrip || input.locationEnabled == true),
            permissionVisible = input.available && input.locationEnabled == true && !input.hasLocationPermission,
            permissionEnabled = editable && !input.requestingPermission,
            permissionStage = if (input.hasPleosPermission) LocationPermissionStage.ANDROID else LocationPermissionStage.PLEOS,
            interestsEnabled = editable,
            refreshEnabled = input.available,
            tripSelectionEnabled = input.available && input.hasTrips,
            endTripEnabled = input.available && !input.saving && !input.tripEndPending && input.selectedTripIsEndable,
        )
    }
}
