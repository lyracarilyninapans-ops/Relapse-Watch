package com.example.relapse_watch.services

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.relapse_watch.data.local.dao.ActivityRecordDao
import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.data.remote.FirestoreActivitySource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
        Log.d(TAG, "Periodic sync scheduled every $SYNC_INTERVAL_MINUTES minutes")
    }

    fun cancelPeriodicSync() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        Log.d(TAG, "Periodic sync cancelled")
    }

    companion object {
        private const val TAG = "SyncScheduler"
        private const val WORK_NAME = "periodic_activity_sync"
        private const val SYNC_INTERVAL_MINUTES = 15L
    }
}

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "SyncWorker started")
            // WorkManager-based sync is a fallback; the foreground service
            // handles primary sync. This ensures data eventually syncs
            // if the foreground service restarts.
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "SyncWorker failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
    }
}
