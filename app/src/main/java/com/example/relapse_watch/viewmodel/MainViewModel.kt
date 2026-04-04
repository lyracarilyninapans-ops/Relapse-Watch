package com.example.relapse_watch.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.domain.repository.ActivityRepository
import com.example.relapse_watch.domain.model.LocationPoint
import com.example.relapse_watch.domain.repository.GeoReminderRepository
import com.example.relapse_watch.domain.repository.PairingRepository
import com.example.relapse_watch.domain.repository.SafeZoneRepository
import com.example.relapse_watch.presentation.model.SafeZoneStatus
import com.example.relapse_watch.services.ActivityTrackingService
import com.example.relapse_watch.services.LocationService
import com.example.relapse_watch.services.SyncService
import com.example.relapse_watch.domain.usecase.UnpairUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    pairingRepository: PairingRepository,
    activityRepository: ActivityRepository,
    private val safeZoneRepository: SafeZoneRepository,
    geoReminderRepository: GeoReminderRepository,
    private val locationService: LocationService,
    private val activityTrackingService: ActivityTrackingService,
    private val syncService: SyncService,
    private val unpairUseCase: UnpairUseCase,
    val preferences: WatchPreferences
) : ViewModel() {

    private val _safeZoneStatus = MutableStateFlow(SafeZoneStatus.Unknown)
    val safeZoneStatus: StateFlow<SafeZoneStatus> = _safeZoneStatus

    // Safe zone navigation/return triggers are handled exclusively by
    // MonitoringForegroundService.checkSafeZoneProximity() — the single
    // authority for launching PreNavigationActivity / SafeZoneReturnActivity.

    init {
        // Listen for phone-initiated unpair: monitor BOTH the global
        // pairing-code document AND the caregiver's watchPairing/current
        // document. The phone always sets the caregiver doc to "unpaired"
        // (via .set()), while the global doc update uses .update() which
        // can fail silently if the document was already deleted. Merging
        // both signals ensures robust detection of phone-initiated unpairs.
        viewModelScope.launch {
            combine(
                preferences.isPaired,
                preferences.pairingCode,
                preferences.caregiverUid
            ) { paired, code, uid ->
                Triple(paired, code, uid)
            }.collectLatest { (paired, code, uid) ->
                if (paired) {
                    val signals = buildList {
                        if (code.isNotBlank()) {
                            add(pairingRepository.observeRemoteUnpairCommand(code))
                        }
                        if (uid.isNotBlank()) {
                            add(pairingRepository.observeCaregiverUnpairCommand(uid))
                        }
                    }
                    if (signals.isNotEmpty()) {
                        merge(*signals.toTypedArray()).collect { isUnpaired ->
                            if (isUnpaired) {
                                // Phone already updated its own Firestore doc,
                                // so we only need local + shared cleanup.
                                unpairUseCase.execute(alsoNotifyPhone = false)
                            }
                        }
                    }
                }
            }
        }

        // Keep local pairing cache continuously aligned with remote pairing data.
        // This both self-heals older incomplete values and propagates future
        // phone-side pairing detail updates (name/patient/watch IDs).
        viewModelScope.launch {
            combine(
                combine(
                    preferences.isPaired,
                    preferences.pairingCode,
                    preferences.caregiverUid,
                    ::Triple
                ),
                combine(
                    preferences.patientId,
                    preferences.patientName,
                    preferences.watchId,
                    ::Triple
                )
            ) { pairingState, patientState ->
                pairingState to patientState
            }.collectLatest { (pairingState, patientState) ->
                val (paired, code, uid) = pairingState
                val (patientId, patientName, watchId) = patientState
                if (!paired || code.isBlank()) return@collectLatest

                pairingRepository.observePairingStatus(code).collect { remote ->
                    val remoteUid = remote.caregiverUid.trim()
                    val remotePatientId = remote.patientId.trim()
                    val remotePatientName = remote.patientName.trim()
                    val remoteWatchId = remote.watchId.trim()

                    val resolvedUid = if (remoteUid.isNotBlank()) remoteUid else uid
                    val resolvedPatientId = if (remotePatientId.isNotBlank()) remotePatientId else patientId
                    val resolvedPatientName = if (remotePatientName.isNotBlank()) remotePatientName else patientName
                    val resolvedWatchId = if (remoteWatchId.isNotBlank()) remoteWatchId else watchId

                    val shouldUpdatePatient =
                        resolvedPatientId.isNotBlank() &&
                            (resolvedPatientId != patientId || resolvedPatientName != patientName)

                    if (shouldUpdatePatient) {
                        preferences.setPatientInfo(
                            name = resolvedPatientName,
                            id = resolvedPatientId
                        )
                    }

                    val shouldUpdatePairing =
                        resolvedUid.isNotBlank() &&
                            (resolvedUid != uid || resolvedWatchId != watchId)

                    if (shouldUpdatePairing) {
                        preferences.setPaired(
                            isPaired = true,
                            caregiverUid = resolvedUid,
                            watchId = resolvedWatchId
                        )
                    }
                }
            }
        }

        // Observe the patient Firestore document directly for edits
        // (e.g. name changes from Edit Patient screen on the phone).
        // This is more reliable than the watchPairingCodes intermediary
        // because it covers ALL patient fields and works regardless of
        // whether the pairing code is still accessible.
        viewModelScope.launch {
            combine(
                preferences.isPaired,
                preferences.caregiverUid,
                preferences.patientId
            ) { paired, uid, pid -> Triple(paired, uid, pid) }
                .collectLatest { (paired, uid, pid) ->
                    if (paired && uid.isNotBlank() && pid.isNotBlank()) {
                        pairingRepository.observePatientDocument(uid, pid)
                            .filterNotNull()
                            .collect { data ->
                                val remoteName = data["name"] as? String ?: ""
                                if (remoteName.isNotBlank()) {
                                    val currentName = preferences.patientName.first()
                                    if (remoteName != currentName) {
                                        preferences.setPatientInfo(
                                            name = remoteName,
                                            id = pid
                                        )
                                    }
                                }
                            }
                    }
                }
        }

        // NOTE: Real-time Firestore listeners for safe zone and reminder
        // changes now live in MonitoringForegroundService so they survive
        // screen-off and backgrounding (see startRealtimeListeners()).

        viewModelScope.launch {
            safeZoneRepository.getActiveSafeZone().collectLatest { zone ->
                if (zone == null || !zone.isActive) {
                    _safeZoneStatus.value = SafeZoneStatus.Unknown
                    return@collectLatest
                }

                locationService.getLocationUpdates(intervalMs = 10_000L)
                    .catch {
                        _safeZoneStatus.value = SafeZoneStatus.Unknown
                    }
                    .collect { location ->
                        val safeZoneCenter = LocationPoint(
                            latitude = zone.centerLat,
                            longitude = zone.centerLng,
                            timestamp = location.timestamp
                        )
                        val distance = locationService.calculateDistance(location, safeZoneCenter)
                        val previousStatus = _safeZoneStatus.value
                        val newStatus = if (distance <= zone.radiusMeters) {
                            SafeZoneStatus.Inside
                        } else {
                            SafeZoneStatus.Outside
                        }
                        _safeZoneStatus.value = newStatus
                    }
            }
        }
    }

    val isPaired: StateFlow<Boolean> = pairingRepository.isPaired()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val patientName: StateFlow<String> = preferences.patientName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val lastSyncTimestamp: StateFlow<Long> = preferences.lastSyncTimestamp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val safeZoneRadiusMeters: StateFlow<Int> = preferences.safeZoneRadiusMeters
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val geoReminderCount: StateFlow<Int> = geoReminderRepository.getReminderCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val lastLocationTimestamp: StateFlow<Long> = activityRepository.getLatestRecord()
        .map { it?.timestamp ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private var immediatePairingLocationSent = false

    fun triggerImmediatePostPairingLocationSync() {
        if (immediatePairingLocationSent) return

        immediatePairingLocationSent = true
        viewModelScope.launch {
            try {
                val location = locationService.getLastKnownLocation()
                if (location != null) {
                    activityTrackingService.recordLocationUpdate(location)
                    activityTrackingService.updateDailySummary()
                }
                syncService.syncActivityData()
            } catch (_: Exception) {
                immediatePairingLocationSent = false
            }
        }
    }

    fun resetImmediatePairingLocationFlag() {
        immediatePairingLocationSent = false
    }
}
