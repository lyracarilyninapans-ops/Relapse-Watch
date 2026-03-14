package com.example.relapse_watch.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.relapse_watch.domain.model.EventTypes
import com.example.relapse_watch.domain.model.LocationPoint
import com.example.relapse_watch.domain.model.SafeZoneEvent
import com.example.relapse_watch.domain.repository.GeoReminderRepository
import com.example.relapse_watch.domain.repository.SafeZoneRepository
import com.example.relapse_watch.data.preferences.WatchPreferences
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    @Inject lateinit var safeZoneRepository: SafeZoneRepository
    @Inject lateinit var geoReminderRepository: GeoReminderRepository
    @Inject lateinit var watchPreferences: WatchPreferences
    @Inject lateinit var activityTrackingService: ActivityTrackingService
    @Inject lateinit var wearableCommunicationService: WearableCommunicationService
    @Inject lateinit var syncService: SyncService
    @Inject lateinit var reminderPlaybackQueueManager: ReminderPlaybackQueueManager
    @Inject lateinit var notificationService: NotificationService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return

        if (geofencingEvent.hasError()) {
            val errorMessage = GeofenceStatusCodes.getStatusCodeString(geofencingEvent.errorCode)
            Log.e(TAG, "Geofencing error: $errorMessage")
            return
        }

        val transition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return
        val location = geofencingEvent.triggeringLocation

        val pendingResult = goAsync()

        scope.launch {
            try {
                for (geofence in triggeringGeofences) {
                    val requestId = geofence.requestId

                    if (requestId.startsWith("reminder_")) {
                        handleReminderGeofence(
                            reminderId = requestId.removePrefix("reminder_"),
                            transition = transition,
                            location = location
                        )
                    } else {
                        handleSafeZoneGeofence(context, requestId, transition, location)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling geofence event", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleSafeZoneGeofence(
        context: Context,
        zoneId: String,
        transition: Int,
        location: android.location.Location?
    ) {
        val eventType = when (transition) {
            Geofence.GEOFENCE_TRANSITION_EXIT -> EventTypes.SAFE_ZONE_EXIT
            Geofence.GEOFENCE_TRANSITION_ENTER -> EventTypes.SAFE_ZONE_ENTER
            else -> return
        }

        val lat = location?.latitude ?: 0.0
        val lng = location?.longitude ?: 0.0
        val timestamp = System.currentTimeMillis()

        val event = SafeZoneEvent(
            id = UUID.randomUUID().toString(),
            safeZoneId = zoneId,
            eventType = eventType,
            timestamp = timestamp,
            latitude = lat,
            longitude = lng
        )
        safeZoneRepository.recordEvent(event)

        val point = LocationPoint(lat, lng, timestamp)
        activityTrackingService.recordSafeZoneEvent(eventType, point)

        val activeZone = safeZoneRepository.getActiveSafeZone().first()
        val alertsEnabled = (activeZone?.alarmEnabled == true) || (activeZone?.vibrationEnabled == true)
        if (alertsEnabled) {
            wearableCommunicationService.sendSafeZoneAlert(eventType, lat, lng)
        }

        if (eventType == EventTypes.SAFE_ZONE_EXIT) {
            try {
                syncService.syncActivityData()
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed during safe-zone exit — continuing to navigation", e)
            }

            // Launch pre-navigation countdown before Google Maps walking navigation.
            // Use a full-screen intent notification — calling startActivity() from
            // a BroadcastReceiver background coroutine is blocked on Android 12+.
            if (activeZone != null) {
                notificationService.showSafeZoneNavigationNotification(
                    safeZoneLat = activeZone.centerLat,
                    safeZoneLng = activeZone.centerLng
                )
                Log.d(TAG, "Safe zone navigation notification posted")
            } else {
                Log.w(TAG, "Active zone is null — cannot launch navigation")
            }
        }

        // When patient re-enters the safe zone, show a prominent alert
        // so they know to stop Google Maps navigation.
        if (eventType == EventTypes.SAFE_ZONE_ENTER) {
            notificationService.showSafeZoneReturnNotification()
            Log.d(TAG, "Safe zone return notification posted")
        }

        Log.d(TAG, "Safe zone $eventType for zone: $zoneId")
    }

    private suspend fun handleReminderGeofence(
        reminderId: String,
        transition: Int,
        location: android.location.Location?
    ) {
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER) return

        reminderMutex.withLock {
            val reminder = geoReminderRepository.getReminder(reminderId) ?: return@withLock
            val timestamp = System.currentTimeMillis()
            val cooldownMinutes = watchPreferences.reminderCooldownMinutes.first()
            val cooldownMs = cooldownMinutes.coerceAtLeast(0) * 60_000L

            val lastTriggeredAt = reminder.lastTriggeredAt ?: 0L
            if ((timestamp - lastTriggeredAt) < cooldownMs) {
                Log.d(TAG, "Reminder trigger skipped due to cooldown: $reminderId")
                return@withLock
            }

            geoReminderRepository.markAsTriggered(reminderId, timestamp)

            val lat = location?.latitude ?: reminder.latitude
            val lng = location?.longitude ?: reminder.longitude
            val point = LocationPoint(lat, lng, timestamp)
            activityTrackingService.recordReminderTriggered(reminderId, point)

            reminderPlaybackQueueManager.enqueue(
                ReminderPlaybackRequest(
                    reminderId = reminder.id,
                    title = reminder.title,
                    body = reminder.body,
                    imageUrl = reminder.imageUrl,
                    audioUrl = reminder.audioUrl,
                    videoUrl = reminder.videoUrl
                )
            )

            wearableCommunicationService.sendReminderTriggered(reminderId, reminder.title)

            Log.d(TAG, "Reminder triggered: ${reminder.title} ($reminderId)")
        }
    }

    companion object {
        private const val TAG = "GeofenceReceiver"
        private val reminderMutex = Mutex()
    }
}
