package com.chikabell.app.geofence

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.RequiresPermission
import com.chikabell.app.domain.model.LocationNotificationDefaults
import com.chikabell.app.domain.model.SavedLocation
import com.chikabell.app.domain.model.TransitionType
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeofenceRegistrar(
    context: Context,
    private val pendingIntentFactory: GeofencePendingIntentFactory,
    private val activityStateSource: ActivityStateSource,
) : VerificationGeofenceGateway {
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun register(locations: List<SavedLocation>): GeofenceRegistrationResult {
        if (locations.isEmpty()) {
            removeAll()
            return GeofenceRegistrationResult.Registered(emptyList())
        }

        return try {
            removeAll()
            addOrReplace(locations)
            GeofenceRegistrationResult.Registered(locations.map { it.id })
        } catch (exception: Exception) {
            GeofenceRegistrationResult.Failed(
                errorCode = exception::class.java.simpleName,
                message = exception.message ?: "Geofence registration failed",
            )
        }
    }

    /** Replaces existing request IDs in place, avoiding a remove/add monitoring gap. */
    suspend fun refreshRadii(locations: List<SavedLocation>): GeofenceRegistrationResult {
        if (locations.isEmpty()) return GeofenceRegistrationResult.Registered(emptyList())
        return try {
            addOrReplace(locations)
            GeofenceRegistrationResult.Registered(locations.map { it.id })
        } catch (exception: Exception) {
            GeofenceRegistrationResult.Failed(
                errorCode = exception::class.java.simpleName,
                message = exception.message ?: "Geofence radius refresh failed",
            )
        }
    }

    override suspend fun armConfirmationRing(location: SavedLocation): Boolean {
        return replaceOne(
            location = location,
            radiusMeters = location.radiusMeters.toFloat(),
            initialTrigger = INITIAL_TRIGGER_ENTER_OR_DWELL,
        )
    }

    override suspend fun restoreLeadRing(location: SavedLocation): Boolean {
        val registrationBand = NearbyVerificationPolicy.registrationMotionBand(
            activityStateSource.read().state,
        )
        return replaceOne(
            location = location,
            radiusMeters = NearbyVerificationPolicy.preVerificationRadiusMeters(
                location.radiusMeters,
                registrationBand,
            ).toFloat(),
            initialTrigger = GeofencingRequest.INITIAL_TRIGGER_EXIT,
        )
    }

    private suspend fun replaceOne(
        location: SavedLocation,
        radiusMeters: Float,
        initialTrigger: Int,
    ): Boolean = runCatching {
        addOrReplace(
            geofences = listOf(location.toGeofence(radiusMeters)),
            initialTrigger = initialTrigger,
        )
        true
    }.getOrDefault(false)

    private suspend fun addOrReplace(locations: List<SavedLocation>) {
        val registrationBand = NearbyVerificationPolicy.registrationMotionBand(
            activityStateSource.read().state,
        )
        addOrReplace(
            geofences = locations.map { location ->
                location.toGeofence(
                    NearbyVerificationPolicy.preVerificationRadiusMeters(
                        location.radiusMeters,
                        registrationBand,
                    ).toFloat(),
                )
            },
            initialTrigger = INITIAL_TRIGGER_ENTER_OR_DWELL,
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun addOrReplace(geofences: List<Geofence>, initialTrigger: Int) {
        withContext(Dispatchers.IO) {
            Tasks.await(
                geofencingClient.addGeofences(
                    GeofencingRequest.Builder()
                        .setInitialTrigger(initialTrigger)
                        .addGeofences(geofences)
                        .build(),
                    pendingIntentFactory.create(),
                ),
            )
        }
    }

    suspend fun removeAll() {
        try {
            withContext(Dispatchers.IO) {
                Tasks.await(geofencingClient.removeGeofences(pendingIntentFactory.create()))
            }
        } catch (_: Exception) {
            // Removing a non-existing PendingIntent registration is safe to ignore here.
        }
    }

    private companion object {
        val INITIAL_TRIGGER_ENTER_OR_DWELL =
            GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL
    }
}

interface VerificationGeofenceGateway {
    suspend fun armConfirmationRing(location: SavedLocation): Boolean
    suspend fun restoreLeadRing(location: SavedLocation): Boolean
}

sealed interface GeofenceRegistrationResult {
    data class Registered(val locationIds: List<String>) : GeofenceRegistrationResult
    data class Failed(val errorCode: String, val message: String) : GeofenceRegistrationResult
}

private fun SavedLocation.toGeofence(radiusMeters: Float): Geofence {
    val transition = when (transitionType) {
        TransitionType.ENTER ->
            Geofence.GEOFENCE_TRANSITION_ENTER or
                Geofence.GEOFENCE_TRANSITION_EXIT
        TransitionType.DWELL ->
            Geofence.GEOFENCE_TRANSITION_ENTER or
                Geofence.GEOFENCE_TRANSITION_DWELL or
                Geofence.GEOFENCE_TRANSITION_EXIT
        TransitionType.EXIT -> Geofence.GEOFENCE_TRANSITION_EXIT
    }
    return Geofence.Builder()
        .setRequestId(id)
        .setCircularRegion(
            latitude,
            longitude,
            radiusMeters,
        )
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .setTransitionTypes(transition)
        .setNotificationResponsiveness(60_000)
        .apply {
            if (transitionType == TransitionType.DWELL) {
                setLoiteringDelay(loiteringDelayMs ?: LocationNotificationDefaults.LOITERING_DELAY_MS)
            }
        }
        .build()
}
