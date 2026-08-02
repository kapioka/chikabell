package com.chikabell.app.permission

data class PermissionSnapshot @JvmOverloads constructor(
    val notificationPermission: NotificationPermissionStatus,
    val foregroundLocation: ForegroundLocationStatus,
    val backgroundLocation: BackgroundLocationStatus,
    val locationServices: LocationServicesStatus,
    val googlePlayServices: GooglePlayServicesStatus,
    val activityRecognition: ActivityRecognitionStatus = ActivityRecognitionStatus.NotRequired,
)

enum class NotificationPermissionStatus {
    Granted,
    Denied,
    NotRequired,
}

enum class ForegroundLocationStatus {
    Precise,
    ApproximateOnly,
    Denied,
}

enum class BackgroundLocationStatus {
    Granted,
    Denied,
    NotRequired,
}

enum class LocationServicesStatus {
    Enabled,
    Disabled,
}

enum class GooglePlayServicesStatus {
    Available,
    UserResolvableError,
    Unavailable,
}

enum class ActivityRecognitionStatus { Granted, Denied, NotRequired }
