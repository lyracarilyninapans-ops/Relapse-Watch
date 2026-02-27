package com.example.relapse_watch.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.relapse_watch.data.local.dao.ActivityRecordDao
import com.example.relapse_watch.data.local.dao.DailySummaryDao
import com.example.relapse_watch.data.local.dao.LocationPointDao
import com.example.relapse_watch.data.local.entity.ActivityRecordEntity
import com.example.relapse_watch.data.local.entity.DailySummaryEntity
import com.example.relapse_watch.data.local.entity.LocationPointEntity

@Database(
    entities = [
        ActivityRecordEntity::class,
        LocationPointEntity::class,
        DailySummaryEntity::class,
    ],
    version = 1,
    exportSchema = false
)
abstract class RelapseWatchDatabase : RoomDatabase() {
    abstract fun activityRecordDao(): ActivityRecordDao
    abstract fun locationPointDao(): LocationPointDao
    abstract fun dailySummaryDao(): DailySummaryDao
}
