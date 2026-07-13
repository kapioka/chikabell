package com.chikabell.app.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "location_tags",
    primaryKeys = ["locationId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = LocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("locationId"),
        Index("tagId"),
    ],
)
data class LocationTagCrossRef(
    val locationId: String,
    val tagId: String,
    val sortOrder: Int,
)
