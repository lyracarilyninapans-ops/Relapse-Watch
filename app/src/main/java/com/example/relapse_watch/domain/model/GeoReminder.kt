package com.example.relapse_watch.domain.model

data class GeoReminder(
    val id: String,
    val title: String,
    val body: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val imageUrl: String? = null,
    val audioUrl: String? = null,
    val videoUrl: String? = null,
    val isActive: Boolean = true,
    val lastTriggeredAt: Long? = null
)
