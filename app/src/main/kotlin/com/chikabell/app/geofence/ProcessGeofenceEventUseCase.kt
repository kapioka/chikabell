package com.chikabell.app.geofence

import com.chikabell.app.domain.model.DeliveryStatus
import com.chikabell.app.domain.model.HistoryUserState
import com.chikabell.app.domain.model.NotificationHistory
import com.chikabell.app.domain.model.SavedLocation
import com.chikabell.app.domain.model.TransitionType
import com.chikabell.app.domain.repository.HistoryRepository
import com.chikabell.app.domain.repository.LocationRepository
import com.chikabell.app.notification.NearbyNotificationPoster
import java.util.UUID

class ProcessGeofenceEventUseCase(
    private val locationRepository: LocationRepository,
    private val historyRepository: HistoryRepository,
    private val notificationPoster: NearbyNotificationPoster,
    private val currentLocationReader: CurrentLocationReader,
) {
    suspend fun execute(requestId: String, transitionType: TransitionType, eventAt: Long): ProcessGeofenceEventResult {
        val location = locationRepository.getLocationById(requestId)
            ?: return ProcessGeofenceEventResult.Ignored("Location not found")

        if (!location.enabled) {
            return saveSuppressed(location, transitionType, eventAt, "地点が無効です")
        }
        if (transitionType == TransitionType.ENTER && location.transitionType == TransitionType.DWELL) {
            val dwellSeconds = (location.loiteringDelayMs ?: 60_000) / 1_000
            return saveTracked(location, transitionType, eventAt, "通知範囲に入りました。${dwellSeconds}秒滞在待ちです")
        }
        if (transitionType == TransitionType.EXIT) {
            return saveTracked(location, transitionType, eventAt, "通知ポイントから出ました")
        }
        if (location.transitionType != transitionType) {
            return ProcessGeofenceEventResult.Ignored("Transition mismatch")
        }
        val lastEventAt = location.lastEventAt
        if (GeofenceEventPolicy.isDuplicateEvent(lastEventAt, eventAt)) {
            return saveSuppressed(location, transitionType, eventAt, "短時間の重複イベントです")
        }
        val lastNotifiedAt = location.lastNotifiedAt
        if (GeofenceEventPolicy.isInCooldown(lastNotifiedAt, location.cooldownMinutes, eventAt)) {
            return saveSuppressed(location, transitionType, eventAt, "再通知間隔内です")
        }

        val canPost = notificationPoster.canPostNotifications()
        val channelEnabled = notificationPoster.isChannelEnabled()
        val postedAt = if (canPost && channelEnabled) System.currentTimeMillis() else null
        val deliveryStatus = if (postedAt != null) DeliveryStatus.POSTED else DeliveryStatus.FAILED
        val reason = when {
            !canPost -> "通知権限がありません"
            !channelEnabled -> "通知チャンネルが無効です"
            else -> null
        }
        val deviceLocation = currentLocationReader.readCurrentLocation()
        val history = location.toHistory(
            transitionType = transitionType,
            eventAt = eventAt,
            postedAt = postedAt,
            deliveryStatus = deliveryStatus,
            reason = reason,
            deviceLocation = deviceLocation,
        )

        historyRepository.addHistory(history)
        locationRepository.markLastEvent(location.id, eventAt)
        if (postedAt != null) {
            notificationPoster.post(history)
            locationRepository.markLastNotified(location.id, postedAt)
            return ProcessGeofenceEventResult.NotificationPosted
        }

        return ProcessGeofenceEventResult.HistorySavedWithoutNotification(reason ?: "通知できません")
    }

    private suspend fun saveSuppressed(
        location: SavedLocation,
        transitionType: TransitionType,
        eventAt: Long,
        reason: String,
    ): ProcessGeofenceEventResult {
        historyRepository.addHistory(
            location.toHistory(
                transitionType = transitionType,
                eventAt = eventAt,
                postedAt = null,
                deliveryStatus = DeliveryStatus.SUPPRESSED,
                reason = reason,
            ),
        )
        locationRepository.markLastEvent(location.id, eventAt)
        return ProcessGeofenceEventResult.HistorySavedWithoutNotification(reason)
    }

    private suspend fun saveTracked(
        location: SavedLocation,
        transitionType: TransitionType,
        eventAt: Long,
        reason: String,
    ): ProcessGeofenceEventResult {
        historyRepository.addHistory(
            location.toHistory(
                transitionType = transitionType,
                eventAt = eventAt,
                postedAt = null,
                deliveryStatus = DeliveryStatus.TRACKED,
                reason = reason,
            ),
        )
        return ProcessGeofenceEventResult.HistorySavedWithoutNotification(reason)
    }
}

sealed interface ProcessGeofenceEventResult {
    data object NotificationPosted : ProcessGeofenceEventResult
    data class HistorySavedWithoutNotification(val reason: String) : ProcessGeofenceEventResult
    data class Ignored(val reason: String) : ProcessGeofenceEventResult
}

private fun SavedLocation.toHistory(
    transitionType: TransitionType,
    eventAt: Long,
    postedAt: Long?,
    deliveryStatus: DeliveryStatus,
    reason: String?,
    deviceLocation: CurrentLocation? = null,
): NotificationHistory {
    val now = System.currentTimeMillis()
    val deviceLocationAt = deviceLocation?.ageMillis?.let { now - it } ?: deviceLocation?.let { now }
    return NotificationHistory(
        id = UUID.randomUUID().toString(),
        locationId = id,
        locationNameSnapshot = name,
        messageSnapshot = message,
        latitudeSnapshot = latitude,
        longitudeSnapshot = longitude,
        radiusSnapshot = radiusMeters,
        deviceLatitude = deviceLocation?.latitude,
        deviceLongitude = deviceLocation?.longitude,
        deviceAccuracyMeters = deviceLocation?.accuracyMeters,
        deviceLocationAt = deviceLocationAt,
        deviceLocationProvider = deviceLocation?.provider,
        transitionType = transitionType,
        eventAt = eventAt,
        postedAt = postedAt,
        deliveryStatus = deliveryStatus,
        deliveryReason = reason,
        userState = HistoryUserState.UNREAD,
        createdAt = now,
        registrationGenerationId = registrationGenerationId,
    )
}
