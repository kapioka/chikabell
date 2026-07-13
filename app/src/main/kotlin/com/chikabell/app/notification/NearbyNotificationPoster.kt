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

class NearbyNotificationPoster(
    private val context: Context,
) {
    fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isChannelEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = manager.getNotificationChannel(NotificationChannels.NEARBY_PLACE_ALERTS)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    @SuppressLint("MissingPermission")
    fun post(history: NotificationHistory) {
        if (!canPostNotifications()) return
        NotificationChannels.ensureCreated(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            history.id.hashCode(),
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "https://www.google.com/maps/search/?api=1&query=" +
                        "${history.latitudeSnapshot},${history.longitudeSnapshot}",
                ),
            ).setPackage(GOOGLE_MAPS_PACKAGE),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
        val message = history.messageSnapshot.ifBlank {
            "${history.locationNameSnapshot} に近づきました"
        }
        val guidance = DestinationGuidanceFormatter.format(
            currentLatitude = history.deviceLatitude,
            currentLongitude = history.deviceLongitude,
            destinationLatitude = history.latitudeSnapshot,
            destinationLongitude = history.longitudeSnapshot,
        )
        val text = listOfNotNull(message, guidance).joinToString("\n")
        val notification = NotificationCompat.Builder(context, NotificationChannels.NEARBY_PLACE_ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(history.locationNameSnapshot)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(history.id.hashCode(), notification)
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
    }
}
