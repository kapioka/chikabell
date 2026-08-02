package com.chikabell.app.geofence

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.chikabell.app.ChikaBellApplication
import com.chikabell.app.MainActivity
import com.chikabell.app.R
import com.chikabell.app.domain.model.TransitionType
import com.chikabell.app.notification.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class NearbyVerificationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeRequestCount = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationChannels.ensureCreated(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, NotificationChannels.NEARBY_VERIFICATION)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("近くの場所を確認中")
                .setContentText("通知範囲内かを短時間だけ確認しています")
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        val requestIds = intent?.getStringArrayExtra(EXTRA_REQUEST_IDS)?.filter(String::isNotBlank).orEmpty()
        val transition = intent?.getStringExtra(EXTRA_TRANSITION)?.let {
            runCatching { TransitionType.valueOf(it) }.getOrNull()
        }
        val eventAt = intent?.getLongExtra(EXTRA_EVENT_AT, System.currentTimeMillis()) ?: System.currentTimeMillis()
        if (requestIds.isEmpty() || transition == null) {
            if (activeRequestCount.get() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        activeRequestCount.incrementAndGet()
        serviceScope.launch {
            try {
                val app = applicationContext as ChikaBellApplication
                GeofenceEventBatchProcessor.processWithinBudget(requestIds) { requestId ->
                    app.container.processGeofenceEventUseCase.execute(requestId, transition, eventAt)
                }
            } finally {
                if (activeRequestCount.decrementAndGet() == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REQUEST_IDS = "request_ids"
        private const val EXTRA_TRANSITION = "transition"
        private const val EXTRA_EVENT_AT = "event_at"
        private const val NOTIFICATION_ID = 0x4E5646

        fun start(context: Context, requestIds: List<String>, transition: TransitionType, eventAt: Long): Boolean {
            val intent = Intent(context, NearbyVerificationService::class.java)
                .putExtra(EXTRA_REQUEST_IDS, requestIds.distinct().toTypedArray())
                .putExtra(EXTRA_TRANSITION, transition.name)
                .putExtra(EXTRA_EVENT_AT, eventAt)
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrDefault(false)
        }
    }
}
