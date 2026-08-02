package com.chikabell.app.domain.repository

import com.chikabell.app.domain.model.LocationDraft
import com.chikabell.app.domain.model.RegistrationStatus
import com.chikabell.app.domain.model.SavedLocation
import com.chikabell.app.domain.model.NearbyState
import kotlinx.coroutines.flow.Flow
import com.chikabell.app.importexport.LocationImportCandidate

interface LocationRepository {
    fun observeLocations(): Flow<List<SavedLocation>>
    suspend fun getEnabledLocations(): List<SavedLocation>
    suspend fun getLocationById(id: String): SavedLocation?
    suspend fun addLocation(draft: LocationDraft)
    suspend fun addImportedLocations(candidates: List<LocationImportCandidate>)
    suspend fun updateLocation(location: SavedLocation, draft: LocationDraft)
    suspend fun updateLocationTags(location: SavedLocation, tags: List<String>)
    suspend fun deleteLocation(location: SavedLocation)
    suspend fun deleteLocations(ids: Set<String>)
    suspend fun setLocationsEnabled(ids: Set<String>, enabled: Boolean)
    suspend fun markRegistrationStatus(
        locationId: String,
        status: RegistrationStatus,
        errorCode: String?,
        errorMessage: String?,
        registeredAt: Long?,
        registrationGenerationId: String? = null,
    )
    suspend fun markInactive(locationId: String)
    suspend fun markLastEvent(locationId: String, eventAt: Long)
    suspend fun markLastNotified(locationId: String, notifiedAt: Long)
    suspend fun updateNearbyState(
        locationId: String,
        state: NearbyState,
        snoozedUntil: Long? = null,
        verifiedAt: Long? = null,
        lastValidLocationAt: Long? = null,
        verificationReason: String? = null,
        suppressionReason: String? = null,
        accuracyMeters: Float? = null,
        speedMetersPerSecond: Float? = null,
        notificationDistanceMeters: Float? = null,
    )
    suspend fun claimVerification(locationId: String, verifiedAt: Long, reason: String): Boolean
    suspend fun snoozeLocations(locationIds: Set<String>, snoozedUntil: Long)
    suspend fun clearSnooze(locationId: String)
    suspend fun refreshExpiredSnoozes(referenceTime: Long = System.currentTimeMillis()): Int
    suspend fun recoverStaleVerifications(referenceTime: Long = System.currentTimeMillis()): Int
}
