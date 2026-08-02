package com.chikabell.app

import com.chikabell.app.domain.model.DeliveryStatus
import com.chikabell.app.domain.model.HistoryUserState
import com.chikabell.app.domain.model.NotificationHistory
import com.chikabell.app.domain.model.TransitionType
import com.chikabell.app.notification.NearbyNotificationContentFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyNotificationContentFormatterTest {
    @Test fun `single accurate location shows rounded distance without direction`() {
        val content = NearbyNotificationContentFormatter.format(listOf(history("a", "店", 35.001, 20F)))!!
        assertEquals("近くにお気に入りの場所があります", content.title)
        assertTrue(content.body.startsWith("店まで推定約"))
        assertFalse(content.body.contains("方向"))
    }

    @Test fun `poor accuracy hides numeric distance`() {
        val content = NearbyNotificationContentFormatter.format(listOf(history("a", "店", 35.001, 250F)))!!
        assertEquals("店がこの付近にあります", content.body)
        assertFalse(content.body.any(Char::isDigit))
    }

    @Test fun `multiple locations use one title and retain every id`() {
        val content = NearbyNotificationContentFormatter.format(
            listOf(history("b", "遠い店", 35.003, 20F), history("a", "近い店", 35.001, 20F)),
        )!!
        assertEquals("近くにお気に入りの場所が2件あります", content.title)
        assertTrue(content.body.startsWith("最も近い近い店まで"))
        assertEquals(listOf("a", "b"), content.locationIds)
    }

    private fun history(id: String, name: String, latitude: Double, accuracy: Float) = NotificationHistory(
        id = "history-$id",
        locationId = id,
        locationNameSnapshot = name,
        messageSnapshot = "",
        latitudeSnapshot = latitude,
        longitudeSnapshot = 139.0,
        radiusSnapshot = 500,
        deviceLatitude = 35.0,
        deviceLongitude = 139.0,
        deviceAccuracyMeters = accuracy,
        deviceLocationAt = 1_000L,
        deviceLocationProvider = "test",
        transitionType = TransitionType.ENTER,
        eventAt = 1_000L,
        postedAt = 1_000L,
        deliveryStatus = DeliveryStatus.POSTED,
        deliveryReason = null,
        userState = HistoryUserState.UNREAD,
        createdAt = 1_000L,
    )
}
