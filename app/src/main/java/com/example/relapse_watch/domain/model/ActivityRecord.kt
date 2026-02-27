package com.example.relapse_watch.domain.model

object EventTypes {
    const val LOCATION_UPDATE = "location_update"
    const val SAFE_ZONE_EXIT = "safe_zone_exit"
    const val SAFE_ZONE_ENTER = "safe_zone_enter"
    const val REMINDER_TRIGGERED = "reminder_triggered"
    const val WATCH_DISCONNECTED = "watch_disconnected"
    const val WATCH_RECONNECTED = "watch_reconnected"
}

data class ActivityRecord(
    val id: String,
    val patientId: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val eventType: String,
    val metadata: Map<String, Any>? = null,
    val uploaded: Boolean = false
)
