package com.example.relapse_watch.presentation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.example.relapse_watch.presentation.screens.SettingsScreen
import com.example.relapse_watch.presentation.theme.Relapse_WatchTheme
import com.example.relapse_watch.viewmodel.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val cacheSize by settingsViewModel.cacheSize.collectAsState()

            Relapse_WatchTheme {
                SettingsScreen(
                    cacheSize = cacheSize,
                    onClearCache = { settingsViewModel.clearCache() },
                    onResetPairing = {
                        settingsViewModel.resetPairing {
                            // Navigate back to MainActivity which will show PairingScreen
                            val intent = Intent(this@SettingsActivity, MainActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(intent)
                            finish()
                        }
                    }
                )
            }
        }
    }
}
