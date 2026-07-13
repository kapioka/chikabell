package com.chikabell.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.chikabell.app.ui.locations.LocationFormState
import com.chikabell.app.ui.locations.LocationsScreen
import com.chikabell.app.ui.locations.LocationsUiState
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
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ChikaBellTheme {
                    LocationsScreen(
                        uiState = LocationsUiState(form = LocationFormState()),
                        onFormChange = {},
                        onApplyPreset = {},
                        onSavePreset = {},
                        onResetPreset = {},
                        onSave = {},
                        onEdit = {},
                        onDelete = {},
                        onDeleteSelected = {},
                        onSetLocationsEnabled = { _, _ -> },
                        onCancelEdit = {},
                        onRefreshPermissions = {},
                        onRequestForegroundLocation = {},
                        onRequestNotification = {},
                        onOpenAppSettings = {},
                        onRegisterGeofences = {},
                        onRestoreGeofences = {},
                        onSendTestEnterEvent = {},
                        onCheckCurrentLocation = {},
                        onExportJson = {},
                        onExportCsv = {},
                        onImportJson = {},
                        onImportCsv = {},
                        onConfirmImport = {},
                        onCancelImport = {},
                        onHistoryFilterChange = {},
                        onDeleteAllHistory = {},
                    )
                }
            }
        }
    }
}
