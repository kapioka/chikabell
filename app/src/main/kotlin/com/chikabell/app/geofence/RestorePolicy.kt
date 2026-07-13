package com.chikabell.app.geofence

import com.chikabell.app.domain.model.RegistrationStatus
import com.chikabell.app.domain.model.RestoreTrigger
import com.chikabell.app.domain.model.SavedLocation

object RestorePolicy {
    private const val BOOT_TOLERANCE_MS = 5 * 60 * 1_000L
    const val HEALTH_CHECK_MIN_RESTORE_INTERVAL_MS = 8 * 60 * 60 * 1_000L

    fun evaluate(
        trigger: RestoreTrigger,
        locations: List<SavedLocation>,
        estimatedBootAt: Long,
        now: Long = System.currentTimeMillis(),
    ): RestoreDecision {
        if (trigger == RestoreTrigger.BOOT ||
            trigger == RestoreTrigger.MANUAL ||
            trigger == RestoreTrigger.PACKAGE_REPLACED
        ) {
            return RestoreDecision(shouldRestore = true, reason = trigger.name)
        }
        if (locations.isEmpty()) return RestoreDecision(shouldRestore = false, reason = "NO_ENABLED_LOCATIONS")
        if (locations.any { it.registrationStatus != RegistrationStatus.REGISTERED }) {
            return RestoreDecision(shouldRestore = true, reason = "NOT_REGISTERED_LOCATION")
        }

        return when (trigger) {
            RestoreTrigger.BOOT,
            RestoreTrigger.MANUAL,
            RestoreTrigger.PACKAGE_REPLACED,
            -> RestoreDecision(shouldRestore = true, reason = trigger.name)
            RestoreTrigger.APP_START -> {
                val shouldRestore = locations.any {
                    val registeredAt = it.lastRegisteredAt ?: return@any true
                    registeredAt < estimatedBootAt - BOOT_TOLERANCE_MS
                }
                RestoreDecision(
                    shouldRestore = shouldRestore,
                    reason = if (shouldRestore) "REGISTERED_BEFORE_BOOT" else "REGISTERED_CURRENT_BOOT",
                )
            }
            RestoreTrigger.HEALTH_CHECK -> {
                val shouldRestore = locations.any {
                    val registeredAt = it.lastRegisteredAt ?: return@any true
                    registeredAt <= now - HEALTH_CHECK_MIN_RESTORE_INTERVAL_MS
                }
                RestoreDecision(
                    shouldRestore = shouldRestore,
                    reason = if (shouldRestore) "REGISTERED_STALE_8H" else "REGISTERED_FRESH",
                )
            }
        }
    }

    fun shouldRestore(
        trigger: RestoreTrigger,
        locations: List<SavedLocation>,
        estimatedBootAt: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean = evaluate(trigger, locations, estimatedBootAt, now).shouldRestore

    fun shouldRetry(errorCode: String, runAttemptCount: Int): Boolean {
        if (runAttemptCount >= 2) return false
        return errorCode.contains("ApiException") ||
            errorCode.contains("Timeout") ||
            errorCode.contains("IOException")
    }
}

data class RestoreDecision(
    val shouldRestore: Boolean,
    val reason: String,
)
