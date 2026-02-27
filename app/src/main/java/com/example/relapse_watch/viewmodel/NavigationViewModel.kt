package com.example.relapse_watch.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.relapse_watch.domain.model.LocationPoint
import com.example.relapse_watch.domain.model.SafeZoneConfig
import com.example.relapse_watch.domain.repository.SafeZoneRepository
import com.example.relapse_watch.services.LocationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val locationService: LocationService,
    private val safeZoneRepository: SafeZoneRepository
) : ViewModel() {

    val activeSafeZone: StateFlow<SafeZoneConfig?> = safeZoneRepository.getActiveSafeZone()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _currentLocation = MutableStateFlow<LocationPoint?>(null)
    val currentLocation: StateFlow<LocationPoint?> = _currentLocation

    private val _distanceToSafeZone = MutableStateFlow<Float?>(null)
    val distanceToSafeZone: StateFlow<Float?> = _distanceToSafeZone

    private val _bearingToSafeZone = MutableStateFlow<Float?>(null)
    val bearingToSafeZone: StateFlow<Float?> = _bearingToSafeZone

    private val _isInsideSafeZone = MutableStateFlow(false)
    val isInsideSafeZone: StateFlow<Boolean> = _isInsideSafeZone

    private var trackingJob: Job? = null

    fun startLiveNavigation() {
        if (trackingJob?.isActive == true) return

        trackingJob = viewModelScope.launch {
            locationService.getLocationUpdates(intervalMs = 5_000L)
                .catch { /* silently handle errors during navigation */ }
                .collect { location ->
                    _currentLocation.value = location
                    updateNavigation(location)
                }
        }
    }

    fun stopLiveNavigation() {
        trackingJob?.cancel()
        trackingJob = null
    }

    private fun updateNavigation(location: LocationPoint) {
        val zone = activeSafeZone.value ?: return
        val zoneCenter = LocationPoint(zone.centerLat, zone.centerLng, 0L)

        val distance = locationService.calculateDistance(location, zoneCenter)
        _distanceToSafeZone.value = distance
        _bearingToSafeZone.value = locationService.calculateBearing(location, zoneCenter)
        _isInsideSafeZone.value = distance <= zone.radiusMeters
    }

    override fun onCleared() {
        stopLiveNavigation()
        super.onCleared()
    }
}
