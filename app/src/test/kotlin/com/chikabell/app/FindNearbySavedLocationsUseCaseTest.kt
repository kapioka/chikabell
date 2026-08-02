package com.chikabell.app

import com.chikabell.app.domain.model.NearbyState
import com.chikabell.app.domain.model.RegistrationStatus
import com.chikabell.app.domain.model.SavedLocation
import com.chikabell.app.domain.model.SourceType
import com.chikabell.app.domain.model.TransitionType
import com.chikabell.app.geofence.CurrentLocation
import com.chikabell.app.geofence.CurrentLocationSource
import com.chikabell.app.geofence.FindNearbySavedLocationsUseCase
import com.chikabell.app.geofence.NearbyDistanceFilter
import com.chikabell.app.geofence.NearbySavedLocationCandidate
import com.chikabell.app.geofence.NearbySavedLocationsResult
import com.chikabell.app.geofence.formatApproxStraightLineDistance
import com.chikabell.app.geofence.selectNearbyCandidates
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindNearbySavedLocationsUseCaseTest {
    @Test
    fun executeSortsAllLocationsByStraightLineDistanceAndKeepsDisabledLocations() = runBlocking {
        val source = FakeNearbyLocationSource(listOf(currentLocation()))
        val locations = listOf(
            location("far", latitude = 0.02, enabled = true),
            location("near-disabled", latitude = 0.001, enabled = false),
            location("middle-error", latitude = 0.01, status = RegistrationStatus.ERROR),
        )

        val result = FindNearbySavedLocationsUseCase(source).execute(locations) as NearbySavedLocationsResult.Success

        assertEquals(listOf("near-disabled", "middle-error", "far"), result.candidates.map { it.location.id })
        assertEquals(1, source.readCount)
        assertFalse(result.lowAccuracy)
    }

    @Test
    fun noSavedLocationsDoesNotReadCurrentLocation() = runBlocking {
        val source = FakeNearbyLocationSource(listOf(currentLocation()))

        assertEquals(NearbySavedLocationsResult.NoSavedLocations, FindNearbySavedLocationsUseCase(source).execute(emptyList()))
        assertEquals(0, source.readCount)
    }

    @Test
    fun missingCurrentLocationReturnsUnavailable() = runBlocking {
        val result = FindNearbySavedLocationsUseCase(FakeNearbyLocationSource(emptyList()))
            .execute(listOf(location("one", latitude = 0.001)))

        assertEquals(NearbySavedLocationsResult.LocationUnavailable, result)
    }

    @Test
    fun sourceFailureReturnsUnavailableInsteadOfLeavingLoadingState() = runBlocking {
        val result = FindNearbySavedLocationsUseCase(
            FakeNearbyLocationSource(emptyList(), IllegalStateException("test")),
        ).execute(listOf(location("one", latitude = 0.001)))

        assertEquals(NearbySavedLocationsResult.LocationUnavailable, result)
    }

    @Test
    fun lowAccuracyIsReported() = runBlocking {
        val source = FakeNearbyLocationSource(listOf(currentLocation(accuracyMeters = 120F)))
        val result = FindNearbySavedLocationsUseCase(source)
            .execute(listOf(location("one", latitude = 0.001))) as NearbySavedLocationsResult.Success

        assertTrue(result.lowAccuracy)
    }

    @Test
    fun filtersLimitCandidatesAndFiveKilometerFilterFallsBackToNearestThree() {
        val candidates = listOf(200F, 800F, 1_200F, 4_900F, 5_100F, 6_000F).mapIndexed { index, distance ->
            NearbySavedLocationCandidate(location("id-$index", latitude = 0.0), distance)
        }

        assertEquals(2, selectNearbyCandidates(candidates, NearbyDistanceFilter.WITHIN_1_KM).candidates.size)
        assertEquals(4, selectNearbyCandidates(candidates, NearbyDistanceFilter.WITHIN_5_KM).candidates.size)
        assertEquals(6, selectNearbyCandidates(candidates, NearbyDistanceFilter.ALL).candidates.size)

        val fallback = selectNearbyCandidates(candidates.map { it.copy(distanceMeters = it.distanceMeters + 6_000F) }, NearbyDistanceFilter.WITHIN_5_KM)
        assertTrue(fallback.showingFarFallback)
        assertEquals(3, fallback.candidates.size)
    }

    @Test
    fun fiveKilometerFilterShowsOnlyNearestFive() {
        val candidates = (1..8).map { index ->
            NearbySavedLocationCandidate(location("id-$index", latitude = 0.0), index * 100F)
        }

        assertEquals(listOf("id-1", "id-2", "id-3", "id-4", "id-5"),
            selectNearbyCandidates(candidates, NearbyDistanceFilter.WITHIN_5_KM).candidates.map { it.location.id })
    }

    @Test
    fun distanceTextUsesReadableMetersAndKilometers() {
        assertEquals("約750m", formatApproxStraightLineDistance(782F))
        assertEquals("約3.2km", formatApproxStraightLineDistance(3_240F))
    }
}

private class FakeNearbyLocationSource(
    private val candidates: List<CurrentLocation>,
    private val failure: Exception? = null,
) : CurrentLocationSource {
    var readCount: Int = 0

    override suspend fun readCurrentLocation(): CurrentLocation? = candidates.firstOrNull()

    override suspend fun readCandidateLocations(): List<CurrentLocation> {
        failure?.let { throw it }
        readCount += 1
        return candidates
    }

    override suspend fun readVerificationLocations(
        intervalMillis: Long,
        maxDurationMillis: Long,
        maxSamples: Int,
        stopWhen: (List<CurrentLocation>) -> Boolean,
    ): List<CurrentLocation> = emptyList()
}

private fun currentLocation(accuracyMeters: Float = 20F) = CurrentLocation(
    latitude = 0.0,
    longitude = 0.0,
    accuracyMeters = accuracyMeters,
    provider = "test",
    ageMillis = 0L,
    speedMetersPerSecond = 0F,
    elapsedRealtimeMillis = 1_000L,
)

private fun location(
    id: String,
    latitude: Double,
    enabled: Boolean = true,
    status: RegistrationStatus = RegistrationStatus.REGISTERED,
) = SavedLocation(
    id = id,
    name = id,
    message = "memo",
    latitude = latitude,
    longitude = 0.0,
    radiusMeters = 300,
    transitionType = TransitionType.DWELL,
    loiteringDelayMs = 60_000,
    cooldownMinutes = 720,
    enabled = enabled,
    sourceType = SourceType.MANUAL,
    sourceUrl = null,
    sourceText = null,
    createdAt = 0L,
    updatedAt = 0L,
    lastNotifiedAt = null,
    lastEventAt = null,
    registrationStatus = status,
    registrationErrorCode = null,
    registrationErrorMessage = null,
    lastRegisteredAt = null,
    sortOrder = 0L,
    nearbyState = NearbyState.MONITORING,
)
