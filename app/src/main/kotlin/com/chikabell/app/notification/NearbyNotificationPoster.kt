package com.chikabell.app.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.chikabell.app.R
import com.chikabell.app.domain.model.NotificationHistory

interface NearbyNotificationGateway {
    fun canPostNotifications(): Boolean
    fun isChannelEnabled(): Boolean
    fun post(histories: List<NotificationHistory>)
    fun postTestNotification(): Boolean
}

enum class TestNotificationResult {
    POSTED,
    PERMISSION_DENIED,
    CHANNEL_DISABLED,
    POST_FAILED,
}

class SendTestNotificationUseCase(
    private val notificationGateway: NearbyNotificationGateway,
) {
    fun execute(): TestNotificationResult {
        if (!notificationGateway.canPostNotifications()) {
            return TestNotificationResult.PERMISSION_DENIED
        }
        if (!notificationGateway.isChannelEnabled()) {
            return TestNotificationResult.CHANNEL_DISABLED
        }
        return if (notificationGateway.postTestNotification()) {
            TestNotificationResult.POSTED
        } else {
            TestNotificationResult.POST_FAILED
        }
    }
}

class NearbyNotificationPoster(
    private val context: Context,
) : NearbyNotificationGateway {
    override fun canPostNotifications(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun isChannelEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = manager.getNotificationChannel(NotificationChannels.NEARBY_PLACE_ALERTS)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    @SuppressLint("MissingPermission")
    override fun post(histories: List<NotificationHistory>) {
        val content = NearbyNotificationContentFormatter.format(histories) ?: return
        val history = content.primary
        if (!canPostNotifications()) return
        NotificationChannels.ensureCreated(context)
        val locationIds = content.locationIds
        val notificationId = content.notificationId
        val mapIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(
                "https://www.google.com/maps/search/?api=1&query=" +
                    "${history.latitudeSnapshot},${history.longitudeSnapshot}",
            ),
        ).setPackage(GOOGLE_MAPS_PACKAGE)
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            mapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
        val snoozeIntent = PendingIntent.getBroadcast(
            context,
            notificationId xor SNOOZE_REQUEST_MASK,
            Intent(context, NearbyNotificationActionReceiver::class.java)
                .setAction(NearbyNotificationActionReceiver.ACTION_SNOOZE)
                .putExtra(NearbyNotificationActionReceiver.EXTRA_LOCATION_IDS, locationIds.toTypedArray())
                .putExtra(NearbyNotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
        val explicitMapIntent = PendingIntent.getActivity(
            context,
            notificationId xor MAP_REQUEST_MASK,
            mapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.NEARBY_PLACE_ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(0, "12時間休止", snoozeIntent)
            .addAction(0, "地図を見る", explicitMapIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    @SuppressLint("MissingPermission")
    override fun postTestNotification(): Boolean {
        if (!canPostNotifications()) return false
        NotificationChannels.ensureCreated(context)
        if (!isChannelEnabled()) return false
        return runCatching {
            val notification = NotificationCompat.Builder(context, NotificationChannels.NEARBY_PLACE_ALERTS)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("ChikaBell")
                .setContentText("テスト通知です")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    private fun immutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    private companion object {
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
        const val TEST_NOTIFICATION_ID = 0x4342
        const val SNOOZE_REQUEST_MASK = 0x53_4E_4F
        const val MAP_REQUEST_MASK = 0x4D_41_50
    }
}
