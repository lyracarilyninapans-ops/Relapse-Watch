package com.example.relapse_watch.services

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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

    private fun getActivityPendingIntent(
        requestCode: Int,
        intent: Intent,
        flags: Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    ): PendingIntent {
        if (Build.VERSION.SDK_INT >= 35) {
            val options = ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
            return PendingIntent.getActivity(context, requestCode, intent, flags, options.toBundle())
        }

        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }

    fun showSafeZoneExitAlert() {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = getActivityPendingIntent(
            requestCode = 0,
            intent = intent
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
        showSafeZoneReturnNotification()
    }

    fun showReminderNotification(reminderId: String, title: String, body: String) {
        val intent = Intent(context, ReminderActivity::class.java).apply {
            putExtra("reminderId", reminderId)
        }
        val pendingIntent = getActivityPendingIntent(
            requestCode = reminderId.hashCode(),
            intent = intent
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
        val requestCode = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
        val navIntent = Intent(
            context,
            com.example.relapse_watch.presentation.PreNavigationActivity::class.java
        ).apply {
            putExtra("safe_zone_lat", safeZoneLat)
            putExtra("safe_zone_lng", safeZoneLng)
            putExtra("navigation_notification_ts", System.currentTimeMillis())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val fullScreenPendingIntent = getActivityPendingIntent(
            requestCode = requestCode,
            intent = navIntent
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Outside Safe Zone")
            .setContentText("Navigating you back to the safe zone")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .setOngoing(false)
            .setVibrate(longArrayOf(0, 700, 200, 700))
            .build()

        // Dismiss existing exit notification so this posts as a fresh alert
        notificationManager.cancel(NOTIFICATION_NAVIGATION)
        notificationManager.notify(NOTIFICATION_NAVIGATION, notification)
        Log.d(TAG, "Safe-zone exit notification posted requestCode=$requestCode")
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
        val requestCode = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
        val returnIntent = Intent(
            context,
            com.example.relapse_watch.presentation.SafeZoneReturnActivity::class.java
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("return_notification_ts", System.currentTimeMillis())
        }

        val fullScreenPendingIntent = getActivityPendingIntent(
            requestCode = requestCode,
            intent = returnIntent
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
            .setTimeoutAfter(SAFE_ZONE_RETURN_AUTO_DISMISS_MS)
            .setVibrate(longArrayOf(0, 800, 200, 800, 200, 800))
            .build()

        // Dismiss lingering notifications first so this posts as a fresh alert
        notificationManager.cancel(NOTIFICATION_NAVIGATION)
        notificationManager.cancel(NOTIFICATION_RETURN)
        notificationManager.notify(NOTIFICATION_RETURN, notification)
        Log.d(TAG, "Safe-zone return notification posted requestCode=$requestCode")
    }

    fun dismissReturnNotification() {
        notificationManager.cancel(NOTIFICATION_RETURN)
    }

    /**
     * Shows a high-priority full-screen notification that launches
     * PreReminderActivity first. That screen gives a short haptic alert,
     * then transitions to ReminderActivity after 2 seconds.
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

        val intent = Intent(context, com.example.relapse_watch.presentation.PreReminderActivity::class.java).apply {
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

        val fullScreenPendingIntent = getActivityPendingIntent(
            requestCode = NOTIFICATION_REMINDER_PLAYBACK_REQUEST,
            intent = intent
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
        private const val SAFE_ZONE_RETURN_AUTO_DISMISS_MS = 10_000L
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
