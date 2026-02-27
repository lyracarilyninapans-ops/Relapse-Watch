package com.example.relapse_watch.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "safe_zones")
data class SafeZoneEntity(
    @PrimaryKey val id: String,
    val centerLat: Double,
    val centerLng: Double,
    val radiusMeters: Int,
    val isActive: Boolean = true,
    val alarmEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true
)
