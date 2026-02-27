package com.example.relapse_watch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.relapse_watch.data.local.entity.SafeZoneEntity
import com.example.relapse_watch.data.local.entity.SafeZoneEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SafeZoneDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertZone(zone: SafeZoneEntity)

    @Query("SELECT * FROM safe_zones WHERE isActive = 1 LIMIT 1")
    fun getActiveSafeZone(): Flow<SafeZoneEntity?>

    @Query("SELECT * FROM safe_zones")
    fun getAllZones(): Flow<List<SafeZoneEntity>>

    @Query("DELETE FROM safe_zones")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: SafeZoneEventEntity)

    @Query("SELECT * FROM safe_zone_events WHERE safeZoneId = :zoneId ORDER BY timestamp DESC")
    fun getEventsForZone(zoneId: String): Flow<List<SafeZoneEventEntity>>

    @Query("SELECT * FROM safe_zone_events WHERE uploaded = 0 ORDER BY timestamp ASC")
    suspend fun getPendingUpload(): List<SafeZoneEventEntity>

    @Query("UPDATE safe_zone_events SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<String>)
}
