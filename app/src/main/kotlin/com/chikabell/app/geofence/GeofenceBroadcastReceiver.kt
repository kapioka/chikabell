package com.chikabell.app.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.chikabell.app.ChikaBellApplication
import com.chikabell.app.domain.model.TransitionType
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val app = context.applicationContext as ChikaBellApplication
        app.applicationScope.launch(Dispatchers.IO) {
            try {
                processIntent(app, intent)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processIntent(app: ChikaBellApplication, intent: Intent) {
        if (intent.action == ACTION_DEBUG_ENTER) {
            val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
            val eventAt = System.currentTimeMillis()
            if (!NearbyVerificationService.start(app, listOf(requestId), TransitionType.DWELL, eventAt)) {
                app.container.processGeofenceEventUseCase.execute(requestId, TransitionType.DWELL, eventAt)
            }
            return
        }

        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return
        if (geofencingEvent.hasError()) {
            Log.w(TAG, "Geofence event error: ${geofencingEvent.errorCode}")
            return
        }
        val transition = geofencingEvent.geofenceTransition.toTransitionType() ?: return
        val eventAt = System.currentTimeMillis()
        val requestIds = geofencingEvent.triggeringGeofences.orEmpty().map { it.requestId }
        if (transition != TransitionType.EXIT &&
            NearbyVerificationService.start(app, requestIds, transition, eventAt)
        ) return
        requestIds.forEach { requestId ->
            app.container.processGeofenceEventUseCase.execute(
                requestId = requestId,
                transitionType = transition,
                eventAt = eventAt,
            )
        }
    }

    companion object {
        const val ACTION_DEBUG_ENTER = "com.chikabell.app.DEBUG_GEOFENCE_ENTER"
        const val EXTRA_REQUEST_ID = "request_id"
        const val TAG = "GeofenceReceiver"
    }
}

private fun Int.toTransitionType(): TransitionType? {
    return when (this) {
        Geofence.GEOFENCE_TRANSITION_ENTER -> TransitionType.ENTER
        Geofence.GEOFENCE_TRANSITION_DWELL -> TransitionType.DWELL
        Geofence.GEOFENCE_TRANSITION_EXIT -> TransitionType.EXIT
        else -> null
    }
}
