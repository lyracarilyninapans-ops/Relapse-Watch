package com.example.relapse_watch.data.repository

import android.util.Log
import com.example.relapse_watch.data.local.dao.GeoReminderDao
import com.example.relapse_watch.data.local.RelapseWatchDatabase
import com.example.relapse_watch.data.local.entity.GeoReminderEntity
import com.example.relapse_watch.domain.model.GeoReminder
import com.example.relapse_watch.domain.repository.GeoReminderRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeoReminderRepositoryImpl @Inject constructor(
    private val dao: GeoReminderDao,
    private val database: RelapseWatchDatabase,
    private val firestore: FirebaseFirestore
) : GeoReminderRepository {

    override fun getActiveReminders(): Flow<List<GeoReminder>> {
        return dao.getActiveReminders().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun syncFromPhone(reminders: List<GeoReminder>) {
        database.withTransaction {
            val existingTriggeredMap = dao.getActiveRemindersSnapshot()
                .associate { it.id to it.lastTriggeredAt }

            val normalized = reminders.map { reminder ->
                reminder.copy(
                    lastTriggeredAt = existingTriggeredMap[reminder.id] ?: reminder.lastTriggeredAt
                ).toEntity()
            }

            syncRemindersDiff(normalized)
        }
    }

    override suspend fun syncFromFirestore(caregiverUid: String, patientId: String): Result<Unit> {
        return try {
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
                    val (latitude, longitude) = extractCoordinates(data) ?: run {
                        Log.w(
                            TAG,
                            "Skipping reminder ${doc.id}: missing/invalid coordinates ${describeCoordinatePayload(data)}"
                        )
                        return@mapNotNull null
                    }
                    val title = extractText(data, "title", "name") ?: "Memory Reminder"
                    val description = extractText(data, "description", "body").orEmpty()
                    val radiusMeters = extractRadiusMeters(data)

                    val (imageUrl, audioUrl, videoUrl) = extractMediaUrls(data)
                    Log.d(
                        TAG,
                        "[REMINDER_SYNC][MEDIA] id=${doc.id} hasImage=${!imageUrl.isNullOrBlank()} hasAudio=${!audioUrl.isNullOrBlank()} hasVideo=${!videoUrl.isNullOrBlank()}"
                    )

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
                        lastTriggeredAt = null
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse reminder ${doc.id}", e)
                    null
                }
            }

            database.withTransaction {
                val existingTriggeredMap = dao.getActiveRemindersSnapshot()
                    .associate { it.id to it.lastTriggeredAt }

                val normalized = reminders.map { reminder ->
                    reminder.copy(
                        lastTriggeredAt = existingTriggeredMap[reminder.id] ?: reminder.lastTriggeredAt
                    ).toEntity()
                }
                syncRemindersDiff(normalized)
            }
            Log.d(TAG, "Synced ${reminders.size} reminders from Firestore")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync reminders from Firestore", e)
            Result.failure(e)
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

    private suspend fun syncRemindersDiff(remote: List<GeoReminderEntity>) {
        val local = dao.getActiveRemindersSnapshot()
        val localById = local.associateBy { it.id }
        val remoteById = remote.associateBy { it.id }

        val toDeleteIds = localById.keys - remoteById.keys
        val toUpsert = remote.filter { remoteReminder ->
            val localReminder = localById[remoteReminder.id]
            localReminder == null || localReminder != remoteReminder
        }

        if (toDeleteIds.isNotEmpty()) {
            dao.deleteByIds(toDeleteIds.toList())
        }
        if (toUpsert.isNotEmpty()) {
            dao.upsertAll(toUpsert)
        }
    }

    companion object {
        private const val TAG = "GeoReminderRepo"

        internal fun extractCoordinatesForTest(data: Map<String, Any>): Pair<Double, Double>? {
            return extractCoordinates(data)
        }

        internal fun extractRadiusMetersForTest(data: Map<String, Any>): Int {
            return extractRadiusMeters(data)
        }

        private fun extractCoordinates(data: Map<String, Any>): Pair<Double, Double>? {
            val directLat = extractDouble(data, "latitude", "lat")
            val directLng = extractDouble(data, "longitude", "lng", "lon")
            if (directLat != null && directLng != null) {
                return directLat to directLng
            }

            val locationGeoPoint = data["location"] as? GeoPoint
            if (locationGeoPoint != null) {
                return locationGeoPoint.latitude to locationGeoPoint.longitude
            }

            return null
        }

        private fun extractDouble(data: Map<String, Any>, vararg keys: String): Double? {
            for (key in keys) {
                val value = data[key] ?: continue
                when (value) {
                    is Number -> return value.toDouble()
                    is String -> value.toDoubleOrNull()?.let { return it }
                }
            }
            return null
        }

        private fun extractText(data: Map<String, Any>, vararg keys: String): String? {
            for (key in keys) {
                val raw = data[key] as? String ?: continue
                val trimmed = raw.trim()
                if (trimmed.isNotEmpty()) {
                    return trimmed
                }
            }
            return null
        }

        private fun extractRadiusMeters(data: Map<String, Any>): Int {
            val radius = extractDouble(data, "radiusMeters", "radius") ?: 100.0
            return radius.toInt().coerceAtLeast(1)
        }

        private fun describeCoordinatePayload(data: Map<String, Any>): String {
            val keys = data.keys.sorted().joinToString(",")
            val latitude = data["latitude"]
            val longitude = data["longitude"]
            val lat = data["lat"]
            val lng = data["lng"]
            val lon = data["lon"]
            val location = data["location"]
            return "keys=[$keys] latitude=$latitude longitude=$longitude lat=$lat lng=$lng lon=$lon location=$location"
        }

        internal fun extractMediaUrls(data: Map<String, Any>): Triple<String?, String?, String?> {
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

            // Backward compatibility for older reminders that stored direct URLs.
            if (imageUrl == null) {
                imageUrl = (data["imageUrl"] as? String)?.takeIf { it.isNotBlank() }
            }
            if (audioUrl == null) {
                audioUrl = (data["audioUrl"] as? String)?.takeIf { it.isNotBlank() }
            }
            if (videoUrl == null) {
                videoUrl = (data["videoUrl"] as? String)?.takeIf { it.isNotBlank() }
            }

            return Triple(imageUrl, audioUrl, videoUrl)
        }
    }
}
