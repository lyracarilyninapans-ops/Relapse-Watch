package com.example.relapse_watch.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.relapse_watch.presentation.screens.NavigationScreen
import com.example.relapse_watch.presentation.theme.Relapse_WatchTheme
import com.example.relapse_watch.viewmodel.NavigationViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class NavigationActivity : ComponentActivity() {

    private val navigationViewModel: NavigationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val bearing by navigationViewModel.bearingToSafeZone.collectAsState()
            val distance by navigationViewModel.distanceToSafeZone.collectAsState()
            val isInside by navigationViewModel.isInsideSafeZone.collectAsState()

            LaunchedEffect(Unit) {
                navigationViewModel.startLiveNavigation()
            }

            // Auto-finish if user returns inside the safe zone
            LaunchedEffect(isInside) {
                if (isInside) {
                    finish()
                }
            }

            Relapse_WatchTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    NavigationScreen(
                        bearing = bearing ?: 0f,
                        distance = distance ?: 0f
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        navigationViewModel.stopLiveNavigation()
        super.onDestroy()
    }
}
