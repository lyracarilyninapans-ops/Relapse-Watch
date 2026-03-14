package com.example.relapse_watch.presentation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
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

        val safeZoneLat = intent.getDoubleExtra("safe_zone_lat", Double.NaN)
        val safeZoneLng = intent.getDoubleExtra("safe_zone_lng", Double.NaN)

        if (safeZoneLat.isNaN() || safeZoneLng.isNaN()) {
            Log.e(TAG, "Missing safe zone coordinates — finishing")
            finish()
            return
        }

        val vibrator = getSystemService<Vibrator>()
        vibrator?.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(500, 500),   // 500 ms vibrate, 500 ms pause
                0                        // repeat indefinitely
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
                    launchGoogleMapsNavigation(safeZoneLat, safeZoneLng)
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

    /**
     * Launches Google Maps Wear OS in walking-navigation mode directed
     * toward the center of the safe zone.
     */
    private fun launchGoogleMapsNavigation(lat: Double, lng: Double) {
        // Try the navigation URI first (turn-by-turn walking directions)
        val navigationUri = Uri.parse("google.navigation:q=$lat,$lng&mode=w")
        val navIntent = Intent(Intent.ACTION_VIEW, navigationUri)

        // Try with explicit Maps package first
        try {
            navIntent.setPackage("com.google.android.apps.maps")
            startActivity(navIntent)
            Log.d(TAG, "Launched Google Maps navigation")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Maps package launch failed: ${e.message}")
        }

        // Fallback: try without package restriction (lets any handler respond)
        try {
            val fallbackNav = Intent(Intent.ACTION_VIEW, navigationUri)
            startActivity(fallbackNav)
            Log.d(TAG, "Launched navigation via fallback intent")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Navigation URI fallback failed: ${e.message}")
        }

        // Last resort: try geo: URI which more apps support
        try {
            val geoUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng")
            val geoIntent = Intent(Intent.ACTION_VIEW, geoUri)
            startActivity(geoIntent)
            Log.d(TAG, "Launched geo: URI fallback")
            return
        } catch (e: Exception) {
            Log.w(TAG, "geo: URI fallback also failed: ${e.message}")
        }

        Toast.makeText(this, "No maps app available", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService<Vibrator>()?.cancel()
    }

    companion object {
        private const val TAG = "PreNavigationActivity"
    }
}
