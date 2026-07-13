package com.chikabell.app.geofence

object GeofenceEventPolicy {
    const val DUPLICATE_EVENT_WINDOW_MS = 60_000L

    fun isDuplicateEvent(lastEventAt: Long?, eventAt: Long): Boolean {
        return lastEventAt != null && eventAt - lastEventAt < DUPLICATE_EVENT_WINDOW_MS
    }

    fun isInCooldown(lastNotifiedAt: Long?, cooldownMinutes: Long, eventAt: Long): Boolean {
        val cooldownMs = cooldownMinutes * 60_000L
        return lastNotifiedAt != null && cooldownMs > 0 && eventAt < lastNotifiedAt + cooldownMs
    }
}
