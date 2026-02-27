package com.example.relapse_watch.services

import android.util.Log
import com.example.relapse_watch.data.local.dao.ActivityRecordDao
import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.data.remote.FirestoreActivitySource
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncService @Inject constructor(
    private val activityRecordDao: ActivityRecordDao,
    private val firestoreActivitySource: FirestoreActivitySource,
    private val preferences: WatchPreferences
) {

    suspend fun syncActivityData(): Result<Int> {
        return try {
            val caregiverUid = preferences.caregiverUid.first()
            val patientId = preferences.patientId.first()
            val watchId = preferences.watchId.first()
            val isPaired = preferences.isPaired.first()

            if (!isPaired || caregiverUid.isBlank() || patientId.isBlank()) {
                Log.d(TAG, "Skipping sync: not paired or missing credentials")
                return Result.success(0)
            }

            val pendingRecords = activityRecordDao.getPendingUpload()
            if (pendingRecords.isEmpty()) {
                Log.d(TAG, "No pending records to sync")
                return Result.success(0)
            }

            Log.d(TAG, "Syncing ${pendingRecords.size} records")

            val firestoreMaps = pendingRecords.map { entity ->
                FirestoreActivitySource.activityRecordToFirestoreMap(
                    id = entity.id,
                    patientId = entity.patientId,
                    timestamp = entity.timestamp,
                    latitude = entity.latitude,
                    longitude = entity.longitude,
                    eventType = entity.eventType,
                    metadataJson = entity.metadataJson
                )
            }

            val uploadResult = firestoreActivitySource.uploadActivityRecords(
                caregiverUid = caregiverUid,
                patientId = patientId,
                records = firestoreMaps
            )

            if (uploadResult.isSuccess) {
                val uploadedIds = pendingRecords.map { it.id }
                activityRecordDao.markUploaded(uploadedIds)
                preferences.updateLastSync(System.currentTimeMillis())

                // Update watch status in Firestore
                firestoreActivitySource.updateWatchStatus(
                    caregiverUid = caregiverUid,
                    patientId = patientId,
                    watchId = watchId,
                    batteryLevel = null
                )

                Log.d(TAG, "Successfully synced ${pendingRecords.size} records")
                Result.success(pendingRecords.size)
            } else {
                Log.e(TAG, "Upload failed", uploadResult.exceptionOrNull())
                Result.failure(uploadResult.exceptionOrNull() ?: Exception("Upload failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "SyncService"
    }
}
