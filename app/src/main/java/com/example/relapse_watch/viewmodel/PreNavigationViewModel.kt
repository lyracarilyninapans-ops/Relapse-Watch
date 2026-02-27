package com.example.relapse_watch.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.relapse_watch.domain.model.LocationPoint
import com.example.relapse_watch.domain.model.SafeZoneConfig
import com.example.relapse_watch.domain.repository.SafeZoneRepository
import com.example.relapse_watch.services.LocationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PreNavigationViewModel @Inject constructor(
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

    fun updateCurrentLocation() {
        viewModelScope.launch {
            val location = locationService.getLastKnownLocation()
            _currentLocation.value = location

            val zone = activeSafeZone.value
            if (location != null && zone != null) {
                val zoneCenter = LocationPoint(zone.centerLat, zone.centerLng, 0L)
                _distanceToSafeZone.value = locationService.calculateDistance(location, zoneCenter)
                _bearingToSafeZone.value = locationService.calculateBearing(location, zoneCenter)
            }
        }
    }
}
