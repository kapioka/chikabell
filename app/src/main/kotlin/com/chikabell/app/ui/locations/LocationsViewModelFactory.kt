package com.chikabell.app.ui.locations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.chikabell.app.domain.repository.LocationRepository
import com.chikabell.app.domain.repository.HistoryRepository
import com.chikabell.app.domain.repository.NotificationPresetRepository
import com.chikabell.app.domain.repository.RestoreDiagnosticsRepository
import com.chikabell.app.geofence.ReconcileGeofencesUseCase
import com.chikabell.app.geofence.ProcessGeofenceEventUseCase
import com.chikabell.app.geofence.CheckCurrentLocationUseCase
import com.chikabell.app.permission.PermissionStateReader
import com.chikabell.app.permission.BackgroundRestrictionReader

class LocationsViewModelFactory(
    private val repository: LocationRepository,
    private val historyRepository: HistoryRepository,
    private val presetRepository: NotificationPresetRepository,
    private val restoreDiagnosticsRepository: RestoreDiagnosticsRepository,
    private val permissionStateReader: PermissionStateReader,
    private val backgroundRestrictionReader: BackgroundRestrictionReader,
    private val reconcileGeofencesUseCase: ReconcileGeofencesUseCase,
    private val processGeofenceEventUseCase: ProcessGeofenceEventUseCase,
    private val checkCurrentLocationUseCase: CheckCurrentLocationUseCase,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LocationsViewModel::class.java)) {
            return LocationsViewModel(
                repository = repository,
                historyRepository = historyRepository,
                presetRepository = presetRepository,
                restoreDiagnosticsRepository = restoreDiagnosticsRepository,
                permissionStateReader = permissionStateReader,
                backgroundRestrictionReader = backgroundRestrictionReader,
                reconcileGeofencesUseCase = reconcileGeofencesUseCase,
                processGeofenceEventUseCase = processGeofenceEventUseCase,
                checkCurrentLocationUseCase = checkCurrentLocationUseCase,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
