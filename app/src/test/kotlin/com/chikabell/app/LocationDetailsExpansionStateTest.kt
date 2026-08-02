package com.chikabell.app

import com.chikabell.app.domain.model.RegistrationStatus
import com.chikabell.app.domain.model.SavedLocation
import com.chikabell.app.domain.model.SourceType
import com.chikabell.app.domain.model.TransitionType
import com.chikabell.app.ui.locations.LocationSortMode
import com.chikabell.app.ui.locations.retainExistingLocationDetailsExpansion
import com.chikabell.app.ui.locations.shouldUseSingleRowNotificationSettings
import com.chikabell.app.ui.locations.sortSavedLocations
import com.chikabell.app.ui.locations.toggleLocationDetailsExpansion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationDetailsExpansionStateTest {
    @Test
    fun firstToggleExpandsOnlyTheSelectedLocation() {
        assertEquals(setOf("first"), toggleLocationDetailsExpansion(emptySet(), "first"))
    }

    @Test
    fun secondToggleCollapsesTheSelectedLocation() {
        assertEquals(emptySet<String>(), toggleLocationDetailsExpansion(setOf("first"), "first"))
    }

    @Test
    fun locationsExpandIndependently() {
        assertEquals(
            setOf("first", "second"),
            toggleLocationDetailsExpansion(setOf("first"), "second"),
        )
    }

    @Test
    fun removedLocationsArePrunedFromExpansionState() {
        assertEquals(
            setOf("remaining"),
            retainExistingLocationDetailsExpansion(
                expandedLocationIds = setOf("removed", "remaining"),
                existingLocationIds = setOf("remaining", "collapsed"),
            ),
        )
    }

    @Test
    fun sortButtonCyclesThroughRegistrationRecentUpdateAndDistance() {
        assertEquals(LocationSortMode.RECENTLY_UPDATED, LocationSortMode.REGISTRATION.next())
        assertEquals(LocationSortMode.DISTANCE, LocationSortMode.RECENTLY_UPDATED.next())
        assertEquals(LocationSortMode.REGISTRATION, LocationSortMode.DISTANCE.next())
    }

    @Test
    fun distanceSortUsesDistanceThenRegistrationOrder() {
        val first = location(id = "first", sortOrder = 1, updatedAt = 10)
        val second = location(id = "second", sortOrder = 2, updatedAt = 30)
        val third = location(id = "third", sortOrder = 3, updatedAt = 20)

        assertEquals(
            listOf("second", "first", "third"),
            sortSavedLocations(
                locations = listOf(first, second, third),
                mode = LocationSortMode.DISTANCE,
                distancesByLocationId = mapOf("first" to 500f, "second" to 100f),
            ).map(SavedLocation::id),
        )
    }

    @Test
    fun recentUpdateSortUsesLatestUpdatedAtFirst() {
        val first = location(id = "first", sortOrder = 1, updatedAt = 10)
        val second = location(id = "second", sortOrder = 2, updatedAt = 30)

        assertEquals(
            listOf("second", "first"),
            sortSavedLocations(listOf(first, second), LocationSortMode.RECENTLY_UPDATED).map(SavedLocation::id),
        )
    }

    @Test
    fun notificationSettingsUseSingleRowAtNormalPhoneWidth() {
        assertTrue(shouldUseSingleRowNotificationSettings(360f, 1f))
    }

    @Test
    fun notificationSettingsReflowForLargeTextOrNarrowWidth() {
        assertFalse(shouldUseSingleRowNotificationSettings(360f, 2f))
        assertFalse(shouldUseSingleRowNotificationSettings(319f, 1f))
    }

    private fun location(id: String, sortOrder: Long, updatedAt: Long) = SavedLocation(
        id = id,
        name = id,
        message = "",
        latitude = 35.0,
        longitude = 139.0,
        radiusMeters = 100,
        transitionType = TransitionType.DWELL,
        loiteringDelayMs = 60_000,
        cooldownMinutes = 720,
        enabled = true,
        sourceType = SourceType.MANUAL,
        sourceUrl = null,
        sourceText = null,
        createdAt = 1,
        updatedAt = updatedAt,
        lastNotifiedAt = null,
        lastEventAt = null,
        registrationStatus = RegistrationStatus.REGISTERED,
        registrationErrorCode = null,
        registrationErrorMessage = null,
        lastRegisteredAt = null,
        sortOrder = sortOrder,
    )
}
