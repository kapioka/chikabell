package com.chikabell.app.geofence

import com.chikabell.app.domain.model.TransitionType
import kotlinx.coroutines.withTimeout

/** A broadcast owns only the handoff, never the location verification or its mutex. */
object GeofenceEventHandoff {
    const val TIMEOUT_MILLIS = 8_000L

    suspend fun deliver(
        requestIds: List<String>,
        transition: TransitionType,
        eventAt: Long,
        startService: (List<String>, TransitionType, Long) -> Boolean,
        enqueue: suspend (List<String>, TransitionType, Long) -> Unit,
        timeoutMillis: Long = TIMEOUT_MILLIS,
    ) = withTimeout(timeoutMillis) {
        val ids = requestIds.filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return@withTimeout
        if (transition != TransitionType.EXIT && startService(ids, transition, eventAt)) {
            return@withTimeout
        }
        // Await persistence only. WorkManager owns execution after the receiver finishes.
        enqueue(ids, transition, eventAt)
    }
}
