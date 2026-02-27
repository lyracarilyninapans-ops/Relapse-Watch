package com.example.relapse_watch.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.relapse_watch.domain.model.SafeZoneConfig
import com.example.relapse_watch.domain.repository.SafeZoneRepository
import com.example.relapse_watch.services.GeofenceService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MonitoringViewModel @Inject constructor(
    private val safeZoneRepository: SafeZoneRepository,
    private val geofenceService: GeofenceService
) : ViewModel() {

    val activeSafeZone: StateFlow<SafeZoneConfig?> = safeZoneRepository.getActiveSafeZone()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _geofenceRegistered = MutableStateFlow(false)
    val geofenceRegistered: StateFlow<Boolean> = _geofenceRegistered

    fun registerSafeZoneGeofence(config: SafeZoneConfig) {
        viewModelScope.launch {
            val result = geofenceService.registerSafeZone(config)
            _geofenceRegistered.value = result.isSuccess
        }
    }

    fun clearGeofences() {
        viewModelScope.launch {
            geofenceService.removeAllGeofences()
            _geofenceRegistered.value = false
        }
    }
}
