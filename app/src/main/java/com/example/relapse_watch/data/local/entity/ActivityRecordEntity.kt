package com.example.relapse_watch.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "activity_records")
data class ActivityRecordEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val eventType: String,
    val metadataJson: String? = null,
    val uploaded: Boolean = false
)
