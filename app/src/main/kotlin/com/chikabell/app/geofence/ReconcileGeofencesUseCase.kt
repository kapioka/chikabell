package com.chikabell.app.geofence

import android.annotation.SuppressLint
import com.chikabell.app.domain.model.RegistrationStatus
import com.chikabell.app.domain.repository.LocationRepository
import com.chikabell.app.domain.repository.RegistrationDiagnosticsRepository
import com.chikabell.app.permission.PermissionStateReader

class ReconcileGeofencesUseCase(
    private val locationRepository: LocationRepository,
    private val permissionStateReader: PermissionStateReader,
    private val geofenceRegistrar: GeofenceRegistrar,
    private val registrationDiagnosticsRepository: RegistrationDiagnosticsRepository,
) {
    @SuppressLint("MissingPermission")
    suspend fun execute(source: String = "USER_ACTION"): ReconcileResult {
        val enabledLocations = locationRepository.getEnabledLocations()
        // Diagnostics must never prevent monitoring from being registered.
        val registrationGenerationId = try {
            registrationDiagnosticsRepository.start(source, enabledLocations.size)
        } catch (_: Exception) {
            null
        }
        val readiness = RegistrationReadinessEvaluator.evaluate(
            permissionSnapshot = permissionStateReader.read(),
            enabledLocationCount = enabledLocations.size,
        )

        if (readiness is RegistrationReadiness.Blocked) {
            enabledLocations.forEach { location ->
                locationRepository.markRegistrationStatus(
                    locationId = location.id,
                    status = RegistrationStatus.ERROR,
                    errorCode = "Blocked",
                    errorMessage = readiness.reason,
                    registeredAt = null,
                )
            }
            finishRejectedSafely(registrationGenerationId, RESULT_BLOCKED, "BLOCKED", readiness.reason)
            return ReconcileResult.Blocked(readiness.reason)
        }

        enabledLocations.forEach { location ->
            locationRepository.markRegistrationStatus(
                locationId = location.id,
                status = RegistrationStatus.PENDING,
                errorCode = null,
                errorMessage = null,
                registeredAt = null,
            )
        }

        return when (val result = geofenceRegistrar.register(enabledLocations)) {
            is GeofenceRegistrationResult.Registered -> {
                val now = System.currentTimeMillis()
                result.locationIds.forEach { id ->
                    locationRepository.markRegistrationStatus(
                        locationId = id,
                        status = RegistrationStatus.REGISTERED,
                        errorCode = null,
                        errorMessage = null,
                        registeredAt = now,
                        registrationGenerationId = registrationGenerationId,
                    )
                }
                finishAcceptedSafely(registrationGenerationId, result.locationIds.size)
                ReconcileResult.Registered(result.locationIds.size, registrationGenerationId)
            }
            is GeofenceRegistrationResult.Failed -> {
                enabledLocations.forEach { location ->
                    locationRepository.markRegistrationStatus(
                        locationId = location.id,
                        status = RegistrationStatus.ERROR,
                        errorCode = result.errorCode,
                        errorMessage = result.message,
                        registeredAt = null,
                        registrationGenerationId = null,
                    )
                }
                finishRejectedSafely(registrationGenerationId, RESULT_FAILED, result.errorCode, result.message)
                ReconcileResult.Failed(result.errorCode, result.message)
            }
        }
    }

    private suspend fun finishAcceptedSafely(registrationGenerationId: String?, acceptedCount: Int) {
        if (registrationGenerationId == null) return
        try {
            registrationDiagnosticsRepository.finishAccepted(registrationGenerationId, acceptedCount)
        } catch (_: Exception) {
            // Keep the already accepted geofence request independent from diagnostics persistence.
        }
    }

    private suspend fun finishRejectedSafely(
        registrationGenerationId: String?,
        result: String,
        errorCode: String?,
        message: String?,
    ) {
        if (registrationGenerationId == null) return
        try {
            registrationDiagnosticsRepository.finishRejected(registrationGenerationId, result, errorCode, message)
        } catch (_: Exception) {
            // A diagnostics write failure must not alter the registration result.
        }
    }

    private companion object {
        const val RESULT_FAILED = "REQUEST_FAILED"
        const val RESULT_BLOCKED = "BLOCKED"
    }
}

sealed interface ReconcileResult {
    data class Registered(val count: Int, val registrationGenerationId: String?) : ReconcileResult
    data class Blocked(val reason: String) : ReconcileResult
    data class Failed(val errorCode: String, val message: String) : ReconcileResult
}
