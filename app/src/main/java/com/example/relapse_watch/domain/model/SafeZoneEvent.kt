package com.example.relapse_watch.domain.model

data class SafeZoneEvent(
    val id: String,
    val safeZoneId: String,
    val eventType: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val uploaded: Boolean = false
)
