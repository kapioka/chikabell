package com.chikabell.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Parcel
import android.os.Parcelable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.chikabell.app.domain.model.TransitionType
import com.chikabell.app.geofence.GeofenceBroadcastReceiver
import com.chikabell.app.geofence.GeofenceEventHandoff
import com.chikabell.app.geofence.GeofenceEventWorker
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Run in an isolated applicationId; never inserts a location or changes permissions. */
@RunWith(AndroidJUnit4::class)
class GeofenceEventHandoffDeviceTest {
    @Test fun rejectedServicePersistsWorkAndProcessesUnknownLocation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Use the isolated QA package", context.packageName.endsWith(".issue18qa"))
        val manager = WorkManager.getInstance(context)
        val before = manager.getWorkInfosByTag(GeofenceEventWorker.TAG).get(5, TimeUnit.SECONDS).map { it.id }.toSet()

        withTimeout(5_000L) {
            GeofenceEventHandoff.deliver(
                listOf("issue18-missing-location"), TransitionType.ENTER, System.currentTimeMillis(),
                startService = { _, _, _ -> false },
                enqueue = { ids, type, at -> GeofenceEventWorker.enqueue(context, ids, type, at) },
            )
        }

        withTimeout(15_000L) {
            while (true) {
                val work = manager.getWorkInfosByTag(GeofenceEventWorker.TAG).get(5, TimeUnit.SECONDS)
                    .filter { it.id !in before }
                assertEquals(1, work.size)
                if (work.single().state.isFinished) {
                    assertEquals(WorkInfo.State.SUCCEEDED, work.single().state)
                    break
                }
                delay(50L)
            }
        }
    }

    @Test fun repeatedReceiverExitDeliveriesFinishWithoutWaitingForVerification() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Use the isolated QA package", context.packageName.endsWith(".issue18qa"))
        repeat(20) { index ->
            val finished = CompletableDeferred<Unit>()
            val intent = exitIntent(context, "issue18-missing-$index")
            val event = GeofencingEvent.fromIntent(intent)!!
            assertEquals(Geofence.GEOFENCE_TRANSITION_EXIT, event.geofenceTransition)
            assertEquals("issue18-missing-$index", event.triggeringGeofences!!.single().requestId)
            context.sendOrderedBroadcast(
                intent,
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) { finished.complete(Unit) }
                },
                null, 0, null, null,
            )
            withTimeout(5_000L) { finished.await() }
        }
    }

    private fun exitIntent(context: Context, requestId: String): Intent {
        // Encode the SDK's Intent payload; this geofence is never registered with Play services.
        val geofence = Geofence.Builder()
            .setRequestId(requestId)
            .setCircularRegion(0.0, 0.0, 100F)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val parcel = Parcel.obtain()
        val bytes = try {
            (geofence as Parcelable).writeToParcel(parcel, 0)
            parcel.marshall()
        } finally { parcel.recycle() }
        return Intent(context, GeofenceBroadcastReceiver::class.java)
            .putExtra("com.google.android.location.intent.extra.transition", Geofence.GEOFENCE_TRANSITION_EXIT)
            .putExtra("com.google.android.location.intent.extra.geofence_list", arrayListOf(bytes))
    }
}
