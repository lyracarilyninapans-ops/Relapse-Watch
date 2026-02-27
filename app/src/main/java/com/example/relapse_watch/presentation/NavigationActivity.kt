package com.example.relapse_watch.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.relapse_watch.presentation.screens.NavigationScreen
import com.example.relapse_watch.presentation.theme.Relapse_WatchTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class NavigationActivity : ComponentActivity() {

    // In a real app, these would be updated by location services
    private var bearing by mutableFloatStateOf(0f)
    private var distance by mutableFloatStateOf(150f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bearing = intent.getFloatExtra("bearing", 0f)
        distance = intent.getFloatExtra("distance", 150f)

        setContent {
            Relapse_WatchTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    NavigationScreen(
                        bearing = bearing,
                        distance = distance
                    )
                }
            }
        }
    }
}
