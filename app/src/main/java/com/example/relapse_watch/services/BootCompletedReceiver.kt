package com.example.relapse_watch.services

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.relapse_watch.data.preferences.WatchPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.example.relapse_watch.domain.repository.SafeZoneRepository

@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var preferences: WatchPreferences
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var geofenceService: GeofenceService
    @Inject lateinit var safeZoneRepository: SafeZoneRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val isPaired = preferences.isPaired.firstOrNull() ?: false
                val hasLocationPermission =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

                if (isPaired && hasLocationPermission) {
                    Log.d(TAG, "Boot completed — paired & permissions granted, starting services")
                    try {
                        MonitoringForegroundService.start(context)
                        syncScheduler.schedulePeriodicSync()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start monitoring service on boot", e)
                    }

                    // The OS drops ALL geofences on reboot.
                    // Re-register them from the local Room DB so they work
                    // immediately, even without Firestore connectivity.
                    try {
                        val count = geofenceService.reRegisterAllRemindersFromDb()
                        Log.d(TAG, "Boot: re-registered $count reminder geofences from DB")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to re-register geofences on boot", e)
                    }

                    // Re-register safe zone from local DB offline
                    try {
                        val activeZone = safeZoneRepository.getActiveSafeZone().firstOrNull()
                        if (activeZone != null && activeZone.isActive) {
                            val result = geofenceService.registerSafeZone(activeZone)
                            if (result.isSuccess) {
                                Log.d(TAG, "Boot: re-registered safe zone geofence from DB: ${activeZone.id}")
                            } else {
                                Log.e(TAG, "Boot: failed to re-register safe zone geofence", result.exceptionOrNull())
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to re-register safe zone geofence on boot", e)
                    }
                } else if (isPaired) {
                    Log.d(TAG, "Boot completed — paired but location not granted, scheduling sync only")
                    syncScheduler.schedulePeriodicSync()
                } else {
                    Log.d(TAG, "Boot completed — NOT paired, skipping")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
