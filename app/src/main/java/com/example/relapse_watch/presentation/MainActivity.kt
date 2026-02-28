package com.example.relapse_watch.presentation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.relapse_watch.presentation.model.MonitoringState
import com.example.relapse_watch.presentation.model.SafeZoneStatus
import com.example.relapse_watch.presentation.screens.MonitoringScreen
import com.example.relapse_watch.presentation.screens.PairingScreen
import com.example.relapse_watch.presentation.theme.Relapse_WatchTheme
import com.example.relapse_watch.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setContent {
            Relapse_WatchTheme {
                val isPaired by mainViewModel.isPaired.collectAsState()
                val patientName by mainViewModel.patientName.collectAsState()
                val lastSync by mainViewModel.lastSyncTimestamp.collectAsState()
                val safeZoneRadius by mainViewModel.safeZoneRadiusMeters.collectAsState()
                val reminderCount by mainViewModel.geoReminderCount.collectAsState()

                if (!isPaired) {
                    PairingScreen()
                } else {
                    MonitoringScreen(
                        monitoringState = MonitoringState(
                            isPaired = true,
                            patientName = patientName,
                            lastSyncTimestamp = if (lastSync > 0) lastSync else null,
                            safeZoneStatus = SafeZoneStatus.Unknown,
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
}
