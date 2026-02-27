package com.example.relapse_watch.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.relapse_watch.R
import com.example.relapse_watch.domain.model.EventTypes
import com.example.relapse_watch.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MonitoringForegroundService : LifecycleService() {

    @Inject lateinit var activityTrackingService: ActivityTrackingService
    @Inject lateinit var syncService: SyncService

    private var trackingJob: Job? = null
    private var syncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        startTracking()
        startPeriodicSync()

        return START_STICKY
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    private fun startTracking() {
        if (trackingJob?.isActive == true) return

        trackingJob = lifecycleScope.launch {
            activityTrackingService.startTracking()
                .catch { e ->
                    Log.e(TAG, "Location tracking error", e)
                }
                .collect { locationPoint ->
                    activityTrackingService.recordLocationUpdate(locationPoint)
                    activityTrackingService.updateDailySummary()
                }
        }
        Log.d(TAG, "Location tracking started")
    }

    private fun startPeriodicSync() {
        if (syncJob?.isActive == true) return

        syncJob = lifecycleScope.launch {
            while (true) {
                delay(SYNC_INTERVAL_MS)
                try {
                    val result = syncService.syncActivityData()
                    result.onSuccess { count ->
                        if (count > 0) {
                            Log.d(TAG, "Periodic sync uploaded $count records")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic sync failed", e)
                }
            }
        }
        Log.d(TAG, "Periodic sync started (interval: ${SYNC_INTERVAL_MS / 1000}s)")
    }

    private fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        syncJob?.cancel()
        syncJob = null
        Log.d(TAG, "Tracking and sync stopped")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Monitoring Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Continuous location monitoring for safety"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MonitoringForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Relapse Watch")
            .setContentText("Monitoring active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(0, "Stop", pendingStop)
            .build()
    }

    companion object {
        private const val TAG = "MonitoringService"
        private const val CHANNEL_ID = "monitoring_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.example.relapse_watch.STOP_MONITORING"
        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        fun start(context: Context) {
            val intent = Intent(context, MonitoringForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MonitoringForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
