package com.chikabell.app.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.chikabell.app.data.database.entity.LocationEntity
import com.chikabell.app.data.database.entity.LocationTagCrossRef
import com.chikabell.app.data.database.entity.LocationWithTags
import com.chikabell.app.data.database.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {
    @Query("SELECT * FROM locations ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<LocationEntity>>

    @Transaction
    @Query("SELECT * FROM locations ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAllWithTags(): Flow<List<LocationWithTags>>

    @Query("SELECT * FROM locations WHERE enabled = 1 ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getEnabledOnce(): List<LocationEntity>

    @Transaction
    @Query("SELECT * FROM locations WHERE enabled = 1 ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getEnabledWithTagsOnce(): List<LocationWithTags>

    @Query("SELECT * FROM locations WHERE id = :id")
    suspend fun getById(id: String): LocationEntity?

    @Transaction
    @Query("SELECT * FROM locations WHERE id = :id")
    suspend fun getByIdWithTags(id: String): LocationWithTags?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(location: LocationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(locations: List<LocationEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationTag(crossRef: LocationTagCrossRef)

    @Update
    suspend fun update(location: LocationEntity)

    @Delete
    suspend fun delete(location: LocationEntity)

    @Query("DELETE FROM locations WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM location_tags WHERE locationId = :locationId")
    suspend fun deleteTagsForLocation(locationId: String)

    @Query("SELECT * FROM tags WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun findTagByNormalizedName(normalizedName: String): TagEntity?

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun countTags(): Int

    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN location_tags ON tags.id = location_tags.tagId
        WHERE location_tags.locationId = :locationId
        ORDER BY location_tags.sortOrder ASC
        """,
    )
    suspend fun getTagsForLocation(locationId: String): List<TagEntity>

    @Query(
        """
        UPDATE locations
        SET enabled = :enabled,
            registrationStatus = :registrationStatus,
            registrationErrorCode = NULL,
            registrationErrorMessage = NULL,
            lastRegisteredAt = NULL,
            registrationGenerationId = NULL,
            updatedAt = :updatedAt
        WHERE id IN (:ids)
        """,
    )
    suspend fun setEnabled(ids: List<String>, enabled: Boolean, registrationStatus: String, updatedAt: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM locations")
    suspend fun nextSortOrder(): Long

    @Query(
        """
        UPDATE locations
        SET registrationStatus = :status,
            registrationErrorCode = :errorCode,
            registrationErrorMessage = :errorMessage,
            lastRegisteredAt = :registeredAt,
            registrationGenerationId = :registrationGenerationId,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateRegistrationStatus(
        id: String,
        status: String,
        errorCode: String?,
        errorMessage: String?,
        registeredAt: Long?,
        registrationGenerationId: String?,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE locations
        SET registrationStatus = :status,
            registrationErrorCode = NULL,
            registrationErrorMessage = NULL,
            lastRegisteredAt = NULL,
            registrationGenerationId = NULL,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun clearRegistrationStatus(
        id: String,
        status: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE locations
        SET lastEventAt = :eventAt,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateLastEvent(
        id: String,
        eventAt: Long,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE locations
        SET lastNotifiedAt = :notifiedAt,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateLastNotified(
        id: String,
        notifiedAt: Long,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE locations
        SET nearbyState = :state,
            snoozedUntil = :snoozedUntil,
            lastVerificationAt = :verifiedAt,
            lastValidLocationAt = COALESCE(:lastValidLocationAt, lastValidLocationAt),
            lastVerificationReason = :verificationReason,
            lastSuppressionReason = :suppressionReason,
            lastAccuracyMeters = :accuracyMeters,
            lastSpeedMetersPerSecond = :speedMetersPerSecond,
            lastNotificationDistanceMeters = :notificationDistanceMeters,
            updatedAt = :updatedAt
        WHERE id = :id
          AND (
            nearbyState != 'SNOOZED'
            OR snoozedUntil IS NULL
            OR snoozedUntil <= :updatedAt
          )
        """,
    )
    suspend fun updateNearbyState(
        id: String,
        state: String,
        snoozedUntil: Long?,
        verifiedAt: Long?,
        lastValidLocationAt: Long?,
        verificationReason: String?,
        suppressionReason: String?,
        accuracyMeters: Float?,
        speedMetersPerSecond: Float?,
        notificationDistanceMeters: Float?,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE locations
        SET nearbyState = 'VERIFYING',
            lastVerificationAt = :verifiedAt,
            lastVerificationReason = :reason,
            lastSuppressionReason = NULL,
            updatedAt = :verifiedAt
        WHERE id = :id AND nearbyState = 'MONITORING'
        """,
    )
    suspend fun claimVerification(id: String, verifiedAt: Long, reason: String): Int

    @Query(
        """
        UPDATE locations
        SET nearbyState = 'SNOOZED',
            snoozedUntil = :snoozedUntil,
            lastSuppressionReason = '12時間休止中です',
            updatedAt = :updatedAt
        WHERE id IN (:ids)
        """,
    )
    suspend fun snooze(ids: List<String>, snoozedUntil: Long, updatedAt: Long)

    @Query(
        """
        UPDATE locations
        SET nearbyState = 'MONITORING',
            snoozedUntil = NULL,
            lastSuppressionReason = NULL,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun clearSnooze(id: String, updatedAt: Long)

    @Query(
        """
        UPDATE locations
        SET nearbyState = 'MONITORING',
            snoozedUntil = NULL,
            lastSuppressionReason = NULL,
            updatedAt = :now
        WHERE nearbyState = 'SNOOZED' AND snoozedUntil IS NOT NULL AND snoozedUntil <= :now
        """,
    )
    suspend fun clearExpiredSnoozes(now: Long): Int

    @Query(
        """
        UPDATE locations
        SET nearbyState = 'MONITORING',
            lastSuppressionReason = '中断された短時間検証を通常監視へ戻しました',
            updatedAt = :now
        WHERE nearbyState = 'VERIFYING'
          AND (lastVerificationAt IS NULL OR lastVerificationAt <= :cutoff)
        """,
    )
    suspend fun recoverStaleVerifications(cutoff: Long, now: Long): Int
}
