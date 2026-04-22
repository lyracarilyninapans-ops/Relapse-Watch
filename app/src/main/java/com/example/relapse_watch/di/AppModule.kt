package com.example.relapse_watch.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.example.relapse_watch.data.local.RelapseWatchDatabase
import com.example.relapse_watch.data.local.dao.ActivityRecordDao
import com.example.relapse_watch.data.local.dao.DailySummaryDao
import com.example.relapse_watch.data.local.dao.GeoReminderDao
import com.example.relapse_watch.data.local.dao.LocationPointDao
import com.example.relapse_watch.data.local.dao.SafeZoneDao
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "watch_prefs")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RelapseWatchDatabase {
        return Room.databaseBuilder(
            context,
            RelapseWatchDatabase::class.java,
            "relapse_watch.db"
        ).build()
    }

    @Provides
    fun provideActivityRecordDao(db: RelapseWatchDatabase): ActivityRecordDao = db.activityRecordDao()

    @Provides
    fun provideLocationPointDao(db: RelapseWatchDatabase): LocationPointDao = db.locationPointDao()

    @Provides
    fun provideDailySummaryDao(db: RelapseWatchDatabase): DailySummaryDao = db.dailySummaryDao()

    @Provides
    fun provideSafeZoneDao(db: RelapseWatchDatabase): SafeZoneDao = db.safeZoneDao()

    @Provides
    fun provideGeoReminderDao(db: RelapseWatchDatabase): GeoReminderDao = db.geoReminderDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }

    @Provides
    @Singleton
    fun provideFirebaseFirestore(): FirebaseFirestore {
        return FirebaseFirestore.getInstance()
    }

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth {
        return FirebaseAuth.getInstance()
    }

    @Provides
    @Singleton
    fun provideFirebaseStorage(): FirebaseStorage {
        return FirebaseStorage.getInstance()
    }
}
