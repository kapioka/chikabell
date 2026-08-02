package com.chikabell.app.geofence

import com.chikabell.app.domain.model.RegistrationStatus
import com.chikabell.app.domain.repository.LocationRepository

class CheckCurrentLocationUseCase(
    private val locationRepository: LocationRepository,
    private val currentLocationReader: CurrentLocationSource,
) {
    suspend fun execute(): CurrentLocationCheckResult {
        val currentLocations = try {
            currentLocationReader.readCandidateLocations()
        } catch (_: SecurityException) {
            return CurrentLocationCheckResult.LocationUnavailable
        }
        val primaryLocation = currentLocations.firstOrNull()
            ?: return CurrentLocationCheckResult.LocationUnavailable

        val activeLocations = locationRepository.getEnabledLocations()
            .filter { it.registrationStatus == RegistrationStatus.REGISTERED }

        if (activeLocations.isEmpty()) {
            return CurrentLocationCheckResult.NoRegisteredLocations(primaryLocation)
        }

        val matchingCandidate = currentLocations.firstNotNullOfOrNull { currentLocation ->
            val matches = activeLocations.filter { location ->
                DistanceCalculator.isWithinRadius(
                    fromLatitude = currentLocation.latitude,
                    fromLongitude = currentLocation.longitude,
                    toLatitude = location.latitude,
                    toLongitude = location.longitude,
                    radiusMeters = location.radiusMeters,
                )
            }
            if (matches.isEmpty()) null else currentLocation to matches
        }

        if (matchingCandidate == null) {
            val nearestDistance = currentLocations.minOfOrNull { currentLocation ->
                activeLocations.minOf { location ->
                    DistanceCalculator.distanceMeters(
                        fromLatitude = currentLocation.latitude,
                        fromLongitude = currentLocation.longitude,
                        toLatitude = location.latitude,
                        toLongitude = location.longitude,
                    )
                }
            }
            return CurrentLocationCheckResult.OutsideRegisteredAreas(
                currentLocation = primaryLocation,
                nearestDistanceMeters = nearestDistance,
            )
        }

        val (currentLocation, matches) = matchingCandidate
        return CurrentLocationCheckResult.InsideRegisteredAreas(
            currentLocation = currentLocation,
            count = matches.size,
        )
    }
}

sealed interface CurrentLocationCheckResult {
    data object LocationUnavailable : CurrentLocationCheckResult
    data class NoRegisteredLocations(val currentLocation: CurrentLocation) : CurrentLocationCheckResult
    data class OutsideRegisteredAreas(
        val currentLocation: CurrentLocation,
        val nearestDistanceMeters: Float?,
    ) : CurrentLocationCheckResult
    data class InsideRegisteredAreas(
        val currentLocation: CurrentLocation,
        val count: Int,
    ) : CurrentLocationCheckResult
}
