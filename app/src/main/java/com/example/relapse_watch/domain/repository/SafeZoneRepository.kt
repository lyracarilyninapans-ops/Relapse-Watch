package com.example.relapse_watch.domain.repository

import com.example.relapse_watch.domain.model.SafeZoneConfig
import com.example.relapse_watch.domain.model.SafeZoneEvent
import kotlinx.coroutines.flow.Flow

interface SafeZoneRepository {
    fun getActiveSafeZone(): Flow<SafeZoneConfig?>
    suspend fun updateFromFirestore(config: SafeZoneConfig)
    suspend fun clearActiveSafeZone()
    suspend fun recordEvent(event: SafeZoneEvent)
    fun getEvents(zoneId: String): Flow<List<SafeZoneEvent>>
    suspend fun getPendingEventUpload(): List<SafeZoneEvent>
    suspend fun markEventsUploaded(ids: List<String>)
}
