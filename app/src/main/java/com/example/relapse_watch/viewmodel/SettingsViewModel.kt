package com.example.relapse_watch.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.relapse_watch.domain.usecase.UnpairUseCase
import com.example.relapse_watch.services.MediaCacheManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val unpairUseCase: UnpairUseCase,
    private val mediaCacheManager: MediaCacheManager
) : ViewModel() {

    private val _cacheSize = MutableStateFlow(0L)
    val cacheSize: StateFlow<Long> = _cacheSize.asStateFlow()

    init {
        updateCacheSize()
    }

    private fun updateCacheSize() {
        viewModelScope.launch {
            _cacheSize.value = mediaCacheManager.getCacheSize()
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            mediaCacheManager.clearCache()
            updateCacheSize()
        }
    }

    fun resetPairing(onComplete: () -> Unit) {
        viewModelScope.launch {
            // Full cleanup: delete Firestore docs, remove geofences,
            // stop services, cancel sync, and clear local preferences.
            // alsoNotifyPhone = true because the watch is initiating the unp air.
            unpairUseCase.execute(alsoNotifyPhone = true)
            onComplete()
        }
    }
}
