package com.chikabell.app

import com.chikabell.app.domain.model.NotificationHistory
import com.chikabell.app.notification.NearbyNotificationGateway
import com.chikabell.app.notification.SendTestNotificationUseCase
import com.chikabell.app.notification.TestNotificationResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SendTestNotificationUseCaseTest {
    @Test fun `posts a generic test notification when notifications are available`() {
        val gateway = FakeTestNotificationGateway()

        val result = SendTestNotificationUseCase(gateway).execute()

        assertEquals(TestNotificationResult.POSTED, result)
        assertEquals(1, gateway.testPostAttempts)
    }

    @Test fun `reports missing notification permission without posting`() {
        val gateway = FakeTestNotificationGateway(canPost = false)

        val result = SendTestNotificationUseCase(gateway).execute()

        assertEquals(TestNotificationResult.PERMISSION_DENIED, result)
        assertEquals(0, gateway.testPostAttempts)
    }

    @Test fun `reports disabled notification channel without posting`() {
        val gateway = FakeTestNotificationGateway(channelEnabled = false)

        val result = SendTestNotificationUseCase(gateway).execute()

        assertEquals(TestNotificationResult.CHANNEL_DISABLED, result)
        assertEquals(0, gateway.testPostAttempts)
    }

    @Test fun `reports a failed notification manager post`() {
        val gateway = FakeTestNotificationGateway(postSucceeds = false)

        val result = SendTestNotificationUseCase(gateway).execute()

        assertEquals(TestNotificationResult.POST_FAILED, result)
        assertEquals(1, gateway.testPostAttempts)
    }
}

private class FakeTestNotificationGateway(
    private val canPost: Boolean = true,
    private val channelEnabled: Boolean = true,
    private val postSucceeds: Boolean = true,
) : NearbyNotificationGateway {
    var testPostAttempts = 0

    override fun canPostNotifications() = canPost
    override fun isChannelEnabled() = channelEnabled
    override fun post(histories: List<NotificationHistory>) = Unit
    override fun postTestNotification(): Boolean {
        testPostAttempts++
        return postSucceeds
    }
}
