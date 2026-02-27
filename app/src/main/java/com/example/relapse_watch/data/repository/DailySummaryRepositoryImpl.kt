package com.example.relapse_watch.data.repository

import com.example.relapse_watch.data.local.dao.DailySummaryDao
import com.example.relapse_watch.data.local.entity.DailySummaryEntity
import com.example.relapse_watch.domain.model.DailySummary
import com.example.relapse_watch.domain.repository.DailySummaryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailySummaryRepositoryImpl @Inject constructor(
    private val dao: DailySummaryDao
) : DailySummaryRepository {

    override suspend fun upsert(summary: DailySummary) {
        dao.upsert(summary.toEntity())
    }

    override fun getSummaryForDate(date: String): Flow<DailySummary?> {
        return dao.getSummaryForDate(date).map { it?.toDomain() }
    }

    private fun DailySummary.toEntity(): DailySummaryEntity {
        return DailySummaryEntity(
            date = date,
            distanceMeters = distanceMeters,
            activeMinutes = activeMinutes,
            placesVisited = placesVisited,
            safeZoneExits = safeZoneExits,
            remindersTriggered = remindersTriggered,
            totalEvents = totalEvents,
            stepCount = stepCount
        )
    }

    private fun DailySummaryEntity.toDomain(): DailySummary {
        return DailySummary(
            date = date,
            distanceMeters = distanceMeters,
            activeMinutes = activeMinutes,
            placesVisited = placesVisited,
            safeZoneExits = safeZoneExits,
            remindersTriggered = remindersTriggered,
            totalEvents = totalEvents,
            stepCount = stepCount
        )
    }
}
