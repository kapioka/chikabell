package com.chikabell.app.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.chikabell.app.data.database.entity.NotificationPresetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationPresetDao {
    @Query("SELECT * FROM notification_presets ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<NotificationPresetEntity>>

    @Upsert
    suspend fun upsert(preset: NotificationPresetEntity)
}
