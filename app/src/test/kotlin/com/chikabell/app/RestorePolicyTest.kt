package com.chikabell.app

import com.chikabell.app.domain.model.RegistrationStatus
import com.chikabell.app.domain.model.RestoreTrigger
import com.chikabell.app.domain.model.SavedLocation
import com.chikabell.app.domain.model.SourceType
import com.chikabell.app.domain.model.TransitionType
import com.chikabell.app.geofence.RestorePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePolicyTest {
    @Test fun bootAndManualAlwaysRestore() {
        assertTrue(RestorePolicy.shouldRestore(RestoreTrigger.BOOT, emptyList(), 10_000))
        assertTrue(RestorePolicy.shouldRestore(RestoreTrigger.MANUAL, emptyList(), 10_000))
    }

    @Test fun packageUpdateAlwaysRestoresRegisteredLocations() {
        assertTrue(RestorePolicy.shouldRestore(RestoreTrigger.PACKAGE_REPLACED, listOf(location(20_000)), 10_000))
    }

    @Test fun packageUpdateRestoresInactiveLocation() {
        assertTrue(RestorePolicy.shouldRestore(RestoreTrigger.PACKAGE_REPLACED, listOf(location(20_000, RegistrationStatus.INACTIVE)), 10_000))
    }

    @Test fun appStartRestoresRegistrationFromPreviousBoot() {
        assertTrue(RestorePolicy.shouldRestore(RestoreTrigger.APP_START, listOf(location(1_000)), 400_000))
        assertFalse(RestorePolicy.shouldRestore(RestoreTrigger.APP_START, listOf(location(500_000)), 400_000))
    }

    @Test fun healthCheckRestoresStaleRegisteredLocations() {
        val now = 9 * 60 * 60 * 1_000L
        assertTrue(RestorePolicy.shouldRestore(RestoreTrigger.HEALTH_CHECK, listOf(location(1_000)), 0, now))
    }

    @Test fun healthCheckSkipsFreshRegisteredLocations() {
        val now = 9 * 60 * 60 * 1_000L
        assertFalse(RestorePolicy.shouldRestore(RestoreTrigger.HEALTH_CHECK, listOf(location(now - 30 * 60 * 1_000L)), 0, now))
    }

    @Test fun transientFailureRetriesOnlyFirstTwoAttempts() {
        assertTrue(RestorePolicy.shouldRetry("ApiException", 0))
        assertTrue(RestorePolicy.shouldRetry("ApiException", 1))
        assertFalse(RestorePolicy.shouldRetry("ApiException", 2))
        assertFalse(RestorePolicy.shouldRetry("SecurityException", 0))
    }

    private fun location(registeredAt: Long?, status: RegistrationStatus = RegistrationStatus.REGISTERED) = SavedLocation(
        id = "id", name = "name", message = "", latitude = 0.0, longitude = 0.0,
        radiusMeters = 300, transitionType = TransitionType.DWELL, loiteringDelayMs = 60_000,
        cooldownMinutes = 720, enabled = true, sourceType = SourceType.MANUAL, sourceUrl = null,
        sourceText = null, createdAt = 1, updatedAt = 1, lastNotifiedAt = null, lastEventAt = null,
        registrationStatus = status, registrationErrorCode = null, registrationErrorMessage = null,
        lastRegisteredAt = registeredAt, sortOrder = 1,
    )
}
