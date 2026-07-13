package com.chikabell.app.data.repository

import com.chikabell.app.data.database.dao.GeofenceHealthCheckDao
import com.chikabell.app.data.database.dao.GeofenceRestoreAttemptDao
import com.chikabell.app.data.database.entity.GeofenceHealthCheckEntity
import com.chikabell.app.data.database.entity.GeofenceRestoreAttemptEntity
import com.chikabell.app.domain.model.GeofenceHealthCheckRecord
import com.chikabell.app.domain.model.GeofenceRestoreAttempt
import com.chikabell.app.domain.model.RestoreAttemptResult
import com.chikabell.app.domain.model.RestoreTrigger
import com.chikabell.app.domain.repository.RestoreDiagnosticsRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RestoreDiagnosticsRepositoryImpl(
    private val restoreAttemptDao: GeofenceRestoreAttemptDao,
    private val healthCheckDao: GeofenceHealthCheckDao,
) : RestoreDiagnosticsRepository {
    override fun observeLatest(): Flow<GeofenceRestoreAttempt?> = restoreAttemptDao.observeLatest().map { it?.toDomain() }
    override suspend fun getLatestSuccess(): GeofenceRestoreAttempt? = restoreAttemptDao.getLatestSuccess()?.toDomain()

    override suspend fun start(trigger: RestoreTrigger, runAttemptCount: Int, enabledCount: Int): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        restoreAttemptDao.insert(GeofenceRestoreAttemptEntity(id, trigger.name, now, null, RestoreAttemptResult.RUNNING.name, runAttemptCount, enabledCount, 0, null, null, now))
        restoreAttemptDao.prune()
        return id
    }

    override suspend fun finish(id: String, result: RestoreAttemptResult, registeredCount: Int, errorCode: String?, message: String?) {
        restoreAttemptDao.finish(id, System.currentTimeMillis(), result.name, registeredCount, errorCode, message?.take(200))
    }

    override suspend fun recordHealthCheck(record: GeofenceHealthCheckRecord) {
        healthCheckDao.insert(record.toEntity())
        healthCheckDao.prune()
    }
}

private fun GeofenceHealthCheckRecord.toEntity(): GeofenceHealthCheckEntity {
    return GeofenceHealthCheckEntity(
        id = UUID.randomUUID().toString(),
        trigger = trigger.name,
        startedAt = startedAt,
        finishedAt = finishedAt,
        result = result.name,
        enabledCount = enabledCount,
        registeredCount = registeredCount,
        notRegisteredCount = notRegisteredCount,
        errorCount = errorCount,
        staleRegisteredCount = staleRegisteredCount,
        oldestRegisteredAt = oldestRegisteredAt,
        newestRegisteredAt = newestRegisteredAt,
        lastEventAt = lastEventAt,
        lastNotifiedAt = lastNotifiedAt,
        shouldRestore = shouldRestore,
        restoreReason = restoreReason,
        restoreAttemptId = restoreAttemptId,
        googlePlayServices = googlePlayServices,
        locationServices = locationServices,
        foregroundLocation = foregroundLocation,
        backgroundLocation = backgroundLocation,
        notificationPermission = notificationPermission,
        errorCode = errorCode,
        message = message?.take(200),
        createdAt = finishedAt,
    )
}

private fun GeofenceRestoreAttemptEntity.toDomain() = GeofenceRestoreAttempt(
    id, RestoreTrigger.valueOf(trigger), startedAt, finishedAt, RestoreAttemptResult.valueOf(result),
    runAttemptCount, enabledCount, registeredCount, errorCode, message, createdAt,
)
