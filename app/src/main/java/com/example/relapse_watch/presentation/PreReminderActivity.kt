package com.example.relapse_watch.presentation

import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import com.example.relapse_watch.presentation.screens.PreReminderScreen
import com.example.relapse_watch.presentation.theme.Relapse_WatchTheme
import com.example.relapse_watch.services.NotificationService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay

@AndroidEntryPoint
class PreReminderActivity : ComponentActivity() {

    @Inject lateinit var notificationService: NotificationService

    private lateinit var vibrator: Vibrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        notificationService.dismissReminderPlaybackNotification()

        val reminderIntent = Intent(this, ReminderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP

            putExtra("reminderId", intent.getStringExtra("reminderId"))
            putExtra("triggeredAt", intent.getLongExtra("triggeredAt", 0L))
            putExtra("correlationKey", intent.getStringExtra("correlationKey"))
            putExtra("title", intent.getStringExtra("title"))
            putExtra("body", intent.getStringExtra("body"))
            putExtra("imageUri", intent.getStringExtra("imageUri"))
            putExtra("audioUri", intent.getStringExtra("audioUri"))
            putExtra("videoUri", intent.getStringExtra("videoUri"))
        }

        val vibratorManager = getSystemService(VibratorManager::class.java)
        vibrator = vibratorManager.defaultVibrator
        vibrator.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 320, 140, 320, 140, 320, 140, 320),
                -1
            )
        )

        setContent {
            Relapse_WatchTheme {
                LaunchedEffect(Unit) {
                    delay(2_000)
                    startActivity(reminderIntent)
                    finish()
                }

                PreReminderScreen()
            }
        }
    }

    override fun onDestroy() {
        if (::vibrator.isInitialized) {
            vibrator.cancel()
        }
        super.onDestroy()
    }
}
