package com.chikabell.app.geofence

import com.chikabell.app.domain.model.SavedLocation
import java.util.Locale
import kotlinx.coroutines.CancellationException

class FindNearbySavedLocationsUseCase(
    private val currentLocationSource: CurrentLocationSource,
) {
    suspend fun execute(locations: List<SavedLocation>): NearbySavedLocationsResult {
        if (locations.isEmpty()) return NearbySavedLocationsResult.NoSavedLocations
        val currentLocation = try {
            currentLocationSource.readCandidateLocations().firstOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return NearbySavedLocationsResult.LocationUnavailable

        val candidates = locations.map { location ->
            NearbySavedLocationCandidate(
                location = location,
                distanceMeters = DistanceCalculator.distanceMeters(
                    fromLatitude = currentLocation.latitude,
                    fromLongitude = currentLocation.longitude,
                    toLatitude = location.latitude,
                    toLongitude = location.longitude,
                ),
            )
        }.sortedWith(
            compareBy<NearbySavedLocationCandidate>(NearbySavedLocationCandidate::distanceMeters)
                .thenBy { it.location.sortOrder }
                .thenBy { it.location.id },
        )

        return NearbySavedLocationsResult.Success(
            currentLocation = currentLocation,
            candidates = candidates,
            lowAccuracy = currentLocation.accuracyMeters == null ||
                currentLocation.accuracyMeters > LOW_ACCURACY_THRESHOLD_METERS,
        )
    }

    private companion object {
        const val LOW_ACCURACY_THRESHOLD_METERS = 75F
    }
}

sealed interface NearbySavedLocationsResult {
    data object NoSavedLocations : NearbySavedLocationsResult
    data object LocationUnavailable : NearbySavedLocationsResult
    data class Success(
        val currentLocation: CurrentLocation,
        val candidates: List<NearbySavedLocationCandidate>,
        val lowAccuracy: Boolean,
    ) : NearbySavedLocationsResult
}

data class NearbySavedLocationCandidate(
    val location: SavedLocation,
    val distanceMeters: Float,
)

enum class NearbyDistanceFilter {
    WITHIN_1_KM,
    WITHIN_5_KM,
    ALL,
}

data class NearbyCandidateSelection(
    val candidates: List<NearbySavedLocationCandidate>,
    val showingFarFallback: Boolean = false,
)

fun selectNearbyCandidates(
    candidates: List<NearbySavedLocationCandidate>,
    filter: NearbyDistanceFilter,
): NearbyCandidateSelection {
    val sorted = candidates.sortedWith(
        compareBy<NearbySavedLocationCandidate>(NearbySavedLocationCandidate::distanceMeters)
            .thenBy { it.location.sortOrder }
            .thenBy { it.location.id },
    )
    return when (filter) {
        NearbyDistanceFilter.WITHIN_1_KM -> NearbyCandidateSelection(
            candidates = sorted.filter { it.distanceMeters <= 1_000F }.take(MAX_FILTERED_CANDIDATES),
        )
        NearbyDistanceFilter.WITHIN_5_KM -> {
            val nearby = sorted.filter { it.distanceMeters <= 5_000F }.take(MAX_FILTERED_CANDIDATES)
            if (nearby.isNotEmpty()) {
                NearbyCandidateSelection(nearby)
            } else {
                NearbyCandidateSelection(
                    candidates = sorted.take(FAR_FALLBACK_CANDIDATES),
                    showingFarFallback = sorted.isNotEmpty(),
                )
            }
        }
        NearbyDistanceFilter.ALL -> NearbyCandidateSelection(sorted)
    }
}

fun formatApproxStraightLineDistance(distanceMeters: Float): String = when {
    distanceMeters < 1_000F -> "約${((distanceMeters / 50F).toInt() * 50).coerceAtLeast(50)}m"
    else -> "約${String.format(Locale.JAPAN, "%.1f", distanceMeters / 1_000F)}km"
}

private const val MAX_FILTERED_CANDIDATES = 5
private const val FAR_FALLBACK_CANDIDATES = 3
