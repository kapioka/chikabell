package com.chikabell.app.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

class PermissionStateReader(
    private val context: Context,
) {
    fun read(): PermissionSnapshot {
        return PermissionSnapshot(
            notificationPermission = readNotificationPermission(),
            foregroundLocation = readForegroundLocation(),
            backgroundLocation = readBackgroundLocation(),
            locationServices = readLocationServices(),
            googlePlayServices = readGooglePlayServices(),
            activityRecognition = readActivityRecognition(),
        )
    }

    private fun readNotificationPermission(): NotificationPermissionStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return NotificationPermissionStatus.NotRequired
        }
        return if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            NotificationPermissionStatus.Granted
        } else {
            NotificationPermissionStatus.Denied
        }
    }

    private fun readForegroundLocation(): ForegroundLocationStatus {
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        return when {
            fine -> ForegroundLocationStatus.Precise
            coarse -> ForegroundLocationStatus.ApproximateOnly
            else -> ForegroundLocationStatus.Denied
        }
    }

    private fun readBackgroundLocation(): BackgroundLocationStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return BackgroundLocationStatus.NotRequired
        }
        return if (hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            BackgroundLocationStatus.Granted
        } else {
            BackgroundLocationStatus.Denied
        }
    }

    private fun readLocationServices(): LocationServicesStatus {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        return if (enabled) LocationServicesStatus.Enabled else LocationServicesStatus.Disabled
    }

    private fun readGooglePlayServices(): GooglePlayServicesStatus {
        val result = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        return when {
            result == ConnectionResult.SUCCESS -> GooglePlayServicesStatus.Available
            GoogleApiAvailability.getInstance().isUserResolvableError(result) ->
                GooglePlayServicesStatus.UserResolvableError
            else -> GooglePlayServicesStatus.Unavailable
        }
    }

    private fun readActivityRecognition(): ActivityRecognitionStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ActivityRecognitionStatus.NotRequired
        return if (hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
            ActivityRecognitionStatus.Granted
        } else ActivityRecognitionStatus.Denied
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
