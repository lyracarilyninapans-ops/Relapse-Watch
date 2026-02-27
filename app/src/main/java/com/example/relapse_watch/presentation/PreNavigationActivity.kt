package com.example.relapse_watch.presentation

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService
import com.example.relapse_watch.presentation.screens.PreNavigationScreen
import com.example.relapse_watch.presentation.theme.Relapse_WatchTheme
import kotlinx.coroutines.delay

class PreNavigationActivity : ComponentActivity() {

    private var countdown by mutableIntStateOf(10)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start vibration pattern: 500ms off, 500ms on, repeating
        val vibrator = getSystemService<Vibrator>()
        vibrator?.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(500, 500),
                0 // repeat from index 0
            )
        )

        setContent {
            Relapse_WatchTheme {
                // Countdown timer
                LaunchedEffect(Unit) {
                    while (countdown > 0) {
                        delay(1000)
                        countdown--
                    }
                    // Countdown finished — in a real app, this would start NavigationActivity
                    vibrator?.cancel()
                    finish()
                }

                PreNavigationScreen(
                    countdown = countdown,
                    onCancel = {
                        vibrator?.cancel()
                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService<Vibrator>()?.cancel()
    }
}
