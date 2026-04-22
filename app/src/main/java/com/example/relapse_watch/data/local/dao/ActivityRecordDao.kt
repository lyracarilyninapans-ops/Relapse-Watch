package com.example.relapse_watch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.relapse_watch.data.local.entity.ActivityRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ActivityRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<ActivityRecordEntity>)

    @Query("SELECT * FROM activity_records WHERE uploaded = 0 ORDER BY timestamp ASC")
    suspend fun getPendingUpload(): List<ActivityRecordEntity>

    @Query("SELECT * FROM activity_records WHERE uploaded = 0 AND patientId = :patientId ORDER BY timestamp ASC")
    suspend fun getPendingUploadForPatient(patientId: String): List<ActivityRecordEntity>

    @Query("SELECT * FROM activity_records WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    fun getRecordsByDateRange(start: Long, end: Long): Flow<List<ActivityRecordEntity>>

    @Query("SELECT * FROM activity_records ORDER BY timestamp DESC LIMIT 1")
    fun getLatestRecord(): Flow<ActivityRecordEntity?>

    @Query("UPDATE activity_records SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<String>)

    @Query("DELETE FROM activity_records WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long)

    @Query("DELETE FROM activity_records")
    suspend fun deleteAll()
}
