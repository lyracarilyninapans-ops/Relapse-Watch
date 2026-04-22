package com.example.relapse_watch.services

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryStatusProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun getBatteryLevelPercent(): Int? {
        val fromManager = readFromBatteryManager()
        if (fromManager != null) return fromManager
        return readFromStickyBatteryIntent()
    }

    private fun readFromBatteryManager(): Int? {
        val batteryManager = context.getSystemService(BatteryManager::class.java) ?: return null
        val capacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return capacity
            .takeIf { it in 0..100 }
    }

    private fun readFromStickyBatteryIntent(): Int? {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return null

        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null

        val percent = ((level * 100f) / scale).toInt()
        return percent.coerceIn(0, 100)
    }
}