package com.chikabell.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.chikabell.app.data.database.entity.NotificationHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationHistoryDao {
    @Query("SELECT * FROM notification_history ORDER BY eventAt DESC")
    fun observeAll(): Flow<List<NotificationHistoryEntity>>

    @Query(
        """
        SELECT * FROM notification_history
        WHERE (:locationQuery = '' OR locationNameSnapshot LIKE '%' || :locationQuery || '%' COLLATE NOCASE)
          AND (:deliveryStatus IS NULL OR deliveryStatus = :deliveryStatus)
          AND eventAt >= :cutoffEpochMillis
        ORDER BY eventAt DESC
        """,
    )
    fun observeFiltered(
        locationQuery: String,
        deliveryStatus: String?,
        cutoffEpochMillis: Long,
    ): Flow<List<NotificationHistoryEntity>>

    @Insert
    suspend fun insert(entity: NotificationHistoryEntity)

    @Query("DELETE FROM notification_history WHERE eventAt < :cutoffEpochMillis")
    suspend fun deleteOlderThanRetentionAge(cutoffEpochMillis: Long)

    @Query(
        "DELETE FROM notification_history WHERE id NOT IN " +
            "(SELECT id FROM notification_history ORDER BY eventAt DESC, createdAt DESC LIMIT :limit)",
    )
    suspend fun deleteOlderThanLatest(limit: Int)

    @Query("DELETE FROM notification_history")
    suspend fun deleteAll()

    /**
     * Keeps enough recent delivery evidence for diagnosis without allowing the history table to
     * grow indefinitely. This is also callable from the periodic health check when no new
     * notification has arrived.
     */
    @Transaction
    suspend fun prune(referenceTimeMillis: Long = System.currentTimeMillis()) {
        deleteOlderThanRetentionAge(referenceTimeMillis - RETENTION_AGE_MILLIS)
        deleteOlderThanLatest(MAX_HISTORY_COUNT)
    }

    companion object {
        const val MAX_HISTORY_COUNT = 1_000
        const val RETENTION_AGE_DAYS = 180L
        const val RETENTION_AGE_MILLIS = RETENTION_AGE_DAYS * 24 * 60 * 60 * 1_000
    }
}
