package com.example.relapse_watch.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_summaries")
data class DailySummaryEntity(
    @PrimaryKey val date: String,
    val distanceMeters: Double = 0.0,
    val activeMinutes: Int = 0,
    val placesVisited: Int = 0,
    val safeZoneExits: Int = 0,
    val remindersTriggered: Int = 0,
    val totalEvents: Int = 0,
    val stepCount: Int = 0
)
