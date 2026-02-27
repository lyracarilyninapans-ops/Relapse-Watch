package com.example.relapse_watch.data.repository

import com.example.relapse_watch.data.local.dao.ActivityRecordDao
import com.example.relapse_watch.data.local.entity.ActivityRecordEntity
import com.example.relapse_watch.domain.model.ActivityRecord
import com.example.relapse_watch.domain.repository.ActivityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivityRepositoryImpl @Inject constructor(
    private val dao: ActivityRecordDao
) : ActivityRepository {

    override suspend fun insertRecord(record: ActivityRecord) {
        dao.insert(record.toEntity())
    }

    override suspend fun insertBatch(records: List<ActivityRecord>) {
        dao.insertAll(records.map { it.toEntity() })
    }

    override fun getRecordsByDateRange(start: Long, end: Long): Flow<List<ActivityRecord>> {
        return dao.getRecordsByDateRange(start, end).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getLatestRecord(): Flow<ActivityRecord?> {
        return dao.getLatestRecord().map { it?.toDomain() }
    }

    override suspend fun getPendingUpload(): List<ActivityRecord> {
        return dao.getPendingUpload().map { it.toDomain() }
    }

    override suspend fun markUploaded(ids: List<String>) {
        dao.markUploaded(ids)
    }

    override suspend fun deleteOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        dao.deleteOlderThan(cutoff)
    }

    private fun ActivityRecord.toEntity(): ActivityRecordEntity {
        return ActivityRecordEntity(
            id = id,
            patientId = patientId,
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude,
            eventType = eventType,
            metadataJson = metadata?.let { Json.encodeToString(it.mapValues { (_, v) -> v.toString() }) },
            uploaded = uploaded
        )
    }

    private fun ActivityRecordEntity.toDomain(): ActivityRecord {
        return ActivityRecord(
            id = id,
            patientId = patientId,
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude,
            eventType = eventType,
            metadata = metadataJson?.let {
                try {
                    Json.decodeFromString<Map<String, String>>(it)
                } catch (_: Exception) {
                    null
                }
            },
            uploaded = uploaded
        )
    }
}
