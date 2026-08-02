package com.chikabell.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chikabell.app.domain.model.TransitionType
import com.chikabell.app.geofence.NearbyVerificationService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NearbyVerificationServiceTest {
    @Test
    fun foregroundVerificationServiceStopsAfterUnknownLocation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(500)

        assertTrue(
            NearbyVerificationService.start(
                context,
                listOf("instrumentation-missing-location"),
                TransitionType.ENTER,
                System.currentTimeMillis(),
            ),
        )
        delay(1_500)

        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val stillRunning = manager.getRunningServices(100).any {
            it.service.className == NearbyVerificationService::class.java.name
        }
        assertFalse(stillRunning)
    }
}
