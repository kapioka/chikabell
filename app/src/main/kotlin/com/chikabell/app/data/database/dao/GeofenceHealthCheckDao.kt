package com.chikabell.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.chikabell.app.data.database.entity.GeofenceHealthCheckEntity

@Dao
interface GeofenceHealthCheckDao {
    @Insert
    suspend fun insert(check: GeofenceHealthCheckEntity)

    @Query("DELETE FROM geofence_health_checks WHERE startedAt < :cutoffEpochMillis")
    suspend fun deleteOlderThanRetentionAge(cutoffEpochMillis: Long)

    @Query("DELETE FROM geofence_health_checks WHERE id NOT IN (SELECT id FROM geofence_health_checks ORDER BY startedAt DESC, createdAt DESC LIMIT :limit)")
    suspend fun deleteOlderThanLatest(limit: Int)

    /** Retains a bounded diagnostic window for intermittent monitoring failures. */
    @androidx.room.Transaction
    suspend fun prune(referenceTimeMillis: Long = System.currentTimeMillis()) {
        deleteOlderThanRetentionAge(referenceTimeMillis - RETENTION_AGE_MILLIS)
        deleteOlderThanLatest(MAX_HISTORY_COUNT)
    }

    companion object {
        const val MAX_HISTORY_COUNT = 500
        const val RETENTION_AGE_DAYS = 90L
        const val RETENTION_AGE_MILLIS = RETENTION_AGE_DAYS * 24 * 60 * 60 * 1_000
    }
}
