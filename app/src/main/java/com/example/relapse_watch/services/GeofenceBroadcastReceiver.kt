package com.example.relapse_watch.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.relapse_watch.domain.model.LocationPoint
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    @Inject lateinit var reminderTriggerCoordinator: ReminderTriggerCoordinator

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

        val point = location?.let {
            LocationPoint(
                latitude = it.latitude,
                longitude = it.longitude,
                timestamp = System.currentTimeMillis()
            )
        }
        reminderTriggerCoordinator.triggerFromGeofence(reminderId, point)
    }

    companion object {
        private const val TAG = "GeofenceReceiver"
    }
}
