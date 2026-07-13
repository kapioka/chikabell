package com.chikabell.app.domain.repository

import com.chikabell.app.domain.model.GeofenceRestoreAttempt
import com.chikabell.app.domain.model.GeofenceHealthCheckRecord
import com.chikabell.app.domain.model.RestoreAttemptResult
import com.chikabell.app.domain.model.RestoreTrigger
import kotlinx.coroutines.flow.Flow

interface RestoreDiagnosticsRepository {
    fun observeLatest(): Flow<GeofenceRestoreAttempt?>
    suspend fun getLatestSuccess(): GeofenceRestoreAttempt?
    suspend fun start(trigger: RestoreTrigger, runAttemptCount: Int, enabledCount: Int): String
    suspend fun finish(id: String, result: RestoreAttemptResult, registeredCount: Int, errorCode: String? = null, message: String? = null)
    suspend fun recordHealthCheck(record: GeofenceHealthCheckRecord)
}
