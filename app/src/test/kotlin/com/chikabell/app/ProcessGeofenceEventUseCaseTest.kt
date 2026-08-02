package com.chikabell.app

import com.chikabell.app.domain.model.HistoryFilter
import com.chikabell.app.domain.model.LocationDraft
import com.chikabell.app.domain.model.NearbyState
import com.chikabell.app.domain.model.NotificationHistory
import com.chikabell.app.domain.model.RegistrationStatus
import com.chikabell.app.domain.model.SavedLocation
import com.chikabell.app.domain.model.SourceType
import com.chikabell.app.domain.model.TransitionType
import com.chikabell.app.domain.repository.HistoryRepository
import com.chikabell.app.domain.repository.LocationRepository
import com.chikabell.app.geofence.CurrentLocation
import com.chikabell.app.geofence.CurrentLocationSource
import com.chikabell.app.geofence.ActivitySnapshot
import com.chikabell.app.geofence.ActivityStateSource
import com.chikabell.app.geofence.DetectedMotion
import com.chikabell.app.geofence.ProcessGeofenceEventResult
import com.chikabell.app.geofence.ProcessGeofenceEventUseCase
import com.chikabell.app.geofence.NearbyVerificationPolicy
import com.chikabell.app.geofence.VerificationGeofenceGateway
import com.chikabell.app.geofence.VerificationSessionEndReason
import com.chikabell.app.geofence.VerificationSessionResult
import com.chikabell.app.importexport.LocationImportCandidate
import com.chikabell.app.notification.NearbyNotificationGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessGeofenceEventUseCaseTest {
    @Test fun `confirmed sample posts once and enters rearm wait`() = runBlocking {
        val fixture = fixture(location())
        val result = fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertEquals(ProcessGeofenceEventResult.NotificationPosted, result)
        assertEquals(1, fixture.poster.posted.size)
        assertEquals(1, fixture.history.items.size)
        assertEquals(NearbyState.REARM_WAIT, fixture.locations.current.nearbyState)
        assertEquals(9_000L, fixture.locations.current.lastValidLocationAt)
        assertTrue(fixture.locations.current.lastVerificationReason?.contains("trigger=ENTER") == true)
        assertTrue(fixture.locations.current.lastVerificationReason?.contains("interval=8000ms") == true)
    }

    @Test fun `snoozed location never starts location verification`() = runBlocking {
        val fixture = fixture(location().copy(nearbyState = NearbyState.SNOOZED, snoozedUntil = 20_000L))
        val result = fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertTrue(result is ProcessGeofenceEventResult.HistorySavedWithoutNotification)
        assertEquals(0, fixture.source.readCount)
        assertEquals(0, fixture.poster.posted.size)
    }

    @Test fun `duplicate event cannot claim an active verification session`() = runBlocking {
        val fixture = fixture(location().copy(nearbyState = NearbyState.VERIFYING))

        val result = fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertTrue(result is ProcessGeofenceEventResult.HistorySavedWithoutNotification)
        assertEquals(0, fixture.source.readCount)
        assertEquals(0, fixture.poster.posted.size)
        assertEquals(NearbyState.VERIFYING, fixture.locations.current.nearbyState)
    }

    @Test fun `recent enter without exit remains suppressed as duplicate`() = runBlocking {
        val fixture = fixture(location().copy(lastEventAt = 9_500L))

        val result = fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertTrue(result is ProcessGeofenceEventResult.HistorySavedWithoutNotification)
        assertEquals(0, fixture.source.readCount)
        assertEquals(0, fixture.poster.posted.size)
        assertEquals("短時間の重複イベントです", fixture.history.items.single().deliveryReason)
    }

    @Test fun `armed confirmation enter bypasses the outer event duplicate window once`() = runBlocking {
        val fixture = fixture(
            location().copy(
                lastEventAt = 9_500L,
                lastVerificationReason = "[FOLLOW_UP_ARMED]",
            ),
        )

        val result = fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertEquals(ProcessGeofenceEventResult.NotificationPosted, result)
        assertEquals(1, fixture.poster.posted.size)
        assertEquals(NearbyState.REARM_WAIT, fixture.locations.current.nearbyState)
    }

    @Test fun `unconfirmed confirmation follow up restores lead ring without arming another follow up`() = runBlocking {
        val fixture = fixture(
            location().copy(
                lastEventAt = 9_500L,
                lastVerificationReason = "[FOLLOW_UP_ARMED]",
            ),
            current = CurrentLocation(36.0, 140.0, 20F, "test", 1_000L, 12F),
        )

        fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertEquals(emptyList<String>(), fixture.geofences.confirmationArmed)
        assertEquals(listOf("place"), fixture.geofences.leadRestored)
        assertTrue(fixture.locations.current.lastVerificationReason?.contains("[FOLLOW_UP_ARMED]") == false)
    }

    @Test fun `exit rearms a notified location without posting`() = runBlocking {
        val fixture = fixture(location().copy(nearbyState = NearbyState.REARM_WAIT))
        fixture.useCase.execute("place", TransitionType.EXIT, 10_000L)

        assertEquals(NearbyState.MONITORING, fixture.locations.current.nearbyState)
        assertEquals(0, fixture.poster.posted.size)
    }

    @Test fun `enter immediately after exit starts a fresh verification`() = runBlocking {
        val fixture = fixture(location().copy(nearbyState = NearbyState.REARM_WAIT))
        fixture.useCase.execute("place", TransitionType.EXIT, 9_500L)

        val result = fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertEquals(ProcessGeofenceEventResult.NotificationPosted, result)
        assertEquals(1, fixture.poster.posted.size)
        assertEquals(NearbyState.REARM_WAIT, fixture.locations.current.nearbyState)
    }

    @Test fun `exit cancels active verification before returning to monitoring`() = runBlocking {
        val fixture = fixture(
            location(),
            current = CurrentLocation(35.009, 139.0, 20F, "outer", 1_000L, 1F),
            blockVerification = true,
        )
        var enterCancelled = false
        val enterJob = launch {
            try {
                fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)
            } catch (_: CancellationException) {
                enterCancelled = true
            }
        }
        fixture.source.verificationStarted.await()

        fixture.useCase.execute("place", TransitionType.EXIT, 11_000L)
        enterJob.join()

        assertTrue(enterCancelled)
        assertTrue(fixture.source.verificationCancelled)
        assertEquals(NearbyState.MONITORING, fixture.locations.current.nearbyState)
        assertEquals(0, fixture.poster.posted.size)
    }

    @Test fun `exit preserves an active twelve hour snooze`() = runBlocking {
        val fixture = fixture(location().copy(nearbyState = NearbyState.SNOOZED, snoozedUntil = 20_000L))

        fixture.useCase.execute("place", TransitionType.EXIT, 10_000L)

        assertEquals(NearbyState.SNOOZED, fixture.locations.current.nearbyState)
        assertEquals(20_000L, fixture.locations.current.snoozedUntil)
        assertEquals(0, fixture.source.readCount)
        assertEquals(0, fixture.poster.posted.size)
    }

    @Test fun `outside sample returns to monitoring and records suppression`() = runBlocking {
        val fixture = fixture(
            location(),
            CurrentLocation(36.0, 140.0, 20F, "test", 1_000L, 12F),
        )
        val result = fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertTrue(result is ProcessGeofenceEventResult.HistorySavedWithoutNotification)
        assertEquals(NearbyState.MONITORING, fixture.locations.current.nearbyState)
        assertEquals(0, fixture.poster.posted.size)
        assertTrue(fixture.locations.current.lastSuppressionReason?.contains("通知範囲外") == true)
        assertEquals(listOf("place"), fixture.geofences.confirmationArmed)
    }

    @Test fun `outer enter keeps one bounded session until an inner sample confirms`() = runBlocking {
        val outer = CurrentLocation(
            35.009,
            139.0,
            20F,
            "outer",
            1_000L,
            1F,
            elapsedRealtimeMillis = 1_000L,
        )
        val inner = CurrentLocation(
            35.0,
            139.0,
            20F,
            "verification",
            1_000L,
            1F,
            elapsedRealtimeMillis = 61_000L,
        )
        val fixture = fixture(location(), current = outer, verificationLocations = listOf(inner))

        val result = fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertEquals(ProcessGeofenceEventResult.NotificationPosted, result)
        assertEquals(1, fixture.poster.posted.size)
        assertEquals(NearbyState.REARM_WAIT, fixture.locations.current.nearbyState)
        assertEquals(NearbyVerificationPolicy.MAX_VERIFICATION_SESSION_MILLIS, fixture.source.lastMaxDurationMillis)
        assertEquals(22, fixture.source.lastMaxSamples)
        assertEquals(listOf("place"), fixture.geofences.leadRestored)
    }

    @Test fun `initial moving away evidence stops without additional location updates`() = runBlocking {
        val movingAway = listOf(
            CurrentLocation(35.0072, 139.0, 20F, "initial", 1_000L, 1F, elapsedRealtimeMillis = 3_000L),
            CurrentLocation(35.0063, 139.0, 20F, "initial", 1_000L, 1F, elapsedRealtimeMillis = 2_000L),
            CurrentLocation(35.0054, 139.0, 20F, "initial", 1_000L, 1F, elapsedRealtimeMillis = 1_000L),
        )
        val fixture = fixture(location(), current = movingAway.first(), candidateLocations = movingAway)

        fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertEquals(1, fixture.source.readCount)
        assertEquals(null, fixture.source.lastMaxDurationMillis)
        assertEquals(emptyList<String>(), fixture.geofences.confirmationArmed)
        assertEquals(listOf("place"), fixture.geofences.leadRestored)
    }

    @Test fun `initial impossible jump stops without additional location updates`() = runBlocking {
        val impossibleJump = listOf(
            CurrentLocation(36.0, 139.0, 20F, "initial", 1_000L, 1F, elapsedRealtimeMillis = 2_000L),
            CurrentLocation(35.001, 139.0, 20F, "initial", 1_000L, 1F, elapsedRealtimeMillis = 1_000L),
        )
        val fixture = fixture(location(), current = impossibleJump.first(), candidateLocations = impossibleJump)

        fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertEquals(1, fixture.source.readCount)
        assertEquals(null, fixture.source.lastMaxDurationMillis)
        assertEquals(emptyList<String>(), fixture.geofences.confirmationArmed)
        assertEquals(listOf("place"), fixture.geofences.leadRestored)
    }

    @Test fun `overlapping locations are grouped and all enter rearm wait`() = runBlocking {
        val second = location().copy(id = "second", name = "2つ目", latitude = 35.0001)
        val fixture = fixture(location(), additional = listOf(second))

        fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertEquals(2, fixture.poster.posted.size)
        assertEquals(2, fixture.history.items.size)
        assertTrue(fixture.locations.all().all { it.nearbyState == NearbyState.REARM_WAIT })
    }

    @Test fun `identical boundary sample is not counted twice`() = runBlocking {
        val fixture = fixture(
            location(),
            CurrentLocation(35.0044, 139.0, 60F, "cached", 1_000L, 1F),
        )

        fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertEquals(2, fixture.source.readCount)
        assertEquals(8_000L, fixture.source.lastIntervalMillis)
        assertEquals(0, fixture.poster.posted.size)
        assertEquals(NearbyState.MONITORING, fixture.locations.current.nearbyState)
    }

    @Test fun `notification permission failure keeps history and returns to monitoring`() = runBlocking {
        val fixture = fixture(location(), canPost = false)

        fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertEquals(0, fixture.poster.posted.size)
        assertEquals(com.chikabell.app.domain.model.DeliveryStatus.FAILED, fixture.history.items.single().deliveryStatus)
        assertEquals(NearbyState.MONITORING, fixture.locations.current.nearbyState)
    }

    @Test fun `internal location failure ends verification and returns to monitoring`() = runBlocking {
        val fixture = fixture(location(), readFailure = IllegalStateException("test failure"))

        val result = fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertTrue(result is ProcessGeofenceEventResult.HistorySavedWithoutNotification)
        assertEquals(NearbyState.MONITORING, fixture.locations.current.nearbyState)
        assertTrue(fixture.locations.current.lastSuppressionReason?.contains("内部エラー") == true)
        assertEquals(null, fixture.locations.current.lastValidLocationAt)
        assertEquals(0, fixture.poster.posted.size)
    }

    @Test fun `persistence failure after notification attempt stays in rearm wait`() = runBlocking {
        val fixture = fixture(
            location(),
            markLastNotifiedFailure = IllegalStateException("database unavailable"),
        )

        val result = fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertEquals(ProcessGeofenceEventResult.NotificationPosted, result)
        assertEquals(1, fixture.poster.posted.size)
        assertEquals(1, fixture.history.items.size)
        assertEquals(NearbyState.REARM_WAIT, fixture.locations.current.nearbyState)
    }

    @Test fun `cancellation after notification attempt stays in rearm wait`() = runBlocking {
        val fixture = fixture(
            location(),
            markLastNotifiedFailure = CancellationException("cancelled after post"),
        )
        var cancelled = false

        try {
            fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(1, fixture.poster.posted.size)
        assertEquals(NearbyState.REARM_WAIT, fixture.locations.current.nearbyState)
    }

    @Test fun `stationary stop arms one confirmation ring without posting`() = runBlocking {
        val outer = CurrentLocation(35.009, 139.0, 20F, "outer", 1_000L, 0.2F, elapsedRealtimeMillis = 1_000L)
        val fixture = fixture(
            location(),
            current = outer,
            verificationLocations = listOf(outer.copy(elapsedRealtimeMillis = 61_000L)),
            verificationEndReason = VerificationSessionEndReason.STATIONARY,
            activityStateSource = FixedActivityStateSource(ActivitySnapshot(DetectedMotion.STILL, 9_000L)),
        )

        val result = fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertTrue(result is ProcessGeofenceEventResult.HistorySavedWithoutNotification)
        assertEquals(0, fixture.poster.posted.size)
        assertEquals(listOf("place"), fixture.geofences.confirmationArmed)
        assertTrue(fixture.locations.current.lastSuppressionReason?.contains("静止") == true)
        assertTrue(fixture.locations.current.lastVerificationReason?.contains("end=静止停止") == true)
    }

    @Test fun `stationary confirmation follow up restores lead ring without chaining`() = runBlocking {
        val outer = CurrentLocation(35.009, 139.0, 20F, "outer", 1_000L, 0.2F, elapsedRealtimeMillis = 1_000L)
        val fixture = fixture(
            location().copy(lastVerificationReason = NearbyVerificationPolicy.FOLLOW_UP_ARMED_MARKER),
            current = outer,
            verificationLocations = listOf(outer.copy(elapsedRealtimeMillis = 61_000L)),
            verificationEndReason = VerificationSessionEndReason.STATIONARY,
            activityStateSource = FixedActivityStateSource(ActivitySnapshot(DetectedMotion.STILL, 9_000L)),
        )

        fixture.useCase.execute("place", TransitionType.ENTER, 10_000L)

        assertEquals(emptyList<String>(), fixture.geofences.confirmationArmed)
        assertEquals(listOf("place"), fixture.geofences.leadRestored)
    }

    @Test fun `confirmed inside sample still posts when session also reports stationary`() = runBlocking {
        val outer = CurrentLocation(35.009, 139.0, 20F, "outer", 1_000L, 0.2F, elapsedRealtimeMillis = 1_000L)
        val inner = CurrentLocation(35.0, 139.0, 20F, "inner", 0L, 0.2F, elapsedRealtimeMillis = 61_000L)
        val fixture = fixture(
            location(),
            current = outer,
            verificationLocations = listOf(inner),
            verificationEndReason = VerificationSessionEndReason.STATIONARY,
            activityStateSource = FixedActivityStateSource(ActivitySnapshot(DetectedMotion.STILL, 9_000L)),
        )

        assertEquals(ProcessGeofenceEventResult.NotificationPosted, fixture.useCase.execute("place", TransitionType.ENTER, 10_000L))
        assertEquals(NearbyState.REARM_WAIT, fixture.locations.current.nearbyState)
        assertEquals(1, fixture.poster.posted.size)
    }

    private fun fixture(
        location: SavedLocation,
        current: CurrentLocation = CurrentLocation(35.0, 139.0, 20F, "test", 1_000L, 1F),
        additional: List<SavedLocation> = emptyList(),
        canPost: Boolean = true,
        readFailure: RuntimeException? = null,
        markLastNotifiedFailure: Throwable? = null,
        verificationLocations: List<CurrentLocation> = listOf(current),
        candidateLocations: List<CurrentLocation> = listOf(current),
        blockVerification: Boolean = false,
        verificationEndReason: VerificationSessionEndReason? = null,
        activityStateSource: ActivityStateSource = FakeActivityStateSource,
    ): Fixture {
        val locations = FakeLocationRepository(location, additional, markLastNotifiedFailure)
        val history = FakeHistoryRepository()
        val poster = FakePoster(canPost)
        val source = FakeSource(current, readFailure, verificationLocations, candidateLocations, blockVerification, verificationEndReason)
        val geofences = FakeVerificationGeofenceGateway()
        return Fixture(
            ProcessGeofenceEventUseCase(
                locations,
                history,
                poster,
                source,
                activityStateSource,
                geofences,
            ),
            locations,
            history,
            poster,
            source,
            geofences,
        )
    }

    private fun location() = SavedLocation(
        id = "place",
        name = "場所",
        message = "",
        latitude = 35.0,
        longitude = 139.0,
        radiusMeters = 500,
        transitionType = TransitionType.DWELL,
        loiteringDelayMs = 60_000,
        cooldownMinutes = 720,
        enabled = true,
        sourceType = SourceType.MANUAL,
        sourceUrl = null,
        sourceText = null,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        lastNotifiedAt = null,
        lastEventAt = null,
        registrationStatus = RegistrationStatus.REGISTERED,
        registrationErrorCode = null,
        registrationErrorMessage = null,
        lastRegisteredAt = 1_000L,
        sortOrder = 1L,
    )

    private data class Fixture(
        val useCase: ProcessGeofenceEventUseCase,
        val locations: FakeLocationRepository,
        val history: FakeHistoryRepository,
        val poster: FakePoster,
        val source: FakeSource,
        val geofences: FakeVerificationGeofenceGateway,
    )
}

private class FakeSource(
    private val current: CurrentLocation,
    private val readFailure: RuntimeException? = null,
    private val verificationLocations: List<CurrentLocation> = listOf(current),
    private val candidateLocations: List<CurrentLocation> = listOf(current),
    private val blockVerification: Boolean = false,
    private val verificationEndReason: VerificationSessionEndReason? = null,
) : CurrentLocationSource {
    var readCount = 0
    var lastIntervalMillis: Long? = null
    var lastMaxDurationMillis: Long? = null
    var lastMaxSamples: Int? = null
    val verificationStarted = CompletableDeferred<Unit>()
    var verificationCancelled = false
    override suspend fun readCurrentLocation() = current.also { readCount++ }
    override suspend fun readCandidateLocations(): List<CurrentLocation> {
        readFailure?.let { throw it }
        readCount++
        return candidateLocations
    }
    override suspend fun readVerificationLocations(
        intervalMillis: Long,
        maxDurationMillis: Long,
        maxSamples: Int,
        stopWhen: (List<CurrentLocation>) -> Boolean,
    ): List<CurrentLocation> {
        readCount++
        lastIntervalMillis = intervalMillis
        lastMaxDurationMillis = maxDurationMillis
        lastMaxSamples = maxSamples
        if (blockVerification) {
            verificationStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                verificationCancelled = true
            }
        }
        val emitted = mutableListOf<CurrentLocation>()
        verificationLocations.take(maxSamples).forEach { sample ->
            emitted += sample
            if (stopWhen(emitted)) return emitted
        }
        return emitted
    }

    override suspend fun readAdaptiveVerificationLocations(
        intervalMillis: Long,
        maxDurationMillis: Long,
        maxSamples: Int,
        freshStill: Boolean,
        stopWhen: (List<CurrentLocation>) -> Boolean,
    ): VerificationSessionResult {
        val samples = readVerificationLocations(intervalMillis, maxDurationMillis, maxSamples, stopWhen)
        return VerificationSessionResult(
            samples = samples,
            endReason = verificationEndReason ?: VerificationSessionEndReason.LEGACY_COMPLETION,
            initialIntervalMillis = intervalMillis,
            finalIntervalMillis = if (verificationEndReason == VerificationSessionEndReason.STATIONARY) 20_000L else intervalMillis,
            durationMillis = if (verificationEndReason == VerificationSessionEndReason.STATIONARY) 60_000L else 0L,
        )
    }
}

private data object FakeActivityStateSource : ActivityStateSource {
    override fun read(now: Long) = ActivitySnapshot(DetectedMotion.UNKNOWN, null)
}

private class FixedActivityStateSource(
    private val snapshot: ActivitySnapshot,
) : ActivityStateSource {
    override fun read(now: Long): ActivitySnapshot = snapshot
}

private class FakeVerificationGeofenceGateway : VerificationGeofenceGateway {
    val confirmationArmed = mutableListOf<String>()
    val leadRestored = mutableListOf<String>()

    override suspend fun armConfirmationRing(location: SavedLocation): Boolean {
        confirmationArmed += location.id
        return true
    }

    override suspend fun restoreLeadRing(location: SavedLocation): Boolean {
        leadRestored += location.id
        return true
    }
}

private class FakePoster(private val allowed: Boolean) : NearbyNotificationGateway {
    val posted = mutableListOf<NotificationHistory>()
    override fun canPostNotifications() = allowed
    override fun isChannelEnabled() = true
    override fun post(histories: List<NotificationHistory>) { posted += histories }
    override fun postTestNotification() = allowed
}

private class FakeHistoryRepository : HistoryRepository {
    val items = mutableListOf<NotificationHistory>()
    override fun observeHistory(filter: HistoryFilter): Flow<List<NotificationHistory>> = flowOf(items)
    override suspend fun addHistory(history: NotificationHistory) { items += history }
    override suspend fun pruneHistory(referenceTimeMillis: Long) = Unit
    override suspend fun deleteAllHistory() { items.clear() }
}

private class FakeLocationRepository(
    initial: SavedLocation,
    additional: List<SavedLocation>,
    private val markLastNotifiedFailure: Throwable? = null,
) : LocationRepository {
    private val values = linkedMapOf(initial.id to initial).apply { additional.forEach { put(it.id, it) } }
    var current: SavedLocation
        get() = values.values.first()
        set(value) { values[value.id] = value }
    fun all(): List<SavedLocation> = values.values.toList()
    override fun observeLocations(): Flow<List<SavedLocation>> = flowOf(all())
    override suspend fun getEnabledLocations() = all().filter(SavedLocation::enabled)
    override suspend fun getLocationById(id: String) = values[id]
    override suspend fun addLocation(draft: LocationDraft) = error("not used")
    override suspend fun addImportedLocations(candidates: List<LocationImportCandidate>) = error("not used")
    override suspend fun updateLocation(location: SavedLocation, draft: LocationDraft) = error("not used")
    override suspend fun updateLocationTags(location: SavedLocation, tags: List<String>) = error("not used")
    override suspend fun deleteLocation(location: SavedLocation) = error("not used")
    override suspend fun deleteLocations(ids: Set<String>) = error("not used")
    override suspend fun setLocationsEnabled(ids: Set<String>, enabled: Boolean) = error("not used")
    override suspend fun markRegistrationStatus(locationId: String, status: RegistrationStatus, errorCode: String?, errorMessage: String?, registeredAt: Long?, registrationGenerationId: String?) = Unit
    override suspend fun markInactive(locationId: String) = Unit
    override suspend fun markLastEvent(locationId: String, eventAt: Long) { update(locationId) { it.copy(lastEventAt = eventAt) } }
    override suspend fun markLastNotified(locationId: String, notifiedAt: Long) {
        markLastNotifiedFailure?.let { throw it }
        update(locationId) { it.copy(lastNotifiedAt = notifiedAt) }
    }
    override suspend fun updateNearbyState(locationId: String, state: NearbyState, snoozedUntil: Long?, verifiedAt: Long?, lastValidLocationAt: Long?, verificationReason: String?, suppressionReason: String?, accuracyMeters: Float?, speedMetersPerSecond: Float?, notificationDistanceMeters: Float?) {
        update(locationId) { it.copy(
            nearbyState = state,
            snoozedUntil = snoozedUntil,
            lastVerificationAt = verifiedAt,
            lastValidLocationAt = lastValidLocationAt ?: it.lastValidLocationAt,
            lastVerificationReason = verificationReason,
            lastSuppressionReason = suppressionReason,
            lastAccuracyMeters = accuracyMeters,
            lastSpeedMetersPerSecond = speedMetersPerSecond,
            lastNotificationDistanceMeters = notificationDistanceMeters,
        ) }
    }
    override suspend fun claimVerification(locationId: String, verifiedAt: Long, reason: String): Boolean {
        val existing = values[locationId] ?: return false
        if (existing.nearbyState != NearbyState.MONITORING) return false
        update(locationId) { it.copy(nearbyState = NearbyState.VERIFYING, lastVerificationAt = verifiedAt, lastVerificationReason = reason) }
        return true
    }
    override suspend fun snoozeLocations(locationIds: Set<String>, snoozedUntil: Long) { locationIds.forEach { id -> update(id) { it.copy(nearbyState = NearbyState.SNOOZED, snoozedUntil = snoozedUntil) } } }
    override suspend fun clearSnooze(locationId: String) { update(locationId) { it.copy(nearbyState = NearbyState.MONITORING, snoozedUntil = null) } }
    override suspend fun refreshExpiredSnoozes(referenceTime: Long): Int = 0
    override suspend fun recoverStaleVerifications(referenceTime: Long): Int = 0
    private fun update(id: String, block: (SavedLocation) -> SavedLocation) { values[id]?.let { values[id] = block(it) } }
}
