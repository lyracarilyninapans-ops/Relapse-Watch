package com.example.relapse_watch.domain.repository

import com.example.relapse_watch.domain.model.GeoReminder
import kotlinx.coroutines.flow.Flow

interface GeoReminderRepository {
    fun getActiveReminders(): Flow<List<GeoReminder>>
    suspend fun syncFromPhone(reminders: List<GeoReminder>)
    suspend fun syncFromFirestore(caregiverUid: String, patientId: String)
    suspend fun markAsTriggered(reminderId: String, timestamp: Long)
    suspend fun getReminder(id: String): GeoReminder?
    fun getReminderCount(): Flow<Int>
}
