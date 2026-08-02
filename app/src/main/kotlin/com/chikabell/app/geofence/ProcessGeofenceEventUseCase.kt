package com.chikabell.app.geofence

import com.chikabell.app.domain.model.DeliveryStatus
import com.chikabell.app.domain.model.HistoryUserState
import com.chikabell.app.domain.model.NotificationHistory
import com.chikabell.app.domain.model.NearbyState
import com.chikabell.app.domain.model.SavedLocation
import com.chikabell.app.domain.model.TransitionType
import com.chikabell.app.domain.repository.HistoryRepository
import com.chikabell.app.domain.repository.LocationRepository
import com.chikabell.app.notification.NearbyNotificationGateway
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ProcessGeofenceEventUseCase(
    private val locationRepository: LocationRepository,
    private val historyRepository: HistoryRepository,
    private val notificationPoster: NearbyNotificationGateway,
    private val currentLocationReader: CurrentLocationSource,
    private val activityStateStore: ActivityStateSource,
    private val verificationGeofenceGateway: VerificationGeofenceGateway,
) {
    private val eventMutex = Mutex()
    private val activeVerificationJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    suspend fun execute(
        requestId: String,
        transitionType: TransitionType,
        eventAt: Long,
    ): ProcessGeofenceEventResult {
        if (transitionType == TransitionType.EXIT) {
            activeVerificationJobs[requestId]?.cancel(
                CancellationException("事前確認領域から退出したため短時間検証を終了しました"),
            )
            return eventMutex.withLock { executeLocked(requestId, transitionType, eventAt) }
        }

        return eventMutex.withLock {
            val job = currentCoroutineContext().job
            activeVerificationJobs[requestId] = job
            try {
                executeLocked(requestId, transitionType, eventAt)
            } finally {
                activeVerificationJobs.remove(requestId, job)
            }
        }
    }

    private suspend fun executeLocked(
        requestId: String,
        transitionType: TransitionType,
        eventAt: Long,
    ): ProcessGeofenceEventResult {
        var location = locationRepository.getLocationById(requestId)
            ?: return ProcessGeofenceEventResult.Ignored("Location not found")

        if (!location.enabled) {
            return saveSuppressed(location, transitionType, eventAt, "地点が無効です")
        }
        if (transitionType == TransitionType.EXIT) {
            if (location.snoozedUntil?.let { eventAt < it } == true) {
                return saveTracked(location, transitionType, eventAt, "12時間休止を維持したまま事前確認領域から退出しました")
            }
            locationRepository.updateNearbyState(
                locationId = location.id,
                state = NearbyState.MONITORING,
                verifiedAt = eventAt,
                verificationReason = "${GeofenceEventPolicy.EXIT_REARMED_MARKER} 事前確認領域から退出したため再通知可能になりました",
            )
            return saveTracked(location, transitionType, eventAt, "通知ポイントから出ました")
        }
        if (transitionType !in setOf(TransitionType.ENTER, TransitionType.DWELL)) {
            return ProcessGeofenceEventResult.Ignored("Transition mismatch")
        }

        val snoozedUntil = location.snoozedUntil
        if (snoozedUntil != null && eventAt < snoozedUntil) {
            return saveSuppressed(location, transitionType, eventAt, "12時間休止中です")
        }
        if (snoozedUntil != null && eventAt >= snoozedUntil) {
            locationRepository.clearSnooze(location.id)
            location = location.copy(nearbyState = NearbyState.MONITORING, snoozedUntil = null)
        }
        if (location.nearbyState == NearbyState.REARM_WAIT) {
            return saveSuppressed(location, transitionType, eventAt, "同じ接近中のため再通知しません")
        }
        val lastEventAt = location.lastEventAt
        val isArmedFollowUp = location.lastVerificationReason?.contains(
            NearbyVerificationPolicy.FOLLOW_UP_ARMED_MARKER,
        ) == true
        val wasRearmedByExit = location.lastVerificationReason?.contains(
            GeofenceEventPolicy.EXIT_REARMED_MARKER,
        ) == true
        if (!isArmedFollowUp && !wasRearmedByExit && GeofenceEventPolicy.isDuplicateEvent(lastEventAt, eventAt)) {
            return saveSuppressed(location, transitionType, eventAt, "短時間の重複イベントです")
        }
        if (!locationRepository.claimVerification(
                locationId = location.id,
                verifiedAt = eventAt,
                reason = "事前確認領域への進入で短時間検証を開始しました",
            )
        ) {
            return saveSuppressed(location, transitionType, eventAt, "別の短時間検証セッションが実行中です")
        }

        var notificationPostAttemptedFor = emptyList<ConfirmedNearby>()
        var diagnosticContext: String? = null
        var verificationSession: VerificationSessionResult? = null
        try {
        val firstSamples = readDistanceSamples(location)
        var allCurrentLocations = firstSamples.map(DistanceSample::location)
        var decision = NearbyVerificationPolicy.decide(location.radiusMeters, firstSamples)
        val activitySnapshot = activityStateStore.read(eventAt)
        val band = NearbyVerificationPolicy.effectiveMotionBand(
            activitySnapshot.state,
            firstSamples.firstOrNull()?.location?.speedMetersPerSecond,
        )
        val intervalMillis = NearbyVerificationPolicy.samplingIntervalMillis(band)
        val freshStill = NearbyVerificationPolicy.isFreshStill(activitySnapshot, eventAt)
        diagnosticContext = "trigger=${transitionType.name}, activity=${activitySnapshot.state.name}, interval=${intervalMillis}ms"
        val initialValidLocation = firstSamples
            .map(DistanceSample::location)
            .firstOrNull(NearbyVerificationPolicy::isUsable)
        if (decision !is VerificationDecision.Confirmed &&
            !(decision is VerificationDecision.Rejected && decision.terminal)
        ) {
            locationRepository.updateNearbyState(
                locationId = location.id,
                state = NearbyState.VERIFYING,
                verifiedAt = firstSamples.firstOrNull()?.location?.sampledAt(eventAt) ?: eventAt,
                lastValidLocationAt = initialValidLocation?.sampledAt(eventAt),
                verificationReason = "短時間検証中: $diagnosticContext",
                accuracyMeters = firstSamples.firstOrNull()?.location?.accuracyMeters,
                speedMetersPerSecond = firstSamples.firstOrNull()?.location?.speedMetersPerSecond,
            )
            val session = currentLocationReader.readAdaptiveVerificationLocations(
                intervalMillis = intervalMillis,
                maxDurationMillis = NearbyVerificationPolicy.MAX_VERIFICATION_SESSION_MILLIS,
                maxSamples = maxVerificationSamples(intervalMillis),
                freshStill = freshStill,
                stopWhen = { pendingLocations ->
                    when (val pendingDecision = NearbyVerificationPolicy.decide(
                        location.radiusMeters,
                        distanceSamples(location, pendingLocations) + firstSamples,
                    )) {
                        is VerificationDecision.Confirmed -> true
                        is VerificationDecision.Rejected -> pendingDecision.terminal
                        is VerificationDecision.NeedsMoreEvidence -> false
                    }
                },
            )
            verificationSession = session
            diagnosticContext = "trigger=${transitionType.name}, activity=${activitySnapshot.state.name}, " +
                "interval=${session.initialIntervalMillis}ms->${session.finalIntervalMillis}ms, " +
                "samples=${session.samples.size}, duration=${session.durationMillis}ms, " +
                "end=${session.endReason.diagnosticLabel()}"
            val additionalLocations = session.samples
            val additionalSamples = distanceSamples(location, additionalLocations)
            allCurrentLocations = (allCurrentLocations + additionalSamples.map(DistanceSample::location)).distinct()
            decision = NearbyVerificationPolicy.decide(
                location.radiusMeters,
                (additionalSamples + firstSamples).distinctBy { sample ->
                    Triple(sample.location.latitude, sample.location.longitude, sample.location.ageMillis)
                },
            )
        }
        if (decision !is VerificationDecision.Confirmed) {
            val reason = if (verificationSession?.endReason == VerificationSessionEndReason.STATIONARY) {
                "静止が続いたため高頻度の位置確認を終了しました"
            } else when (decision) {
                is VerificationDecision.Confirmed -> decision.reason
                is VerificationDecision.NeedsMoreEvidence -> decision.reason
                is VerificationDecision.Rejected -> decision.reason
            }
            val sample = when (decision) {
                is VerificationDecision.Confirmed -> decision.sample
                is VerificationDecision.NeedsMoreEvidence -> decision.sample
                is VerificationDecision.Rejected -> when (decision.kind) {
                    VerificationRejectionKind.OUTSIDE,
                    VerificationRejectionKind.MOVING_AWAY,
                    -> distanceSamples(location, allCurrentLocations)
                        .filter { NearbyVerificationPolicy.isUsable(it.location) }
                        .maxByOrNull { it.location.elapsedRealtimeMillis ?: Long.MIN_VALUE }
                    VerificationRejectionKind.NO_USABLE_LOCATION,
                    VerificationRejectionKind.IMPOSSIBLE_JUMP,
                    VerificationRejectionKind.MISSING_ACCURACY,
                    -> null
                }
            }
            val shouldRestoreLeadRing = isArmedFollowUp ||
                (decision is VerificationDecision.Rejected && decision.terminal)
            val followUpReason = if (shouldRestoreLeadRing) {
                "$diagnosticContext / $reason / 追加検証を終了して通常リングへ戻しました"
            } else {
                "$diagnosticContext / $reason / ${NearbyVerificationPolicy.FOLLOW_UP_ARMED_MARKER}"
            }
            locationRepository.updateNearbyState(
                locationId = location.id,
                state = NearbyState.MONITORING,
                verifiedAt = sample?.location?.sampledAt(eventAt) ?: eventAt,
                lastValidLocationAt = sample?.location?.sampledAt(eventAt),
                verificationReason = followUpReason,
                suppressionReason = reason,
                accuracyMeters = sample?.location?.accuracyMeters,
                speedMetersPerSecond = sample?.location?.speedMetersPerSecond,
            )
            val ringUpdated = if (shouldRestoreLeadRing) {
                verificationGeofenceGateway.restoreLeadRing(location)
            } else {
                verificationGeofenceGateway.armConfirmationRing(location)
            }
            if (!ringUpdated) {
                val registrationFailure = if (shouldRestoreLeadRing) {
                    "事前確認リングの復元に失敗しました"
                } else {
                    "確認リングの登録に失敗しました"
                }
                locationRepository.updateNearbyState(
                    locationId = location.id,
                    state = NearbyState.MONITORING,
                    verifiedAt = sample?.location?.sampledAt(eventAt) ?: eventAt,
                    lastValidLocationAt = sample?.location?.sampledAt(eventAt),
                    verificationReason = "$diagnosticContext / $reason / $registrationFailure",
                    suppressionReason = reason,
                    accuracyMeters = sample?.location?.accuracyMeters,
                    speedMetersPerSecond = sample?.location?.speedMetersPerSecond,
                )
            }
            return saveSuppressed(location, transitionType, eventAt, reason)
        }

        val confirmedLocations = locationRepository.getEnabledLocations()
            .filter { candidate ->
                candidate.snoozedUntil?.let { it <= eventAt } != false &&
                    candidate.nearbyState !in setOf(NearbyState.SNOOZED, NearbyState.REARM_WAIT)
            }
            .mapNotNull { candidate ->
                val candidateDecision = if (candidate.id == location.id) {
                    decision
                } else {
                    NearbyVerificationPolicy.decide(
                        candidate.radiusMeters,
                        distanceSamples(candidate, allCurrentLocations),
                    )
                }
                (candidateDecision as? VerificationDecision.Confirmed)?.let {
                    ConfirmedNearby(candidate, it)
                }
            }
        if (confirmedLocations.isEmpty()) {
            locationRepository.updateNearbyState(location.id, NearbyState.MONITORING)
            return saveSuppressed(location, transitionType, eventAt, "通知対象地点を確認できません")
        }

        val canPost = notificationPoster.canPostNotifications()
        val channelEnabled = notificationPoster.isChannelEnabled()
        val postedAt = if (canPost && channelEnabled) System.currentTimeMillis() else null
        val deliveryStatus = if (postedAt != null) DeliveryStatus.POSTED else DeliveryStatus.FAILED
        val reason = when {
            !canPost -> "通知権限がありません"
            !channelEnabled -> "通知チャンネルが無効です"
            else -> null
        }
        val histories = confirmedLocations.map { confirmed ->
            confirmed.location.toHistory(
                transitionType = transitionType,
                eventAt = eventAt,
                postedAt = postedAt,
                deliveryStatus = deliveryStatus,
                reason = reason,
                deviceLocation = confirmed.decision.sample.location,
            )
        }
        for (history in histories) {
            historyRepository.addHistory(history)
        }
        confirmedLocations.forEach { locationRepository.markLastEvent(it.location.id, eventAt) }
        if (postedAt != null) {
            notificationPostAttemptedFor = confirmedLocations
            notificationPoster.post(histories)
            confirmedLocations.forEach { confirmed ->
                val sample = confirmed.decision.sample
                locationRepository.markLastNotified(confirmed.location.id, postedAt)
                locationRepository.updateNearbyState(
                    locationId = confirmed.location.id,
                    state = NearbyState.REARM_WAIT,
                    verifiedAt = sample.location.sampledAt(eventAt),
                    lastValidLocationAt = sample.location.sampledAt(eventAt),
                    verificationReason = "$diagnosticContext / ${confirmed.decision.reason}",
                    accuracyMeters = sample.location.accuracyMeters,
                    speedMetersPerSecond = sample.location.speedMetersPerSecond,
                    notificationDistanceMeters = sample.distanceMeters,
                )
                verificationGeofenceGateway.restoreLeadRing(confirmed.location)
            }
            return ProcessGeofenceEventResult.NotificationPosted
        }

        confirmedLocations.forEach { confirmed ->
            val sample = confirmed.decision.sample
            locationRepository.updateNearbyState(
                locationId = confirmed.location.id,
                state = NearbyState.MONITORING,
                verifiedAt = sample.location.sampledAt(eventAt),
                lastValidLocationAt = sample.location.sampledAt(eventAt),
                verificationReason = "$diagnosticContext / ${confirmed.decision.reason}",
                suppressionReason = reason,
                accuracyMeters = sample.location.accuracyMeters,
                speedMetersPerSecond = sample.location.speedMetersPerSecond,
                notificationDistanceMeters = sample.distanceMeters,
            )
        }

        return ProcessGeofenceEventResult.HistorySavedWithoutNotification(reason ?: "通知できません")
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                recoverAfterFailure(
                    triggeringLocation = location,
                    notificationPostAttemptedFor = notificationPostAttemptedFor,
                    eventAt = eventAt,
                    reason = listOfNotNull(diagnosticContext, "短時間検証がキャンセルされました").joinToString(" / "),
                )
            }
            throw cancelled
        } catch (error: Exception) {
            val errorReason = listOfNotNull(
                diagnosticContext,
                "短時間検証の内部エラーで終了しました (${error::class.simpleName ?: "unknown"})",
            ).joinToString(" / ")
            withContext(NonCancellable) {
                recoverAfterFailure(
                    triggeringLocation = location,
                    notificationPostAttemptedFor = notificationPostAttemptedFor,
                    eventAt = eventAt,
                    reason = errorReason,
                )
            }
            if (notificationPostAttemptedFor.isNotEmpty()) {
                return ProcessGeofenceEventResult.NotificationPosted
            }
            return runCatching {
                saveSuppressed(location, transitionType, eventAt, errorReason)
            }.getOrElse {
                ProcessGeofenceEventResult.HistorySavedWithoutNotification(errorReason)
            }
        }
    }

    private suspend fun recoverAfterFailure(
        triggeringLocation: SavedLocation,
        notificationPostAttemptedFor: List<ConfirmedNearby>,
        eventAt: Long,
        reason: String,
    ) {
        if (notificationPostAttemptedFor.isEmpty()) {
            runCatching {
                locationRepository.updateNearbyState(
                    locationId = triggeringLocation.id,
                    state = NearbyState.MONITORING,
                    verifiedAt = System.currentTimeMillis(),
                    verificationReason = reason,
                    suppressionReason = reason,
                )
            }
            return
        }

        notificationPostAttemptedFor.forEach { confirmed ->
            val sample = confirmed.decision.sample
            runCatching {
                locationRepository.updateNearbyState(
                    locationId = confirmed.location.id,
                    state = NearbyState.REARM_WAIT,
                    verifiedAt = sample.location.sampledAt(eventAt),
                    lastValidLocationAt = sample.location.sampledAt(eventAt),
                    verificationReason = "${confirmed.decision.reason} / $reason",
                    suppressionReason = reason,
                    accuracyMeters = sample.location.accuracyMeters,
                    speedMetersPerSecond = sample.location.speedMetersPerSecond,
                    notificationDistanceMeters = sample.distanceMeters,
                )
            }
        }
    }

    private suspend fun readDistanceSamples(location: SavedLocation): List<DistanceSample> {
        return try {
            distanceSamples(location, currentLocationReader.readCandidateLocations())
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun distanceSamples(
        location: SavedLocation,
        currentLocations: List<CurrentLocation>,
    ): List<DistanceSample> = currentLocations.map { current ->
        DistanceSample(
            location = current,
            distanceMeters = DistanceCalculator.distanceMeters(
                fromLatitude = current.latitude,
                fromLongitude = current.longitude,
                toLatitude = location.latitude,
                toLongitude = location.longitude,
            ),
        )
    }

    private fun maxVerificationSamples(intervalMillis: Long): Int {
        return (NearbyVerificationPolicy.MAX_VERIFICATION_SESSION_MILLIS / intervalMillis)
            .toInt()
            .coerceIn(MIN_ADDITIONAL_SAMPLES, MAX_ADDITIONAL_SAMPLES)
    }

    private companion object {
        const val MIN_ADDITIONAL_SAMPLES = 2
        const val MAX_ADDITIONAL_SAMPLES = 60
    }

    private suspend fun saveSuppressed(
        location: SavedLocation,
        transitionType: TransitionType,
        eventAt: Long,
        reason: String,
    ): ProcessGeofenceEventResult {
        historyRepository.addHistory(
            location.toHistory(
                transitionType = transitionType,
                eventAt = eventAt,
                postedAt = null,
                deliveryStatus = DeliveryStatus.SUPPRESSED,
                reason = reason,
            ),
        )
        locationRepository.markLastEvent(location.id, eventAt)
        return ProcessGeofenceEventResult.HistorySavedWithoutNotification(reason)
    }

    private suspend fun saveTracked(
        location: SavedLocation,
        transitionType: TransitionType,
        eventAt: Long,
        reason: String,
    ): ProcessGeofenceEventResult {
        historyRepository.addHistory(
            location.toHistory(
                transitionType = transitionType,
                eventAt = eventAt,
                postedAt = null,
                deliveryStatus = DeliveryStatus.TRACKED,
                reason = reason,
            ),
        )
        locationRepository.markLastEvent(location.id, eventAt)
        return ProcessGeofenceEventResult.HistorySavedWithoutNotification(reason)
    }
}

private fun VerificationSessionEndReason.diagnosticLabel(): String = when (this) {
    VerificationSessionEndReason.DECISION_REACHED -> "判定完了"
    VerificationSessionEndReason.STATIONARY -> "静止停止"
    VerificationSessionEndReason.MAX_DURATION -> "最大時間"
    VerificationSessionEndReason.MAX_SAMPLES -> "最大サンプル"
    VerificationSessionEndReason.PERMISSION_MISSING -> "権限不足"
    VerificationSessionEndReason.INTERNAL_ERROR -> "内部エラー"
    VerificationSessionEndReason.LEGACY_COMPLETION -> "従来経路完了"
}

private fun CurrentLocation.sampledAt(referenceTimeMillis: Long): Long {
    return ageMillis?.takeIf { it >= 0L }?.let { referenceTimeMillis - it } ?: referenceTimeMillis
}

private data class ConfirmedNearby(
    val location: SavedLocation,
    val decision: VerificationDecision.Confirmed,
)

sealed interface ProcessGeofenceEventResult {
    data object NotificationPosted : ProcessGeofenceEventResult
    data class HistorySavedWithoutNotification(val reason: String) : ProcessGeofenceEventResult
    data class Ignored(val reason: String) : ProcessGeofenceEventResult
}

private fun SavedLocation.toHistory(
    transitionType: TransitionType,
    eventAt: Long,
    postedAt: Long?,
    deliveryStatus: DeliveryStatus,
    reason: String?,
    deviceLocation: CurrentLocation? = null,
): NotificationHistory {
    val now = System.currentTimeMillis()
    val deviceLocationAt = deviceLocation?.ageMillis?.let { now - it } ?: deviceLocation?.let { now }
    return NotificationHistory(
        id = UUID.randomUUID().toString(),
        locationId = id,
        locationNameSnapshot = name,
        messageSnapshot = message,
        latitudeSnapshot = latitude,
        longitudeSnapshot = longitude,
        radiusSnapshot = radiusMeters,
        deviceLatitude = deviceLocation?.latitude,
        deviceLongitude = deviceLocation?.longitude,
        deviceAccuracyMeters = deviceLocation?.accuracyMeters,
        deviceLocationAt = deviceLocationAt,
        deviceLocationProvider = deviceLocation?.provider,
        transitionType = transitionType,
        eventAt = eventAt,
        postedAt = postedAt,
        deliveryStatus = deliveryStatus,
        deliveryReason = reason,
        userState = HistoryUserState.UNREAD,
        createdAt = now,
        registrationGenerationId = registrationGenerationId,
    )
}
