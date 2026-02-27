package com.example.relapse_watch.services

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HapticService @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(VibratorManager::class.java)
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun vibrateSafeZoneExit() {
        val effect = VibrationEffect.createWaveform(
            longArrayOf(0, 500, 200, 500, 200, 500),
            -1
        )
        vibrator.vibrate(effect)
    }

    fun vibrateSafeZoneEnter() {
        val effect = VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(effect)
    }

    fun vibrateReminder() {
        val effect = VibrationEffect.createWaveform(
            longArrayOf(0, 200, 100, 200),
            -1
        )
        vibrator.vibrate(effect)
    }

    fun vibrateClick() {
        val effect = VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(effect)
    }

    fun cancel() {
        vibrator.cancel()
    }
}
