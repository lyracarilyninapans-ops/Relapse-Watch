package com.example.relapse_watch.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.domain.repository.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

sealed class PairingUiState {
    data object Idle : PairingUiState()
    data class ShowCode(val code: String) : PairingUiState()
    data class Paired(val patientName: String) : PairingUiState()
    data class Error(val message: String) : PairingUiState()
}

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val pairingRepository: PairingRepository,
    private val preferences: WatchPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    private var pairingJob: Job? = null

    /** Tracks whether we have ever seen isPaired = true so we only reset
     *  on a genuine paired→unpaired transition, not on every `false` emission
     *  (which happens on fresh launches before pairing has occurred). */
    private var wasPreviouslyPaired = false

    init {
        // When the watch transitions from paired → unpaired (e.g. phone-initiated),
        // reset the UI state so the PairingScreen doesn't retain stale "Paired"
        // state from a previous session and auto-navigate back to monitoring.
        viewModelScope.launch {
            preferences.isPaired.collect { paired ->
                if (paired) {
                    wasPreviouslyPaired = true
                } else if (wasPreviouslyPaired) {
                    // Genuine unpair transition — reset everything.
                    wasPreviouslyPaired = false
                    pairingJob?.cancel()
                    pairingJob = null
                    _uiState.value = PairingUiState.Idle
                }
            }
        }
    }

    fun generatePairingCode() {
        val code = List(6) { Random.nextInt(10) }.joinToString("")
        _uiState.value = PairingUiState.ShowCode(code)

        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            val watchId = "watch_${System.currentTimeMillis()}"
            val result = pairingRepository.createPairingEntry(code, watchId)
            if (result.isFailure) {
                _uiState.value = PairingUiState.Error("Failed to create pairing entry")
                return@launch
            }

            // Wait until the caregiver claims AND finalizes the pairing.
            // Use .first{} so we stop listening after the first paired emission
            // instead of calling confirmPairing repeatedly on every snapshot.
            val pairedState = pairingRepository.observePairingStatus(code)
                .first { it.isPaired }

            pairingRepository.confirmPairing(
                pairedState.caregiverUid,
                pairedState.patientId,
                pairedState.patientName,
                pairedState.watchId
            )
            _uiState.value = PairingUiState.Paired(pairedState.patientName.ifBlank { pairedState.patientId })
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
        _uiState.value = PairingUiState.Idle
    }
}
