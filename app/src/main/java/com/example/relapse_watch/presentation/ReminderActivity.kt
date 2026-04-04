package com.example.relapse_watch.presentation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.example.relapse_watch.presentation.screens.ReminderScreen
import com.example.relapse_watch.presentation.theme.Relapse_WatchTheme
import com.example.relapse_watch.services.NotificationService
import com.example.relapse_watch.services.ReminderPlaybackQueueManager
import com.example.relapse_watch.services.MediaCacheManager
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@AndroidEntryPoint
class ReminderActivity : ComponentActivity() {

    @Inject lateinit var reminderPlaybackQueueManager: ReminderPlaybackQueueManager
    @Inject lateinit var mediaCacheManager: MediaCacheManager
    @Inject lateinit var notificationService: NotificationService

    private var currentReminderId: String = ""
    private val finishReported = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationService.dismissReminderPlaybackNotification()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Reset for the new reminder
        finishReported.set(false)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val reminderId = intent.getStringExtra("reminderId") ?: ""
        currentReminderId = reminderId
        val triggeredAt = intent.getLongExtra("triggeredAt", 0L)
        val correlationKey = intent.getStringExtra("correlationKey")
            ?.takeIf { it.isNotBlank() }
            ?: if (triggeredAt > 0L) "$reminderId:$triggeredAt" else reminderId
        val title = intent.getStringExtra("title") ?: "Reminder"
        val body = intent.getStringExtra("body") ?: ""
        val imageUriString = intent.getStringExtra("imageUri")?.takeIf { it.isNotBlank() }
        val audioUriString = intent.getStringExtra("audioUri")?.takeIf { it.isNotBlank() }
        val videoUriString = intent.getStringExtra("videoUri")?.takeIf { it.isNotBlank() }

        Log.d(
            TAG,
            "[R_TRACE][REMINDER_ACTIVITY] key=$correlationKey id=$reminderId triggeredAt=$triggeredAt " +
                "imageUri=${imageUriString?.take(60)} " +
                "audioUri=${audioUriString?.take(60)} " +
                "videoUri=${videoUriString?.take(60)}"
        )

        lifecycleScope.launch {
            val imageUri = resolvePlayableUri(imageUriString, "${reminderId}_photo")
            Log.d(TAG, "[R_TRACE][REMINDER_ACTIVITY] imageUri resolved=${imageUri != null}")

            val audioUri = resolvePlayableUri(
                raw = audioUriString,
                cacheFileName = "${reminderId}_audio",
                requireCache = true
            )
            Log.d(TAG, "[R_TRACE][REMINDER_ACTIVITY] audioUri resolved=${audioUri != null} (raw was ${if (audioUriString != null) "present" else "null"})")

            val videoUri = resolvePlayableUri(
                raw = videoUriString,
                cacheFileName = "${reminderId}_video",
                requireCache = true
            )
            Log.d(TAG, "[R_TRACE][REMINDER_ACTIVITY] videoUri resolved=${videoUri != null} (raw was ${if (videoUriString != null) "present" else "null"})")

            // Graceful degradation: if the reminder is video-only and
            // the video cache failed, skip — there's nothing to show.
            if (!videoUriString.isNullOrBlank() && videoUri == null && imageUri == null) {
                Log.w(TAG, "[R_TRACE][REMINDER_ACTIVITY] Video-only reminder but video cache failed — skipping")
                finishReminderPlayback(reminderId)
                return@launch
            }

            // For photo+audio: if audio fails, still show the photo.
            // Only skip if audio is the ONLY media and it failed.
            if (!audioUriString.isNullOrBlank() && audioUri == null && imageUri == null && videoUri == null) {
                Log.w(TAG, "[R_TRACE][REMINDER_ACTIVITY] Audio-only reminder but audio cache failed — skipping at activity level")
                finishReminderPlayback(reminderId)
                return@launch
            }

            if (audioUri == null && audioUriString != null) {
                Log.w(TAG, "[R_TRACE][REMINDER_ACTIVITY] Audio cache failed for id=$reminderId, degrading to photo-only")
            }

            setContent {
                Relapse_WatchTheme {
                    ReminderScreen(
                        correlationKey = correlationKey,
                        title = title,
                        body = body,
                        imageUri = imageUri,
                        audioUri = audioUri,
                        videoUri = videoUri,
                        onPlaybackFinished = {
                            finishReminderPlayback(reminderId)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            markFinished(currentReminderId)
        }
        super.onDestroy()
    }

    private fun finishReminderPlayback(reminderId: String) {
        val launchedNext = markFinished(reminderId)
        if (!launchedNext) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )
        }
        finish()
    }

    private fun markFinished(id: String): Boolean {
        val reminderId = id.ifBlank { currentReminderId }
        if (reminderId.isNotBlank() && finishReported.compareAndSet(false, true)) {
            return reminderPlaybackQueueManager.onPlaybackFinished(reminderId)
        }
        return false
    }

    private suspend fun resolvePlayableUri(
        raw: String?,
        cacheFileName: String,
        requireCache: Boolean = false
    ): Uri? {
        if (raw.isNullOrBlank()) return null

        val cachedFile = mediaCacheManager.getCachedFile(cacheFileName)
        if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
            Log.d(TAG, "[R_TRACE][RESOLVE] Cache hit: $cacheFileName (${cachedFile.length()} bytes)")
            return Uri.fromFile(cachedFile)
        }

        if (requireCache) {
            Log.d(TAG, "[R_TRACE][RESOLVE] Cache miss for $cacheFileName, downloading...")
            val result = mediaCacheManager.downloadMedia(raw, cacheFileName)
            if (result.isSuccess) {
                Log.d(TAG, "[R_TRACE][RESOLVE] Download success: $cacheFileName")
                return result.getOrNull()?.let { Uri.fromFile(it) }
            }
            Log.w(TAG, "[R_TRACE][RESOLVE] Download failed for $cacheFileName: ${result.exceptionOrNull()?.message}")
            return null
        }

        return try {
            when {
                raw.startsWith("gs://") -> {
                    val downloadUrl = FirebaseStorage.getInstance()
                        .getReferenceFromUrl(raw)
                        .downloadUrl
                        .await()
                    downloadUrl
                }
                else -> Uri.parse(raw)
            }
        } catch (_: Exception) {
            Uri.parse(raw)
        }
    }

    companion object {
        private const val TAG = "ReminderActivity"
    }
}
