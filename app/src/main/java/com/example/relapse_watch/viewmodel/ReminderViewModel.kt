package com.example.relapse_watch.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.relapse_watch.domain.model.GeoReminder
import com.example.relapse_watch.domain.repository.GeoReminderRepository
import com.example.relapse_watch.services.GeofenceService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReminderViewModel @Inject constructor(
    private val geoReminderRepository: GeoReminderRepository,
    private val geofenceService: GeofenceService
) : ViewModel() {

    val activeReminders: StateFlow<List<GeoReminder>> = geoReminderRepository.getActiveReminders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reminderCount: StateFlow<Int> = geoReminderRepository.getReminderCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _registrationState = MutableStateFlow<ReminderRegistrationState>(ReminderRegistrationState.Idle)
    val registrationState: StateFlow<ReminderRegistrationState> = _registrationState

    fun registerAllGeofences() {
        viewModelScope.launch {
            _registrationState.value = ReminderRegistrationState.Registering
            var successCount = 0
            var failCount = 0

            activeReminders.value.forEach { reminder ->
                val result = geofenceService.registerReminder(
                    id = reminder.id,
                    latitude = reminder.latitude,
                    longitude = reminder.longitude,
                    radiusMeters = reminder.radiusMeters
                )
                if (result.isSuccess) successCount++ else failCount++
            }

            _registrationState.value = ReminderRegistrationState.Done(successCount, failCount)
        }
    }

    fun registerSingleGeofence(reminder: GeoReminder) {
        viewModelScope.launch {
            geofenceService.registerReminder(
                id = reminder.id,
                latitude = reminder.latitude,
                longitude = reminder.longitude,
                radiusMeters = reminder.radiusMeters
            )
        }
    }

    sealed class ReminderRegistrationState {
        data object Idle : ReminderRegistrationState()
        data object Registering : ReminderRegistrationState()
        data class Done(val successCount: Int, val failCount: Int) : ReminderRegistrationState()
    }
}
