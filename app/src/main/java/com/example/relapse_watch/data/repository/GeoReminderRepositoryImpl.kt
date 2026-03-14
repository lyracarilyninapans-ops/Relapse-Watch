package com.example.relapse_watch.data.repository

import android.util.Log
import com.example.relapse_watch.data.local.dao.GeoReminderDao
import com.example.relapse_watch.data.local.entity.GeoReminderEntity
import com.example.relapse_watch.domain.model.GeoReminder
import com.example.relapse_watch.domain.repository.GeoReminderRepository
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeoReminderRepositoryImpl @Inject constructor(
    private val dao: GeoReminderDao,
    private val firestore: FirebaseFirestore
) : GeoReminderRepository {

    override fun getActiveReminders(): Flow<List<GeoReminder>> {
        return dao.getActiveReminders().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun syncFromPhone(reminders: List<GeoReminder>) {
        val existingTriggeredMap = dao.getActiveReminders()
            .first()
            .associate { it.id to it.lastTriggeredAt }

        dao.deleteAll()
        dao.insertAll(
            reminders.map { reminder ->
                reminder.copy(
                    lastTriggeredAt = existingTriggeredMap[reminder.id] ?: reminder.lastTriggeredAt
                ).toEntity()
            }
        )
    }

    override suspend fun syncFromFirestore(caregiverUid: String, patientId: String) {
        try {
            val existingTriggeredMap = dao.getActiveReminders()
                .first()
                .associate { it.id to it.lastTriggeredAt }

            val snapshot = firestore
                .collection("users").document(caregiverUid)
                .collection("patients").document(patientId)
                .collection("memoryReminders")
                .whereEqualTo("isActive", true)
                .get()
                .await()

            val reminders = snapshot.documents.mapNotNull { doc ->
                try {
                    val data = doc.data ?: return@mapNotNull null
                    val latitude = (data["latitude"] as? Number)?.toDouble() ?: return@mapNotNull null
                    val longitude = (data["longitude"] as? Number)?.toDouble() ?: return@mapNotNull null
                    val title = data["title"] as? String ?: ""
                    val description = data["description"] as? String ?: ""
                    val radiusMeters = (data["radiusMeters"] as? Number)?.toInt() ?: 100

                    // Extract photo and video URLs from mediaItems
                    val mediaItems = data["mediaItems"] as? List<*>
                    var imageUrl: String? = null
                    var audioUrl: String? = null
                    var videoUrl: String? = null
                    mediaItems?.forEach { item ->
                        val mediaMap = item as? Map<*, *> ?: return@forEach
                        val type = mediaMap["type"] as? String
                        val cloudUrl = (mediaMap["cloudUrl"] as? String)?.takeIf { it.isNotBlank() }
                        if (cloudUrl != null) {
                            when (type) {
                                "photo" -> if (imageUrl == null) imageUrl = cloudUrl
                                "audio" -> if (audioUrl == null) audioUrl = cloudUrl
                                "video" -> if (videoUrl == null) videoUrl = cloudUrl
                            }
                        }
                    }

                    GeoReminder(
                        id = doc.id,
                        title = title,
                        body = description,
                        latitude = latitude,
                        longitude = longitude,
                        radiusMeters = radiusMeters,
                        imageUrl = imageUrl,
                        audioUrl = audioUrl,
                        videoUrl = videoUrl,
                        isActive = true,
                        lastTriggeredAt = existingTriggeredMap[doc.id]
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse reminder ${doc.id}", e)
                    null
                }
            }

            dao.deleteAll()
            dao.insertAll(reminders.map { it.toEntity() })
            Log.d(TAG, "Synced ${reminders.size} reminders from Firestore")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync reminders from Firestore", e)
        }
    }

    override suspend fun markAsTriggered(reminderId: String, timestamp: Long) {
        dao.markTriggered(reminderId, timestamp)
    }

    override suspend fun getReminder(id: String): GeoReminder? {
        return dao.getById(id)?.toDomain()
    }

    override fun getReminderCount(): Flow<Int> {
        return dao.getActiveCount()
    }

    private fun GeoReminderEntity.toDomain() = GeoReminder(
        id = id, title = title, body = body,
        latitude = latitude, longitude = longitude,
        radiusMeters = radiusMeters, imageUrl = imageUrl,
        audioUrl = audioUrl, videoUrl = videoUrl, isActive = isActive,
        lastTriggeredAt = lastTriggeredAt
    )

    private fun GeoReminder.toEntity() = GeoReminderEntity(
        id = id, title = title, body = body,
        latitude = latitude, longitude = longitude,
        radiusMeters = radiusMeters, imageUrl = imageUrl,
        audioUrl = audioUrl, videoUrl = videoUrl, isActive = isActive,
        lastTriggeredAt = lastTriggeredAt
    )

    companion object {
        private const val TAG = "GeoReminderRepo"
    }
}
