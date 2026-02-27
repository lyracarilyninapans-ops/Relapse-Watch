package com.example.relapse_watch.data.repository

import com.example.relapse_watch.data.local.dao.SafeZoneDao
import com.example.relapse_watch.data.local.entity.SafeZoneEntity
import com.example.relapse_watch.data.local.entity.SafeZoneEventEntity
import com.example.relapse_watch.domain.model.SafeZoneConfig
import com.example.relapse_watch.domain.model.SafeZoneEvent
import com.example.relapse_watch.domain.repository.SafeZoneRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafeZoneRepositoryImpl @Inject constructor(
    private val dao: SafeZoneDao
) : SafeZoneRepository {

    override fun getActiveSafeZone(): Flow<SafeZoneConfig?> {
        return dao.getActiveSafeZone().map { it?.toDomain() }
    }

    override suspend fun updateFromFirestore(config: SafeZoneConfig) {
        dao.upsertZone(config.toEntity())
    }

    override suspend fun recordEvent(event: SafeZoneEvent) {
        dao.insertEvent(event.toEntity())
    }

    override fun getEvents(zoneId: String): Flow<List<SafeZoneEvent>> {
        return dao.getEventsForZone(zoneId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getPendingEventUpload(): List<SafeZoneEvent> {
        return dao.getPendingUpload().map { it.toDomain() }
    }

    override suspend fun markEventsUploaded(ids: List<String>) {
        dao.markUploaded(ids)
    }

    private fun SafeZoneEntity.toDomain() = SafeZoneConfig(
        id = id, centerLat = centerLat, centerLng = centerLng,
        radiusMeters = radiusMeters, isActive = isActive,
        alarmEnabled = alarmEnabled, vibrationEnabled = vibrationEnabled
    )

    private fun SafeZoneConfig.toEntity() = SafeZoneEntity(
        id = id, centerLat = centerLat, centerLng = centerLng,
        radiusMeters = radiusMeters, isActive = isActive,
        alarmEnabled = alarmEnabled, vibrationEnabled = vibrationEnabled
    )

    private fun SafeZoneEventEntity.toDomain() = SafeZoneEvent(
        id = id, safeZoneId = safeZoneId, eventType = eventType,
        timestamp = timestamp, latitude = latitude, longitude = longitude,
        uploaded = uploaded
    )

    private fun SafeZoneEvent.toEntity() = SafeZoneEventEntity(
        id = id, safeZoneId = safeZoneId, eventType = eventType,
        timestamp = timestamp, latitude = latitude, longitude = longitude,
        uploaded = uploaded
    )
}
