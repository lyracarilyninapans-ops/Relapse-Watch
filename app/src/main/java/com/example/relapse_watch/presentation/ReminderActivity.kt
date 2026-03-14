package com.example.relapse_watch.presentation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
        val title = intent.getStringExtra("title") ?: "Reminder"
        val body = intent.getStringExtra("body") ?: ""
        val imageUriString = intent.getStringExtra("imageUri")?.takeIf { it.isNotBlank() }
        val audioUriString = intent.getStringExtra("audioUri")?.takeIf { it.isNotBlank() }
        val videoUriString = intent.getStringExtra("videoUri")?.takeIf { it.isNotBlank() }

        lifecycleScope.launch {
            val imageUri = resolvePlayableUri(imageUriString, "${reminderId}_photo")
            val audioUri = resolvePlayableUri(audioUriString, "${reminderId}_audio")
            val videoUri = resolvePlayableUri(videoUriString, "${reminderId}_video")

            setContent {
                Relapse_WatchTheme {
                    ReminderScreen(
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

    private suspend fun resolvePlayableUri(raw: String?, cacheFileName: String): Uri? {
        if (raw.isNullOrBlank()) return null

        val cachedFile = mediaCacheManager.getCachedFile(cacheFileName)
        if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
            return Uri.fromFile(cachedFile)
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
}
