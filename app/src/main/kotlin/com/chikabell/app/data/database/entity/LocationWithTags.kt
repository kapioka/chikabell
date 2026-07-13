package com.chikabell.app.data.database.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class LocationWithTags(
    @Embedded val location: LocationEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = LocationTagCrossRef::class,
            parentColumn = "locationId",
            entityColumn = "tagId",
        ),
    )
    val tags: List<TagEntity>,
)
