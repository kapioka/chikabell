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
    val registrationMessage: String? = null,
    val isRegisteringGeofences: Boolean = false,
    val isSaving: Boolean = false,
    val importPreview: LocationImportPreview? = null,
    val isImporting: Boolean = false,
) {
    val tags: List<LocationTag>
        get() = locations
            .flatMap(SavedLocation::tags)
            .groupBy { it.normalizedName }
            .map { (_, tags) -> tags.first().copy(usageCount = tags.size) }
            .sortedWith(compareByDescending<LocationTag> { it.usageCount }.thenBy { it.name })
}

data class LocationFormState(
    val name: String = "",
    val message: String = "",
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
