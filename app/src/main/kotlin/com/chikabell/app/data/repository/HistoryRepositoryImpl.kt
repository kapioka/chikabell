package com.chikabell.app.data.repository

import com.chikabell.app.data.database.dao.NotificationHistoryDao
import com.chikabell.app.data.database.entity.NotificationHistoryEntity
import com.chikabell.app.domain.model.DeliveryStatus
import com.chikabell.app.domain.model.HistoryUserState
import com.chikabell.app.domain.model.HistoryFilter
import com.chikabell.app.domain.model.NotificationHistory
import com.chikabell.app.domain.model.TransitionType
import com.chikabell.app.domain.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class HistoryRepositoryImpl(
    private val historyDao: NotificationHistoryDao,
) : HistoryRepository {
    override fun observeHistory(filter: HistoryFilter): Flow<List<NotificationHistory>> {
        return historyDao.observeFiltered(
            locationQuery = filter.locationQuery.trim(),
            deliveryStatus = filter.deliveryStatus?.name,
            cutoffEpochMillis = filter.cutoffEpochMillis(),
        ).map { history ->
            history.map(NotificationHistoryEntity::toDomain)
        }
    }

    override suspend fun addHistory(history: NotificationHistory) {
        historyDao.insert(history.toEntity())
    }

    override suspend fun pruneHistory(referenceTimeMillis: Long) {
        historyDao.prune(referenceTimeMillis)
    }

    override suspend fun deleteAllHistory() {
        historyDao.deleteAll()
    }
}

private fun NotificationHistoryEntity.toDomain(): NotificationHistory {
    return NotificationHistory(
        id = id,
        locationId = locationId,
        locationNameSnapshot = locationNameSnapshot,
        messageSnapshot = messageSnapshot,
        latitudeSnapshot = latitudeSnapshot,
        longitudeSnapshot = longitudeSnapshot,
        radiusSnapshot = radiusSnapshot,
        deviceLatitude = deviceLatitude,
        deviceLongitude = deviceLongitude,
        deviceAccuracyMeters = deviceAccuracyMeters,
        deviceLocationAt = deviceLocationAt,
        deviceLocationProvider = deviceLocationProvider,
        transitionType = TransitionType.valueOf(transitionType),
        eventAt = eventAt,
        postedAt = postedAt,
        deliveryStatus = DeliveryStatus.valueOf(deliveryStatus),
        deliveryReason = deliveryReason,
        userState = HistoryUserState.valueOf(userState),
        createdAt = createdAt,
        registrationGenerationId = registrationGenerationId,
    )
}

private fun NotificationHistory.toEntity(): NotificationHistoryEntity {
    return NotificationHistoryEntity(
        id = id,
        locationId = locationId,
        locationNameSnapshot = locationNameSnapshot,
        messageSnapshot = messageSnapshot,
        latitudeSnapshot = latitudeSnapshot,
        longitudeSnapshot = longitudeSnapshot,
        radiusSnapshot = radiusSnapshot,
        deviceLatitude = deviceLatitude,
        deviceLongitude = deviceLongitude,
        deviceAccuracyMeters = deviceAccuracyMeters,
        deviceLocationAt = deviceLocationAt,
        deviceLocationProvider = deviceLocationProvider,
        transitionType = transitionType.name,
        eventAt = eventAt,
        postedAt = postedAt,
        deliveryStatus = deliveryStatus.name,
        deliveryReason = deliveryReason,
        userState = userState.name,
        readAt = null,
        completedAt = null,
        dismissedAt = null,
        createdAt = createdAt,
        registrationGenerationId = registrationGenerationId,
    )
}
