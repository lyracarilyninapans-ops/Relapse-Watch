package com.example.relapse_watch.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    private val _isConnected = MutableStateFlow(checkCurrentConnectivity())
    val isConnected: StateFlow<Boolean> = _isConnected

    fun observeConnectivity(): Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isConnected.value = true
                trySend(true)
            }

            override fun onLost(network: Network) {
                _isConnected.value = false
                trySend(false)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                _isConnected.value = hasInternet
                trySend(hasInternet)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)
        trySend(checkCurrentConnectivity())

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    private fun checkCurrentConnectivity(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val TAG = "ConnectivityMonitor"
    }
}
