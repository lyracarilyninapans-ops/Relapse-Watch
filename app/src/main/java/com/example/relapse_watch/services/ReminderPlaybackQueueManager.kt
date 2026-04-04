package com.example.relapse_watch.services

import android.util.Log
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.domain.repository.GeoReminderRepository

@Singleton
class ReminderPlaybackQueueManager @Inject constructor(
    private val notificationService: NotificationService,
    private val geoReminderRepository: GeoReminderRepository,
    private val watchPreferences: WatchPreferences,
    private val mediaCacheManager: MediaCacheManager
) {

    private val queue = ArrayDeque<ReminderPlaybackRequest>()
    private var activeReminderId: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun enqueue(request: ReminderPlaybackRequest) {
        val correlation = correlationKey(request.reminderId, request.triggeredAt)
        val isDuplicate = activeReminderId == request.reminderId || queue.any { it.reminderId == request.reminderId }
        if (isDuplicate) {
            Log.d(TAG, "[R_TRACE][REMINDER_QUEUE][SKIP_DUPLICATE] key=$correlation id=${request.reminderId} triggeredAt=${request.triggeredAt}")
            return
        }

        queue.addLast(request)
        Log.d(TAG, "[R_TRACE][REMINDER_QUEUE][ENQUEUE] key=$correlation id=${request.reminderId} triggeredAt=${request.triggeredAt} pending=${queue.size}")

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
        Log.d(TAG, "[R_TRACE][REMINDER_QUEUE][FINISHED] id=$reminderId pending=${queue.size}")
        return launchNextLocked()
    }

    @Synchronized
    private fun launchNextLocked(): Boolean {
        if (activeReminderId != null) return false

        if (queue.isEmpty()) return false
        val next = queue.removeFirst()
        activeReminderId = next.reminderId
        val correlation = correlationKey(next.reminderId, next.triggeredAt)
        Log.d(TAG, "[R_TRACE][REMINDER_QUEUE][DEQUEUE] key=$correlation id=${next.reminderId} triggeredAt=${next.triggeredAt} remaining=${queue.size}")

        scope.launch {
            launchPrepared(next)
        }

        return true
    }

    private suspend fun launchPrepared(next: ReminderPlaybackRequest) {
        val correlation = correlationKey(next.reminderId, next.triggeredAt)
        if (!isEligibleAtLaunch(next)) {
            Log.d(TAG, "[R_TRACE][REMINDER_QUEUE][DROP_INELIGIBLE] key=$correlation id=${next.reminderId} triggeredAt=${next.triggeredAt}")
            clearActiveAndLaunchNext(next.reminderId)
            return
        }

        val hasAudio = !next.audioUrl.isNullOrBlank()
        if (hasAudio) {
            val audioReady = isAudioReady(next)
            if (!audioReady) {
                Log.w(
                    TAG,
                    "[R_TRACE][REMINDER_QUEUE][AUDIO_CACHE_FAILED] key=$correlation id=${next.reminderId} — launching anyway for in-activity fallback"
                )
            }
        }

        if (!isVideoReady(next)) {
            Log.w(TAG, "[R_TRACE][REMINDER_QUEUE][VIDEO_CACHE_FAILED] key=$correlation id=${next.reminderId} — continuing with degraded playback")
        }

        try {
            // Use a full-screen intent notification instead of startActivity().
            // On Android 12+ (Wear OS 3+), calling startActivity() from a
            // background BroadcastReceiver coroutine is silently blocked.
            // A fullScreenIntent bypasses this restriction and wakes the screen.
            notificationService.showReminderPlaybackNotification(
                reminderId = next.reminderId,
                triggeredAt = next.triggeredAt,
                title = next.title,
                body = next.body,
                imageUrl = next.imageUrl,
                audioUrl = next.audioUrl,
                videoUrl = next.videoUrl
            )
            Log.d(TAG, "[R_TRACE][REMINDER_QUEUE][PLAYING] key=$correlation id=${next.reminderId} triggeredAt=${next.triggeredAt} remaining=${queue.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch reminder playback: ${next.reminderId}", e)
            clearActiveAndLaunchNext(next.reminderId)
        }
    }

    private suspend fun isEligibleAtLaunch(request: ReminderPlaybackRequest): Boolean {
        val correlation = correlationKey(request.reminderId, request.triggeredAt)
        val reminder = geoReminderRepository.getReminder(request.reminderId) ?: return false
        if (!reminder.isActive) return false

        val lastTriggeredAt = reminder.lastTriggeredAt ?: return false
        val isLatestQueuedTrigger = lastTriggeredAt == request.triggeredAt
        if (isLatestQueuedTrigger) {
            Log.d(
                TAG,
                "[R_TRACE][REMINDER_QUEUE][ELIGIBLE_LATEST] key=$correlation id=${request.reminderId} queuedAt=${request.triggeredAt} dbLast=$lastTriggeredAt"
            )
            return true
        }

        val cooldownMinutes = watchPreferences.reminderCooldownMinutes.first()
        val cooldownMs = cooldownMinutes.coerceAtLeast(0) * 60_000L
        val now = System.currentTimeMillis()
        val eligible = (now - lastTriggeredAt) >= cooldownMs
        Log.d(
            TAG,
            "[R_TRACE][REMINDER_QUEUE][ELIGIBILITY_RECHECK] key=$correlation id=${request.reminderId} queuedAt=${request.triggeredAt} dbLast=$lastTriggeredAt now=$now cooldownMs=$cooldownMs eligible=$eligible"
        )
        return eligible
    }

    private suspend fun isAudioReady(request: ReminderPlaybackRequest): Boolean {
        val correlation = correlationKey(request.reminderId, request.triggeredAt)
        val audioUrl = request.audioUrl?.takeIf { it.isNotBlank() } ?: return true
        val cacheFileName = "${request.reminderId}_audio"
        val cached = mediaCacheManager.getCachedFile(cacheFileName)
        if (cached != null && cached.exists() && cached.length() > 0) {
            Log.d(TAG, "[R_TRACE][REMINDER_QUEUE][AUDIO_READY_CACHE] key=$correlation id=${request.reminderId} file=$cacheFileName bytes=${cached.length()}")
            return true
        }
        val downloaded = mediaCacheManager.downloadMedia(audioUrl, cacheFileName).isSuccess
        Log.d(TAG, "[R_TRACE][REMINDER_QUEUE][AUDIO_READY_DOWNLOAD] key=$correlation id=${request.reminderId} file=$cacheFileName success=$downloaded")
        return downloaded
    }

    private suspend fun isVideoReady(request: ReminderPlaybackRequest): Boolean {
        val correlation = correlationKey(request.reminderId, request.triggeredAt)
        val videoUrl = request.videoUrl?.takeIf { it.isNotBlank() } ?: return true
        val cacheFileName = "${request.reminderId}_video"
        val cached = mediaCacheManager.getCachedFile(cacheFileName)
        if (cached != null && cached.exists() && cached.length() > 0) {
            Log.d(TAG, "[R_TRACE][REMINDER_QUEUE][VIDEO_READY_CACHE] key=$correlation id=${request.reminderId} file=$cacheFileName bytes=${cached.length()}")
            return true
        }
        val downloaded = mediaCacheManager.downloadMedia(videoUrl, cacheFileName).isSuccess
        Log.d(TAG, "[R_TRACE][REMINDER_QUEUE][VIDEO_READY_DOWNLOAD] key=$correlation id=${request.reminderId} file=$cacheFileName success=$downloaded")
        return downloaded
    }

    private fun clearActiveAndLaunchNext(reminderId: String) {
        synchronized(this) {
            if (activeReminderId == reminderId) {
                activeReminderId = null
            }
            launchNextLocked()
        }
    }

    companion object {
        private const val TAG = "ReminderQueueManager"

        private fun correlationKey(reminderId: String, triggeredAt: Long): String {
            return "$reminderId:$triggeredAt"
        }
    }
}

data class ReminderPlaybackRequest(
    val reminderId: String,
    val triggeredAt: Long,
    val title: String,
    val body: String,
    val imageUrl: String?,
    val audioUrl: String?,
    val videoUrl: String?
)
