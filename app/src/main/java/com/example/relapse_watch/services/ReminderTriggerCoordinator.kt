package com.example.relapse_watch.services

import android.util.Log
import com.example.relapse_watch.domain.model.LocationPoint
import com.example.relapse_watch.domain.repository.GeoReminderRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ReminderTriggerCoordinator @Inject constructor(
    private val geoReminderRepository: GeoReminderRepository,
    private val reminderPlaybackQueueManager: ReminderPlaybackQueueManager
) {

    private val reminderMutex = Mutex()

    suspend fun triggerFromGeofence(reminderId: String, location: LocationPoint?): Boolean {
        return trigger(reminderId = reminderId, location = location, source = "GEOFENCE")
    }

    suspend fun triggerFromProximity(
        reminderId: String,
        location: LocationPoint,
        distanceMeters: Float
    ): Boolean {
        return trigger(
            reminderId = reminderId,
            location = location,
            source = "PROXIMITY",
            distanceMeters = distanceMeters
        )
    }

    private suspend fun trigger(
        reminderId: String,
        location: LocationPoint?,
        source: String,
        distanceMeters: Float? = null
    ): Boolean {
        return reminderMutex.withLock {
            val reminder = geoReminderRepository.getReminder(reminderId)
            if (reminder == null || !reminder.isActive) {
                Log.d(TAG, "[REMINDER_TRIGGER][$source][SKIP_MISSING] id=$reminderId")
                return@withLock false
            }

            val now = System.currentTimeMillis()
            val correlation = "$reminderId:$now"

            val point = location ?: LocationPoint(
                latitude = reminder.latitude,
                longitude = reminder.longitude,
                timestamp = now
            )
            val enqueued = reminderPlaybackQueueManager.enqueue(
                ReminderPlaybackRequest(
                    reminderId = reminder.id,
                    triggeredAt = now,
                    location = point,
                    title = reminder.title,
                    body = reminder.body,
                    imageUrl = reminder.imageUrl,
                    audioUrl = reminder.audioUrl,
                    videoUrl = reminder.videoUrl
                )
            )
            if (!enqueued) {
                Log.d(
                    TAG,
                    "[REMINDER_TRIGGER][$source][SKIP_DUPLICATE_QUEUE] key=$correlation id=${reminder.id} triggeredAt=$now"
                )
                return@withLock false
            }

            Log.d(
                TAG,
                "[REMINDER_TRIGGER][$source][ENQUEUED] key=$correlation id=${reminder.id} triggeredAt=$now hasAudio=${!reminder.audioUrl.isNullOrBlank()}"
            )

            true
        }
    }

    companion object {
        private const val TAG = "ReminderTriggerCoordinator"
    }
}