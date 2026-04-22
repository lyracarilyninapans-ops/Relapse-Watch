package com.example.relapse_watch.domain.usecase

import android.content.Context
import android.util.Log
import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.domain.repository.PairingRepository
import com.example.relapse_watch.domain.repository.SafeZoneRepository
import com.example.relapse_watch.services.GeofenceService
import com.example.relapse_watch.services.MonitoringForegroundService
import com.example.relapse_watch.services.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encapsulates all cleanup that must happen when the watch is unpaired,
 * regardless of whether the unpair was initiated by the phone or the watch.
 *
 * Both [com.example.relapse_watch.viewmodel.MainViewModel] (remote/phone-initiated)
 * and [com.example.relapse_watch.viewmodel.SettingsViewModel] (local/watch-initiated)
 * delegate to this class so the logic stays in one place.
 */
@Singleton
class UnpairUseCase @Inject constructor(
    private val pairingRepository: PairingRepository,
    private val safeZoneRepository: SafeZoneRepository,
    private val geofenceService: GeofenceService,
    private val syncScheduler: SyncScheduler,
    private val preferences: WatchPreferences,
    @ApplicationContext private val context: Context
) {

    private val cleanupMutex = Mutex()

    /**
     * Perform full unpair cleanup.
     *
     * @param alsoNotifyPhone When `true` the caregiver's Firestore doc is set
     *   to "unpaired" so the phone app detects the change. Pass `true` only
     *   for *watch-initiated* unpairs; for phone-initiated unpairs the phone
     *   has already updated its own doc.
     */
    suspend fun execute(alsoNotifyPhone: Boolean = false) {
        cleanupMutex.withLock {
            val currentlyPaired = preferences.isPaired.firstOrNull() ?: false
            if (!currentlyPaired) {
                Log.d(TAG, "Unpair cleanup skipped; already unpaired")
                return
            }

            // Read identifiers before we wipe local state.
            val code = preferences.pairingCode.firstOrNull() ?: ""
            val caregiverUid = preferences.caregiverUid.firstOrNull() ?: ""

            // 1. Delete the global watchPairingCodes/{code} document.
            if (code.isNotBlank()) {
                try {
                    pairingRepository.deletePairingEntry(code)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete pairing code doc", e)
                }
            }

            // 2. (Watch-initiated only) Tell the phone we unpaired.
            if (alsoNotifyPhone && caregiverUid.isNotBlank()) {
                try {
                    pairingRepository.unpairCaregiver(caregiverUid)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to notify phone of unpair", e)
                }
            }

            // 3. Remove all registered geofences (safe zone + reminders).
            try {
                geofenceService.removeAllGeofences()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove geofences", e)
            }

            // 4. Cancel WorkManager periodic sync.
            try {
                syncScheduler.cancelPeriodicSync()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel sync scheduler", e)
            }

            // 5. Stop the monitoring foreground service.
            try {
                MonitoringForegroundService.stop(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop monitoring service", e)
            }

            // 6. Clear local DataStore preferences (isPaired, code, caregiver, etc.).
            pairingRepository.clearPairing()

            // 7. Clear local safe-zone state so a later pairing cannot reuse
            // stale active zones from a previous caregiver/patient.
            try {
                safeZoneRepository.clearActiveSafeZone()
                preferences.setSafeZoneRadius(0)
                preferences.clearInsideSafeZone()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear local safe-zone state", e)
            }

            Log.d(TAG, "Unpair cleanup complete (notifiedPhone=$alsoNotifyPhone)")
        }
    }

    companion object {
        private const val TAG = "UnpairUseCase"
    }
}
