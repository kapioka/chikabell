package com.chikabell.app.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chikabell.app.domain.model.RestoreTrigger

class GeofenceRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val trigger = if (action == Intent.ACTION_BOOT_COMPLETED) RestoreTrigger.BOOT else RestoreTrigger.PACKAGE_REPLACED
        GeofenceRestoreScheduler.enqueue(context, trigger)
    }
}
