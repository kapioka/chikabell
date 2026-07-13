package com.chikabell.app.data.repository

import com.chikabell.app.data.database.dao.GeofenceRegistrationAttemptDao
import com.chikabell.app.data.database.entity.GeofenceRegistrationAttemptEntity
import com.chikabell.app.domain.repository.RegistrationDiagnosticsRepository
import java.util.UUID

class RegistrationDiagnosticsRepositoryImpl(
    private val registrationAttemptDao: GeofenceRegistrationAttemptDao,
) : RegistrationDiagnosticsRepository {
    override suspend fun start(source: String, requestedCount: Int): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        registrationAttemptDao.insert(
            GeofenceRegistrationAttemptEntity(
                id = id,
                generationId = id,
                source = source,
                startedAt = now,
                finishedAt = null,
                result = RESULT_RUNNING,
                requestedCount = requestedCount,
                acceptedCount = 0,
                errorCode = null,
                message = null,
                createdAt = now,
            ),
        )
        return id
    }

    override suspend fun finishAccepted(id: String, acceptedCount: Int) {
        registrationAttemptDao.finish(
            id = id,
            finishedAt = System.currentTimeMillis(),
            result = RESULT_ACCEPTED,
            acceptedCount = acceptedCount,
            errorCode = null,
            message = "Geofencing APIが登録要求を受理しました",
        )
    }

    override suspend fun finishRejected(id: String, result: String, errorCode: String?, message: String?) {
        registrationAttemptDao.finish(
            id = id,
            finishedAt = System.currentTimeMillis(),
            result = result,
            acceptedCount = 0,
            errorCode = errorCode?.take(MAX_ERROR_CODE_LENGTH),
            message = message?.take(MAX_MESSAGE_LENGTH),
        )
    }

    override suspend fun prune(referenceTimeMillis: Long) {
        registrationAttemptDao.prune(referenceTimeMillis)
    }

    companion object {
        const val RESULT_RUNNING = "RUNNING"
        const val RESULT_ACCEPTED = "REQUEST_ACCEPTED"
        const val RESULT_FAILED = "REQUEST_FAILED"
        const val RESULT_BLOCKED = "BLOCKED"
        private const val MAX_ERROR_CODE_LENGTH = 120
        private const val MAX_MESSAGE_LENGTH = 500
    }
}
