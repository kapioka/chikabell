package com.chikabell.app.geofence

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

class GeofencePendingIntentFactory(
    private val context: Context,
) {
    fun create(): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    private companion object {
        const val REQUEST_CODE = 1001
    }
}
