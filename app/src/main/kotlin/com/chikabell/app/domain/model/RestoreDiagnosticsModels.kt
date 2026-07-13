package com.chikabell.app.domain.model

data class GeofenceRestoreAttempt(
    val id: String,
    val trigger: RestoreTrigger,
    val startedAt: Long,
    val finishedAt: Long?,
    val result: RestoreAttemptResult,
    val runAttemptCount: Int,
    val enabledCount: Int,
    val registeredCount: Int,
    val errorCode: String?,
    val message: String?,
    val createdAt: Long,
)

data class GeofenceHealthCheckRecord(
    val trigger: RestoreTrigger,
    val startedAt: Long,
    val finishedAt: Long,
    val result: RestoreAttemptResult,
    val enabledCount: Int,
    val registeredCount: Int,
    val notRegisteredCount: Int,
    val errorCount: Int,
    val staleRegisteredCount: Int,
    val oldestRegisteredAt: Long?,
    val newestRegisteredAt: Long?,
    val lastEventAt: Long?,
    val lastNotifiedAt: Long?,
    val shouldRestore: Boolean,
    val restoreReason: String,
    val restoreAttemptId: String?,
    val googlePlayServices: String,
    val locationServices: String,
    val foregroundLocation: String,
    val backgroundLocation: String,
    val notificationPermission: String,
    val errorCode: String?,
    val message: String?,
)

enum class RestoreTrigger { BOOT, PACKAGE_REPLACED, APP_START, MANUAL, HEALTH_CHECK }

enum class RestoreAttemptResult { RUNNING, SUCCESS, SKIPPED, RETRY, BLOCKED, FAILED }
