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
import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.data.remote.FirestoreActivitySource
import com.example.relapse_watch.domain.model.EventTypes
import com.example.relapse_watch.domain.model.LocationPoint
import com.example.relapse_watch.domain.repository.GeoReminderRepository
import com.example.relapse_watch.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MonitoringForegroundService : LifecycleService() {

    @Inject lateinit var activityTrackingService: ActivityTrackingService
    @Inject lateinit var syncService: SyncService
    @Inject lateinit var locationService: LocationService
    @Inject lateinit var geoReminderRepository: GeoReminderRepository
    @Inject lateinit var watchPreferences: WatchPreferences
    @Inject lateinit var reminderPlaybackQueueManager: ReminderPlaybackQueueManager
    @Inject lateinit var wearableCommunicationService: WearableCommunicationService
    @Inject lateinit var notificationService: NotificationService
    @Inject lateinit var safeZoneRepository: com.example.relapse_watch.domain.repository.SafeZoneRepository
    @Inject lateinit var firestoreActivitySource: FirestoreActivitySource
    @Inject lateinit var geofenceService: GeofenceService

    private var trackingJob: Job? = null
    private var syncJob: Job? = null
    private var safeZoneListenerJob: Job? = null
    private var reminderListenerJob: Job? = null
    // isInsideSafeZone is persisted in WatchPreferences (DataStore)
    // so it survives service restarts and device reboots.

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
        startRealtimeListeners()

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

                    // Software-based proximity check — more reliable than
                    // the Android Geofencing API on Wear OS where aggressive
                    // battery optimization often delays or drops transitions.
                    checkReminderProximity(locationPoint)
                    checkSafeZoneProximity(locationPoint)
                }
        }
        Log.d(TAG, "Location tracking started (with proximity checking)")
    }

    /**
     * On every location update, check if the patient is inside any active
     * reminder zone. If so, trigger the reminder using the same cooldown
     * and playback queue logic as the geofence BroadcastReceiver.
     *
     * This is the primary trigger mechanism — the Geofencing API serves
     * as a secondary backup that can fire between polling intervals.
     */
    private suspend fun checkReminderProximity(location: LocationPoint) {
        try {
            val reminders = geoReminderRepository.getActiveReminders().first()
            if (reminders.isEmpty()) return

            val cooldownMinutes = watchPreferences.reminderCooldownMinutes.first()
            val cooldownMs = cooldownMinutes.coerceAtLeast(0) * 60_000L
            val now = System.currentTimeMillis()

            for (reminder in reminders) {
                val reminderCenter = LocationPoint(
                    latitude = reminder.latitude,
                    longitude = reminder.longitude,
                    timestamp = now
                )
                val distance = locationService.calculateDistance(location, reminderCenter)

                if (distance <= reminder.radiusMeters) {
                    val correlation = "${reminder.id}:$now"
                    // Patient is inside this reminder zone
                    val lastTriggeredAt = reminder.lastTriggeredAt ?: 0L
                    if ((now - lastTriggeredAt) < cooldownMs) {
                        Log.d(
                            TAG,
                            "[REMINDER_PROXIMITY][SKIP_COOLDOWN] key=$correlation id=${reminder.id} now=$now last=$lastTriggeredAt cooldownMs=$cooldownMs remainingMs=${cooldownMs - (now - lastTriggeredAt)}"
                        )
                        // Still in cooldown — skip
                        continue
                    }

                    Log.d(TAG, "Proximity trigger: ${reminder.title} (${reminder.id}), distance=${distance}m")

                    // Mark as triggered (same as geofence path)
                    geoReminderRepository.markAsTriggered(reminder.id, now)
                    Log.d(
                        TAG,
                        "[REMINDER_PROXIMITY][MARKED] key=$correlation id=${reminder.id} triggeredAt=$now prevLast=$lastTriggeredAt cooldownMs=$cooldownMs distance=$distance"
                    )

                    // Record activity event
                    activityTrackingService.recordReminderTriggered(reminder.id, location)

                    // Enqueue for playback via full-screen notification
                    reminderPlaybackQueueManager.enqueue(
                        ReminderPlaybackRequest(
                            reminderId = reminder.id,
                            triggeredAt = now,
                            title = reminder.title,
                            body = reminder.body,
                            imageUrl = reminder.imageUrl,
                            audioUrl = reminder.audioUrl,
                            videoUrl = reminder.videoUrl
                        )
                    )
                    Log.d(
                        TAG,
                        "[REMINDER_PROXIMITY][ENQUEUED] key=$correlation id=${reminder.id} triggeredAt=$now hasAudio=${!reminder.audioUrl.isNullOrBlank()} distance=$distance"
                    )

                    // Notify the phone
                    wearableCommunicationService.sendReminderTriggered(
                        reminder.id, reminder.title
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Proximity check failed", e)
        }
    }

    private suspend fun checkSafeZoneProximity(location: LocationPoint) {
        try {
            val activeZone = safeZoneRepository.getActiveSafeZone().first()
            if (activeZone == null || !activeZone.isActive) return

            val safeZoneCenter = LocationPoint(
                latitude = activeZone.centerLat,
                longitude = activeZone.centerLng,
                timestamp = location.timestamp
            )
            val distance = locationService.calculateDistance(location, safeZoneCenter)
            val currentlyInside = distance <= activeZone.radiusMeters

            // Read persisted state from DataStore (survives reboots)
            val previouslyInside = watchPreferences.isInsideSafeZone.first()

            if (previouslyInside != null && previouslyInside != currentlyInside) {
                val eventType = if (currentlyInside) EventTypes.SAFE_ZONE_ENTER else EventTypes.SAFE_ZONE_EXIT
                val event = com.example.relapse_watch.domain.model.SafeZoneEvent(
                    id = java.util.UUID.randomUUID().toString(),
                    safeZoneId = activeZone.id,
                    eventType = eventType,
                    timestamp = location.timestamp,
                    latitude = location.latitude,
                    longitude = location.longitude
                )
                safeZoneRepository.recordEvent(event)
                activityTrackingService.recordSafeZoneEvent(eventType, location)

                // Immediate sync on ANY transition so the cloud function
                // can evaluate and send FCM push notifications promptly.
                try {
                    syncService.syncActivityData()
                } catch (e: Exception) {
                    Log.e(TAG, "Sync failed during safe-zone transition", e)
                }

                val alertsEnabled = activeZone.alarmEnabled || activeZone.vibrationEnabled
                if (alertsEnabled) {
                    wearableCommunicationService.sendSafeZoneAlert(eventType, location.latitude, location.longitude)
                }

                if (eventType == EventTypes.SAFE_ZONE_EXIT) {
                    notificationService.showSafeZoneNavigationNotification(activeZone.centerLat, activeZone.centerLng)
                    Log.d(TAG, "Proximity trigger: Safe zone exit notification posted")
                } else {
                    notificationService.showSafeZoneReturnNotification()
                    Log.d(TAG, "Proximity trigger: Safe zone return notification posted")
                }
            }

            // Persist state in DataStore
            watchPreferences.setInsideSafeZone(currentlyInside)
        } catch (e: Exception) {
            Log.e(TAG, "Proximity check for safe zone failed", e)
        }
    }

    private fun startPeriodicSync() {
        if (syncJob?.isActive == true) return

        syncJob = lifecycleScope.launch {
            // Immediate sync on start to pull latest reminders & upload pending data
            try {
                syncService.syncActivityData()
            } catch (e: Exception) {
                Log.e(TAG, "Initial sync failed", e)
            }

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

    /**
     * Real-time Firestore listeners for safe zone and reminder changes.
     * These run inside the foreground service's lifecycleScope, so they
     * survive screen-off and backgrounding — unlike the old ViewModel-based
     * listeners that died the moment the watch UI was dismissed.
     */
    private fun startRealtimeListeners() {
        if (safeZoneListenerJob?.isActive == true && reminderListenerJob?.isActive == true) return

        safeZoneListenerJob = lifecycleScope.launch {
            combine(
                watchPreferences.isPaired,
                watchPreferences.caregiverUid,
                watchPreferences.patientId
            ) { paired, uid, pid -> Triple(paired, uid, pid) }
                .collectLatest { (paired, uid, pid) ->
                    if (paired && uid.isNotBlank() && pid.isNotBlank()) {
                        firestoreActivitySource.observeActiveSafeZone(uid, pid)
                            .collectLatest {
                                Log.d(TAG, "Real-time safe zone change detected, syncing")
                                syncService.syncSafeZoneFromFirestore(uid, pid)
                            }
                    }
                }
        }
        Log.d(TAG, "Real-time safe zone listener started")

        reminderListenerJob = lifecycleScope.launch {
            combine(
                watchPreferences.isPaired,
                watchPreferences.caregiverUid,
                watchPreferences.patientId
            ) { paired, uid, pid -> Triple(paired, uid, pid) }
                .collectLatest { (paired, uid, pid) ->
                    if (paired && uid.isNotBlank() && pid.isNotBlank()) {
                        firestoreActivitySource.observeActiveReminders(uid, pid)
                            .collectLatest {
                                Log.d(TAG, "Real-time reminder change detected, syncing")
                                syncService.syncGeoRemindersFromFirestore(uid, pid)
                            }
                    }
                }
        }
        Log.d(TAG, "Real-time reminder listener started")
    }

    private fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        syncJob?.cancel()
        syncJob = null
        safeZoneListenerJob?.cancel()
        safeZoneListenerJob = null
        reminderListenerJob?.cancel()
        reminderListenerJob = null
        Log.d(TAG, "Tracking, sync, and real-time listeners stopped")
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
