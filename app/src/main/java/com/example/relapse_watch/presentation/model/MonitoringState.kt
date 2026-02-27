package com.example.relapse_watch.presentation.model

/**
 * Represents the current monitoring state of the watch app.
 * This is a UI model — business logic for populating it lives elsewhere.
 */
data class MonitoringState(
    val isPaired: Boolean = false,
    val pairingCode: String = "",
    val statusMessage: String? = null,
    val patientName: String = "",
    val lastSyncTimestamp: Long? = null,
    val safeZoneStatus: SafeZoneStatus = SafeZoneStatus.Unknown,
    val safeZoneRadiusMeters: Int? = null,
    val geoReminderCount: Int = 0
)

enum class SafeZoneStatus {
    Inside,
    Outside,
    Unknown
}
