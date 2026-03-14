package com.example.relapse_watch.services

import android.util.Log
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderPlaybackQueueManager @Inject constructor(
    private val notificationService: NotificationService
) {

    private val queue = ArrayDeque<ReminderPlaybackRequest>()
    private var activeReminderId: String? = null

    @Synchronized
    fun enqueue(request: ReminderPlaybackRequest) {
        val isDuplicate = activeReminderId == request.reminderId || queue.any { it.reminderId == request.reminderId }
        if (isDuplicate) {
            Log.d(TAG, "Skipping duplicate queued/active reminder: ${request.reminderId}")
            return
        }

        queue.addLast(request)
        Log.d(TAG, "[REMINDER_QUEUE] queued id=${request.reminderId} pending=${queue.size}")

        if (activeReminderId == null) {
            launchNextLocked()
        }
    }

    @Synchronized
    fun onPlaybackFinished(reminderId: String): Boolean {
        if (activeReminderId == reminderId) {
            activeReminderId = null
        }
        notificationService.dismissReminderPlaybackNotification()
        return launchNextLocked()
    }

    @Synchronized
    private fun launchNextLocked(): Boolean {
        if (activeReminderId != null) return false

        if (queue.isEmpty()) return false
        val next = queue.removeFirst()
        activeReminderId = next.reminderId

        try {
            // Use a full-screen intent notification instead of startActivity().
            // On Android 12+ (Wear OS 3+), calling startActivity() from a
            // background BroadcastReceiver coroutine is silently blocked.
            // A fullScreenIntent bypasses this restriction and wakes the screen.
            notificationService.showReminderPlaybackNotification(
                reminderId = next.reminderId,
                title = next.title,
                body = next.body,
                imageUrl = next.imageUrl,
                audioUrl = next.audioUrl,
                videoUrl = next.videoUrl
            )
            Log.d(TAG, "[REMINDER_QUEUE] playing id=${next.reminderId} remaining=${queue.size}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch reminder playback: ${next.reminderId}", e)
            activeReminderId = null
            return launchNextLocked()
        }
    }

    companion object {
        private const val TAG = "ReminderQueueManager"
    }
}

data class ReminderPlaybackRequest(
    val reminderId: String,
    val title: String,
    val body: String,
    val imageUrl: String?,
    val audioUrl: String?,
    val videoUrl: String?
)
