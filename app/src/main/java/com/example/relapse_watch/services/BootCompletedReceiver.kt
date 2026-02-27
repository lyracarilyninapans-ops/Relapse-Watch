package com.example.relapse_watch.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed — restarting monitoring service")
            MonitoringForegroundService.start(context)
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
