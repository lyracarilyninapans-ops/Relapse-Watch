package com.example.relapse_watch.services

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.relapse_watch.domain.model.SafeZoneConfig
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeofenceService @Inject constructor(
    private val geofencingClient: GeofencingClient,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {

    /**
     * In-memory set of reminder geofence IDs known to be registered with the OS.
     * Cleared on process death (boot / app update / force-stop), which is exactly
     * when the OS also drops all geofence registrations.
     */
    private val registeredReminderKeys = mutableSetOf<String>()

    /**
     * Key describing the currently registered safe zone geofence.
     * Used to skip re-registration when the same safe zone config is synced again.
     */
    private var currentSafeZoneKey: String? = null

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun registerSafeZone(config: SafeZoneConfig): Result<Unit> {
        val key = "${config.id}|${config.centerLat}|${config.centerLng}|${config.radiusMeters}"
        if (key == currentSafeZoneKey) {
            Log.d(TAG, "Safe zone geofence already registered (skipped): ${config.id}")
            return Result.success(Unit)
        }

        return try {
            val geofence = Geofence.Builder()
                .setRequestId(config.id)
                .setCircularRegion(config.centerLat, config.centerLng, config.radiusMeters.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()

            val request = GeofencingRequest.Builder()
                .setInitialTrigger(0) // No initial trigger — prevents duplicate enter events on re-registration
                .addGeofence(geofence)
                .build()

            geofencingClient.addGeofences(request, geofencePendingIntent).await()
            currentSafeZoneKey = key
            Log.d(TAG, "Geofence registered: ${config.id}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register geofence: ${config.id}", e)
            Result.failure(e)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun registerReminder(
        id: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Int
    ): Result<Unit> {
        val key = "reminder_$id|$latitude|$longitude|$radiusMeters"

        if (key in registeredReminderKeys) {
            Log.d(TAG, "Reminder geofence already registered (skipped): $id")
            return Result.success(Unit)
        }

        return try {
            val geofence = Geofence.Builder()
                .setRequestId("reminder_$id")
                .setCircularRegion(latitude, longitude, radiusMeters.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()

            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(geofence)
                .build()

            geofencingClient.addGeofences(request, geofencePendingIntent).await()
            registeredReminderKeys.add(key)
            Log.d(TAG, "Reminder geofence registered: $id")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register reminder geofence: $id", e)
            Result.failure(e)
        }
    }

    suspend fun removeGeofence(requestId: String): Result<Unit> {
        registeredReminderKeys.removeAll { it.startsWith("$requestId|") }
        return try {
            geofencingClient.removeGeofences(listOf(requestId)).await()
            Log.d(TAG, "Geofence removed: $requestId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove geofence: $requestId", e)
            Result.failure(e)
        }
    }

    suspend fun removeAllGeofences(): Result<Unit> {
        registeredReminderKeys.clear()
        return try {
            geofencingClient.removeGeofences(geofencePendingIntent).await()
            Log.d(TAG, "All geofences removed")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove all geofences", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "GeofenceService"
    }
}
