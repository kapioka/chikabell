package com.chikabell.app.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chikabell.app.ChikaBellApplication
import com.chikabell.app.geofence.NearbyVerificationPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NearbyNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SNOOZE) return
        val locationIds = intent.getStringArrayExtra(EXTRA_LOCATION_IDS)
            ?.filter(String::isNotBlank)
            ?.toSet()
            .orEmpty()
        if (locationIds.isEmpty()) return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, Int.MIN_VALUE)
        val pendingResult = goAsync()
        val app = context.applicationContext as ChikaBellApplication
        app.applicationScope.launch(Dispatchers.IO) {
            try {
                val until = System.currentTimeMillis() + NearbyVerificationPolicy.SNOOZE_DURATION_MILLIS
                app.container.locationRepository.snoozeLocations(locationIds, until)
                if (notificationId != Int.MIN_VALUE) {
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.cancel(notificationId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.chikabell.app.action.SNOOZE_NEARBY"
        const val EXTRA_LOCATION_IDS = "location_ids"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
