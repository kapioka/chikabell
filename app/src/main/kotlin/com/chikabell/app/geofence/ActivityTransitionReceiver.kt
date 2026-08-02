package com.chikabell.app.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chikabell.app.ChikaBellApplication
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val app = context.applicationContext as ChikaBellApplication
        val store = app.container.activityStateStore
        val previousBand = NearbyVerificationPolicy.registrationMotionBand(store.read().state)
        result.transitionEvents.forEach { event ->
            val motion = event.activityType.toDetectedMotion() ?: return@forEach
            if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                store.update(motion)
            } else if (store.read().state == motion) {
                store.update(DetectedMotion.UNKNOWN)
            }
        }
        val currentBand = NearbyVerificationPolicy.registrationMotionBand(store.read().state)
        if (currentBand == previousBand) return

        val pendingResult = goAsync()
        app.applicationScope.launch(Dispatchers.IO) {
            try {
                app.container.geofenceRadiusRefreshUseCase.execute()
            } finally {
                pendingResult.finish()
            }
        }
    }
}

private fun Int.toDetectedMotion(): DetectedMotion? = when (this) {
    DetectedActivity.STILL -> DetectedMotion.STILL
    DetectedActivity.WALKING -> DetectedMotion.WALKING
    DetectedActivity.RUNNING -> DetectedMotion.RUNNING
    DetectedActivity.ON_BICYCLE -> DetectedMotion.ON_BICYCLE
    DetectedActivity.IN_VEHICLE -> DetectedMotion.IN_VEHICLE
    else -> null
}
