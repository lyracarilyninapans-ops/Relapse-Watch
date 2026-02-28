package com.example.relapse_watch.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.relapse_watch.domain.repository.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val pairingRepository: PairingRepository
) : ViewModel() {

    fun resetPairing(onComplete: () -> Unit) {
        viewModelScope.launch {
            pairingRepository.clearPairing()
            onComplete()
        }
    }
}
