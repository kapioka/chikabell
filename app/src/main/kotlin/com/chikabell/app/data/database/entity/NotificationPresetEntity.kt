package com.chikabell.app.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notification_presets")
data class NotificationPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val radiusMeters: Int,
    val loiteringDelaySeconds: Int,
    val cooldownMinutes: Long,
    val sortOrder: Int,
)
