package com.chikabell.app.ui.locations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.chikabell.app.domain.repository.LocationRepository
import com.chikabell.app.domain.repository.HistoryRepository
import com.chikabell.app.domain.repository.NotificationPresetRepository
import com.chikabell.app.domain.repository.RestoreDiagnosticsRepository
import com.chikabell.app.geofence.ReconcileGeofencesUseCase
import com.chikabell.app.geofence.ProcessGeofenceEventUseCase
import com.chikabell.app.geofence.CheckCurrentLocationUseCase
import com.chikabell.app.geofence.FindNearbySavedLocationsUseCase
import com.chikabell.app.permission.PermissionStateReader
import com.chikabell.app.permission.BackgroundRestrictionReader
import com.chikabell.app.geofence.ActivityStateSource
import com.chikabell.app.share.AddressCandidateProvider
import com.chikabell.app.share.UnavailableAddressCandidateProvider
import com.chikabell.app.notification.SendTestNotificationUseCase

class LocationsViewModelFactory(
    private val repository: LocationRepository,
    private val historyRepository: HistoryRepository,
    private val presetRepository: NotificationPresetRepository,
    private val restoreDiagnosticsRepository: RestoreDiagnosticsRepository,
    private val permissionStateReader: PermissionStateReader,
    private val backgroundRestrictionReader: BackgroundRestrictionReader,
    private val reconcileGeofencesUseCase: ReconcileGeofencesUseCase,
    private val processGeofenceEventUseCase: ProcessGeofenceEventUseCase,
    private val sendTestNotificationUseCase: SendTestNotificationUseCase,
    private val checkCurrentLocationUseCase: CheckCurrentLocationUseCase,
    private val findNearbySavedLocationsUseCase: FindNearbySavedLocationsUseCase,
    private val activityStateSource: ActivityStateSource,
    private val addressCandidateProvider: AddressCandidateProvider = UnavailableAddressCandidateProvider,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        return createViewModel(modelClass, extras.createSavedStateHandle())
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return createViewModel(modelClass, SavedStateHandle())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : ViewModel> createViewModel(modelClass: Class<T>, savedStateHandle: SavedStateHandle): T {
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
                sendTestNotificationUseCase = sendTestNotificationUseCase,
                checkCurrentLocationUseCase = checkCurrentLocationUseCase,
                findNearbySavedLocationsUseCase = findNearbySavedLocationsUseCase,
                activityStateSource = activityStateSource,
                savedStateHandle = savedStateHandle,
                addressCandidateProvider = addressCandidateProvider,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
