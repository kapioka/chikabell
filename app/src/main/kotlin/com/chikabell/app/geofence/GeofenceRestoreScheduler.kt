package com.chikabell.app.geofence

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.chikabell.app.domain.model.RestoreTrigger
import java.util.concurrent.TimeUnit

object GeofenceRestoreScheduler {
    private const val UNIQUE_RESTORE_WORK = "restore_geofences"
    private const val UNIQUE_HEALTH_CHECK_WORK = "restore_geofence_health_check"
    private const val HEALTH_CHECK_INTERVAL_HOURS = 4L
    private const val HEALTH_CHECK_FLEX_HOURS = 1L

    fun enqueue(context: Context, trigger: RestoreTrigger) {
        val request = OneTimeWorkRequestBuilder<RestoreGeofencesWorker>()
            .setInputData(Data.Builder().putString(RestoreGeofencesWorker.KEY_TRIGGER, trigger.name).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(UNIQUE_RESTORE_WORK)
            .build()
        val policy = if (trigger == RestoreTrigger.MANUAL) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(UNIQUE_RESTORE_WORK, policy, request)
    }

    fun ensurePeriodicHealthCheck(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<RestoreGeofencesWorker>(
            HEALTH_CHECK_INTERVAL_HOURS,
            TimeUnit.HOURS,
            HEALTH_CHECK_FLEX_HOURS,
            TimeUnit.HOURS,
        )
            .setInputData(
                Data.Builder()
                    .putString(RestoreGeofencesWorker.KEY_TRIGGER, RestoreTrigger.HEALTH_CHECK.name)
                    .build(),
            )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(UNIQUE_HEALTH_CHECK_WORK)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_HEALTH_CHECK_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
