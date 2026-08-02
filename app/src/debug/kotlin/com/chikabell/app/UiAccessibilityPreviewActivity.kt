package com.chikabell.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.chikabell.app.domain.model.NearbyState
import com.chikabell.app.domain.model.RegistrationStatus
import com.chikabell.app.domain.model.SavedLocation
import com.chikabell.app.domain.model.SourceType
import com.chikabell.app.domain.model.TransitionType
import com.chikabell.app.geofence.CurrentLocation
import com.chikabell.app.geofence.NearbyDistanceFilter
import com.chikabell.app.geofence.NearbySavedLocationCandidate
import com.chikabell.app.geofence.NearbySavedLocationsResult
import com.chikabell.app.share.ParsedSharedPlace
import com.chikabell.app.share.SharedPlaceConfidence
import com.chikabell.app.share.SharedPlaceParseMethod
import com.chikabell.app.ui.locations.LocationFormState
import com.chikabell.app.ui.locations.LocationsScreen
import com.chikabell.app.ui.locations.LocationsUiState
import com.chikabell.app.ui.locations.NearbySearchUiState
import com.chikabell.app.ui.theme.ChikaBellTheme

/** Debug-only surface used to inspect semantics and layout at 200% font scale. */
class UiAccessibilityPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            val density = LocalDensity.current
            val previewFontScale = if (intent.getBooleanExtra(EXTRA_FONT_SCALE_200, false)) {
                2f
            } else {
                density.fontScale
            }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = previewFontScale)) {
                ChikaBellTheme {
                    val showLocationRegistration = intent.getBooleanExtra(EXTRA_LOCATION_REGISTRATION, false)
                    LocationsScreen(
                        uiState = if (showLocationRegistration) {
                            accessibilityLocationRegistrationState
                        } else {
                            LocationsUiState(
                                locations = accessibilityPreviewLocations,
                                form = LocationFormState(),
                                nearbySearch = NearbySearchUiState.Results(accessibilityNearbyResult),
                                nearbyDistanceFilter = NearbyDistanceFilter.WITHIN_5_KM,
                            )
                        },
                        onFormChange = {},
                        onApplyPreset = {},
                        onSavePreset = {},
                        onResetPreset = {},
                        onSave = {},
                        onEdit = {},
                        onDelete = {},
                        onDeleteSelected = {},
                        onSetLocationsEnabled = { _, _ -> },
                        onClearSnooze = {},
                        onCancelEdit = {},
                        onRefreshPermissions = {},
                        onRequestForegroundLocation = {},
                        onRequestNotification = {},
                        onRequestActivityRecognition = {},
                        onOpenAppSettings = {},
                        onRegisterGeofences = {},
                        onRestoreGeofences = {},
                        onSendTestEnterEvent = {},
                        onFindNearbySavedLocations = {},
                        onCloseNearbySavedLocations = {},
                        onExportJson = {},
                        onExportCsv = {},
                        onImportJson = {},
                        onImportCsv = {},
                        onConfirmImport = {},
                        onCancelImport = {},
                        onHistoryFilterChange = {},
                        onDeleteAllHistory = {},
                        onSelectSharedPlaceCandidate = {},
                        onConfirmSharedPlaceCandidateAndSave = {},
                        onOpenSharedPlaceMap = {},
                        onOpenSharedPlaceQueryMap = {},
                        onSearchAddressCandidates = {},
                        onSelectAddressCandidate = {},
                        onMergePendingSharedPlace = {},
                        onStartNewFromPendingSharedPlace = {},
                        onDismissStartNewRegistration = {},
                        onConfirmStartNewFromPendingSharedPlace = {},
                        onDismissPendingSharedPlace = {},
                        onDismissDiscardRegistration = {},
                        onDiscardRegistration = {},
                    )
                }
            }
        }
    }

    private companion object {
        const val EXTRA_LOCATION_REGISTRATION = "location_registration"
        const val EXTRA_FONT_SCALE_200 = "font_scale_200"
        val accessibilityLocationRegistrationState = LocationsUiState(
            form = LocationFormState(
                name = "サンプルカフェ 梅田店",
                message = "友人と待ち合わせ。東側入口の近く",
                address = "大阪府大阪市北区サンプル茶屋町1-10-12",
                latitude = "34.696833",
                longitude = "135.511917",
                sourceType = SourceType.MAP_SHARE,
                sourceUrl = "https://www.google.com/maps/search/?api=1&query=34.696833,135.511917",
            ),
            sharedPlace = ParsedSharedPlace(
                nameCandidate = "サンプルカフェ 梅田店",
                latitude = 34.696833,
                longitude = 135.511917,
                sourceUrl = "https://www.google.com/maps/search/?api=1&query=34.696833,135.511917",
                rawText = null,
                parseMethod = SharedPlaceParseMethod.URL_QUERY_COORDINATES,
                confidence = SharedPlaceConfidence.NEEDS_CONFIRMATION,
            ),
        )
        val accessibilityPreviewLocation = SavedLocation(
            id = "accessibility-preview",
            name = "文字拡大で確認する登録地点",
            message = "長いメモでも操作部品と重ならないことを確認します",
            latitude = 0.0,
            longitude = 0.0,
            radiusMeters = 400,
            transitionType = TransitionType.DWELL,
            loiteringDelayMs = 60_000,
            cooldownMinutes = 720,
            enabled = true,
            sourceType = SourceType.MANUAL,
            sourceUrl = null,
            sourceText = null,
            createdAt = 0L,
            updatedAt = 0L,
            lastNotifiedAt = null,
            lastEventAt = null,
            registrationStatus = RegistrationStatus.REGISTERED,
            registrationErrorCode = null,
            registrationErrorMessage = null,
            lastRegisteredAt = null,
            sortOrder = 0L,
            nearbyState = NearbyState.MONITORING,
        )
        val accessibilityPreviewLocations = listOf(
            accessibilityPreviewLocation,
            accessibilityPreviewLocation.copy(
                id = "accessibility-preview-disabled",
                name = "通知を休止している候補地点",
                message = "候補一覧では通知オフと明示します",
                enabled = false,
                sortOrder = 1L,
            ),
        )
        val accessibilityNearbyResult = NearbySavedLocationsResult.Success(
            currentLocation = CurrentLocation(
                latitude = 0.0,
                longitude = 0.0,
                accuracyMeters = 90F,
                provider = "preview",
                ageMillis = 0L,
            ),
            candidates = listOf(
                NearbySavedLocationCandidate(accessibilityPreviewLocations[0], 820F),
                NearbySavedLocationCandidate(accessibilityPreviewLocations[1], 3_240F),
            ),
            lowAccuracy = true,
        )
    }
}
