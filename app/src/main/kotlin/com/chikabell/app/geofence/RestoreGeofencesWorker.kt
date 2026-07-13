package com.chikabell.app.geofence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.chikabell.app.ChikaBellApplication
import com.chikabell.app.domain.model.RestoreTrigger

class RestoreGeofencesWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER)?.let { runCatching { RestoreTrigger.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        val app = applicationContext as ChikaBellApplication
        return when (app.container.restoreGeofencesCoordinator.execute(trigger, runAttemptCount)) {
            RestoreExecutionResult.Success -> Result.success()
            RestoreExecutionResult.Retry -> Result.retry()
            RestoreExecutionResult.Failure -> Result.failure()
        }
    }

    companion object { const val KEY_TRIGGER = "restore_trigger" }
}
