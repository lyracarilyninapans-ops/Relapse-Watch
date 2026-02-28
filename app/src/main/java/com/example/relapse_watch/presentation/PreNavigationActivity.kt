package com.example.relapse_watch.presentation

import android.content.Intent
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

@AndroidEntryPoint
class PreNavigationActivity : ComponentActivity() {

    private var countdown by mutableIntStateOf(10)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val vibrator = getSystemService<Vibrator>()
        vibrator?.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(500, 500),
                0
            )
        )

        setContent {
            Relapse_WatchTheme {
                LaunchedEffect(Unit) {
                    while (countdown > 0) {
                        delay(1000)
                        countdown--
                    }
                    vibrator?.cancel()
                    // Launch NavigationActivity to guide patient home
                    startActivity(
                        Intent(this@PreNavigationActivity, NavigationActivity::class.java)
                    )
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
