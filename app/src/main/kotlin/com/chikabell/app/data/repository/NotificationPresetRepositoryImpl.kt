package com.chikabell.app.data.repository

import com.chikabell.app.data.database.dao.NotificationPresetDao
import com.chikabell.app.data.database.entity.NotificationPresetEntity
import com.chikabell.app.domain.model.NotificationPreset
import com.chikabell.app.domain.repository.NotificationPresetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class NotificationPresetRepositoryImpl(
    private val presetDao: NotificationPresetDao,
) : NotificationPresetRepository {
    override fun observePresets(): Flow<List<NotificationPreset>> =
        presetDao.observeAll().map { rows -> rows.map(NotificationPresetEntity::toDomain) }

    override suspend fun savePreset(preset: NotificationPreset) {
        presetDao.upsert(preset.toEntity())
    }
}

private fun NotificationPresetEntity.toDomain() = NotificationPreset(
    id = id,
    name = name,
    radiusMeters = radiusMeters,
    loiteringDelaySeconds = loiteringDelaySeconds,
    cooldownMinutes = cooldownMinutes,
    sortOrder = sortOrder,
)

private fun NotificationPreset.toEntity() = NotificationPresetEntity(
    id = id,
    name = name,
    radiusMeters = radiusMeters,
    loiteringDelaySeconds = loiteringDelaySeconds,
    cooldownMinutes = cooldownMinutes,
    sortOrder = sortOrder,
)
