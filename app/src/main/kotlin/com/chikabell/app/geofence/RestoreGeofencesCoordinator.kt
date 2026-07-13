package com.chikabell.app.geofence

import android.os.SystemClock
import com.chikabell.app.domain.model.GeofenceHealthCheckRecord
import com.chikabell.app.domain.model.RegistrationStatus
import com.chikabell.app.domain.model.RestoreAttemptResult
import com.chikabell.app.domain.model.RestoreTrigger
import com.chikabell.app.domain.model.SavedLocation
import com.chikabell.app.domain.repository.LocationRepository
import com.chikabell.app.domain.repository.RestoreDiagnosticsRepository
import com.chikabell.app.domain.repository.HistoryRepository
import com.chikabell.app.domain.repository.RegistrationDiagnosticsRepository
import com.chikabell.app.permission.PermissionSnapshot
import com.chikabell.app.permission.PermissionStateReader

class RestoreGeofencesCoordinator(
    private val locationRepository: LocationRepository,
    private val historyRepository: HistoryRepository,
    private val diagnosticsRepository: RestoreDiagnosticsRepository,
    private val registrationDiagnosticsRepository: RegistrationDiagnosticsRepository,
    private val reconcileGeofencesUseCase: ReconcileGeofencesUseCase,
    private val permissionStateReader: PermissionStateReader,
) {
    suspend fun execute(trigger: RestoreTrigger, runAttemptCount: Int): RestoreExecutionResult {
        val startedAt = System.currentTimeMillis()
        val locations = locationRepository.getEnabledLocations()
        val permissionSnapshot = permissionStateReader.read()
        val healthStats = HealthCheckStats.from(locations, startedAt)
        val attemptId = diagnosticsRepository.start(trigger, runAttemptCount, locations.size)
        val estimatedBootAt = startedAt - SystemClock.elapsedRealtime()
        val decision = RestorePolicy.evaluate(trigger, locations, estimatedBootAt, startedAt)
        if (!decision.shouldRestore) {
            diagnosticsRepository.finish(
                attemptId,
                RestoreAttemptResult.SKIPPED,
                healthStats.registeredCount,
                message = "再登録は不要です",
            )
            recordHealthCheck(
                trigger = trigger,
                startedAt = startedAt,
                locations = locations,
                stats = healthStats,
                permissionSnapshot = permissionSnapshot,
                decision = decision,
                result = RestoreAttemptResult.SKIPPED,
                registeredCount = healthStats.registeredCount,
                restoreAttemptId = attemptId,
                message = "再登録は不要です",
            )
            return RestoreExecutionResult.Success
        }

        return when (val result = reconcileGeofencesUseCase.execute(trigger.name)) {
            is ReconcileResult.Registered -> {
                diagnosticsRepository.finish(attemptId, RestoreAttemptResult.SUCCESS, result.count, message = "監視を復元しました")
                recordHealthCheck(
                    trigger = trigger,
                    startedAt = startedAt,
                    locations = locations,
                    stats = healthStats,
                    permissionSnapshot = permissionSnapshot,
                    decision = decision,
                    result = RestoreAttemptResult.SUCCESS,
                    registeredCount = result.count,
                    restoreAttemptId = attemptId,
                    message = "監視を復元しました",
                )
                RestoreExecutionResult.Success
            }
            is ReconcileResult.Blocked -> {
                diagnosticsRepository.finish(attemptId, RestoreAttemptResult.BLOCKED, 0, "BLOCKED", result.reason)
                recordHealthCheck(
                    trigger = trigger,
                    startedAt = startedAt,
                    locations = locations,
                    stats = healthStats,
                    permissionSnapshot = permissionSnapshot,
                    decision = decision,
                    result = RestoreAttemptResult.BLOCKED,
                    registeredCount = 0,
                    restoreAttemptId = attemptId,
                    errorCode = "BLOCKED",
                    message = result.reason,
                )
                RestoreExecutionResult.Failure
            }
            is ReconcileResult.Failed -> {
                if (RestorePolicy.shouldRetry(result.errorCode, runAttemptCount)) {
                    diagnosticsRepository.finish(attemptId, RestoreAttemptResult.RETRY, 0, result.errorCode, "一時的なエラーです")
                    recordHealthCheck(
                        trigger = trigger,
                        startedAt = startedAt,
                        locations = locations,
                        stats = healthStats,
                        permissionSnapshot = permissionSnapshot,
                        decision = decision,
                        result = RestoreAttemptResult.RETRY,
                        registeredCount = 0,
                        restoreAttemptId = attemptId,
                        errorCode = result.errorCode,
                        message = "一時的なエラーです",
                    )
                    RestoreExecutionResult.Retry
                } else {
                    diagnosticsRepository.finish(attemptId, RestoreAttemptResult.FAILED, 0, result.errorCode, result.message)
                    recordHealthCheck(
                        trigger = trigger,
                        startedAt = startedAt,
                        locations = locations,
                        stats = healthStats,
                        permissionSnapshot = permissionSnapshot,
                        decision = decision,
                        result = RestoreAttemptResult.FAILED,
                        registeredCount = 0,
                        restoreAttemptId = attemptId,
                        errorCode = result.errorCode,
                        message = result.message,
                    )
                    RestoreExecutionResult.Failure
                }
            }
        }
    }

    private suspend fun recordHealthCheck(
        trigger: RestoreTrigger,
        startedAt: Long,
        locations: List<SavedLocation>,
        stats: HealthCheckStats,
        permissionSnapshot: PermissionSnapshot,
        decision: RestoreDecision,
        result: RestoreAttemptResult,
        registeredCount: Int,
        restoreAttemptId: String,
        errorCode: String? = null,
        message: String? = null,
    ) {
        diagnosticsRepository.recordHealthCheck(
            GeofenceHealthCheckRecord(
                trigger = trigger,
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                result = result,
                enabledCount = locations.size,
                registeredCount = registeredCount,
                notRegisteredCount = stats.notRegisteredCount,
                errorCount = stats.errorCount,
                staleRegisteredCount = stats.staleRegisteredCount,
                oldestRegisteredAt = stats.oldestRegisteredAt,
                newestRegisteredAt = stats.newestRegisteredAt,
                lastEventAt = stats.lastEventAt,
                lastNotifiedAt = stats.lastNotifiedAt,
                shouldRestore = decision.shouldRestore,
                restoreReason = decision.reason,
                restoreAttemptId = restoreAttemptId,
                googlePlayServices = permissionSnapshot.googlePlayServices.name,
                locationServices = permissionSnapshot.locationServices.name,
                foregroundLocation = permissionSnapshot.foregroundLocation.name,
                backgroundLocation = permissionSnapshot.backgroundLocation.name,
                notificationPermission = permissionSnapshot.notificationPermission.name,
                errorCode = errorCode,
                message = message,
            ),
        )
        if (trigger == RestoreTrigger.HEALTH_CHECK) {
            try {
                historyRepository.pruneHistory()
                registrationDiagnosticsRepository.prune()
            } catch (_: Exception) {
                // Housekeeping is best-effort and must not turn a successful health check into retry work.
            }
        }
    }
}

enum class RestoreExecutionResult { Success, Retry, Failure }

private data class HealthCheckStats(
    val registeredCount: Int,
    val notRegisteredCount: Int,
    val errorCount: Int,
    val staleRegisteredCount: Int,
    val oldestRegisteredAt: Long?,
    val newestRegisteredAt: Long?,
    val lastEventAt: Long?,
    val lastNotifiedAt: Long?,
) {
    companion object {
        fun from(locations: List<SavedLocation>, now: Long): HealthCheckStats {
            val registeredTimes = locations.mapNotNull(SavedLocation::lastRegisteredAt)
            return HealthCheckStats(
                registeredCount = locations.count { it.registrationStatus == RegistrationStatus.REGISTERED },
                notRegisteredCount = locations.count { it.registrationStatus != RegistrationStatus.REGISTERED },
                errorCount = locations.count { it.registrationStatus == RegistrationStatus.ERROR },
                staleRegisteredCount = locations.count {
                    it.registrationStatus == RegistrationStatus.REGISTERED &&
                        ((it.lastRegisteredAt ?: 0L) <= now - RestorePolicy.HEALTH_CHECK_MIN_RESTORE_INTERVAL_MS)
                },
                oldestRegisteredAt = registeredTimes.minOrNull(),
                newestRegisteredAt = registeredTimes.maxOrNull(),
                lastEventAt = locations.mapNotNull(SavedLocation::lastEventAt).maxOrNull(),
                lastNotifiedAt = locations.mapNotNull(SavedLocation::lastNotifiedAt).maxOrNull(),
            )
        }
    }
}
