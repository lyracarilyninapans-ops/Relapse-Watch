package com.example.relapse_watch.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private companion object {
        val IS_PAIRED = booleanPreferencesKey("is_paired")
        val PAIRING_CODE = stringPreferencesKey("pairing_code")
        val CAREGIVER_UID = stringPreferencesKey("caregiver_uid")
        val WATCH_ID = stringPreferencesKey("watch_id")
        val PATIENT_NAME = stringPreferencesKey("patient_name")
        val PATIENT_ID = stringPreferencesKey("patient_id")
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
        val SAFE_ZONE_RADIUS_METERS = intPreferencesKey("safe_zone_radius_meters")
        val REMINDER_COOLDOWN_MINUTES = intPreferencesKey("reminder_cooldown_minutes")
        val REGISTERED_GEOFENCE_KEYS = stringSetPreferencesKey("registered_geofence_keys")
    }

    val isPaired: Flow<Boolean> = dataStore.data.map { it[IS_PAIRED] ?: false }
    val pairingCode: Flow<String> = dataStore.data.map { it[PAIRING_CODE] ?: "" }
    val caregiverUid: Flow<String> = dataStore.data.map { it[CAREGIVER_UID] ?: "" }
    val watchId: Flow<String> = dataStore.data.map { it[WATCH_ID] ?: "" }
    val patientName: Flow<String> = dataStore.data.map { it[PATIENT_NAME] ?: "" }
    val patientId: Flow<String> = dataStore.data.map { it[PATIENT_ID] ?: "" }
    val lastSyncTimestamp: Flow<Long> = dataStore.data.map { it[LAST_SYNC_TIMESTAMP] ?: 0L }
    val safeZoneRadiusMeters: Flow<Int> = dataStore.data.map { it[SAFE_ZONE_RADIUS_METERS] ?: 0 }
    val reminderCooldownMinutes: Flow<Int> = dataStore.data.map { it[REMINDER_COOLDOWN_MINUTES] ?: 30 }
    val registeredGeofenceKeys: Flow<Set<String>> = dataStore.data.map { it[REGISTERED_GEOFENCE_KEYS] ?: emptySet() }

    suspend fun setPaired(isPaired: Boolean, caregiverUid: String, watchId: String) {
        dataStore.edit { prefs ->
            prefs[IS_PAIRED] = isPaired
            prefs[CAREGIVER_UID] = caregiverUid
            prefs[WATCH_ID] = watchId
        }
    }

    suspend fun clearPairing() {
        dataStore.edit { prefs ->
            prefs[IS_PAIRED] = false
            prefs[PAIRING_CODE] = ""
            prefs[CAREGIVER_UID] = ""
            prefs[WATCH_ID] = ""
            prefs[PATIENT_NAME] = ""
            prefs[PATIENT_ID] = ""
        }
    }

    suspend fun updateLastSync(timestamp: Long) {
        dataStore.edit { prefs ->
            prefs[LAST_SYNC_TIMESTAMP] = timestamp
        }
    }

    suspend fun setPatientInfo(name: String, id: String) {
        dataStore.edit { prefs ->
            prefs[PATIENT_NAME] = name
            prefs[PATIENT_ID] = id
        }
    }

    suspend fun setPairingCode(code: String) {
        dataStore.edit { prefs ->
            prefs[PAIRING_CODE] = code
        }
    }

    suspend fun setSafeZoneRadius(radiusMeters: Int) {
        dataStore.edit { prefs ->
            prefs[SAFE_ZONE_RADIUS_METERS] = radiusMeters
        }
    }

    suspend fun setReminderCooldownMinutes(minutes: Int) {
        dataStore.edit { prefs ->
            prefs[REMINDER_COOLDOWN_MINUTES] = minutes
        }
    }

    suspend fun addRegisteredGeofenceKey(key: String) {
        dataStore.edit { prefs ->
            val current = prefs[REGISTERED_GEOFENCE_KEYS] ?: emptySet()
            prefs[REGISTERED_GEOFENCE_KEYS] = current + key
        }
    }

    suspend fun removeRegisteredGeofenceKeysStartingWith(prefix: String) {
        dataStore.edit { prefs ->
            val current = prefs[REGISTERED_GEOFENCE_KEYS] ?: emptySet()
            prefs[REGISTERED_GEOFENCE_KEYS] = current.filterNot { it.startsWith(prefix) }.toSet()
        }
    }

    suspend fun clearRegisteredGeofenceKeys() {
        dataStore.edit { prefs ->
            prefs[REGISTERED_GEOFENCE_KEYS] = emptySet()
        }
    }
}
