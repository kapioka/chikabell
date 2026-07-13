package com.chikabell.app.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "geofence_health_checks",
    indices = [
        Index("startedAt"),
        Index("result"),
        Index("trigger"),
    ],
)
data class GeofenceHealthCheckEntity(
    @PrimaryKey val id: String,
    val trigger: String,
    val startedAt: Long,
    val finishedAt: Long,
    val result: String,
    val enabledCount: Int,
    val registeredCount: Int,
    val notRegisteredCount: Int,
    val errorCount: Int,
    val staleRegisteredCount: Int,
    val oldestRegisteredAt: Long?,
    val newestRegisteredAt: Long?,
    val lastEventAt: Long?,
    val lastNotifiedAt: Long?,
    val shouldRestore: Boolean,
    val restoreReason: String,
    val restoreAttemptId: String?,
    val googlePlayServices: String,
    val locationServices: String,
    val foregroundLocation: String,
    val backgroundLocation: String,
    val notificationPermission: String,
    val errorCode: String?,
    val message: String?,
    val createdAt: Long,
)
