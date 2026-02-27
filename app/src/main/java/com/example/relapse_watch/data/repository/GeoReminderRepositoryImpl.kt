package com.example.relapse_watch.data.repository

import com.example.relapse_watch.data.local.dao.GeoReminderDao
import com.example.relapse_watch.data.local.entity.GeoReminderEntity
import com.example.relapse_watch.domain.model.GeoReminder
import com.example.relapse_watch.domain.repository.GeoReminderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeoReminderRepositoryImpl @Inject constructor(
    private val dao: GeoReminderDao
) : GeoReminderRepository {

    override fun getActiveReminders(): Flow<List<GeoReminder>> {
        return dao.getActiveReminders().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun syncFromPhone(reminders: List<GeoReminder>) {
        dao.deleteAll()
        dao.insertAll(reminders.map { it.toEntity() })
    }

    override suspend fun markAsTriggered(reminderId: String, timestamp: Long) {
        dao.markTriggered(reminderId, timestamp)
    }

    override suspend fun getReminder(id: String): GeoReminder? {
        return dao.getById(id)?.toDomain()
    }

    override fun getReminderCount(): Flow<Int> {
        return dao.getActiveCount()
    }

    private fun GeoReminderEntity.toDomain() = GeoReminder(
        id = id, title = title, body = body,
        latitude = latitude, longitude = longitude,
        radiusMeters = radiusMeters, imageUrl = imageUrl,
        videoUrl = videoUrl, isActive = isActive,
        lastTriggeredAt = lastTriggeredAt
    )

    private fun GeoReminder.toEntity() = GeoReminderEntity(
        id = id, title = title, body = body,
        latitude = latitude, longitude = longitude,
        radiusMeters = radiusMeters, imageUrl = imageUrl,
        videoUrl = videoUrl, isActive = isActive,
        lastTriggeredAt = lastTriggeredAt
    )
}
