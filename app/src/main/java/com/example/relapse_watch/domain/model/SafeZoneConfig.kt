package com.example.relapse_watch.domain.model

data class SafeZoneConfig(
    val id: String,
    val centerLat: Double,
    val centerLng: Double,
    val radiusMeters: Int,
    val isActive: Boolean = true,
    val alarmEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true
)
