package com.example.relapse_watch.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.relapse_watch.presentation.screens.SettingsScreen
import com.example.relapse_watch.presentation.theme.Relapse_WatchTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Relapse_WatchTheme {
                SettingsScreen(
                    onResetPairing = {
                        // In a real app, this would clear pairing data
                        // and navigate back to PairingScreen
                        finish()
                    }
                )
            }
        }
    }
}
