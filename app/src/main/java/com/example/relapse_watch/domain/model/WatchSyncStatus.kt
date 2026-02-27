package com.example.relapse_watch.domain.model

data class WatchSyncStatus(
    val lastSyncTimestamp: Long? = null,
    val pendingUploads: Int = 0,
    val isConnected: Boolean = false
)
