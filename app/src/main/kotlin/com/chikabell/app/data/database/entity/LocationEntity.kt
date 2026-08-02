package com.chikabell.app.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "locations",
    indices = [Index("registrationGenerationId")],
)
data class LocationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val message: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val transitionType: String,
    val loiteringDelayMs: Int?,
    val cooldownMinutes: Long,
    val enabled: Boolean,
    val sourceType: String,
    val sourceUrl: String?,
    val sourceText: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastNotifiedAt: Long?,
    val lastEventAt: Long?,
    val registrationStatus: String,
    val registrationErrorCode: String?,
    val registrationErrorMessage: String?,
    val lastRegisteredAt: Long?,
    val sortOrder: Long,
    /** The most recent Geofencing API registration generation accepted for this location. */
    val registrationGenerationId: String? = null,
    val nearbyState: String = "MONITORING",
    val snoozedUntil: Long? = null,
    val lastVerificationAt: Long? = null,
    val lastValidLocationAt: Long? = null,
    val lastVerificationReason: String? = null,
    val lastSuppressionReason: String? = null,
    val lastAccuracyMeters: Float? = null,
    val lastSpeedMetersPerSecond: Float? = null,
    val lastNotificationDistanceMeters: Float? = null,
)
