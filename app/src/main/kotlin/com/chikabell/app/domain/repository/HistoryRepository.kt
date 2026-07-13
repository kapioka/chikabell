package com.chikabell.app.domain.repository

import com.chikabell.app.domain.model.NotificationHistory
import com.chikabell.app.domain.model.HistoryFilter
import kotlinx.coroutines.flow.Flow

interface HistoryRepository {
    fun observeHistory(filter: HistoryFilter = HistoryFilter()): Flow<List<NotificationHistory>>
    suspend fun addHistory(history: NotificationHistory)
    /** Runs bounded history retention from the periodic health-check housekeeping path. */
    suspend fun pruneHistory(referenceTimeMillis: Long = System.currentTimeMillis())
    suspend fun deleteAllHistory()
}
