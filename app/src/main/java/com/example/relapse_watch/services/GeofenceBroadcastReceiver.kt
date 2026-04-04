package com.example.relapse_watch.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.relapse_watch.domain.model.LocationPoint
import com.example.relapse_watch.domain.repository.GeoReminderRepository
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

    @Inject lateinit var geoReminderRepository: GeoReminderRepository
    @Inject lateinit var watchPreferences: WatchPreferences
    @Inject lateinit var activityTrackingService: ActivityTrackingService
    @Inject lateinit var wearableCommunicationService: WearableCommunicationService
    @Inject lateinit var reminderPlaybackQueueManager: ReminderPlaybackQueueManager

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
                        // Safe zone geofence transitions are handled exclusively
                        // by MonitoringForegroundService.checkSafeZoneProximity()
                        // to prevent double-trigger issues.
                        Log.d(TAG, "Ignoring safe zone geofence transition (handled by MonitoringForegroundService)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling geofence event", e)
            } finally {
                pendingResult.finish()
            }
        }
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
            val correlation = "$reminderId:$timestamp"
            val cooldownMinutes = watchPreferences.reminderCooldownMinutes.first()
            val cooldownMs = cooldownMinutes.coerceAtLeast(0) * 60_000L

            val lastTriggeredAt = reminder.lastTriggeredAt ?: 0L
            if ((timestamp - lastTriggeredAt) < cooldownMs) {
                Log.d(
                    TAG,
                    "[REMINDER_TRIGGER][SKIP_COOLDOWN] key=$correlation id=$reminderId now=$timestamp last=$lastTriggeredAt cooldownMs=$cooldownMs remainingMs=${cooldownMs - (timestamp - lastTriggeredAt)}"
                )
                return@withLock
            }

            geoReminderRepository.markAsTriggered(reminderId, timestamp)
            Log.d(
                TAG,
                "[REMINDER_TRIGGER][MARKED] key=$correlation id=$reminderId triggeredAt=$timestamp prevLast=$lastTriggeredAt cooldownMs=$cooldownMs"
            )

            val lat = location?.latitude ?: reminder.latitude
            val lng = location?.longitude ?: reminder.longitude
            val point = LocationPoint(lat, lng, timestamp)
            activityTrackingService.recordReminderTriggered(reminderId, point)

            reminderPlaybackQueueManager.enqueue(
                ReminderPlaybackRequest(
                    reminderId = reminder.id,
                    triggeredAt = timestamp,
                    title = reminder.title,
                    body = reminder.body,
                    imageUrl = reminder.imageUrl,
                    audioUrl = reminder.audioUrl,
                    videoUrl = reminder.videoUrl
                )
            )
            Log.d(
                TAG,
                "[REMINDER_TRIGGER][ENQUEUED] key=$correlation id=${reminder.id} triggeredAt=$timestamp hasAudio=${!reminder.audioUrl.isNullOrBlank()}"
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
