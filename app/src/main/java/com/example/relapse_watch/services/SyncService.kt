package com.example.relapse_watch.services

import android.util.Log
import com.example.relapse_watch.data.local.dao.ActivityRecordDao
import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.data.remote.FirestoreActivitySource
import com.example.relapse_watch.domain.model.SafeZoneConfig
import com.example.relapse_watch.domain.repository.DailySummaryRepository
import com.example.relapse_watch.domain.repository.GeoReminderRepository
import com.example.relapse_watch.domain.repository.SafeZoneRepository
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncService @Inject constructor(
    private val activityRecordDao: ActivityRecordDao,
    private val firestoreActivitySource: FirestoreActivitySource,
    private val preferences: WatchPreferences,
    private val dailySummaryRepository: DailySummaryRepository,
    private val geoReminderRepository: GeoReminderRepository,
    private val safeZoneRepository: SafeZoneRepository,
    private val geofenceService: GeofenceService,
    private val mediaCacheManager: MediaCacheManager
) {

    private val syncScope = CoroutineScope(Dispatchers.IO)

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

            // Always send a heartbeat so the phone knows we're alive
            val heartbeatResult = firestoreActivitySource.updateWatchStatus(
                caregiverUid = caregiverUid,
                patientId = patientId,
                watchId = watchId,
                batteryLevel = null
            )
            if (heartbeatResult.isSuccess) {
                preferences.updateLastSync(System.currentTimeMillis())
            } else {
                Log.w(TAG, "Heartbeat update failed", heartbeatResult.exceptionOrNull())
            }

            val reminderCooldownSynced = syncReminderCooldownFromFirestore(caregiverUid, patientId)
            val safeZoneSynced = syncSafeZoneFromFirestore(caregiverUid, patientId)

            // Always sync today's daily summary
            syncDailySummaryToFirestore(caregiverUid, patientId)

            // Sync geo-reminders from Firestore into local Room DB
            val remindersSynced = syncGeoRemindersFromFirestore(caregiverUid, patientId)

            if (!reminderCooldownSynced || !safeZoneSynced || !remindersSynced) {
                Log.w(TAG, "Cloud config sync incomplete")
            }

            val pendingRecords = activityRecordDao.getPendingUpload()
            if (pendingRecords.isEmpty()) {
                Log.d(TAG, "No pending records to sync (heartbeat sent)")
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

    suspend fun syncSafeZoneFromFirestore(caregiverUid: String, patientId: String): Boolean {
        try {
            val remoteSafeZone = firestoreActivitySource.getActiveSafeZone(caregiverUid, patientId)
            if (remoteSafeZone == null) {
                val activeZone = safeZoneRepository.getActiveSafeZone().first()
                if (activeZone != null) {
                    geofenceService.removeGeofence(activeZone.id)
                }
                safeZoneRepository.clearActiveSafeZone()
                preferences.setSafeZoneRadius(0)
                return true
            }

            val id = remoteSafeZone["id"] as? String ?: return false
            val centerLat = (remoteSafeZone["centerLat"] as? Number)?.toDouble() ?: return false
            val centerLng = (remoteSafeZone["centerLng"] as? Number)?.toDouble() ?: return false
            val radiusMeters = (remoteSafeZone["radiusMeters"] as? Number)?.toInt() ?: return false
            val isActive = remoteSafeZone["isActive"] as? Boolean ?: true
            val alarmEnabled = remoteSafeZone["alarmEnabled"] as? Boolean ?: true
            val vibrationEnabled = remoteSafeZone["vibrationEnabled"] as? Boolean ?: true

            val config = SafeZoneConfig(
                id = id,
                centerLat = centerLat,
                centerLng = centerLng,
                radiusMeters = radiusMeters,
                isActive = isActive,
                alarmEnabled = alarmEnabled,
                vibrationEnabled = vibrationEnabled
            )

            val currentActiveZone = safeZoneRepository.getActiveSafeZone().first()
            if (currentActiveZone != null && currentActiveZone.id != config.id) {
                geofenceService.removeGeofence(currentActiveZone.id)
            }

            safeZoneRepository.updateFromFirestore(config)

            geofenceService.removeGeofence(config.id)
            if (config.isActive) {
                preferences.setSafeZoneRadius(radiusMeters)
                val registerResult = geofenceService.registerSafeZone(config)
                if (registerResult.isFailure) {
                    Log.e(
                        TAG,
                        "Failed to register safe-zone geofence from Firestore sync: ${config.id}",
                        registerResult.exceptionOrNull()
                    )
                }
            } else {
                preferences.setSafeZoneRadius(0)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync safe zone from Firestore", e)
            return false
        }
    }

    private suspend fun syncDailySummaryToFirestore(caregiverUid: String, patientId: String) {
        try {
            val today = LocalDate.now().toString()
            val summary = dailySummaryRepository.getSummaryForDate(today).first()
            if (summary != null) {
                val summaryMap = mapOf<String, Any>(
                    "date" to summary.date,
                    "patientId" to patientId,
                    "distanceMeters" to summary.distanceMeters,
                    "activeMinutes" to summary.activeMinutes,
                    "placesVisited" to summary.placesVisited,
                    "safeZoneExits" to summary.safeZoneExits,
                    "remindersTriggered" to summary.remindersTriggered,
                    "totalEvents" to summary.totalEvents,
                    "stepCount" to summary.stepCount,
                    "lastUpdated" to Timestamp.now()
                )
                firestoreActivitySource.uploadDailySummary(
                    caregiverUid = caregiverUid,
                    patientId = patientId,
                    summary = summaryMap
                )
                Log.d(TAG, "Daily summary synced for $today")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync daily summary", e)
        }
    }

    suspend fun syncGeoRemindersFromFirestore(caregiverUid: String, patientId: String): Boolean {
        try {
            val existingReminders = geoReminderRepository.getActiveReminders().first()
            geoReminderRepository.syncFromFirestore(caregiverUid, patientId)
            val syncedReminders = geoReminderRepository.getActiveReminders().first()

            val syncedReminderIds = syncedReminders.map { it.id }.toSet()
            existingReminders
                .filter { it.id !in syncedReminderIds }
                .forEach { reminder ->
                    geofenceService.removeGeofence("reminder_${reminder.id}")
                }

            syncedReminders.forEach { reminder ->
                val registerResult = geofenceService.registerReminder(
                    id = reminder.id,
                    latitude = reminder.latitude,
                    longitude = reminder.longitude,
                    radiusMeters = reminder.radiusMeters
                )
                if (registerResult.isFailure) {
                    Log.e(
                        TAG,
                        "Failed to register reminder geofence: ${reminder.id}",
                        registerResult.exceptionOrNull()
                    )
                }

                // Background download of media
                syncScope.launch {
                    val id = reminder.id
                    reminder.imageUrl?.let { url ->
                        mediaCacheManager.downloadMedia(url, "${id}_photo")
                    }
                    reminder.audioUrl?.let { url ->
                        mediaCacheManager.downloadMedia(url, "${id}_audio")
                    }
                    reminder.videoUrl?.let { url ->
                        mediaCacheManager.downloadMedia(url, "${id}_video")
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync geo-reminders from Firestore", e)
            return false
        }
    }

    private suspend fun syncReminderCooldownFromFirestore(caregiverUid: String, patientId: String): Boolean {
        try {
            val cooldown = firestoreActivitySource.getReminderCooldownMinutes(caregiverUid, patientId)
            if (cooldown != null && cooldown > 0) {
                preferences.setReminderCooldownMinutes(cooldown)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync reminder cooldown setting", e)
            return false
        }
    }

    companion object {
        private const val TAG = "SyncService"
    }
}
