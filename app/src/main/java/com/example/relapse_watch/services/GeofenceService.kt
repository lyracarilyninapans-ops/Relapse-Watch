package com.example.relapse_watch.services

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.relapse_watch.data.local.dao.GeoReminderDao
import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.domain.model.GeoReminder
import com.example.relapse_watch.domain.model.SafeZoneConfig
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeofenceService @Inject constructor(
    private val geofencingClient: GeofencingClient,
    private val preferences: WatchPreferences,
    private val geoReminderDao: GeoReminderDao,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {

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

    /**
     * Register a single reminder geofence with the OS.
     *
     * Uses `setInitialTrigger(0)` instead of `INITIAL_TRIGGER_ENTER` to
     * prevent phantom triggers when re-registering after process death.
     * Without this, every re-registration while the patient is already
     * inside the zone fires a spurious enter event, exhausting the
     * cooldown and silently suppressing real triggers.
     *
     * The geofence key is persisted in DataStore so that after process
     * death we know which geofences were already registered and can skip
     * re-registration for unchanged ones.
     */
    @SuppressLint("MissingPermission")
    suspend fun registerReminder(
        id: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Int
    ): Result<Unit> {
        val key = "reminder_$id|$latitude|$longitude|$radiusMeters"

        val persistedKeys = preferences.registeredGeofenceKeys.first()
        if (key in persistedKeys) {
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
                .setInitialTrigger(0) // Never fire on registration — prevents phantom triggers
                .addGeofence(geofence)
                .build()

            geofencingClient.addGeofences(request, geofencePendingIntent).await()
            preferences.addRegisteredGeofenceKey(key)
            Log.d(TAG, "Reminder geofence registered: $id")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register reminder geofence: $id", e)
            Result.failure(e)
        }
    }

    suspend fun removeGeofence(requestId: String): Result<Unit> {
        preferences.removeRegisteredGeofenceKeysStartingWith("$requestId|")
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
        preferences.clearRegisteredGeofenceKeys()
        currentSafeZoneKey = null
        return try {
            geofencingClient.removeGeofences(geofencePendingIntent).await()
            Log.d(TAG, "All geofences removed")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove all geofences", e)
            Result.failure(e)
        }
    }

    /**
     * Called after reboot / process death to re-register ALL reminder
     * geofences from the local Room DB.  This does NOT rely on Firestore
     * connectivity so it works offline.
     *
     * Clears the persisted key set first (OS dropped all geofences),
     * then re-registers every active reminder.
     */
    @SuppressLint("MissingPermission")
    suspend fun reRegisterAllRemindersFromDb(): Int {
        // The OS dropped all geofences — wipe persisted keys so
        // registerReminder() won't skip anything.
        preferences.clearRegisteredGeofenceKeys()
        currentSafeZoneKey = null

        val reminders = geoReminderDao.getActiveReminders().first().map { entity ->
            GeoReminder(
                id = entity.id,
                title = entity.title,
                body = entity.body,
                latitude = entity.latitude,
                longitude = entity.longitude,
                radiusMeters = entity.radiusMeters,
                imageUrl = entity.imageUrl,
                audioUrl = entity.audioUrl,
                videoUrl = entity.videoUrl,
                isActive = entity.isActive,
                lastTriggeredAt = entity.lastTriggeredAt
            )
        }

        var registered = 0
        for (reminder in reminders) {
            val result = registerReminder(
                id = reminder.id,
                latitude = reminder.latitude,
                longitude = reminder.longitude,
                radiusMeters = reminder.radiusMeters
            )
            if (result.isSuccess) registered++
        }
        Log.d(TAG, "Re-registered $registered/${reminders.size} reminder geofences from DB")
        return registered
    }

    companion object {
        private const val TAG = "GeofenceService"
    }
}
