package com.chikabell.app

import com.chikabell.app.geofence.CurrentLocation
import com.chikabell.app.geofence.DistanceSample
import com.chikabell.app.geofence.MotionBand
import com.chikabell.app.geofence.NearbyVerificationPolicy
import com.chikabell.app.geofence.VerificationDecision
import com.chikabell.app.geofence.DetectedMotion
import com.chikabell.app.geofence.ActivitySnapshot
import com.chikabell.app.geofence.AdaptiveVerificationAction
import com.chikabell.app.domain.model.NearbyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyVerificationPolicyTest {
    @Test fun `speed fallback selects safer motion band`() {
        assertEquals(MotionBand.UNKNOWN, NearbyVerificationPolicy.motionBand(null))
        assertEquals(MotionBand.LOW, NearbyVerificationPolicy.motionBand(1F))
        assertEquals(MotionBand.BICYCLE, NearbyVerificationPolicy.motionBand(4F))
        assertEquals(MotionBand.VEHICLE, NearbyVerificationPolicy.motionBand(15F))
    }

    @Test fun `activity and measured speed use the faster safe band`() {
        assertEquals(MotionBand.LOW, NearbyVerificationPolicy.effectiveMotionBand(DetectedMotion.STILL, 0F))
        assertEquals(MotionBand.WALKING, NearbyVerificationPolicy.effectiveMotionBand(DetectedMotion.WALKING, 1F))
        assertEquals(MotionBand.VEHICLE, NearbyVerificationPolicy.effectiveMotionBand(DetectedMotion.WALKING, 15F))
        assertEquals(MotionBand.VEHICLE, NearbyVerificationPolicy.effectiveMotionBand(DetectedMotion.IN_VEHICLE, 0F))
        assertEquals(10_000L, NearbyVerificationPolicy.samplingIntervalMillis(MotionBand.WALKING))
        assertEquals(3_000L, NearbyVerificationPolicy.samplingIntervalMillis(MotionBand.VEHICLE))
        assertEquals(8_000L, NearbyVerificationPolicy.samplingIntervalMillis(MotionBand.UNKNOWN))
    }

    @Test fun `registration uses fresh activity and vehicle fallback for unknown`() {
        assertEquals(MotionBand.WALKING, NearbyVerificationPolicy.registrationMotionBand(DetectedMotion.WALKING))
        assertEquals(MotionBand.BICYCLE, NearbyVerificationPolicy.registrationMotionBand(DetectedMotion.ON_BICYCLE))
        assertEquals(MotionBand.VEHICLE, NearbyVerificationPolicy.registrationMotionBand(DetectedMotion.IN_VEHICLE))
        assertEquals(MotionBand.VEHICLE, NearbyVerificationPolicy.registrationMotionBand(DetectedMotion.UNKNOWN))
        assertTrue(NearbyVerificationPolicy.shouldRefreshLeadRingForActivity(NearbyState.MONITORING, null))
        assertFalse(NearbyVerificationPolicy.shouldRefreshLeadRingForActivity(NearbyState.VERIFYING, null))
        assertFalse(
            NearbyVerificationPolicy.shouldRefreshLeadRingForActivity(
                NearbyState.MONITORING,
                NearbyVerificationPolicy.FOLLOW_UP_ARMED_MARKER,
            ),
        )
    }

    @Test fun `stale sample is rejected`() {
        val decision = NearbyVerificationPolicy.decide(500, listOf(sample(100F, age = 61_000L)))
        assertTrue(decision is VerificationDecision.Rejected)
    }

    @Test fun `sample without a monotonic age is rejected`() {
        val decision = NearbyVerificationPolicy.decide(500, listOf(sample(100F, age = null)))
        assertTrue(decision is VerificationDecision.Rejected)
    }

    @Test fun `accurate sample sufficiently inside confirms immediately`() {
        val decision = NearbyVerificationPolicy.decide(500, listOf(sample(300F, accuracy = 25F)))
        assertTrue(decision is VerificationDecision.Confirmed)
    }

    @Test fun `boundary needs two usable samples`() {
        val one = NearbyVerificationPolicy.decide(500, listOf(sample(490F, accuracy = 60F)))
        assertTrue(one is VerificationDecision.NeedsMoreEvidence)
        val two = NearbyVerificationPolicy.decide(
            500,
            listOf(
                sample(490F, accuracy = 60F, elapsedRealtimeMillis = 2_000L),
                sample(470F, accuracy = 55F, elapsedRealtimeMillis = 1_000L),
            ),
        )
        assertTrue(two is VerificationDecision.Confirmed)
    }

    @Test fun `same monotonic fix is not counted as two boundary confirmations`() {
        val decision = NearbyVerificationPolicy.decide(
            500,
            listOf(
                sample(490F, accuracy = 60F, age = 1_000L, elapsedRealtimeMillis = 5_000L),
                sample(470F, accuracy = 55F, age = 1_100L, elapsedRealtimeMillis = 5_000L),
            ),
        )

        assertTrue(decision is VerificationDecision.NeedsMoreEvidence)
    }

    @Test fun `rearm distance exceeds notification radius`() {
        assertEquals(800F, NearbyVerificationPolicy.rearmDistanceMeters(500))
        assertTrue(
            NearbyVerificationPolicy.preVerificationRadiusMeters(800) >=
                NearbyVerificationPolicy.rearmDistanceMeters(800),
        )
        assertEquals(7_500, NearbyVerificationPolicy.preVerificationRadiusMeters(5_000, MotionBand.VEHICLE))
    }

    @Test fun `invalid coordinates accuracy and age are unusable`() {
        assertFalse(NearbyVerificationPolicy.isUsable(CurrentLocation(91.0, 139.0, 20F, "test", 1_000L)))
        assertFalse(NearbyVerificationPolicy.isUsable(CurrentLocation(35.0, 181.0, 20F, "test", 1_000L)))
        assertFalse(NearbyVerificationPolicy.isUsable(CurrentLocation(35.0, 139.0, null, "test", 1_000L)))
        assertFalse(NearbyVerificationPolicy.isUsable(CurrentLocation(35.0, 139.0, 20F, "test", -1L)))
    }

    @Test fun `distance display rounds by accuracy and hides unusable values`() {
        assertEquals("推定約70m", NearbyVerificationPolicy.roundedDistanceText(74F, 20F))
        assertEquals("推定約50m", NearbyVerificationPolicy.roundedDistanceText(74F, 50F))
        assertEquals("推定約100m", NearbyVerificationPolicy.roundedDistanceText(174F, 100F))
        assertEquals(null, NearbyVerificationPolicy.roundedDistanceText(174F, 250F))
    }

    @Test fun `physically impossible jump is rejected`() {
        val decision = NearbyVerificationPolicy.decide(
            500,
            listOf(
                sample(200F, latitude = 36.0, elapsedRealtimeMillis = 2_000L),
                sample(250F, latitude = 35.0, elapsedRealtimeMillis = 1_000L),
            ),
        )

        assertTrue(decision is VerificationDecision.Rejected)
        assertTrue((decision as VerificationDecision.Rejected).reason.contains("物理的に不可能"))
        assertTrue(decision.terminal)
    }

    @Test fun `consecutive samples moving away terminate verification`() {
        val decision = NearbyVerificationPolicy.decide(
            500,
            listOf(
                sample(700F, elapsedRealtimeMillis = 3_000L),
                sample(600F, elapsedRealtimeMillis = 2_000L),
                sample(500F, elapsedRealtimeMillis = 1_000L),
            ),
        )

        assertTrue(decision is VerificationDecision.Rejected)
        assertTrue((decision as VerificationDecision.Rejected).reason.contains("遠ざかっています"))
        assertTrue(decision.terminal)
    }

    @Test fun `fresh still requires a recent still transition`() {
        assertTrue(NearbyVerificationPolicy.isFreshStill(ActivitySnapshot(DetectedMotion.STILL, 1_000L), 121_000L))
        assertFalse(NearbyVerificationPolicy.isFreshStill(ActivitySnapshot(DetectedMotion.STILL, 1_000L), 121_001L))
        assertFalse(NearbyVerificationPolicy.isFreshStill(ActivitySnapshot(DetectedMotion.WALKING, 120_000L), 121_000L))
        assertFalse(NearbyVerificationPolicy.isFreshStill(ActivitySnapshot(DetectedMotion.STILL, null), 121_000L))
    }

    @Test fun `three low speed samples over fifteen seconds downgrade to twenty seconds`() {
        val samples = listOf(
            adaptiveSample(0L),
            adaptiveSample(7_000L),
            adaptiveSample(15_000L),
        )

        assertEquals(
            AdaptiveVerificationAction.DOWNGRADE_TO_LOW_FREQUENCY,
            NearbyVerificationPolicy.adaptiveSessionAction(freshStill = true, samples = samples),
        )
        assertEquals(
            AdaptiveVerificationAction.KEEP_CURRENT_INTERVAL,
            NearbyVerificationPolicy.adaptiveSessionAction(freshStill = true, samples = samples.dropLast(1)),
        )
    }

    @Test fun `stale still speed alone and poor accuracy do not downgrade`() {
        val samples = listOf(adaptiveSample(0L), adaptiveSample(8_000L), adaptiveSample(16_000L))
        assertEquals(
            AdaptiveVerificationAction.KEEP_CURRENT_INTERVAL,
            NearbyVerificationPolicy.adaptiveSessionAction(freshStill = false, samples = samples),
        )
        assertEquals(
            AdaptiveVerificationAction.KEEP_CURRENT_INTERVAL,
            NearbyVerificationPolicy.adaptiveSessionAction(
                freshStill = true,
                samples = samples.map { it.copy(accuracyMeters = 250F) },
            ),
        )
    }

    @Test fun `sixty seconds of accurate small movement stops stationary verification`() {
        val samples = listOf(
            adaptiveSample(0L, latitude = 35.0),
            adaptiveSample(15_000L, latitude = 35.00001),
            adaptiveSample(40_000L, latitude = 35.00002),
            adaptiveSample(60_000L, latitude = 35.00003),
        )

        assertEquals(
            AdaptiveVerificationAction.STOP_STATIONARY,
            NearbyVerificationPolicy.adaptiveSessionAction(freshStill = true, samples = samples),
        )
    }

    @Test fun `large movement prevents stationary stop but retains low frequency`() {
        val samples = listOf(
            adaptiveSample(0L, latitude = 35.0),
            adaptiveSample(20_000L, latitude = 35.0),
            adaptiveSample(40_000L, latitude = 35.0),
            adaptiveSample(60_000L, latitude = 35.001),
        )

        assertEquals(
            AdaptiveVerificationAction.DOWNGRADE_TO_LOW_FREQUENCY,
            NearbyVerificationPolicy.adaptiveSessionAction(freshStill = true, samples = samples),
        )
    }

    private fun adaptiveSample(
        elapsedRealtimeMillis: Long,
        latitude: Double = 35.0,
        accuracy: Float = 15F,
        speed: Float = 0.2F,
    ) = CurrentLocation(
        latitude = latitude,
        longitude = 139.0,
        accuracyMeters = accuracy,
        provider = "test",
        ageMillis = 0L,
        speedMetersPerSecond = speed,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
    )

    private fun sample(
        distance: Float,
        accuracy: Float = 30F,
        age: Long? = 1_000L,
        latitude: Double = 35.0,
        elapsedRealtimeMillis: Long? = null,
    ) = DistanceSample(
        location = CurrentLocation(
            latitude,
            139.0,
            accuracy,
            "test",
            age,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
        ),
        distanceMeters = distance,
    )
}
