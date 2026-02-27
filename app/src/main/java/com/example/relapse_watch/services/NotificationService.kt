package com.example.relapse_watch.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

    fun dismissSafeZoneAlert() {
        notificationManager.cancel(NOTIFICATION_SAFE_ZONE)
    }

    companion object {
        const val CHANNEL_ALERT = "alert_channel"
        const val CHANNEL_REMINDER = "reminder_channel"
        const val CHANNEL_SYNC = "sync_channel"

        private const val NOTIFICATION_SAFE_ZONE = 2001
        private const val NOTIFICATION_SYNC = 2002
    }
}
