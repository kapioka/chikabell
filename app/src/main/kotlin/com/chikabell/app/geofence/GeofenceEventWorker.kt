package com.chikabell.app.geofence

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.chikabell.app.ChikaBellApplication
import com.chikabell.app.domain.model.TransitionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Durable fallback when foreground verification cannot start; also handles EXIT off-receiver. */
class GeofenceEventWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ids = inputData.getStringArray(KEY_REQUEST_IDS)?.filter(String::isNotBlank).orEmpty()
        val transition = inputData.getString(KEY_TRANSITION)?.let {
            runCatching { TransitionType.valueOf(it) }.getOrNull()
        } ?: return@withContext Result.failure()
        val eventAt = inputData.getLong(KEY_EVENT_AT, -1L)
        if (ids.isEmpty() || eventAt < 0L) return@withContext Result.failure()

        val app = applicationContext as ChikaBellApplication
        val completed = GeofenceEventBatchProcessor.processWithinBudget(ids) { id ->
            app.container.processGeofenceEventUseCase.execute(id, transition, eventAt)
        }
        if (completed) {
            Result.success()
        } else {
            Log.w(TAG, "Event processing budget reached; attempt=$runAttemptCount")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val TAG = "GeofenceEventWork"
        private const val KEY_REQUEST_IDS = "request_ids"
        private const val KEY_TRANSITION = "transition"
        private const val KEY_EVENT_AT = "event_at"
        private const val MAX_RETRIES = 2

        suspend fun enqueue(context: Context, ids: List<String>, transition: TransitionType, eventAt: Long) {
            val request = OneTimeWorkRequestBuilder<GeofenceEventWorker>()
                .setInputData(
                    Data.Builder()
                        .putStringArray(KEY_REQUEST_IDS, ids.toTypedArray())
                        .putString(KEY_TRANSITION, transition.name)
                        .putLong(KEY_EVENT_AT, eventAt)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(request).await()
        }
    }
}
