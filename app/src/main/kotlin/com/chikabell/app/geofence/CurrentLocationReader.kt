package com.chikabell.app.geofence

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicBoolean

interface CurrentLocationSource {
    suspend fun readCurrentLocation(): CurrentLocation?
    suspend fun readCandidateLocations(): List<CurrentLocation>
    suspend fun readVerificationLocations(
        intervalMillis: Long,
        maxDurationMillis: Long,
        maxSamples: Int,
        stopWhen: (List<CurrentLocation>) -> Boolean,
    ): List<CurrentLocation>

    suspend fun readAdaptiveVerificationLocations(
        intervalMillis: Long,
        maxDurationMillis: Long,
        maxSamples: Int,
        freshStill: Boolean,
        stopWhen: (List<CurrentLocation>) -> Boolean,
    ): VerificationSessionResult {
        val samples = readVerificationLocations(intervalMillis, maxDurationMillis, maxSamples, stopWhen)
        return VerificationSessionResult(
            samples = samples,
            endReason = VerificationSessionEndReason.LEGACY_COMPLETION,
            initialIntervalMillis = intervalMillis,
            finalIntervalMillis = intervalMillis,
            durationMillis = 0L,
        )
    }
}

class CurrentLocationReader(context: Context) : CurrentLocationSource {
    private val appContext = context.applicationContext
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)

    @SuppressLint("MissingPermission")
    override suspend fun readCurrentLocation(): CurrentLocation? {
        if (!hasForegroundLocationPermission()) {
            return null
        }
        return readCandidateLocations().firstOrNull()
    }

    @SuppressLint("MissingPermission")
    override suspend fun readCandidateLocations(): List<CurrentLocation> {
        if (!hasForegroundLocationPermission()) {
            return emptyList()
        }
        val cancellationTokenSource = CancellationTokenSource()
        val current = try {
            withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    continuation.invokeOnCancellation { cancellationTokenSource.cancel() }
                    fusedLocationClient.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        cancellationTokenSource.token,
                    ).addOnSuccessListener { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }.addOnFailureListener {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }?.toCurrentLocation("fused current")
        } finally {
            cancellationTokenSource.cancel()
        }

        val fusedLast = suspendCancellableCoroutine<Location?> { continuation ->
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        }?.toCurrentLocation("fused last")

        return listOfNotNull(current, fusedLast)
            .filter { it.isFreshEnough() }
            .distinctBy { Triple(it.latitude, it.longitude, it.ageMillis) }
    }

    @SuppressLint("MissingPermission")
    override suspend fun readVerificationLocations(
        intervalMillis: Long,
        maxDurationMillis: Long,
        maxSamples: Int,
        stopWhen: (List<CurrentLocation>) -> Boolean,
    ): List<CurrentLocation> = readAdaptiveVerificationLocations(
        intervalMillis = intervalMillis,
        maxDurationMillis = maxDurationMillis,
        maxSamples = maxSamples,
        freshStill = false,
        stopWhen = stopWhen,
    ).samples

    @SuppressLint("MissingPermission")
    override suspend fun readAdaptiveVerificationLocations(
        intervalMillis: Long,
        maxDurationMillis: Long,
        maxSamples: Int,
        freshStill: Boolean,
        stopWhen: (List<CurrentLocation>) -> Boolean,
    ): VerificationSessionResult {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return VerificationSessionResult(
                samples = emptyList(),
                endReason = VerificationSessionEndReason.PERMISSION_MISSING,
                initialIntervalMillis = intervalMillis,
                finalIntervalMillis = intervalMillis,
                durationMillis = 0L,
            )
        }
        val samples = mutableListOf<CurrentLocation>()
        val sessionStartedAt = SystemClock.elapsedRealtime()
        var finalIntervalMillis = intervalMillis
        return try {
            val endReason = withTimeoutOrNull(maxDurationMillis) {
                suspendCancellableCoroutine { continuation ->
                val reconfiguring = AtomicBoolean(false)
                val completed = AtomicBoolean(false)
                lateinit var callback: LocationCallback

                fun removeUpdatesSafely() = runCatching {
                    fusedLocationClient.removeLocationUpdates(callback)
                }.getOrNull()

                fun finish(reason: VerificationSessionEndReason) {
                    if (continuation.isActive && completed.compareAndSet(false, true)) {
                        val removalStarted = removeUpdatesSafely() != null
                        continuation.resume(if (removalStarted) reason else VerificationSessionEndReason.INTERNAL_ERROR)
                    }
                }

                fun requestUpdates(requestIntervalMillis: Long) {
                    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, requestIntervalMillis)
                        .setMinUpdateIntervalMillis(requestIntervalMillis)
                        .setMaxUpdateDelayMillis(requestIntervalMillis)
                        .build()
                    try {
                        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
                            .addOnFailureListener {
                            if (continuation.isActive && completed.compareAndSet(false, true)) {
                                removeUpdatesSafely()
                                continuation.resume(VerificationSessionEndReason.INTERNAL_ERROR)
                            }
                        }
                    } catch (_: Throwable) {
                        if (continuation.isActive && completed.compareAndSet(false, true)) {
                            removeUpdatesSafely()
                            continuation.resume(VerificationSessionEndReason.INTERNAL_ERROR)
                        }
                    }
                }

                fun downgradeToLowFrequency() {
                    if (finalIntervalMillis >= NearbyVerificationPolicy.LOW_FREQUENCY_INTERVAL_MILLIS ||
                        !reconfiguring.compareAndSet(false, true)
                    ) return
                    val removalTask = removeUpdatesSafely()
                    if (removalTask == null) {
                        finish(VerificationSessionEndReason.INTERNAL_ERROR)
                        return
                    }
                    removalTask.addOnCompleteListener { removal ->
                        if (!continuation.isActive || completed.get()) return@addOnCompleteListener
                        if (!removal.isSuccessful) {
                            finish(VerificationSessionEndReason.INTERNAL_ERROR)
                            return@addOnCompleteListener
                        }
                        finalIntervalMillis = NearbyVerificationPolicy.LOW_FREQUENCY_INTERVAL_MILLIS
                        requestUpdates(finalIntervalMillis)
                        reconfiguring.set(false)
                    }
                }

                callback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        result.locations.forEach { location ->
                            val sample = location.toCurrentLocation("fused verification")
                            if (sample.isFreshEnough() && samples.none { existing ->
                                    existing.latitude == sample.latitude &&
                                        existing.longitude == sample.longitude &&
                                        existing.ageMillis == sample.ageMillis
                                }
                            ) {
                                samples += sample
                            }
                        }
                        val shouldStop = runCatching { stopWhen(samples.toList()) }.getOrDefault(false)
                        if (shouldStop) {
                            finish(VerificationSessionEndReason.DECISION_REACHED)
                            return
                        }
                        when (NearbyVerificationPolicy.adaptiveSessionAction(freshStill, samples)) {
                            AdaptiveVerificationAction.STOP_STATIONARY -> {
                                finish(VerificationSessionEndReason.STATIONARY)
                                return
                            }
                            AdaptiveVerificationAction.DOWNGRADE_TO_LOW_FREQUENCY -> downgradeToLowFrequency()
                            AdaptiveVerificationAction.KEEP_CURRENT_INTERVAL -> Unit
                        }
                        if (samples.size >= maxSamples) {
                            finish(VerificationSessionEndReason.MAX_SAMPLES)
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    completed.set(true)
                    removeUpdatesSafely()
                }
                requestUpdates(intervalMillis)
                }
            } ?: VerificationSessionEndReason.MAX_DURATION
            VerificationSessionResult(
                samples = samples.toList(),
                endReason = endReason,
                initialIntervalMillis = intervalMillis,
                finalIntervalMillis = finalIntervalMillis,
                durationMillis = (SystemClock.elapsedRealtime() - sessionStartedAt).coerceAtLeast(0L),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            VerificationSessionResult(
                samples = samples.toList(),
                endReason = VerificationSessionEndReason.INTERNAL_ERROR,
                initialIntervalMillis = intervalMillis,
                finalIntervalMillis = finalIntervalMillis,
                durationMillis = (SystemClock.elapsedRealtime() - sessionStartedAt).coerceAtLeast(0L),
            )
        }
    }

    private fun Location.toCurrentLocation(providerLabel: String): CurrentLocation {
        val elapsedRealtimeMillis = elapsedRealtimeNanos
            .takeIf { it > 0L }
            ?.div(1_000_000L)
        return CurrentLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            provider = providerLabel,
            ageMillis = elapsedRealtimeAgeMillis(),
            speedMetersPerSecond = if (hasSpeed()) speed.coerceAtLeast(0F) else null,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
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
        return ageMillis != null && ageMillis in 0..MAX_LAST_KNOWN_AGE_MILLIS &&
            (accuracyMeters == null || accuracyMeters <= MAX_LOCATION_ACCURACY_METERS)
    }

    private fun hasForegroundLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val MAX_LAST_KNOWN_AGE_MILLIS = 5L * 60L * 1_000L
        const val MAX_LOCATION_ACCURACY_METERS = 200F
        const val CURRENT_LOCATION_TIMEOUT_MILLIS = 5_000L
    }
}

data class CurrentLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val provider: String,
    val ageMillis: Long?,
    val speedMetersPerSecond: Float? = null,
    /** Monotonic fix timestamp, used only to reject impossible jumps within one bounded session. */
    val elapsedRealtimeMillis: Long? = null,
)

data class VerificationSessionResult(
    val samples: List<CurrentLocation>,
    val endReason: VerificationSessionEndReason,
    val initialIntervalMillis: Long,
    val finalIntervalMillis: Long,
    val durationMillis: Long,
)

enum class VerificationSessionEndReason {
    DECISION_REACHED,
    STATIONARY,
    MAX_DURATION,
    MAX_SAMPLES,
    PERMISSION_MISSING,
    INTERNAL_ERROR,
    LEGACY_COMPLETION,
}
