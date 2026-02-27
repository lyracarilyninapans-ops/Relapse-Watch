package com.example.relapse_watch.services

import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WearableCommunicationService @Inject constructor(
    private val messageClient: MessageClient,
    private val dataClient: DataClient,
    private val nodeClient: NodeClient
) {

    suspend fun sendMessage(path: String, data: ByteArray = ByteArray(0)): Boolean {
        return try {
            val nodes = nodeClient.connectedNodes.await()
            if (nodes.isEmpty()) {
                Log.d(TAG, "No connected nodes")
                return false
            }
            nodes.forEach { node ->
                messageClient.sendMessage(node.id, path, data).await()
            }
            Log.d(TAG, "Message sent to ${nodes.size} node(s) on path: $path")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message on path: $path", e)
            false
        }
    }

    suspend fun sendSafeZoneAlert(eventType: String, latitude: Double, longitude: Double) {
        val payload = Json.encodeToString(
            mapOf(
                "eventType" to eventType,
                "latitude" to latitude.toString(),
                "longitude" to longitude.toString(),
                "timestamp" to System.currentTimeMillis().toString()
            )
        )
        sendMessage(PATH_SAFE_ZONE_ALERT, payload.toByteArray())
    }

    suspend fun sendReminderTriggered(reminderId: String, title: String) {
        val payload = Json.encodeToString(
            mapOf(
                "reminderId" to reminderId,
                "title" to title,
                "timestamp" to System.currentTimeMillis().toString()
            )
        )
        sendMessage(PATH_REMINDER_TRIGGERED, payload.toByteArray())
    }

    suspend fun sendWatchStatus(batteryLevel: Int, isTracking: Boolean) {
        val dataMapRequest = PutDataMapRequest.create(PATH_WATCH_STATUS).apply {
            dataMap.putInt("batteryLevel", batteryLevel)
            dataMap.putBoolean("isTracking", isTracking)
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }
        try {
            dataClient.putDataItem(dataMapRequest.asPutDataRequest().setUrgent()).await()
            Log.d(TAG, "Watch status synced via DataLayer")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync watch status", e)
        }
    }

    suspend fun requestConfigSync() {
        sendMessage(PATH_REQUEST_CONFIG, ByteArray(0))
    }

    suspend fun isPhoneConnected(): Boolean {
        return try {
            val nodes = nodeClient.connectedNodes.await()
            nodes.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "WearableComms"
        const val PATH_SAFE_ZONE_ALERT = "/safe_zone_alert"
        const val PATH_REMINDER_TRIGGERED = "/reminder_triggered"
        const val PATH_WATCH_STATUS = "/watch_status"
        const val PATH_REQUEST_CONFIG = "/request_config"
        const val PATH_CONFIG_UPDATE = "/config_update"
        const val PATH_SAFE_ZONE_CONFIG = "/safe_zone_config"
        const val PATH_REMINDERS_SYNC = "/reminders_sync"
    }
}
