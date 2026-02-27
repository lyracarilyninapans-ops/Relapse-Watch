package com.example.relapse_watch.domain.repository

import com.example.relapse_watch.domain.model.DailySummary
import kotlinx.coroutines.flow.Flow

interface DailySummaryRepository {
    suspend fun upsert(summary: DailySummary)
    fun getSummaryForDate(date: String): Flow<DailySummary?>
}
