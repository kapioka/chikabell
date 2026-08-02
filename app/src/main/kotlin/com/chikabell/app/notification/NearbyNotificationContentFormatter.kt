package com.chikabell.app.notification

import com.chikabell.app.domain.model.NotificationHistory
import com.chikabell.app.geofence.DistanceCalculator
import com.chikabell.app.geofence.NearbyVerificationPolicy

object NearbyNotificationContentFormatter {
    fun format(histories: List<NotificationHistory>): NearbyNotificationContent? {
        val primary = histories.minByOrNull(::distanceFromDevice) ?: return null
        val locationIds = histories.mapNotNull(NotificationHistory::locationId).distinct().sorted()
        val distance = distanceFromDevice(primary).takeUnless { it == Float.MAX_VALUE }
        val distanceText = distance?.let {
            NearbyVerificationPolicy.roundedDistanceText(it, primary.deviceAccuracyMeters)
        }
        val body = when {
            histories.size > 1 && distanceText != null -> "最も近い${primary.locationNameSnapshot}まで$distanceText"
            histories.size > 1 -> "最も近い${primary.locationNameSnapshot}がこの付近にあります"
            distanceText != null -> "${primary.locationNameSnapshot}まで$distanceText"
            else -> "${primary.locationNameSnapshot}がこの付近にあります"
        }
        return NearbyNotificationContent(
            title = if (histories.size > 1) "近くにお気に入りの場所が${histories.size}件あります"
                else "近くにお気に入りの場所があります",
            body = body,
            primary = primary,
            locationIds = locationIds,
            notificationId = locationIds.joinToString("|").ifBlank { primary.id }.hashCode(),
        )
    }

    private fun distanceFromDevice(history: NotificationHistory): Float {
        val latitude = history.deviceLatitude ?: return Float.MAX_VALUE
        val longitude = history.deviceLongitude ?: return Float.MAX_VALUE
        return DistanceCalculator.distanceMeters(
            latitude,
            longitude,
            history.latitudeSnapshot,
            history.longitudeSnapshot,
        )
    }
}

data class NearbyNotificationContent(
    val title: String,
    val body: String,
    val primary: NotificationHistory,
    val locationIds: List<String>,
    val notificationId: Int,
)
