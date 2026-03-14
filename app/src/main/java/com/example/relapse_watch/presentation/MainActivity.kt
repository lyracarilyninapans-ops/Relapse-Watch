package com.example.relapse_watch.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.relapse_watch.presentation.model.MonitoringState
import com.example.relapse_watch.presentation.screens.MonitoringScreen
import com.example.relapse_watch.presentation.screens.PairingScreen
import com.example.relapse_watch.presentation.theme.Relapse_WatchTheme
import com.example.relapse_watch.services.MonitoringForegroundService
import com.example.relapse_watch.services.SyncScheduler
import com.example.relapse_watch.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    @Inject lateinit var syncScheduler: SyncScheduler

    /** Tracks whether location permissions have been granted so the UI
     *  can react and start services. Updated by the permission launcher. */
    private val _locationGranted = mutableStateOf(false)

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val locationGranted = fineGranted || coarseGranted

        _locationGranted.value = locationGranted
        if (_locationGranted.value) {
            Log.d(TAG, "Location permission granted — starting monitoring")
            startMonitoringServices()
        } else {
            Log.w(TAG, "Location permission denied — monitoring/geofencing may not start")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Check if permissions are already granted (e.g. after a restart)
        _locationGranted.value = hasLocationPermission()

        setContent {
            Relapse_WatchTheme {
                val isPaired by mainViewModel.isPaired.collectAsState()
                val patientName by mainViewModel.patientName.collectAsState()
                val lastSync by mainViewModel.lastSyncTimestamp.collectAsState()
                val safeZoneStatus by mainViewModel.safeZoneStatus.collectAsState()
                val safeZoneRadius by mainViewModel.safeZoneRadiusMeters.collectAsState()
                val reminderCount by mainViewModel.geoReminderCount.collectAsState()
                val lastLocation by mainViewModel.lastLocationTimestamp.collectAsState()
                val locationGranted by _locationGranted

                // Fallback flag: if the DataStore → StateFlow chain is slow,
                // the PairingScreen callback sets this so the UI still transitions.
                var pairingConfirmed by remember { mutableStateOf(false) }

                // Reset the fallback flag when the watch becomes unpaired
                // (e.g. phone-initiated remote unpair) so PairingScreen re-appears.
                LaunchedEffect(isPaired) {
                    if (!isPaired) {
                        pairingConfirmed = false
                        mainViewModel.resetImmediatePairingLocationFlag()
                    }
                }

                // When the watch is paired, either start services (if
                // permissions are already granted) or request permissions
                // first. The service start is safe inside the callback.
                LaunchedEffect(isPaired, pairingConfirmed, locationGranted) {
                    if (isPaired || pairingConfirmed) {
                        syncScheduler.schedulePeriodicSync()
                        syncScheduler.requestImmediateSync()

                        if (locationGranted) {
                            startMonitoringServices()
                            mainViewModel.triggerImmediatePostPairingLocationSync()
                        } else {
                            requestLocationPermissions()
                        }
                    }
                }

                // Software-based safe-zone navigation backup.
                // When MainViewModel detects the patient transitioned to
                // Outside, launch PreNavigationActivity from this Activity
                // context (avoids background activity-start restrictions).
                LaunchedEffect(Unit) {
                    mainViewModel.navigateToSafeZone.collect { (lat, lng) ->
                        Log.d(TAG, "Software safe-zone trigger → launching PreNavigationActivity")
                        startActivity(
                            Intent(this@MainActivity, PreNavigationActivity::class.java).apply {
                                putExtra("safe_zone_lat", lat)
                                putExtra("safe_zone_lng", lng)
                            }
                        )
                    }
                }

                // Software-based return-to-safe-zone alert.
                // When the patient crosses back inside, show a full-screen
                // alert telling them to stop Google Maps navigation.
                LaunchedEffect(Unit) {
                    mainViewModel.returnedToSafeZone.collect {
                        Log.d(TAG, "Software safe-zone return → launching SafeZoneReturnActivity")
                        startActivity(
                            Intent(this@MainActivity, SafeZoneReturnActivity::class.java)
                        )
                    }
                }

                if (!isPaired && !pairingConfirmed) {
                    PairingScreen(
                        onPairingComplete = { pairingConfirmed = true }
                    )
                } else {
                    MonitoringScreen(
                        monitoringState = MonitoringState(
                            isPaired = true,
                            patientName = patientName,
                            lastSyncTimestamp = if (lastSync > 0) lastSync else null,
                            lastLocationTimestamp = if (lastLocation > 0) lastLocation else null,
                            safeZoneStatus = safeZoneStatus,
                            safeZoneRadiusMeters = if (safeZoneRadius > 0) safeZoneRadius else null,
                            geoReminderCount = reminderCount
                        ),
                        onOpenSettings = {
                            startActivity(
                                Intent(this@MainActivity, SettingsActivity::class.java)
                            )
                        }
                    )
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        val locationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return locationGranted
    }

    private fun requestLocationPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        locationPermissionLauncher.launch(
            permissions.toTypedArray()
        )
    }

    private fun startMonitoringServices() {
        try {
            MonitoringForegroundService.start(this)
            Log.d(TAG, "Monitoring service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start monitoring services", e)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
