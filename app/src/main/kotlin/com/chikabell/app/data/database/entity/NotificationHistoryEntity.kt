package com.chikabell.app.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notification_history",
    foreignKeys = [
        ForeignKey(
            entity = LocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("eventAt"),
        Index("deliveryStatus"),
        Index("locationId"),
        Index("registrationGenerationId"),
    ],
)
data class NotificationHistoryEntity(
    @PrimaryKey val id: String,
    val locationId: String?,
    val locationNameSnapshot: String,
    val messageSnapshot: String,
    val latitudeSnapshot: Double,
    val longitudeSnapshot: Double,
    val radiusSnapshot: Int,
    val deviceLatitude: Double?,
    val deviceLongitude: Double?,
    val deviceAccuracyMeters: Float?,
    val deviceLocationAt: Long?,
    val deviceLocationProvider: String?,
    val transitionType: String,
    val eventAt: Long,
    val postedAt: Long?,
    val deliveryStatus: String,
    val deliveryReason: String?,
    val userState: String,
    val readAt: Long?,
    val completedAt: Long?,
    val dismissedAt: Long?,
    val createdAt: Long,
    /** Links an observed transition to the accepted registration generation, when known. */
    val registrationGenerationId: String? = null,
)
