package com.example.relapse_watch.presentation

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.relapse_watch.presentation.screens.ReminderScreen
import com.example.relapse_watch.presentation.theme.Relapse_WatchTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ReminderActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = intent.getStringExtra("title") ?: "Reminder"
        val body = intent.getStringExtra("body") ?: ""
        val imageUriString = intent.getStringExtra("imageUri")
        val videoUriString = intent.getStringExtra("videoUri")
        val allowManualDismiss = intent.getBooleanExtra("allowManualDismiss", false)

        val imageUri = imageUriString?.let { Uri.parse(it) }
        val videoUri = videoUriString?.let { Uri.parse(it) }

        setContent {
            Relapse_WatchTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    ReminderScreen(
                        title = title,
                        body = body,
                        imageUri = imageUri,
                        videoUri = videoUri,
                        allowManualDismiss = allowManualDismiss,
                        onPlayVideo = {
                            // In a real app, this would launch a video player
                        },
                        onDismiss = { finish() }
                    )
                }
            }
        }
    }
}
