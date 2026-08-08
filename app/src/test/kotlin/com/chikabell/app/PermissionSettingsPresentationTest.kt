package com.chikabell.app

import com.chikabell.app.permission.ActivityRecognitionStatus
import com.chikabell.app.permission.BackgroundLocationStatus
import com.chikabell.app.permission.BackgroundRestrictionSnapshot
import com.chikabell.app.permission.ForegroundLocationStatus
import com.chikabell.app.permission.GooglePlayServicesStatus
import com.chikabell.app.permission.LocationServicesStatus
import com.chikabell.app.permission.NotificationPermissionStatus
import com.chikabell.app.permission.PermissionSnapshot
import com.chikabell.app.ui.locations.PermissionSettingHealth
import com.chikabell.app.ui.locations.PermissionSettingsAction
import com.chikabell.app.ui.locations.permissionSettingsPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionSettingsPresentationTest {
    @Test
    fun allRequiredSettingsReadyShowsNoRequiredAction() {
        val presentation = permissionSettingsPresentation(readySnapshot(), unrestrictedBattery())

        assertEquals(0, presentation.requiredActionCount)
        assertEquals("必要な設定は完了しています", presentation.summaryTitle)
        assertEquals(PermissionSettingsAction.OPEN_APP_SETTINGS, presentation.primaryAction)
        assertEquals(7, presentation.items.size)
        assertTrue(presentation.items.all { it.health == PermissionSettingHealth.READY })
    }

    @Test
    fun deniedForegroundLocationUsesRuntimePermissionAsFirstAction() {
        val snapshot = readySnapshot().copy(foregroundLocation = ForegroundLocationStatus.Denied)

        val presentation = permissionSettingsPresentation(snapshot, unrestrictedBattery())

        assertEquals(1, presentation.requiredActionCount)
        assertEquals(PermissionSettingsAction.REQUEST_FOREGROUND_LOCATION, presentation.primaryAction)
        assertEquals("位置情報を許可する", presentation.primaryActionLabel)
    }

    @Test
    fun approximateLocationUsesAppSettingsForPreciseToggle() {
        val snapshot = readySnapshot().copy(foregroundLocation = ForegroundLocationStatus.ApproximateOnly)

        val presentation = permissionSettingsPresentation(snapshot, unrestrictedBattery())

        assertEquals(PermissionSettingsAction.OPEN_APP_SETTINGS, presentation.primaryAction)
        assertEquals("必要なAndroid設定を開く", presentation.primaryActionLabel)
    }

    @Test
    fun disabledDeviceLocationTakesPriorityAndOpensLocationSettings() {
        val snapshot = readySnapshot().copy(
            foregroundLocation = ForegroundLocationStatus.Denied,
            locationServices = LocationServicesStatus.Disabled,
        )

        val presentation = permissionSettingsPresentation(snapshot, unrestrictedBattery())

        assertEquals(PermissionSettingsAction.OPEN_LOCATION_SETTINGS, presentation.primaryAction)
        assertEquals("端末の位置情報設定を開く", presentation.primaryActionLabel)
    }

    @Test
    fun deniedActivityRecognitionIsClearlyOptional() {
        val snapshot = readySnapshot().copy(activityRecognition = ActivityRecognitionStatus.Denied)

        val presentation = permissionSettingsPresentation(snapshot, unrestrictedBattery())

        assertEquals(0, presentation.requiredActionCount)
        assertEquals(PermissionSettingsAction.REQUEST_ACTIVITY_RECOGNITION, presentation.primaryAction)
        assertEquals("活動認識を許可する（任意）", presentation.primaryActionLabel)
        assertEquals(
            PermissionSettingHealth.OPTIONAL,
            presentation.items.single { it.title == "活動認識" }.health,
        )
    }

    @Test
    fun backgroundRestrictionIsReportedAsRequired() {
        val presentation = permissionSettingsPresentation(
            readySnapshot(),
            BackgroundRestrictionSnapshot(backgroundRestricted = true, ignoringBatteryOptimizations = false),
        )

        assertEquals(1, presentation.requiredActionCount)
        assertEquals(PermissionSettingsAction.OPEN_APP_SETTINGS, presentation.primaryAction)
        assertEquals(
            PermissionSettingHealth.ACTION_REQUIRED,
            presentation.items.single { it.title == "バッテリーのバックグラウンド制限" }.health,
        )
    }

    private fun readySnapshot() = PermissionSnapshot(
        notificationPermission = NotificationPermissionStatus.Granted,
        foregroundLocation = ForegroundLocationStatus.Precise,
        backgroundLocation = BackgroundLocationStatus.Granted,
        locationServices = LocationServicesStatus.Enabled,
        googlePlayServices = GooglePlayServicesStatus.Available,
        activityRecognition = ActivityRecognitionStatus.Granted,
    )

    private fun unrestrictedBattery() = BackgroundRestrictionSnapshot(
        backgroundRestricted = false,
        ignoringBatteryOptimizations = false,
    )
}
