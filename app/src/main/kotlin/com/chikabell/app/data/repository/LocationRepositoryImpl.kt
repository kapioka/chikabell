package com.chikabell.app.data.repository

import com.chikabell.app.data.database.dao.LocationDao
import com.chikabell.app.data.database.entity.LocationEntity
import com.chikabell.app.data.database.entity.LocationTagCrossRef
import com.chikabell.app.data.database.entity.LocationWithTags
import com.chikabell.app.data.database.entity.TagEntity
import com.chikabell.app.domain.model.LocationDraft
import com.chikabell.app.domain.model.LocationTag
import com.chikabell.app.domain.model.RegistrationStatus
import com.chikabell.app.domain.model.SavedLocation
import com.chikabell.app.domain.model.SourceType
import com.chikabell.app.domain.model.TagRules
import com.chikabell.app.domain.model.TransitionType
import com.chikabell.app.domain.repository.LocationRepository
import java.util.UUID
import com.chikabell.app.importexport.LocationImportCandidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LocationRepositoryImpl(
    private val locationDao: LocationDao,
) : LocationRepository {
    override fun observeLocations(): Flow<List<SavedLocation>> {
        return locationDao.observeAllWithTags().map { locations ->
            locations.map(LocationWithTags::toDomain)
        }
    }

    override suspend fun getEnabledLocations(): List<SavedLocation> {
        return locationDao.getEnabledWithTagsOnce().map(LocationWithTags::toDomain)
    }

    override suspend fun getLocationById(id: String): SavedLocation? {
        return locationDao.getByIdWithTags(id)?.toDomain()
    }

    override suspend fun addLocation(draft: LocationDraft) {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        locationDao.insert(
            LocationEntity(
                id = id,
                name = draft.name.trim(),
                message = draft.message.trim(),
                latitude = draft.latitude,
                longitude = draft.longitude,
                radiusMeters = draft.radiusMeters,
                transitionType = TransitionType.DWELL.name,
                loiteringDelayMs = draft.loiteringDelaySeconds * 1_000,
                cooldownMinutes = draft.cooldownMinutes,
                enabled = draft.enabled,
                sourceType = draft.sourceType.name,
                sourceUrl = draft.sourceUrl,
                sourceText = draft.sourceText,
                createdAt = now,
                updatedAt = now,
                lastNotifiedAt = null,
                lastEventAt = null,
                registrationStatus = RegistrationStatus.INACTIVE.name,
                registrationErrorCode = null,
                registrationErrorMessage = null,
                lastRegisteredAt = null,
                sortOrder = locationDao.nextSortOrder(),
            ),
        )
        replaceLocationTags(id, draft.tags)
    }

    override suspend fun addImportedLocations(candidates: List<LocationImportCandidate>) {
        if (candidates.isEmpty()) return
        val now = System.currentTimeMillis()
        var sortOrder = locationDao.nextSortOrder()
        val imported = candidates.map { candidate ->
            val draft = candidate.draft
            val id = candidate.originalId?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
            LocationEntity(
                id = id,
                name = draft.name.trim(),
                message = draft.message.trim(),
                latitude = draft.latitude,
                longitude = draft.longitude,
                radiusMeters = draft.radiusMeters,
                transitionType = TransitionType.DWELL.name,
                loiteringDelayMs = draft.loiteringDelaySeconds * 1_000,
                cooldownMinutes = draft.cooldownMinutes,
                enabled = draft.enabled,
                sourceType = draft.sourceType.name,
                sourceUrl = draft.sourceUrl,
                sourceText = draft.sourceText,
                createdAt = now,
                updatedAt = now,
                lastNotifiedAt = null,
                lastEventAt = null,
                registrationStatus = RegistrationStatus.INACTIVE.name,
                registrationErrorCode = null,
                registrationErrorMessage = null,
                lastRegisteredAt = null,
                sortOrder = sortOrder++,
            ) to draft.tags
        }
        locationDao.insertAll(imported.map { it.first })
        imported.forEach { (entity, tags) -> replaceLocationTags(entity.id, tags) }
    }

    override suspend fun updateLocation(location: SavedLocation, draft: LocationDraft) {
        locationDao.update(
            location.toEntity().copy(
                name = draft.name.trim(),
                message = draft.message.trim(),
                latitude = draft.latitude,
                longitude = draft.longitude,
                radiusMeters = draft.radiusMeters,
                transitionType = TransitionType.DWELL.name,
                loiteringDelayMs = draft.loiteringDelaySeconds * 1_000,
                cooldownMinutes = draft.cooldownMinutes,
                enabled = draft.enabled,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        replaceLocationTags(location.id, draft.tags)
    }

    override suspend fun updateLocationTags(location: SavedLocation, tags: List<String>) {
        replaceLocationTags(location.id, tags)
    }

    override suspend fun deleteLocation(location: SavedLocation) {
        locationDao.delete(location.toEntity())
    }

    override suspend fun deleteLocations(ids: Set<String>) {
        if (ids.isNotEmpty()) locationDao.deleteByIds(ids.toList())
    }

    override suspend fun setLocationsEnabled(ids: Set<String>, enabled: Boolean) {
        if (ids.isEmpty()) return
        locationDao.setEnabled(
            ids = ids.toList(),
            enabled = enabled,
            registrationStatus = RegistrationStatus.INACTIVE.name,
            updatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun markRegistrationStatus(
        locationId: String,
        status: RegistrationStatus,
        errorCode: String?,
        errorMessage: String?,
        registeredAt: Long?,
        registrationGenerationId: String?,
    ) {
        locationDao.updateRegistrationStatus(
            id = locationId,
            status = status.name,
            errorCode = errorCode,
            errorMessage = errorMessage,
            registeredAt = registeredAt,
            registrationGenerationId = registrationGenerationId,
            updatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun markInactive(locationId: String) {
        locationDao.clearRegistrationStatus(
            id = locationId,
            status = RegistrationStatus.INACTIVE.name,
            updatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun markLastEvent(locationId: String, eventAt: Long) {
        locationDao.updateLastEvent(
            id = locationId,
            eventAt = eventAt,
            updatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun markLastNotified(locationId: String, notifiedAt: Long) {
        locationDao.updateLastNotified(
            id = locationId,
            notifiedAt = notifiedAt,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun replaceLocationTags(locationId: String, rawTags: List<String>) {
        val names = TagRules.sanitizeNames(rawTags)
        if (rawTags.size > TagRules.MAX_TAGS_PER_LOCATION || names.size > TagRules.MAX_TAGS_PER_LOCATION) {
            error("1地点に設定できるタグは${TagRules.MAX_TAGS_PER_LOCATION}個までです")
        }
        locationDao.deleteTagsForLocation(locationId)
        names.forEachIndexed { index, name ->
            val normalized = TagRules.normalize(name)
            val existing = locationDao.findTagByNormalizedName(normalized)
            val tag = existing ?: run {
                if (locationDao.countTags() >= TagRules.MAX_TAGS_TOTAL) {
                    error("タグは全体で${TagRules.MAX_TAGS_TOTAL}個までです")
                }
                val now = System.currentTimeMillis()
                val created = TagEntity(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    normalizedName = normalized,
                    createdAt = now,
                    updatedAt = now,
                )
                locationDao.insertTag(created)
                created
            }
            locationDao.insertLocationTag(LocationTagCrossRef(locationId = locationId, tagId = tag.id, sortOrder = index))
        }
    }
}

private fun LocationWithTags.toDomain(): SavedLocation {
    return location.toDomain(tags.map(TagEntity::toDomain))
}

private fun LocationEntity.toDomain(): SavedLocation {
    return toDomain(emptyList())
}

private fun LocationEntity.toDomain(tags: List<LocationTag>): SavedLocation {
    return SavedLocation(
        id = id,
        name = name,
        message = message,
        latitude = latitude,
        longitude = longitude,
        radiusMeters = radiusMeters,
        transitionType = TransitionType.valueOf(transitionType),
        loiteringDelayMs = loiteringDelayMs,
        cooldownMinutes = cooldownMinutes,
        enabled = enabled,
        sourceType = SourceType.valueOf(sourceType),
        sourceUrl = sourceUrl,
        sourceText = sourceText,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastNotifiedAt = lastNotifiedAt,
        lastEventAt = lastEventAt,
        registrationStatus = RegistrationStatus.valueOf(registrationStatus),
        registrationErrorCode = registrationErrorCode,
        registrationErrorMessage = registrationErrorMessage,
        lastRegisteredAt = lastRegisteredAt,
        registrationGenerationId = registrationGenerationId,
        sortOrder = sortOrder,
        tags = tags,
    )
}

private fun TagEntity.toDomain(): LocationTag {
    return LocationTag(
        id = id,
        name = name,
        normalizedName = normalizedName,
    )
}

private fun SavedLocation.toEntity(): LocationEntity {
    return LocationEntity(
        id = id,
        name = name,
        message = message,
        latitude = latitude,
        longitude = longitude,
        radiusMeters = radiusMeters,
        transitionType = transitionType.name,
        loiteringDelayMs = loiteringDelayMs,
        cooldownMinutes = cooldownMinutes,
        enabled = enabled,
        sourceType = sourceType.name,
        sourceUrl = sourceUrl,
        sourceText = sourceText,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastNotifiedAt = lastNotifiedAt,
        lastEventAt = lastEventAt,
        registrationStatus = registrationStatus.name,
        registrationErrorCode = registrationErrorCode,
        registrationErrorMessage = registrationErrorMessage,
        lastRegisteredAt = lastRegisteredAt,
        sortOrder = sortOrder,
        registrationGenerationId = registrationGenerationId,
    )
}
