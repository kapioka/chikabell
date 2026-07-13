package com.chikabell.app.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "geofence_restore_attempts")
data class GeofenceRestoreAttemptEntity(
    @PrimaryKey val id: String,
    val trigger: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val result: String,
    val runAttemptCount: Int,
    val enabledCount: Int,
    val registeredCount: Int,
    val errorCode: String?,
    val message: String?,
    val createdAt: Long,
)
