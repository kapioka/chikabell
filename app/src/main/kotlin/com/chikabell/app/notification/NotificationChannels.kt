package com.chikabell.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val NEARBY_PLACE_ALERTS = "nearby_place_alerts"
    const val NEARBY_VERIFICATION = "nearby_verification"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NEARBY_PLACE_ALERTS,
            "接近通知",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(
            NotificationChannel(
                NEARBY_VERIFICATION,
                "近接確認中",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "通知範囲内かを短時間だけ確認している間に表示します"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }
}
