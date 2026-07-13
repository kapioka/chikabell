package com.chikabell.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GeofenceRestoreAttemptDao {
    @Query("SELECT * FROM geofence_restore_attempts ORDER BY startedAt DESC LIMIT 1")
    fun observeLatest(): Flow<com.chikabell.app.data.database.entity.GeofenceRestoreAttemptEntity?>

    @Query("SELECT * FROM geofence_restore_attempts WHERE result = 'SUCCESS' ORDER BY finishedAt DESC LIMIT 1")
    suspend fun getLatestSuccess(): com.chikabell.app.data.database.entity.GeofenceRestoreAttemptEntity?

    @Insert
    suspend fun insert(attempt: com.chikabell.app.data.database.entity.GeofenceRestoreAttemptEntity)

    @Query("UPDATE geofence_restore_attempts SET finishedAt=:finishedAt, result=:result, registeredCount=:registeredCount, errorCode=:errorCode, message=:message WHERE id=:id")
    suspend fun finish(id: String, finishedAt: Long, result: String, registeredCount: Int, errorCode: String?, message: String?)

    @Query("DELETE FROM geofence_restore_attempts WHERE startedAt < :cutoffEpochMillis")
    suspend fun deleteOlderThanRetentionAge(cutoffEpochMillis: Long)

    @Query("DELETE FROM geofence_restore_attempts WHERE id NOT IN (SELECT id FROM geofence_restore_attempts ORDER BY startedAt DESC, createdAt DESC LIMIT :limit)")
    suspend fun deleteOlderThanLatest(limit: Int)

    /** Retains a bounded diagnostic window for monitoring re-registration attempts. */
    @androidx.room.Transaction
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
