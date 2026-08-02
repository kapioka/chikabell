package com.chikabell.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chikabell.app.data.database.AppDatabase
import com.chikabell.app.data.database.entity.LocationEntity
import com.chikabell.app.data.database.entity.NotificationHistoryEntity
import com.chikabell.app.data.database.entity.NotificationPresetEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiRedesignDatabaseTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun bulkEnableAndDeleteAreIsolatedAndAtomicSqlOperations() = runBlocking {
        val dao = database.locationDao()
        dao.insertAll(listOf(location("a", true, 1), location("b", true, 2), location("c", true, 3)))

        dao.setEnabled(listOf("a", "b"), false, "INACTIVE", 2_000L)
        assertEquals(listOf("c"), dao.getEnabledOnce().map(LocationEntity::id))

        dao.deleteByIds(listOf("b", "c"))
        assertEquals(listOf("a"), dao.observeAll().first().map(LocationEntity::id))
    }

    @Test
    fun historyFilteringRunsInDatabaseForNameStatusAndPeriod() = runBlocking {
        val dao = database.notificationHistoryDao()
        dao.insert(history("1", "Office", "POSTED", 1_000L))
        dao.insert(history("2", "Office Annex", "FAILED", 2_000L))
        dao.insert(history("3", "Home", "POSTED", 3_000L))

        assertEquals(
            listOf("2", "1"),
            dao.observeFiltered("office", null, 0L).first().map(NotificationHistoryEntity::id),
        )
        assertEquals(
            listOf("3"),
            dao.observeFiltered("", "POSTED", 2_500L).first().map(NotificationHistoryEntity::id),
        )
    }

    @Test
    fun historyPruningKeepsLatestBoundedRowsAndCanDeleteAll() = runBlocking {
        val dao = database.notificationHistoryDao()
        repeat(1_001) { index ->
            dao.insert(history(index.toString(), "Place", "POSTED", index.toLong()))
        }
        dao.prune(referenceTimeMillis = 1_001L)

        val retained = dao.observeAll().first()
        assertEquals(1_000, retained.size)
        assertEquals("1000", retained.first().id)
        assertEquals("1", retained.last().id)

        dao.deleteAll()
        assertEquals(emptyList<NotificationHistoryEntity>(), dao.observeAll().first())
    }

    @Test
    fun fixedPresetNameAndValuesCanBeUpdatedWithoutChangingItsId() = runBlocking {
        val dao = database.notificationPresetDao()
        dao.upsert(NotificationPresetEntity("walk", "徒歩", 300, 60, 720, 0))
        dao.upsert(NotificationPresetEntity("walk", "ゆっくり徒歩", 350, 75, 360, 0))

        val preset = dao.observeAll().first().single()
        assertEquals("walk", preset.id)
        assertEquals("ゆっくり徒歩", preset.name)
        assertEquals(350, preset.radiusMeters)
    }

    @Test
    fun nearbyStateAndSnoozeCanBePersistedAndCleared() = runBlocking {
        val dao = database.locationDao()
        dao.insert(location("nearby", true, 1))

        dao.updateNearbyState(
            id = "nearby",
            state = "REARM_WAIT",
            snoozedUntil = null,
            verifiedAt = 2_000L,
            lastValidLocationAt = 1_500L,
            verificationReason = "confirmed",
            suppressionReason = null,
            accuracyMeters = 20F,
            speedMetersPerSecond = 4F,
            notificationDistanceMeters = 250F,
            updatedAt = 2_000L,
        )
        assertEquals("REARM_WAIT", dao.getById("nearby")?.nearbyState)
        assertEquals(1_500L, dao.getById("nearby")?.lastValidLocationAt)

        dao.snooze(listOf("nearby"), 50_000L, 3_000L)
        assertEquals("SNOOZED", dao.getById("nearby")?.nearbyState)
        assertEquals(50_000L, dao.getById("nearby")?.snoozedUntil)

        assertEquals(1, dao.clearExpiredSnoozes(50_000L))
        assertEquals("MONITORING", dao.getById("nearby")?.nearbyState)

        dao.snooze(listOf("nearby"), 60_000L, 3_500L)
        dao.updateNearbyState("nearby", "MONITORING", null, 4_000L, null, "stale exit", null, null, null, null, 4_000L)
        assertEquals("SNOOZED", dao.getById("nearby")?.nearbyState)
        assertEquals(60_000L, dao.getById("nearby")?.snoozedUntil)

        dao.clearSnooze("nearby", 4_000L)
        assertEquals("MONITORING", dao.getById("nearby")?.nearbyState)
        assertEquals(null, dao.getById("nearby")?.snoozedUntil)

        assertEquals(1, dao.claimVerification("nearby", 9_000L, "claim"))
        assertEquals(0, dao.claimVerification("nearby", 9_001L, "duplicate"))
        dao.updateNearbyState("nearby", "MONITORING", null, 9_002L, null, "reset", null, null, null, null, 9_002L)

        dao.updateNearbyState("nearby", "VERIFYING", null, 10_000L, null, "started", null, null, null, null, 10_000L)
        assertEquals(1, dao.recoverStaleVerifications(10_000L, 20_000L))
        assertEquals("MONITORING", dao.getById("nearby")?.nearbyState)
        assertEquals(1_500L, dao.getById("nearby")?.lastValidLocationAt)
    }

    private fun location(id: String, enabled: Boolean, sortOrder: Long) = LocationEntity(
        id = id,
        name = id,
        message = "",
        latitude = 35.0,
        longitude = 139.0,
        radiusMeters = 300,
        transitionType = "DWELL",
        loiteringDelayMs = 60_000,
        cooldownMinutes = 720,
        enabled = enabled,
        sourceType = "MANUAL",
        sourceUrl = null,
        sourceText = null,
        createdAt = 1_000,
        updatedAt = 1_000,
        lastNotifiedAt = null,
        lastEventAt = null,
        registrationStatus = "REGISTERED",
        registrationErrorCode = null,
        registrationErrorMessage = null,
        lastRegisteredAt = 1_000,
        sortOrder = sortOrder,
    )

    private fun history(id: String, name: String, status: String, eventAt: Long) = NotificationHistoryEntity(
        id = id,
        locationId = null,
        locationNameSnapshot = name,
        messageSnapshot = "",
        latitudeSnapshot = 35.0,
        longitudeSnapshot = 139.0,
        radiusSnapshot = 300,
        deviceLatitude = null,
        deviceLongitude = null,
        deviceAccuracyMeters = null,
        deviceLocationAt = null,
        deviceLocationProvider = null,
        transitionType = "DWELL",
        eventAt = eventAt,
        postedAt = eventAt,
        deliveryStatus = status,
        deliveryReason = null,
        userState = "UNREAD",
        readAt = null,
        completedAt = null,
        dismissedAt = null,
        createdAt = eventAt,
    )
}
