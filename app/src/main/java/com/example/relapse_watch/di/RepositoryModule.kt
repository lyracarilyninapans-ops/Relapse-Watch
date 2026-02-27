package com.example.relapse_watch.di

import com.example.relapse_watch.data.repository.ActivityRepositoryImpl
import com.example.relapse_watch.data.repository.DailySummaryRepositoryImpl
import com.example.relapse_watch.data.repository.PairingRepositoryImpl
import com.example.relapse_watch.domain.repository.ActivityRepository
import com.example.relapse_watch.domain.repository.DailySummaryRepository
import com.example.relapse_watch.domain.repository.PairingRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindActivityRepository(impl: ActivityRepositoryImpl): ActivityRepository

    @Binds
    abstract fun bindDailySummaryRepository(impl: DailySummaryRepositoryImpl): DailySummaryRepository

    @Binds
    abstract fun bindPairingRepository(impl: PairingRepositoryImpl): PairingRepository
}
