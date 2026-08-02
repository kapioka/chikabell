package com.chikabell.app.geofence

import com.chikabell.app.domain.model.NearbyState
import kotlin.math.max
import kotlin.math.min

/** Central policy for the bounded, speed-based verification fallback. */
object NearbyVerificationPolicy {
    const val MAX_SAMPLE_AGE_MILLIS = 60_000L
    const val MAX_ACCURACY_METERS = 200F
    const val IMMEDIATE_ACCURACY_METERS = 75F
    const val SNOOZE_DURATION_MILLIS = 12L * 60L * 60L * 1_000L
    const val MAX_VERIFICATION_SESSION_MILLIS = 3L * 60L * 1_000L
    const val MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND = 100F
    const val MIN_IMPOSSIBLE_JUMP_METERS = 1_000F
    const val FOLLOW_UP_ARMED_MARKER = "[FOLLOW_UP_ARMED]"
    const val LOW_FREQUENCY_INTERVAL_MILLIS = 20_000L
    const val LOW_SPEED_THRESHOLD_METERS_PER_SECOND = 0.8F
    const val LOW_FREQUENCY_AFTER_MILLIS = 15_000L
    const val STATIONARY_STOP_AFTER_MILLIS = 60_000L
    const val MIN_STATIONARY_SAMPLES = 3
    const val MIN_STATIONARY_MOVEMENT_METERS = 30F
    const val FRESH_STILL_MAX_AGE_MILLIS = 2L * 60L * 1_000L

    fun motionBand(speedMetersPerSecond: Float?): MotionBand = when {
        speedMetersPerSecond == null -> MotionBand.UNKNOWN
        speedMetersPerSecond < 1.5F -> MotionBand.LOW
        speedMetersPerSecond < 6F -> MotionBand.BICYCLE
        else -> MotionBand.VEHICLE
    }

    fun motionBand(activity: DetectedMotion): MotionBand = when (activity) {
        DetectedMotion.STILL -> MotionBand.LOW
        DetectedMotion.WALKING -> MotionBand.WALKING
        DetectedMotion.RUNNING, DetectedMotion.ON_BICYCLE -> MotionBand.BICYCLE
        DetectedMotion.IN_VEHICLE -> MotionBand.VEHICLE
        DetectedMotion.UNKNOWN -> MotionBand.UNKNOWN
    }

    fun effectiveMotionBand(activity: DetectedMotion, speedMetersPerSecond: Float?): MotionBand {
        val activityBand = motionBand(activity)
        val speedBand = motionBand(speedMetersPerSecond)
        return if (rank(speedBand) > rank(activityBand)) speedBand else activityBand
    }

    /**
     * Geofence registration must fail safe when activity is unavailable or stale.
     * A measured speed is not available during registration, so UNKNOWN uses the
     * vehicle-sized outer ring until a fresh activity transition replaces it.
     */
    fun registrationMotionBand(activity: DetectedMotion): MotionBand {
        val band = motionBand(activity)
        return if (band == MotionBand.UNKNOWN) MotionBand.VEHICLE else band
    }

    fun shouldRefreshLeadRingForActivity(state: NearbyState, verificationReason: String?): Boolean {
        return state != NearbyState.VERIFYING &&
            verificationReason?.contains(FOLLOW_UP_ARMED_MARKER) != true
    }

    fun samplingIntervalMillis(band: MotionBand): Long = when (band) {
        MotionBand.LOW -> LOW_FREQUENCY_INTERVAL_MILLIS
        MotionBand.WALKING -> 10_000L
        MotionBand.BICYCLE -> 5_000L
        MotionBand.VEHICLE -> 3_000L
        MotionBand.UNKNOWN -> 8_000L
    }

    fun preVerificationMarginMeters(speedMetersPerSecond: Float?): Int = when (motionBand(speedMetersPerSecond)) {
        MotionBand.LOW -> 200
        MotionBand.WALKING -> 200
        MotionBand.BICYCLE -> 400
        MotionBand.VEHICLE -> 700
        MotionBand.UNKNOWN -> 300
    }

    fun preVerificationMarginMeters(band: MotionBand): Int = when (band) {
        MotionBand.LOW -> 200
        MotionBand.WALKING -> 200
        MotionBand.BICYCLE -> 400
        MotionBand.VEHICLE -> 700
        MotionBand.UNKNOWN -> 300
    }

    fun preVerificationRadiusMeters(notificationRadiusMeters: Int, speedMetersPerSecond: Float? = null): Int {
        return preVerificationRadiusMeters(notificationRadiusMeters, motionBand(speedMetersPerSecond))
    }

    fun preVerificationRadiusMeters(notificationRadiusMeters: Int, band: MotionBand): Int {
        return max(
            notificationRadiusMeters + preVerificationMarginMeters(band),
            rearmDistanceMeters(notificationRadiusMeters).toInt(),
        )
            .coerceAtLeast(notificationRadiusMeters)
    }

    private fun rank(band: MotionBand): Int = when (band) {
        MotionBand.LOW -> 0
        MotionBand.WALKING -> 1
        MotionBand.UNKNOWN -> 2
        MotionBand.BICYCLE -> 3
        MotionBand.VEHICLE -> 4
    }

    fun rearmDistanceMeters(notificationRadiusMeters: Int): Float {
        return max(notificationRadiusMeters + 300F, notificationRadiusMeters * 1.5F)
    }

    fun isUsable(sample: CurrentLocation): Boolean {
        val age = sample.ageMillis
        val accuracy = sample.accuracyMeters
        return sample.latitude in -90.0..90.0 &&
            sample.longitude in -180.0..180.0 &&
            age != null && age in 0..MAX_SAMPLE_AGE_MILLIS &&
            accuracy != null && accuracy in 0F..MAX_ACCURACY_METERS
    }

    fun decide(notificationRadiusMeters: Int, samples: List<DistanceSample>): VerificationDecision {
        val usable = samples.filter { isUsable(it.location) }
            .sortedWith(compareByDescending { it.location.elapsedRealtimeMillis ?: Long.MIN_VALUE })
            .distinctBy(::sampleIdentity)
        if (usable.isEmpty()) {
            return VerificationDecision.Rejected(
                reason = "有効な位置情報を取得できません",
                kind = VerificationRejectionKind.NO_USABLE_LOCATION,
            )
        }
        if (containsImpossibleJump(usable)) {
            return VerificationDecision.Rejected(
                reason = "物理的に不可能な位置変化を破棄しました",
                terminal = true,
                kind = VerificationRejectionKind.IMPOSSIBLE_JUMP,
            )
        }

        val newest = usable.first()
        val accuracy = newest.location.accuracyMeters ?: return VerificationDecision.Rejected(
            reason = "位置精度を取得できません",
            kind = VerificationRejectionKind.MISSING_ACCURACY,
        )
        if (newest.distanceMeters + accuracy <= notificationRadiusMeters && accuracy <= IMMEDIATE_ACCURACY_METERS) {
            return VerificationDecision.Confirmed(newest, "通知範囲の十分内側で確認しました")
        }

        val inside = usable.filter { it.distanceMeters <= notificationRadiusMeters }
        if (inside.size >= 2) {
            val selected = inside.minBy(DistanceSample::distanceMeters)
            return VerificationDecision.Confirmed(selected, "境界付近を複数測位で確認しました")
        }

        if (isMovingAway(usable)) {
            return VerificationDecision.Rejected(
                reason = "通知地点から継続的に遠ざかっています",
                terminal = true,
                kind = VerificationRejectionKind.MOVING_AWAY,
            )
        }

        return if (inside.isNotEmpty()) {
            VerificationDecision.NeedsMoreEvidence(inside.first(), "境界付近のため追加確認が必要です")
        } else {
            VerificationDecision.Rejected(
                reason = "通知範囲外です",
                kind = VerificationRejectionKind.OUTSIDE,
            )
        }
    }

    private fun sampleIdentity(sample: DistanceSample): Any {
        return sample.location.elapsedRealtimeMillis
            ?: Triple(sample.location.latitude, sample.location.longitude, sample.location.ageMillis)
    }

    private fun containsImpossibleJump(samplesNewestFirst: List<DistanceSample>): Boolean {
        return samplesNewestFirst.zipWithNext().any { (newer, older) ->
            val newerAt = newer.location.elapsedRealtimeMillis ?: return@any false
            val olderAt = older.location.elapsedRealtimeMillis ?: return@any false
            val elapsedSeconds = (newerAt - olderAt) / 1_000F
            if (elapsedSeconds <= 0F) return@any false
            val travelled = DistanceCalculator.distanceMeters(
                newer.location.latitude,
                newer.location.longitude,
                older.location.latitude,
                older.location.longitude,
            )
            val accuracyAllowance =
                (newer.location.accuracyMeters ?: 0F) + (older.location.accuracyMeters ?: 0F)
            val allowed = max(
                MIN_IMPOSSIBLE_JUMP_METERS,
                MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND * elapsedSeconds + accuracyAllowance,
            )
            travelled > allowed
        }
    }

    private fun isMovingAway(samplesNewestFirst: List<DistanceSample>): Boolean {
        if (samplesNewestFirst.size < 2) return false
        return samplesNewestFirst.zipWithNext().all { (newer, older) ->
            val noiseAllowance = max(
                25F,
                max(newer.location.accuracyMeters ?: 0F, older.location.accuracyMeters ?: 0F),
            )
            newer.distanceMeters > older.distanceMeters + noiseAllowance
        }
    }

    fun roundedDistanceText(distanceMeters: Float, accuracyMeters: Float?): String? {
        if (accuracyMeters == null || accuracyMeters > MAX_ACCURACY_METERS) return null
        val step = when {
            accuracyMeters <= 25F -> 10
            accuracyMeters <= 75F -> 50
            else -> 100
        }
        val rounded = ((distanceMeters / step).toInt() * step).coerceAtLeast(step)
        return "推定約${min(rounded, 99_900)}m"
    }

    fun isFreshStill(snapshot: ActivitySnapshot, nowMillis: Long): Boolean {
        val updatedAt = snapshot.updatedAt ?: return false
        return snapshot.state == DetectedMotion.STILL &&
            nowMillis - updatedAt in 0..FRESH_STILL_MAX_AGE_MILLIS
    }

    fun adaptiveSessionAction(
        freshStill: Boolean,
        samples: List<CurrentLocation>,
    ): AdaptiveVerificationAction {
        if (!freshStill) return AdaptiveVerificationAction.KEEP_CURRENT_INTERVAL
        val chronological = samples
            .filter(::isUsable)
            .filter { it.elapsedRealtimeMillis != null && it.speedMetersPerSecond != null }
            .distinctBy(CurrentLocation::elapsedRealtimeMillis)
            .sortedBy(CurrentLocation::elapsedRealtimeMillis)
        val consecutiveLowSpeed = chronological.takeLastWhile { sample ->
            (sample.speedMetersPerSecond ?: Float.MAX_VALUE) < LOW_SPEED_THRESHOLD_METERS_PER_SECOND
        }
        if (consecutiveLowSpeed.size < MIN_STATIONARY_SAMPLES) {
            return AdaptiveVerificationAction.KEEP_CURRENT_INTERVAL
        }
        val firstAt = consecutiveLowSpeed.first().elapsedRealtimeMillis ?: return AdaptiveVerificationAction.KEEP_CURRENT_INTERVAL
        val lastAt = consecutiveLowSpeed.last().elapsedRealtimeMillis ?: return AdaptiveVerificationAction.KEEP_CURRENT_INTERVAL
        val lowSpeedDuration = lastAt - firstAt
        if (lowSpeedDuration >= STATIONARY_STOP_AFTER_MILLIS &&
            hasSmallAccuracyAdjustedMovement(consecutiveLowSpeed)
        ) {
            return AdaptiveVerificationAction.STOP_STATIONARY
        }
        return if (lowSpeedDuration >= LOW_FREQUENCY_AFTER_MILLIS) {
            AdaptiveVerificationAction.DOWNGRADE_TO_LOW_FREQUENCY
        } else {
            AdaptiveVerificationAction.KEEP_CURRENT_INTERVAL
        }
    }

    private fun hasSmallAccuracyAdjustedMovement(samples: List<CurrentLocation>): Boolean {
        val newestAt = samples.lastOrNull()?.elapsedRealtimeMillis ?: return false
        val window = samples.dropWhile { sample ->
            val sampledAt = sample.elapsedRealtimeMillis ?: return@dropWhile true
            newestAt - sampledAt > STATIONARY_STOP_AFTER_MILLIS
        }
        val anchor = window.firstOrNull() ?: return false
        val span = newestAt - (anchor.elapsedRealtimeMillis ?: return false)
        if (span < STATIONARY_STOP_AFTER_MILLIS) return false
        return window.all { sample ->
            val distance = DistanceCalculator.distanceMeters(
                anchor.latitude,
                anchor.longitude,
                sample.latitude,
                sample.longitude,
            )
            val allowance = max(
                MIN_STATIONARY_MOVEMENT_METERS,
                max(anchor.accuracyMeters ?: 0F, sample.accuracyMeters ?: 0F),
            )
            distance <= allowance
        }
    }
}

enum class AdaptiveVerificationAction {
    KEEP_CURRENT_INTERVAL,
    DOWNGRADE_TO_LOW_FREQUENCY,
    STOP_STATIONARY,
}

enum class MotionBand { LOW, WALKING, BICYCLE, VEHICLE, UNKNOWN }

data class DistanceSample(
    val location: CurrentLocation,
    val distanceMeters: Float,
)

sealed interface VerificationDecision {
    data class Confirmed(val sample: DistanceSample, val reason: String) : VerificationDecision
    data class NeedsMoreEvidence(val sample: DistanceSample, val reason: String) : VerificationDecision
    data class Rejected(
        val reason: String,
        val terminal: Boolean = false,
        val kind: VerificationRejectionKind,
    ) : VerificationDecision
}

enum class VerificationRejectionKind {
    NO_USABLE_LOCATION,
    IMPOSSIBLE_JUMP,
    MISSING_ACCURACY,
    MOVING_AWAY,
    OUTSIDE,
}
