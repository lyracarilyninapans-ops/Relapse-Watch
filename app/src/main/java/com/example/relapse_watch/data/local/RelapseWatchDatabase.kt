package com.example.relapse_watch.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.relapse_watch.data.local.dao.ActivityRecordDao
import com.example.relapse_watch.data.local.dao.DailySummaryDao
import com.example.relapse_watch.data.local.dao.GeoReminderDao
import com.example.relapse_watch.data.local.dao.LocationPointDao
import com.example.relapse_watch.data.local.dao.SafeZoneDao
import com.example.relapse_watch.data.local.entity.ActivityRecordEntity
import com.example.relapse_watch.data.local.entity.DailySummaryEntity
import com.example.relapse_watch.data.local.entity.GeoReminderEntity
import com.example.relapse_watch.data.local.entity.LocationPointEntity
import com.example.relapse_watch.data.local.entity.SafeZoneEntity
import com.example.relapse_watch.data.local.entity.SafeZoneEventEntity

@Database(
    entities = [
        ActivityRecordEntity::class,
        LocationPointEntity::class,
        DailySummaryEntity::class,
        SafeZoneEntity::class,
        SafeZoneEventEntity::class,
        GeoReminderEntity::class,
    ],
    version = 3,
    exportSchema = true
)
abstract class RelapseWatchDatabase : RoomDatabase() {
    abstract fun activityRecordDao(): ActivityRecordDao
    abstract fun locationPointDao(): LocationPointDao
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun safeZoneDao(): SafeZoneDao
    abstract fun geoReminderDao(): GeoReminderDao
}
