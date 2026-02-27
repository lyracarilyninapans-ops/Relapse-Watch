package com.example.relapse_watch.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "safe_zone_events")
data class SafeZoneEventEntity(
    @PrimaryKey val id: String,
    val safeZoneId: String,
    val eventType: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val uploaded: Boolean = false
)
