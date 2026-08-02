package com.chikabell.app.geofence

import android.annotation.SuppressLint
import com.chikabell.app.domain.repository.LocationRepository
import com.chikabell.app.permission.PermissionStateReader

/** Best-effort in-place ring refresh after a material activity-band change. */
class GeofenceRadiusRefreshUseCase(
    private val locationRepository: LocationRepository,
    private val permissionStateReader: PermissionStateReader,
    private val geofenceRegistrar: GeofenceRegistrar,
) {
    @SuppressLint("MissingPermission")
    suspend fun execute(): GeofenceRegistrationResult? {
        val enabledLocations = locationRepository.getEnabledLocations()
        val readiness = RegistrationReadinessEvaluator.evaluate(
            permissionSnapshot = permissionStateReader.read(),
            enabledLocationCount = enabledLocations.size,
        )
        if (readiness !is RegistrationReadiness.Ready) return null
        val refreshableLocations = enabledLocations.filter { location ->
            NearbyVerificationPolicy.shouldRefreshLeadRingForActivity(
                state = location.nearbyState,
                verificationReason = location.lastVerificationReason,
            )
        }
        return geofenceRegistrar.refreshRadii(refreshableLocations)
    }
}
