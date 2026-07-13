package com.chikabell.app.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Evidence that a Geofencing API registration request was accepted or rejected.
 *
 * This intentionally contains no location names, identifiers, coordinates, or notification text.
 * The [generationId] is copied to locations and received notification history only after an
 * accepted request, allowing those records to be correlated without duplicating location data.
 */
@Entity(
    tableName = "geofence_registration_attempts",
    indices = [
        Index(value = ["generationId"], unique = true),
        Index("startedAt"),
        Index("result"),
        Index("source"),
    ],
)
data class GeofenceRegistrationAttemptEntity(
    @PrimaryKey val id: String,
    val generationId: String,
    val source: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val result: String,
    val requestedCount: Int,
    val acceptedCount: Int,
    val errorCode: String?,
    val message: String?,
    val createdAt: Long,
)
