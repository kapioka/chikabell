package com.chikabell.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.chikabell.app.data.database.entity.GeofenceRegistrationAttemptEntity

@Dao
interface GeofenceRegistrationAttemptDao {
    @Insert
    suspend fun insert(attempt: GeofenceRegistrationAttemptEntity)

    @Query(
        """
        UPDATE geofence_registration_attempts
        SET finishedAt = :finishedAt,
            result = :result,
            acceptedCount = :acceptedCount,
            errorCode = :errorCode,
            message = :message
        WHERE id = :id
        """,
    )
    suspend fun finish(
        id: String,
        finishedAt: Long,
        result: String,
        acceptedCount: Int,
        errorCode: String?,
        message: String?,
    )

    @Query(
        """
        SELECT * FROM geofence_registration_attempts
        WHERE result = 'REQUEST_ACCEPTED'
        ORDER BY finishedAt DESC, startedAt DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestAccepted(): GeofenceRegistrationAttemptEntity?

    @Query("DELETE FROM geofence_registration_attempts WHERE startedAt < :cutoffEpochMillis")
    suspend fun deleteOlderThanRetentionAge(cutoffEpochMillis: Long)

    @Query(
        """
        DELETE FROM geofence_registration_attempts
        WHERE id NOT IN (
            SELECT id FROM geofence_registration_attempts
            ORDER BY startedAt DESC, createdAt DESC
            LIMIT :limit
        )
        """,
    )
    suspend fun deleteOlderThanLatest(limit: Int)

    /** Retains enough accepted/rejected request evidence without unbounded storage growth. */
    @Transaction
    suspend fun prune(referenceTimeMillis: Long = System.currentTimeMillis()) {
        deleteOlderThanRetentionAge(referenceTimeMillis - RETENTION_AGE_MILLIS)
        deleteOlderThanLatest(MAX_HISTORY_COUNT)
    }

    companion object {
        const val MAX_HISTORY_COUNT = 200
        const val RETENTION_AGE_DAYS = 90L
        const val RETENTION_AGE_MILLIS = RETENTION_AGE_DAYS * 24 * 60 * 60 * 1_000
    }
}
