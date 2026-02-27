package com.example.relapse_watch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.relapse_watch.data.local.entity.GeoReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GeoReminderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reminders: List<GeoReminderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: GeoReminderEntity)

    @Query("SELECT * FROM geo_reminders WHERE isActive = 1")
    fun getActiveReminders(): Flow<List<GeoReminderEntity>>

    @Query("SELECT * FROM geo_reminders WHERE id = :id")
    suspend fun getById(id: String): GeoReminderEntity?

    @Query("UPDATE geo_reminders SET lastTriggeredAt = :timestamp WHERE id = :id")
    suspend fun markTriggered(id: String, timestamp: Long)

    @Query("SELECT COUNT(*) FROM geo_reminders WHERE isActive = 1")
    fun getActiveCount(): Flow<Int>

    @Query("DELETE FROM geo_reminders")
    suspend fun deleteAll()
}
