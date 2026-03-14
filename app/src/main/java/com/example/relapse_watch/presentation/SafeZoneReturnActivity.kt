package com.example.relapse_watch.presentation

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.getSystemService
import com.example.relapse_watch.presentation.screens.SafeZoneReturnScreen
import com.example.relapse_watch.presentation.theme.Relapse_WatchTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

/**
 * Full-screen alert shown when the patient re-enters the safe zone while
 * Google Maps walking navigation may still be running.  Vibrates strongly
 * for 5 seconds so the patient notices, then auto-dismisses after 8 s.
 */
@AndroidEntryPoint
class SafeZoneReturnActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val vibrator = getSystemService<Vibrator>()
        vibrator?.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 800, 200, 800, 200, 800, 200, 800, 200, 800),
                -1   // do not repeat — plays once (≈5 s)
            )
        )

        setContent {
            Relapse_WatchTheme {
                // Auto-dismiss after 8 seconds
                LaunchedEffect(Unit) {
                    delay(8_000)
                    finish()
                }

                SafeZoneReturnScreen(
                    onDismiss = {
                        vibrator?.cancel()
                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        getSystemService<Vibrator>()?.cancel()
        super.onDestroy()
    }
}
