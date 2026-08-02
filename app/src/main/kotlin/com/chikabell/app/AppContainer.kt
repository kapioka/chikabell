package com.chikabell.app

import android.content.Context
import com.chikabell.app.data.database.AppDatabase
import com.chikabell.app.data.repository.HistoryRepositoryImpl
import com.chikabell.app.data.repository.LocationRepositoryImpl
import com.chikabell.app.data.repository.NotificationPresetRepositoryImpl
import com.chikabell.app.data.repository.RestoreDiagnosticsRepositoryImpl
import com.chikabell.app.data.repository.RegistrationDiagnosticsRepositoryImpl
import com.chikabell.app.domain.repository.HistoryRepository
import com.chikabell.app.domain.repository.LocationRepository
import com.chikabell.app.domain.repository.NotificationPresetRepository
import com.chikabell.app.domain.repository.RestoreDiagnosticsRepository
import com.chikabell.app.domain.repository.RegistrationDiagnosticsRepository
import com.chikabell.app.geofence.GeofencePendingIntentFactory
import com.chikabell.app.geofence.GeofenceRegistrar
import com.chikabell.app.geofence.CheckCurrentLocationUseCase
import com.chikabell.app.geofence.CurrentLocationReader
import com.chikabell.app.geofence.FindNearbySavedLocationsUseCase
import com.chikabell.app.geofence.ProcessGeofenceEventUseCase
import com.chikabell.app.geofence.ReconcileGeofencesUseCase
import com.chikabell.app.geofence.RestoreGeofencesCoordinator
import com.chikabell.app.geofence.ActivityStateStore
import com.chikabell.app.geofence.ActivityRecognitionRegistrar
import com.chikabell.app.geofence.GeofenceRadiusRefreshUseCase
import com.chikabell.app.notification.NearbyNotificationPoster
import com.chikabell.app.notification.NotificationChannels
import com.chikabell.app.notification.SendTestNotificationUseCase
import com.chikabell.app.permission.PermissionStateReader
import com.chikabell.app.permission.BackgroundRestrictionReader

class AppContainer(context: Context) {
    private val database = AppDatabase.create(context)
    private val appContext = context.applicationContext

    val locationRepository: LocationRepository = LocationRepositoryImpl(
        locationDao = database.locationDao(),
    )
    val historyRepository: HistoryRepository = HistoryRepositoryImpl(
        historyDao = database.notificationHistoryDao(),
    )
    val notificationPresetRepository: NotificationPresetRepository = NotificationPresetRepositoryImpl(
        presetDao = database.notificationPresetDao(),
    )
    val restoreDiagnosticsRepository: RestoreDiagnosticsRepository = RestoreDiagnosticsRepositoryImpl(
        restoreAttemptDao = database.geofenceRestoreAttemptDao(),
        healthCheckDao = database.geofenceHealthCheckDao(),
    )
    val registrationDiagnosticsRepository: RegistrationDiagnosticsRepository = RegistrationDiagnosticsRepositoryImpl(
        registrationAttemptDao = database.geofenceRegistrationAttemptDao(),
    )
    val permissionStateReader = PermissionStateReader(appContext)
    val backgroundRestrictionReader = BackgroundRestrictionReader(appContext)
    val activityStateStore = ActivityStateStore(appContext)
    val activityRecognitionRegistrar = ActivityRecognitionRegistrar(appContext)
    private val notificationPoster = NearbyNotificationPoster(appContext)
    val sendTestNotificationUseCase = SendTestNotificationUseCase(notificationPoster)
    private val geofenceRegistrar = GeofenceRegistrar(
        context = appContext,
        pendingIntentFactory = GeofencePendingIntentFactory(appContext),
        activityStateSource = activityStateStore,
    )

    init {
        NotificationChannels.ensureCreated(appContext)
    }

    val reconcileGeofencesUseCase = ReconcileGeofencesUseCase(
        locationRepository = locationRepository,
        permissionStateReader = permissionStateReader,
        geofenceRegistrar = geofenceRegistrar,
        registrationDiagnosticsRepository = registrationDiagnosticsRepository,
    )
    val geofenceRadiusRefreshUseCase = GeofenceRadiusRefreshUseCase(
        locationRepository = locationRepository,
        permissionStateReader = permissionStateReader,
        geofenceRegistrar = geofenceRegistrar,
    )
    val processGeofenceEventUseCase = ProcessGeofenceEventUseCase(
        locationRepository = locationRepository,
        historyRepository = historyRepository,
        notificationPoster = notificationPoster,
        currentLocationReader = CurrentLocationReader(appContext),
        activityStateStore = activityStateStore,
        verificationGeofenceGateway = geofenceRegistrar,
    )
    val checkCurrentLocationUseCase = CheckCurrentLocationUseCase(
        locationRepository = locationRepository,
        currentLocationReader = CurrentLocationReader(appContext),
    )
    val findNearbySavedLocationsUseCase = FindNearbySavedLocationsUseCase(
        currentLocationSource = CurrentLocationReader(appContext),
    )
    val restoreGeofencesCoordinator = RestoreGeofencesCoordinator(
        locationRepository = locationRepository,
        historyRepository = historyRepository,
        diagnosticsRepository = restoreDiagnosticsRepository,
        registrationDiagnosticsRepository = registrationDiagnosticsRepository,
        reconcileGeofencesUseCase = reconcileGeofencesUseCase,
        permissionStateReader = permissionStateReader,
    )
}
