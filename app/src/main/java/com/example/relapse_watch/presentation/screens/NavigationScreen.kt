package com.example.relapse_watch.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.North
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

@Composable
fun NavigationScreen(
    bearing: Float,
    distance: Float
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Direction arrow rotated by bearing
        Icon(
            imageVector = Icons.Filled.North,
            contentDescription = "Direction",
            tint = Color.White,
            modifier = Modifier.rotate(bearing)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Distance in meters
        Text(
            text = "${distance.toInt()}m",
            style = MaterialTheme.typography.displayLarge,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Label
        Text(
            text = "Home",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
    }
}
