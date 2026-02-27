package com.example.relapse_watch.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "geo_reminders")
data class GeoReminderEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val isActive: Boolean = true,
    val lastTriggeredAt: Long? = null
)
