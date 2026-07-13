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
) {
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
            withContext(Dispatchers.IO) {
                Tasks.await(
                    geofencingClient.addGeofences(
                        GeofencingRequest.Builder()
                            .setInitialTrigger(
                                GeofencingRequest.INITIAL_TRIGGER_ENTER or
                                    GeofencingRequest.INITIAL_TRIGGER_DWELL,
                            )
                            .addGeofences(locations.map { it.toGeofence() })
                            .build(),
                        pendingIntentFactory.create(),
                    ),
                )
            }
            GeofenceRegistrationResult.Registered(locations.map { it.id })
        } catch (exception: Exception) {
            GeofenceRegistrationResult.Failed(
                errorCode = exception::class.java.simpleName,
                message = exception.message ?: "Geofence registration failed",
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
}

sealed interface GeofenceRegistrationResult {
    data class Registered(val locationIds: List<String>) : GeofenceRegistrationResult
    data class Failed(val errorCode: String, val message: String) : GeofenceRegistrationResult
}

private fun SavedLocation.toGeofence(): Geofence {
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
        .setCircularRegion(latitude, longitude, radiusMeters.toFloat())
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
