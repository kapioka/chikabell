package com.chikabell.app.geofence

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class CurrentLocationReader(context: Context) {
    private val appContext = context.applicationContext
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)

    @SuppressLint("MissingPermission")
    suspend fun readCurrentLocation(): CurrentLocation? {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return readCandidateLocations().firstOrNull()
    }

    @SuppressLint("MissingPermission")
    suspend fun readCandidateLocations(): List<CurrentLocation> {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            val cancellationTokenSource = CancellationTokenSource()
            val current = try {
                runCatching {
                    Tasks.await(
                        fusedLocationClient.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                            cancellationTokenSource.token,
                        ),
                        CURRENT_LOCATION_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                    )
                }.getOrNull()?.toCurrentLocation("fused current")
            } finally {
                cancellationTokenSource.cancel()
            }

            if (current != null && current.isFreshEnough()) {
                return@withContext listOf(current)
            }

            val fusedLast = runCatching {
                Tasks.await(fusedLocationClient.lastLocation)
            }.getOrNull()?.toCurrentLocation("fused last")

            listOfNotNull(fusedLast).filter { it.isFreshEnough() }
        }
    }

    private fun Location.toCurrentLocation(providerLabel: String): CurrentLocation {
        return CurrentLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            provider = providerLabel,
            ageMillis = elapsedRealtimeAgeMillis(),
        )
    }

    private fun Location.elapsedRealtimeAgeMillis(): Long? {
        val elapsedRealtimeNanos = elapsedRealtimeNanos
        if (elapsedRealtimeNanos <= 0L) return null
        return (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L
    }

    private fun CurrentLocation.isFreshEnough(): Boolean {
        val ageMillis = ageMillis
        val accuracyMeters = accuracyMeters
        return (ageMillis == null || ageMillis <= MAX_LAST_KNOWN_AGE_MILLIS) &&
            (accuracyMeters == null || accuracyMeters <= MAX_LOCATION_ACCURACY_METERS)
    }

    private companion object {
        const val MAX_LAST_KNOWN_AGE_MILLIS = 5L * 60L * 1_000L
        const val MAX_LOCATION_ACCURACY_METERS = 200F
        const val CURRENT_LOCATION_TIMEOUT_SECONDS = 5L
    }
}

data class CurrentLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val provider: String,
    val ageMillis: Long?,
)
