package com.chikabell.app.geofence

import kotlinx.coroutines.withTimeoutOrNull

/** Keeps one Play services geofence delivery inside a single short verification budget. */
object GeofenceEventBatchProcessor {
    suspend fun processWithinBudget(
        requestIds: List<String>,
        budgetMillis: Long = NearbyVerificationPolicy.MAX_VERIFICATION_SESSION_MILLIS,
        process: suspend (String) -> Unit,
    ): Boolean {
        return withTimeoutOrNull(budgetMillis) {
            requestIds.distinct().forEach { requestId -> process(requestId) }
            true
        } ?: false
    }
}
