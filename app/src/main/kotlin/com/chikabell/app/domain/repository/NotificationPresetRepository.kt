package com.chikabell.app.domain.repository

import com.chikabell.app.domain.model.NotificationPreset
import kotlinx.coroutines.flow.Flow

interface NotificationPresetRepository {
    fun observePresets(): Flow<List<NotificationPreset>>
    suspend fun savePreset(preset: NotificationPreset)
}
