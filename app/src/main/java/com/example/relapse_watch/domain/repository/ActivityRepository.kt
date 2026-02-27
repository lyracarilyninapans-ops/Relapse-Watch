package com.example.relapse_watch.domain.repository

import com.example.relapse_watch.domain.model.ActivityRecord
import kotlinx.coroutines.flow.Flow

interface ActivityRepository {
    suspend fun insertRecord(record: ActivityRecord)
    suspend fun insertBatch(records: List<ActivityRecord>)
    fun getRecordsByDateRange(start: Long, end: Long): Flow<List<ActivityRecord>>
    fun getLatestRecord(): Flow<ActivityRecord?>
    suspend fun getPendingUpload(): List<ActivityRecord>
    suspend fun markUploaded(ids: List<String>)
    suspend fun deleteOlderThan(days: Int)
}
