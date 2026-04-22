package com.example.relapse_watch.services

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var lastImmediateSyncRequestElapsedMs: Long = 0L

    fun schedulePeriodicSync() {
        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule periodic sync", e)
        }
    }

    fun requestImmediateSync() {
        val now = SystemClock.elapsedRealtime()
        if ((now - lastImmediateSyncRequestElapsedMs) < MIN_IMMEDIATE_SYNC_SPACING_MS) {
            Log.d(TAG, "Immediate sync request debounced")
            return
        }
        lastImmediateSyncRequestElapsedMs = now

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            oneTimeRequest
        )
        Log.d(TAG, "Immediate sync requested")
    }

    fun cancelPeriodicSync() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        Log.d(TAG, "Periodic sync cancelled")
    }

    companion object {
        private const val TAG = "SyncScheduler"
        private const val WORK_NAME = "periodic_activity_sync"
        private const val IMMEDIATE_WORK_NAME = "immediate_activity_sync"
        private const val SYNC_INTERVAL_MINUTES = 15L
        private const val MIN_IMMEDIATE_SYNC_SPACING_MS = 2 * 60_000L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncWorkerEntryPoint {
    fun syncService(): SyncService
}

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "SyncWorker started")
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                SyncWorkerEntryPoint::class.java
            )
            val syncService = entryPoint.syncService()
            val syncResult = syncService.syncActivityData()
            if (syncResult.isSuccess) {
                Log.d(TAG, "SyncWorker synced ${syncResult.getOrDefault(0)} records")
                Result.success()
            } else {
                Log.e(TAG, "SyncWorker sync failed", syncResult.exceptionOrNull())
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "SyncWorker failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
    }
}
