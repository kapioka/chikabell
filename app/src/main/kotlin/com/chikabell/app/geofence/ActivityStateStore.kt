package com.chikabell.app.geofence

import android.content.Context

interface ActivityStateSource { fun read(now: Long = System.currentTimeMillis()): ActivitySnapshot }

class ActivityStateStore(context: Context) : ActivityStateSource {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(now: Long): ActivitySnapshot {
        val state = runCatching {
            DetectedMotion.valueOf(preferences.getString(KEY_STATE, null) ?: DetectedMotion.UNKNOWN.name)
        }.getOrDefault(DetectedMotion.UNKNOWN)
        val updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L).takeIf { it > 0L }
        return if (updatedAt == null || now - updatedAt > STALE_AFTER_MILLIS) {
            ActivitySnapshot(DetectedMotion.UNKNOWN, updatedAt)
        } else {
            ActivitySnapshot(state, updatedAt)
        }
    }

    fun update(state: DetectedMotion, updatedAt: Long = System.currentTimeMillis()) {
        preferences.edit().putString(KEY_STATE, state.name).putLong(KEY_UPDATED_AT, updatedAt).apply()
    }

    companion object {
        const val STALE_AFTER_MILLIS = 10L * 60L * 1_000L
        private const val PREFERENCES = "nearby_activity_state"
        private const val KEY_STATE = "state"
        private const val KEY_UPDATED_AT = "updated_at"
    }
}

data class ActivitySnapshot(val state: DetectedMotion, val updatedAt: Long?)

enum class DetectedMotion { STILL, WALKING, RUNNING, ON_BICYCLE, IN_VEHICLE, UNKNOWN }
