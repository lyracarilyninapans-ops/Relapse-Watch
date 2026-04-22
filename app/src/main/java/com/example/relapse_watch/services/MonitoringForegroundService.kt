package com.example.relapse_watch.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
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
import com.example.relapse_watch.domain.repository.PairingRepository
import com.example.relapse_watch.domain.usecase.UnpairUseCase
import com.example.relapse_watch.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

internal enum class ReminderTransitionDecision {
    BASELINE,
    TRIGGER,
    NO_TRIGGER
}

internal fun decideReminderTransition(
    previousInside: Boolean?,
    previousZoneSignature: String?,
    currentInside: Boolean,
    currentZoneSignature: String
): ReminderTransitionDecision {
    if (previousInside == null || previousZoneSignature == null) {
        // Cold-start / unknown-state baseline: do not trigger until we observe
        // a confirmed outside -> inside transition from a known prior state.
        return ReminderTransitionDecision.BASELINE
    }

    if (previousZoneSignature != currentZoneSignature) {
        return ReminderTransitionDecision.BASELINE
    }

    return if (currentInside && !previousInside) {
        ReminderTransitionDecision.TRIGGER
    } else {
        ReminderTransitionDecision.NO_TRIGGER
    }
}

@AndroidEntryPoint
class MonitoringForegroundService : LifecycleService() {

    @Inject lateinit var activityTrackingService: ActivityTrackingService
    @Inject lateinit var syncService: SyncService
    @Inject lateinit var locationService: LocationService
    @Inject lateinit var geoReminderRepository: GeoReminderRepository
    @Inject lateinit var watchPreferences: WatchPreferences
    @Inject lateinit var reminderTriggerCoordinator: ReminderTriggerCoordinator
    @Inject lateinit var notificationService: NotificationService
    @Inject lateinit var safeZoneRepository: com.example.relapse_watch.domain.repository.SafeZoneRepository
    @Inject lateinit var firestoreActivitySource: FirestoreActivitySource
    @Inject lateinit var geofenceService: GeofenceService
    @Inject lateinit var pairingRepository: PairingRepository
    @Inject lateinit var unpairUseCase: UnpairUseCase

    private var trackingJob: Job? = null
    private var safeZoneListenerJob: Job? = null
    private var reminderListenerJob: Job? = null
    private var remoteUnpairListenerJob: Job? = null
    private var remoteUnpairReconcileJob: Job? = null
    private var unpairHandledForCurrentPairSession: Boolean = false
    private var lastSafeZoneRealtimeSyncElapsedMs: Long = 0L
    private var lastSafeZoneRealtimeFingerprint: String? = null
    private var lastReminderRealtimeFingerprint: String? = null
    private val reminderInsideState: MutableMap<String, Boolean> = mutableMapOf()
    private val reminderZoneSignatureState: MutableMap<String, String> = mutableMapOf()
    private var currentTrackingProfile: TrackingProfile = TrackingProfile.BALANCED
    private var consecutiveFarBoundarySamples: Int = 0
    private var pendingSafeZoneTransitionToInside: Boolean? = null
    private var pendingSafeZoneTransitionConfirmations: Int = 0
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
        lifecycleScope.launch {
            evaluateSafeZoneFromLastKnownLocation()
        }
        lifecycleScope.launch {
            try {
                syncService.syncActivityData()
            } catch (e: Exception) {
                Log.e(TAG, "Initial sync failed", e)
            }
        }
        startRealtimeListeners()

        return START_STICKY
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    private fun startTracking(
        profile: TrackingProfile = currentTrackingProfile,
        restart: Boolean = false
    ) {
        if (trackingJob?.isActive == true && !restart) return
        if (restart) {
            trackingJob?.cancel()
            trackingJob = null
        }

        currentTrackingProfile = profile
        val intervalMs = if (profile == TrackingProfile.HIGH_ACCURACY) {
            HIGH_ACCURACY_INTERVAL_MS
        } else {
            BALANCED_INTERVAL_MS
        }

        trackingJob = lifecycleScope.launch {
            activityTrackingService.startTracking(intervalMs = intervalMs, profile = profile)
                .catch { e ->
                    Log.e(TAG, "Location tracking error", e)
                }
                .collect { locationPoint ->
                    activityTrackingService.recordLocationUpdate(locationPoint)
                    activityTrackingService.updateDailySummaryWithLocation(locationPoint)

                    // Software-based proximity check — more reliable than
                    // the Android Geofencing API on Wear OS where aggressive
                    // battery optimization often delays or drops transitions.
                    checkReminderProximity(locationPoint)
                    val nearSafeZoneBoundary = checkSafeZoneProximity(locationPoint)
                    adjustTrackingProfile(nearSafeZoneBoundary)
                }
        }
        Log.d(TAG, "Location tracking started profile=$profile intervalMs=$intervalMs")
    }

    private fun adjustTrackingProfile(nearSafeZoneBoundary: Boolean) {
        if (nearSafeZoneBoundary) {
            consecutiveFarBoundarySamples = 0
            if (currentTrackingProfile != TrackingProfile.HIGH_ACCURACY) {
                lifecycleScope.launch {
                    Log.d(TAG, "Switching tracking to HIGH_ACCURACY near safe-zone boundary")
                    startTracking(profile = TrackingProfile.HIGH_ACCURACY, restart = true)
                }
            }
            return
        }

        if (currentTrackingProfile == TrackingProfile.HIGH_ACCURACY) {
            consecutiveFarBoundarySamples++
            if (consecutiveFarBoundarySamples >= HIGH_ACCURACY_COOLDOWN_SAMPLES) {
                consecutiveFarBoundarySamples = 0
                lifecycleScope.launch {
                    Log.d(TAG, "Switching tracking to BALANCED after stable samples")
                    startTracking(profile = TrackingProfile.BALANCED, restart = true)
                }
            }
        }
    }

    /**
     * On every location update, check if the patient is inside any active
     * reminder zone and only trigger on outside -> inside transitions.
     *
     * This is the primary trigger mechanism — the Geofencing API serves
     * as a secondary backup that can fire between polling intervals.
     */
    private suspend fun checkReminderProximity(location: LocationPoint) {
        try {
            val reminders = geoReminderRepository.getActiveReminders().first()
            if (reminders.isEmpty()) {
                reminderInsideState.clear()
                reminderZoneSignatureState.clear()
                return
            }

            val activeReminderIds = reminders.map { it.id }.toSet()
            reminderInsideState.keys.retainAll(activeReminderIds)
            reminderZoneSignatureState.keys.retainAll(activeReminderIds)

            for (reminder in reminders) {
                val reminderCenter = LocationPoint(
                    latitude = reminder.latitude,
                    longitude = reminder.longitude,
                    timestamp = location.timestamp
                )
                val distance = locationService.calculateDistance(location, reminderCenter)
                val isInside = distance <= reminder.radiusMeters
                val reminderZoneSignature = buildReminderZoneSignature(reminder)
                val previousZoneSignature = reminderZoneSignatureState[reminder.id]
                val wasInside = reminderInsideState[reminder.id]

                val decision = decideReminderTransition(
                    previousInside = wasInside,
                    previousZoneSignature = previousZoneSignature,
                    currentInside = isInside,
                    currentZoneSignature = reminderZoneSignature
                )

                if (
                    previousZoneSignature != null &&
                    previousZoneSignature != reminderZoneSignature &&
                    decision == ReminderTransitionDecision.BASELINE
                ) {
                    Log.d(TAG, "Reminder zone changed, resetting inside baseline: ${reminder.id}")
                }

                if (decision == ReminderTransitionDecision.TRIGGER) {
                    reminderTriggerCoordinator.triggerFromProximity(
                        reminderId = reminder.id,
                        location = location,
                        distanceMeters = distance
                    )
                }

                // For BASELINE and NO_TRIGGER cases, persist current inside/sig
                // so subsequent checks trigger only on true outside -> inside.
                reminderInsideState[reminder.id] = isInside
                reminderZoneSignatureState[reminder.id] = reminderZoneSignature
            }
        } catch (e: Exception) {
            Log.e(TAG, "Proximity check failed", e)
        }
    }

    private suspend fun checkSafeZoneProximity(location: LocationPoint): Boolean {
        try {
            val activeZone = safeZoneRepository.getActiveSafeZone().first()
            if (activeZone == null || !activeZone.isActive) {
                resetPendingSafeZoneTransitionState()
                return false
            }

            val safeZoneCenter = LocationPoint(
                latitude = activeZone.centerLat,
                longitude = activeZone.centerLng,
                timestamp = location.timestamp
            )
            val distance = locationService.calculateDistance(location, safeZoneCenter)
            val boundaryDeltaMeters = abs(distance - activeZone.radiusMeters.toFloat())
            val nearBoundary = boundaryDeltaMeters <= SAFE_ZONE_BOUNDARY_MARGIN_METERS
            val currentlyInside = distance <= activeZone.radiusMeters
            val highConfidenceTransition = isHighConfidenceTransition(boundaryDeltaMeters, location.accuracy)
            val requiredConfirmations = if (currentlyInside) {
                SAFE_ZONE_ENTER_CONFIRMATION_SAMPLES
            } else {
                SAFE_ZONE_EXIT_CONFIRMATION_SAMPLES
            }

            // Read persisted state from DataStore (survives reboots)
            val previouslyInside = watchPreferences.isInsideSafeZone.first()

            if (previouslyInside == null) {
                Log.d(
                    TAG,
                    "Baseline safe-zone state initialized currentlyInside=$currentlyInside distance=$distance accuracy=${location.accuracy}"
                )
                watchPreferences.setInsideSafeZone(currentlyInside)
                resetPendingSafeZoneTransitionState()
                return nearBoundary
            }

            if (previouslyInside != currentlyInside) {
                if (pendingSafeZoneTransitionToInside != currentlyInside) {
                    pendingSafeZoneTransitionToInside = currentlyInside
                    pendingSafeZoneTransitionConfirmations = if (highConfidenceTransition) {
                        requiredConfirmations
                    } else {
                        1
                    }

                    if (highConfidenceTransition) {
                        Log.d(TAG, "High-confidence safe-zone transition detected, triggering immediately")
                    } else {
                        Log.d(
                            TAG,
                            "Safe-zone transition candidate detected direction=${if (currentlyInside) "enter" else "exit"} confirmations=1/$requiredConfirmations"
                        )
                    }
                } else {
                    pendingSafeZoneTransitionConfirmations++
                }

                if (pendingSafeZoneTransitionConfirmations < requiredConfirmations) {
                    Log.d(
                        TAG,
                        "Safe-zone transition confirmation ${pendingSafeZoneTransitionConfirmations}/$requiredConfirmations"
                    )
                    return nearBoundary
                }

                val eventType = if (currentlyInside) EventTypes.SAFE_ZONE_ENTER else EventTypes.SAFE_ZONE_EXIT
                handleConfirmedSafeZoneTransition(
                    eventType = eventType,
                    location = location,
                    activeZone = activeZone
                )

                resetPendingSafeZoneTransitionState()
            } else {
                resetPendingSafeZoneTransitionState()
            }

            // Persist state in DataStore
            watchPreferences.setInsideSafeZone(currentlyInside)
            return nearBoundary
        } catch (e: Exception) {
            Log.e(TAG, "Proximity check for safe zone failed", e)
            return false
        }
    }

    /**
     * Real-time Firestore listeners for safe zone and reminder changes.
     * These run inside the foreground service's lifecycleScope, so they
     * survive screen-off and backgrounding — unlike the old ViewModel-based
     * listeners that died the moment the watch UI was dismissed.
     */
    private fun startRealtimeListeners() {
        if (
            safeZoneListenerJob?.isActive == true &&
            reminderListenerJob?.isActive == true &&
            remoteUnpairListenerJob?.isActive == true &&
            remoteUnpairReconcileJob?.isActive == true
        ) return

        safeZoneListenerJob = lifecycleScope.launch {
            combine(
                watchPreferences.isPaired,
                watchPreferences.caregiverUid,
                watchPreferences.patientId
            ) { paired, uid, pid -> Triple(paired, uid, pid) }
                .collectLatest { (paired, uid, pid) ->
                    if (paired && uid.isNotBlank() && pid.isNotBlank()) {
                        collectSafeZoneRealtimeUpdatesWithRetry(uid, pid)
                    } else {
                        lastSafeZoneRealtimeFingerprint = null
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
                        collectReminderRealtimeUpdatesWithRetry(uid, pid)
                    } else {
                        lastReminderRealtimeFingerprint = null
                    }
                }
        }
        Log.d(TAG, "Real-time reminder listener started")

        remoteUnpairListenerJob = lifecycleScope.launch {
            combine(
                watchPreferences.isPaired,
                watchPreferences.pairingCode,
                watchPreferences.caregiverUid
            ) { paired, code, uid ->
                Triple(paired, code, uid)
            }
                .distinctUntilChanged()
                .collectLatest { (paired, code, uid) ->
                if (!paired) {
                    unpairHandledForCurrentPairSession = false
                    return@collectLatest
                }
                collectRemoteUnpairSignalsWithRetry(code = code, caregiverUid = uid)
            }
        }
        Log.d(TAG, "Real-time remote unpair listener started")

        remoteUnpairReconcileJob = lifecycleScope.launch {
            combine(
                watchPreferences.isPaired,
                watchPreferences.pairingCode,
                watchPreferences.caregiverUid
            ) { paired, code, uid ->
                Triple(paired, code, uid)
            }
                .distinctUntilChanged()
                .collectLatest { (paired, code, uid) ->
                if (!paired) {
                    unpairHandledForCurrentPairSession = false
                    Log.d(TAG, "REMOTE_UNPAIR_RECONCILE_STATE paired=false resetHandled=true")
                    return@collectLatest
                }

                Log.d(
                    TAG,
                    "REMOTE_UNPAIR_RECONCILE_STATE paired=true handled=$unpairHandledForCurrentPairSession codePresent=${code.isNotBlank()} uidPresent=${uid.isNotBlank()}"
                )

                while (true) {
                    if (!unpairHandledForCurrentPairSession) {
                        val confirmed = pairingRepository.confirmRemoteUnpairFromServer(
                            pairingCode = code,
                            caregiverUid = uid
                        )
                        Log.d(TAG, "REMOTE_UNPAIR_RECONCILE_CHECK confirmed=$confirmed")
                        if (confirmed) {
                            unpairHandledForCurrentPairSession = true
                            Log.d(TAG, "REMOTE_UNPAIR_RECONCILE_CONFIRMED")
                            unpairUseCase.execute(alsoNotifyPhone = false)
                            Log.d(TAG, "REMOTE_UNPAIR_CLEANUP_EXECUTED")
                        }
                    }
                    delay(REMOTE_UNPAIR_RECONCILE_INTERVAL_MS)
                }
            }
        }
        Log.d(TAG, "Remote unpair reconciliation loop started")
    }

    private suspend fun collectRemoteUnpairSignalsWithRetry(code: String, caregiverUid: String) {
        while (true) {
            try {
                val signals = buildList {
                    if (code.isNotBlank()) {
                        add(
                            pairingRepository.observeRemoteUnpairCommand(code)
                                .distinctUntilChanged()
                        )
                    }
                    if (caregiverUid.isNotBlank()) {
                        add(
                            pairingRepository.observeCaregiverUnpairCommand(caregiverUid)
                                .distinctUntilChanged()
                        )
                    }
                }

                if (signals.isEmpty()) {
                    // Local pairing state is incomplete; avoid tight retry loops.
                    delay(REALTIME_LISTENER_RETRY_DELAY_MS)
                    continue
                }

                merge(*signals.toTypedArray()).collect { isUnpaired ->
                    if (isUnpaired && !unpairHandledForCurrentPairSession) {
                        val confirmed = pairingRepository.confirmRemoteUnpairFromServer(
                            pairingCode = code,
                            caregiverUid = caregiverUid
                        )
                        if (!confirmed) {
                            Log.d(TAG, "REMOTE_UNPAIR_SIGNAL_IGNORED_STALE")
                            return@collect
                        }

                        unpairHandledForCurrentPairSession = true
                        Log.d(TAG, "REMOTE_UNPAIR_SIGNAL_RECEIVED")
                        unpairUseCase.execute(alsoNotifyPhone = false)
                        Log.d(TAG, "REMOTE_UNPAIR_CLEANUP_EXECUTED")
                    }
                }

                return
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "REMOTE_UNPAIR_LISTENER_RETRY", e)
                delay(REALTIME_LISTENER_RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun collectSafeZoneRealtimeUpdatesWithRetry(uid: String, pid: String) {
        while (true) {
            try {
                firestoreActivitySource.observeActiveSafeZone(uid, pid)
                    .collect { safeZoneData ->
                        val fingerprint = safeZoneFingerprint(safeZoneData)
                        val changed = fingerprint != lastSafeZoneRealtimeFingerprint
                        val now = SystemClock.elapsedRealtime()
                        if ((now - lastSafeZoneRealtimeSyncElapsedMs) < REALTIME_SYNC_DEBOUNCE_MS) {
                            // Collapse bursty snapshot streams from Firestore. Keep the latest
                            // fingerprint so the next non-debounced tick reflects newest state.
                            if (changed) {
                                lastSafeZoneRealtimeFingerprint = fingerprint
                            }
                            Log.d(TAG, "Real-time safe zone sync debounced")
                            return@collect
                        }
                        lastSafeZoneRealtimeFingerprint = fingerprint
                        lastSafeZoneRealtimeSyncElapsedMs = now
                        Log.d(TAG, "Real-time safe zone change detected, syncing")
                        syncService.syncSafeZoneFromFirestore(uid, pid)
                        refreshSafeZoneStateFromLastKnownLocation()
                    }
                return
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Real-time safe zone listener failed, retrying", e)
                delay(REALTIME_LISTENER_RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun collectReminderRealtimeUpdatesWithRetry(uid: String, pid: String) {
        while (true) {
            try {
                firestoreActivitySource.observeActiveReminders(uid, pid)
                    .collect { reminderData ->
                        val fingerprint = reminderFingerprint(reminderData)
                        lastReminderRealtimeFingerprint = fingerprint
                        Log.d(TAG, "Real-time reminder change detected, syncing")
                        val reminderSyncResult = syncService.syncGeoRemindersFromFirestore(uid, pid)
                        if (reminderSyncResult.isFailure) {
                            val exception = reminderSyncResult.exceptionOrNull() ?: Exception("Real-time reminder sync failed")
                            Log.e(
                                TAG,
                                "Real-time reminder sync failed",
                                exception
                            )
                            throw exception
                        }

                        triggerInsideRemindersWithoutBaselineFromLastKnownLocation()
                    }
                return
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Real-time reminder listener failed, retrying", e)
                delay(REALTIME_LISTENER_RETRY_DELAY_MS)
            }
        }
    }

    private fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        safeZoneListenerJob?.cancel()
        safeZoneListenerJob = null
        reminderListenerJob?.cancel()
        reminderListenerJob = null
        remoteUnpairListenerJob?.cancel()
        remoteUnpairListenerJob = null
        remoteUnpairReconcileJob?.cancel()
        remoteUnpairReconcileJob = null
        unpairHandledForCurrentPairSession = false
        lastSafeZoneRealtimeFingerprint = null
        lastReminderRealtimeFingerprint = null
        consecutiveFarBoundarySamples = 0
        currentTrackingProfile = TrackingProfile.BALANCED
        reminderInsideState.clear()
        reminderZoneSignatureState.clear()
        pendingSafeZoneTransitionToInside = null
        pendingSafeZoneTransitionConfirmations = 0
        Log.d(TAG, "Tracking, sync, and real-time listeners stopped")
    }

    private fun buildReminderZoneSignature(reminder: com.example.relapse_watch.domain.model.GeoReminder): String {
        return "${reminder.latitude}|${reminder.longitude}|${reminder.radiusMeters}"
    }

    private fun safeZoneFingerprint(data: Map<String, Any>?): String {
        if (data == null) return "none"
        val id = data["id"]?.toString().orEmpty()
        val lat = (data["centerLat"] as? Number)?.toDouble()?.toString().orEmpty()
        val lng = (data["centerLng"] as? Number)?.toDouble()?.toString().orEmpty()
        val radius = (data["radiusMeters"] as? Number)?.toInt()?.toString().orEmpty()
        val isActive = (data["isActive"] as? Boolean)?.toString() ?: "true"
        val alarmEnabled = (data["alarmEnabled"] as? Boolean)?.toString() ?: "true"
        val vibrationEnabled = (data["vibrationEnabled"] as? Boolean)?.toString() ?: "true"
        return listOf(id, lat, lng, radius, isActive, alarmEnabled, vibrationEnabled).joinToString("|")
    }

    private fun reminderFingerprint(data: List<Map<String, Any>>): String {
        if (data.isEmpty()) return "none"
        return data
            .map { reminder ->
                val id = reminder["id"]?.toString().orEmpty()
                val title = reminder["title"]?.toString().orEmpty()
                val body = reminder["description"]?.toString() ?: reminder["body"]?.toString().orEmpty()
                val lat = (reminder["latitude"] as? Number)?.toDouble()?.toString().orEmpty()
                val lng = (reminder["longitude"] as? Number)?.toDouble()?.toString().orEmpty()
                val radius = (reminder["radiusMeters"] as? Number)?.toInt()?.toString().orEmpty()
                val isActive = (reminder["isActive"] as? Boolean)?.toString() ?: "true"
                val audioUrl = reminder["audioUrl"]?.toString().orEmpty()
                val imageUrl = reminder["imageUrl"]?.toString().orEmpty()
                val videoUrl = reminder["videoUrl"]?.toString().orEmpty()
                listOf(id, title, body, lat, lng, radius, isActive, audioUrl, imageUrl, videoUrl).joinToString("|")
            }
            .sorted()
            .joinToString("||")
    }

    private suspend fun triggerInsideRemindersWithoutBaselineFromLastKnownLocation() {
        val location = locationService.getLastKnownLocation()
        if (location == null) {
            Log.d(TAG, "Post-sync inside trigger skipped: no last known location")
            return
        }
        val reminders = geoReminderRepository.getActiveReminders().first()
        if (reminders.isEmpty()) {
            Log.d(TAG, "Post-sync inside trigger skipped: no active reminders")
            return
        }

        for (reminder in reminders) {
            val reminderCenter = LocationPoint(
                latitude = reminder.latitude,
                longitude = reminder.longitude,
                timestamp = location.timestamp
            )
            val distance = locationService.calculateDistance(location, reminderCenter)
            val isInside = distance <= reminder.radiusMeters
            val hasBaseline = reminderInsideState.containsKey(reminder.id)
            val previousZoneSignature = reminderZoneSignatureState[reminder.id]
            val currentZoneSignature = buildReminderZoneSignature(reminder)
            val zoneChanged = previousZoneSignature != null && previousZoneSignature != currentZoneSignature

            if (!hasBaseline && isInside) {
                val triggered = reminderTriggerCoordinator.triggerFromProximity(
                    reminderId = reminder.id,
                    location = location,
                    distanceMeters = distance
                )
                Log.d(
                    TAG,
                    "Post-sync inside trigger id=${reminder.id} triggered=$triggered distance=$distance"
                )
            } else {
                Log.d(
                    TAG,
                    "Post-sync inside trigger skipped id=${reminder.id} hasBaseline=$hasBaseline isInside=$isInside zoneChanged=$zoneChanged"
                )
            }

            reminderInsideState[reminder.id] = isInside
            reminderZoneSignatureState[reminder.id] = currentZoneSignature
        }
    }

    private suspend fun refreshSafeZoneStateFromLastKnownLocation() {
        val activeZone = safeZoneRepository.getActiveSafeZone().first()
        if (activeZone == null || !activeZone.isActive) {
            watchPreferences.clearInsideSafeZone()
            return
        }

        val lastKnownLocation = locationService.getLastKnownLocation() ?: return
        val nearBoundary = checkSafeZoneProximity(lastKnownLocation)
        adjustTrackingProfile(nearBoundary)
    }

    private suspend fun evaluateSafeZoneFromLastKnownLocation() {
        refreshSafeZoneStateFromLastKnownLocation()
    }

    private suspend fun handleConfirmedSafeZoneTransition(
        eventType: String,
        location: LocationPoint,
        activeZone: com.example.relapse_watch.domain.model.SafeZoneConfig
    ) {
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

        if (eventType == EventTypes.SAFE_ZONE_EXIT) {
            notificationService.showSafeZoneNavigationNotification(activeZone.centerLat, activeZone.centerLng)
            Log.d(TAG, "Proximity trigger: Safe zone exit notification posted")
        } else {
            notificationService.showSafeZoneEnterNotification()
            Log.d(TAG, "Proximity trigger: Safe zone enter notification posted")
        }

        // Immediate sync on ANY transition so safeZoneEvents are uploaded
        // quickly and the cloud trigger can send caregiver FCM promptly.
        lifecycleScope.launch {
            try {
                syncService.syncActivityData()
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed during safe-zone transition", e)
            }
        }
    }

    private fun resetPendingSafeZoneTransitionState() {
        pendingSafeZoneTransitionToInside = null
        pendingSafeZoneTransitionConfirmations = 0
    }

    private fun isHighConfidenceTransition(boundaryDeltaMeters: Float, accuracyMeters: Float?): Boolean {
        val uncertaintyMeters = (accuracyMeters ?: Float.MAX_VALUE).coerceAtLeast(0f)
        val confidenceThresholdMeters = maxOf(75f, uncertaintyMeters * 1.5f)
        return boundaryDeltaMeters >= confidenceThresholdMeters
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
        private const val REALTIME_SYNC_DEBOUNCE_MS = 15_000L
        private const val REALTIME_LISTENER_RETRY_DELAY_MS = 5_000L
        private const val REMOTE_UNPAIR_RECONCILE_INTERVAL_MS = 20_000L
        private const val BALANCED_INTERVAL_MS = 60_000L
        private const val HIGH_ACCURACY_INTERVAL_MS = 30_000L
        private const val SAFE_ZONE_BOUNDARY_MARGIN_METERS = 120f
        private const val HIGH_ACCURACY_COOLDOWN_SAMPLES = 4
        private const val SAFE_ZONE_ENTER_CONFIRMATION_SAMPLES = 2
        private const val SAFE_ZONE_EXIT_CONFIRMATION_SAMPLES = 3

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
