package com.example.relapse_watch.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.domain.repository.GeoReminderRepository
import com.example.relapse_watch.domain.repository.PairingRepository
import com.example.relapse_watch.domain.repository.SafeZoneRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    pairingRepository: PairingRepository,
    safeZoneRepository: SafeZoneRepository,
    geoReminderRepository: GeoReminderRepository,
    val preferences: WatchPreferences
) : ViewModel() {

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
}
