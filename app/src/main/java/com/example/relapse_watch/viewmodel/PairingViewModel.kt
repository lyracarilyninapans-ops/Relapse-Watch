package com.example.relapse_watch.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.domain.repository.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

sealed class PairingUiState {
    data object Idle : PairingUiState()
    data class ShowCode(val code: String) : PairingUiState()
    data class Connecting(val code: String) : PairingUiState()
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

    fun generatePairingCode() {
        val code = List(6) { Random.nextInt(10) }.joinToString("")
        _uiState.value = PairingUiState.ShowCode(code)

        viewModelScope.launch {
            val watchId = preferences.watchId.let { "watch_${System.currentTimeMillis()}" }
            val result = pairingRepository.createPairingEntry(code, watchId)
            if (result.isFailure) {
                _uiState.value = PairingUiState.Error("Failed to create pairing entry")
                return@launch
            }

            _uiState.value = PairingUiState.Connecting(code)

            pairingRepository.observePairingStatus(code).collect { state ->
                if (state.isPaired) {
                    pairingRepository.confirmPairing(state.caregiverUid, state.patientId)
                    _uiState.value = PairingUiState.Paired(state.patientId)
                }
            }
        }
    }

    fun cancelPairing() {
        _uiState.value = PairingUiState.Idle
    }
}
