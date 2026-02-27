package com.example.relapse_watch.presentation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material3.Text
import com.example.relapse_watch.presentation.model.MonitoringState
import com.example.relapse_watch.presentation.model.SafeZoneStatus
import com.example.relapse_watch.presentation.screens.MonitoringScreen
import com.example.relapse_watch.presentation.screens.PairingScreen
import com.example.relapse_watch.presentation.theme.Relapse_WatchTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // In a real app, this would come from a ViewModel / repository.
    // For now, we use a sample state so the UI compiles and renders.
    // TODO: TEMP — set isPaired=true to preview MonitoringScreen. Revert to false for PairingScreen.
    private var monitoringState by mutableStateOf(
        MonitoringState(
            isPaired = true, // TEMP: was false
            pairingCode = "A1B2C3",
            statusMessage = null,
            patientName = "John Doe",
            lastSyncTimestamp = System.currentTimeMillis() - 3 * 60 * 1000, // 3 min ago
            safeZoneStatus = SafeZoneStatus.Inside,
            safeZoneRadiusMeters = 200,
            geoReminderCount = 5
        )
    )

    private var permissionsGranted by mutableStateOf(true) // Simplified for now

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setContent {
            Relapse_WatchTheme {
                if (!permissionsGranted) {
                    // Permission gate
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Please grant permissions to use the app.")
                    }
                } else if (!monitoringState.isPaired) {
                    // Pairing Screen
                    PairingScreen(
                        pairingCode = monitoringState.pairingCode,
                        statusMessage = monitoringState.statusMessage
                    )
                } else {
                    // Monitoring Screen (Home)
                    MonitoringScreen(
                        monitoringState = monitoringState,
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
