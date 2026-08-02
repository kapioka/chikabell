package com.chikabell.app.ui.locations

import com.chikabell.app.domain.model.LocationNotificationDefaults
import com.chikabell.app.domain.model.SavedLocation
import com.chikabell.app.domain.model.NotificationHistory
import com.chikabell.app.domain.model.NotificationPreset
import com.chikabell.app.domain.model.GeofenceRestoreAttempt
import com.chikabell.app.permission.PermissionSnapshot
import com.chikabell.app.permission.BackgroundRestrictionSnapshot
import com.chikabell.app.domain.model.SourceType
import com.chikabell.app.importexport.LocationImportPreview
import com.chikabell.app.domain.model.HistoryFilter
import com.chikabell.app.domain.model.LocationTag
import com.chikabell.app.geofence.ActivitySnapshot
import com.chikabell.app.geofence.NearbyDistanceFilter
import com.chikabell.app.geofence.NearbySavedLocationsResult
import com.chikabell.app.share.ParsedSharedPlace
import com.chikabell.app.share.AddressCoordinateCandidate

data class LocationsUiState(
    val locations: List<SavedLocation> = emptyList(),
    val histories: List<NotificationHistory> = emptyList(),
    val historyFilter: HistoryFilter = HistoryFilter(),
    val presets: List<NotificationPreset> = emptyList(),
    val latestRestoreAttempt: GeofenceRestoreAttempt? = null,
    val selectedPresetId: String = "walk",
    val form: LocationFormState = LocationFormState(),
    val editingLocation: SavedLocation? = null,
    val validationMessage: String? = null,
    val permissionSnapshot: PermissionSnapshot? = null,
    val backgroundRestriction: BackgroundRestrictionSnapshot? = null,
    val activitySnapshot: ActivitySnapshot? = null,
    val registrationMessage: String? = null,
    val isRegisteringGeofences: Boolean = false,
    val isSaving: Boolean = false,
    val saveSuccessId: Long = 0L,
    val importPreview: LocationImportPreview? = null,
    val isImporting: Boolean = false,
    val nearbySearch: NearbySearchUiState = NearbySearchUiState.Hidden,
    val nearbyDistanceFilter: NearbyDistanceFilter = NearbyDistanceFilter.WITHIN_5_KM,
    val sharedPlace: ParsedSharedPlace? = null,
    val sharedPlaceCandidateConfirmed: Boolean = false,
    val sharedPlaceCoordinatesManuallyEdited: Boolean = false,
    val sharedRegistrationSession: SharedRegistrationSession? = null,
    val showDiscardRegistrationDialog: Boolean = false,
    val showStartNewRegistrationDialog: Boolean = false,
    val isAddressSearching: Boolean = false,
    val addressCandidates: List<AddressCoordinateCandidate> = emptyList(),
    val addressSearchMessage: String? = null,
) {
    val tags: List<LocationTag>
        get() = locations
            .flatMap(SavedLocation::tags)
            .groupBy { it.normalizedName }
            .map { (_, tags) -> tags.first().copy(usageCount = tags.size) }
            .sortedWith(compareByDescending<LocationTag> { it.usageCount }.thenBy { it.name })
}

sealed interface NearbySearchUiState {
    data object Hidden : NearbySearchUiState
    data object Loading : NearbySearchUiState
    data object PermissionRequired : NearbySearchUiState
    data object LocationServicesDisabled : NearbySearchUiState
    data object LocationUnavailable : NearbySearchUiState
    data object NoSavedLocations : NearbySearchUiState
    data class Results(val result: NearbySavedLocationsResult.Success) : NearbySearchUiState
}

data class LocationFormState(
    val name: String = "",
    val message: String = "",
    val address: String = "",
    val latitude: String = "",
    val longitude: String = "",
    val radiusMeters: String = "300",
    val loiteringDelaySeconds: String = LocationNotificationDefaults.LOITERING_DELAY_SECONDS.toString(),
    val cooldownHours: String = CooldownHours.format(LocationNotificationDefaults.COOLDOWN_MINUTES),
    val enabled: Boolean = true,
    val sourceType: SourceType = SourceType.MANUAL,
    val sourceUrl: String? = null,
    val sourceText: String? = null,
    val tags: List<String> = emptyList(),
    val tagInput: String = "",
)
