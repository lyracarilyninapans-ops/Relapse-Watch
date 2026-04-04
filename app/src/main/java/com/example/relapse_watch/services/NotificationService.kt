package com.example.relapse_watch.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.relapse_watch.R
import com.example.relapse_watch.presentation.MainActivity
import com.example.relapse_watch.presentation.ReminderActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationService @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    init {
        createChannels()
    }

    private fun createChannels() {
        val alertChannel = NotificationChannel(
            CHANNEL_ALERT,
            "Safety Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts for safe zone exits and critical events"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500)
        }

        val reminderChannel = NotificationChannel(
            CHANNEL_REMINDER,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Geo-triggered reminders"
            enableVibration(true)
        }

        val syncChannel = NotificationChannel(
            CHANNEL_SYNC,
            "Sync Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Data sync status updates"
        }

        notificationManager.createNotificationChannels(
            listOf(alertChannel, reminderChannel, syncChannel)
        )
    }

    fun showSafeZoneExitAlert() {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Safe Zone Alert")
            .setContentText("You have left the safe zone")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()

        notificationManager.notify(NOTIFICATION_SAFE_ZONE, notification)
    }

    fun showSafeZoneEnterNotification() {
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Safe Zone")
            .setContentText("You are back in the safe zone")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_SAFE_ZONE, notification)
    }

    fun showReminderNotification(reminderId: String, title: String, body: String) {
        val intent = Intent(context, ReminderActivity::class.java).apply {
            putExtra("reminderId", reminderId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, reminderId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(reminderId.hashCode(), notification)
    }

    fun showSyncFailedNotification() {
        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Sync Issue")
            .setContentText("Data will sync when connection is restored")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_SYNC, notification)
    }

    /**
     * Shows a high-priority notification with a fullScreenIntent that opens
     * PreNavigationActivity (countdown + vibration → Google Maps walking nav).
     *
     * On Wear OS a fullScreenIntent is displayed immediately as a full-screen
     * Activity, bypassing background activity-start restrictions (Android 12+).
     */
    fun showSafeZoneNavigationNotification(safeZoneLat: Double, safeZoneLng: Double) {
        val navIntent = Intent(
            context,
            com.example.relapse_watch.presentation.PreNavigationActivity::class.java
        ).apply {
            putExtra("safe_zone_lat", safeZoneLat)
            putExtra("safe_zone_lng", safeZoneLng)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_NAVIGATION_REQUEST,
            navIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Outside Safe Zone")
            .setContentText("Navigating you back to the safe zone")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        notificationManager.notify(NOTIFICATION_NAVIGATION, notification)
    }

    fun dismissNavigationNotification() {
        notificationManager.cancel(NOTIFICATION_NAVIGATION)
    }

    fun dismissSafeZoneAlert() {
        notificationManager.cancel(NOTIFICATION_SAFE_ZONE)
    }

    /**
     * Shows a high-priority full-screen notification that opens
     * SafeZoneReturnActivity — tells the patient they are back
     * inside the safe zone and can stop Google Maps navigation.
     */
    fun showSafeZoneReturnNotification() {
        val returnIntent = Intent(
            context,
            com.example.relapse_watch.presentation.SafeZoneReturnActivity::class.java
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_RETURN_REQUEST,
            returnIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Back in Safe Zone")
            .setContentText("You can stop navigation now")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .setOngoing(false)
            .setVibrate(longArrayOf(0, 800, 200, 800, 200, 800))
            .build()

        // Dismiss any lingering navigation notification first
        notificationManager.cancel(NOTIFICATION_NAVIGATION)
        notificationManager.notify(NOTIFICATION_RETURN, notification)
    }

    fun dismissReturnNotification() {
        notificationManager.cancel(NOTIFICATION_RETURN)
    }

    /**
     * Shows a high-priority full-screen notification that launches
     * ReminderActivity with media playback extras.
     *
     * On Wear OS (Android 12+) calling startActivity() from a background
     * BroadcastReceiver coroutine is silently blocked. Using fullScreenIntent
     * bypasses this restriction and wakes the screen immediately.
     */
    fun showReminderPlaybackNotification(
        reminderId: String,
        triggeredAt: Long,
        title: String,
        body: String,
        imageUrl: String?,
        audioUrl: String?,
        videoUrl: String?
    ) {
        val correlationKey = "$reminderId:$triggeredAt"
        Log.d(
            TAG,
            "[R_TRACE][REMINDER_NOTIFICATION][SHOW] key=$correlationKey id=$reminderId hasImage=${!imageUrl.isNullOrBlank()} hasAudio=${!audioUrl.isNullOrBlank()} hasVideo=${!videoUrl.isNullOrBlank()}"
        )

        val intent = Intent(context, ReminderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("reminderId", reminderId)
            putExtra("triggeredAt", triggeredAt)
            putExtra("correlationKey", correlationKey)
            putExtra("title", title)
            putExtra("body", body)
            putExtra("imageUri", imageUrl ?: "")
            putExtra("audioUri", audioUrl ?: "")
            putExtra("videoUri", videoUrl ?: "")
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_REMINDER_PLAYBACK_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body.ifBlank { "Tap to view your memory reminder" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .setOngoing(false)
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .build()

        notificationManager.notify(NOTIFICATION_REMINDER_PLAYBACK, notification)
    }

    fun dismissReminderPlaybackNotification() {
        notificationManager.cancel(NOTIFICATION_REMINDER_PLAYBACK)
    }

    companion object {
        private const val TAG = "NotificationService"
        const val CHANNEL_ALERT = "alert_channel"
        const val CHANNEL_REMINDER = "reminder_channel"
        const val CHANNEL_SYNC = "sync_channel"

        private const val NOTIFICATION_SAFE_ZONE = 2001
        private const val NOTIFICATION_SYNC = 2002
        private const val NOTIFICATION_NAVIGATION = 2003
        private const val NOTIFICATION_NAVIGATION_REQUEST = 3001
        private const val NOTIFICATION_RETURN = 2004
        private const val NOTIFICATION_RETURN_REQUEST = 3002
        private const val NOTIFICATION_REMINDER_PLAYBACK = 2005
        private const val NOTIFICATION_REMINDER_PLAYBACK_REQUEST = 3003
    }
}
