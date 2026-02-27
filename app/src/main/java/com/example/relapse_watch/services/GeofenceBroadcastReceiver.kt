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
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    @Inject lateinit var safeZoneRepository: SafeZoneRepository
    @Inject lateinit var geoReminderRepository: GeoReminderRepository
    @Inject lateinit var activityTrackingService: ActivityTrackingService
    @Inject lateinit var wearableCommunicationService: WearableCommunicationService
    @Inject lateinit var syncService: SyncService

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
                        handleReminderGeofence(requestId.removePrefix("reminder_"), transition, location)
                    } else {
                        handleSafeZoneGeofence(requestId, transition, location)
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

        wearableCommunicationService.sendSafeZoneAlert(eventType, lat, lng)

        if (eventType == EventTypes.SAFE_ZONE_EXIT) {
            syncService.syncActivityData()
        }

        Log.d(TAG, "Safe zone $eventType for zone: $zoneId")
    }

    private suspend fun handleReminderGeofence(
        reminderId: String,
        transition: Int,
        location: android.location.Location?
    ) {
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER) return

        val reminder = geoReminderRepository.getReminder(reminderId) ?: return
        val timestamp = System.currentTimeMillis()

        geoReminderRepository.markAsTriggered(reminderId, timestamp)

        val lat = location?.latitude ?: reminder.latitude
        val lng = location?.longitude ?: reminder.longitude
        val point = LocationPoint(lat, lng, timestamp)
        activityTrackingService.recordReminderTriggered(reminderId, point)

        wearableCommunicationService.sendReminderTriggered(reminderId, reminder.title)

        Log.d(TAG, "Reminder triggered: ${reminder.title} ($reminderId)")
    }

    companion object {
        private const val TAG = "GeofenceReceiver"
    }
}
