package com.example.relapse_watch.services

import android.util.Log
import com.example.relapse_watch.domain.model.GeoReminder
import com.example.relapse_watch.domain.model.SafeZoneConfig
import com.example.relapse_watch.domain.repository.GeoReminderRepository
import com.example.relapse_watch.domain.repository.SafeZoneRepository
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

@AndroidEntryPoint
class WearableDataListenerService : WearableListenerService() {

    @Inject lateinit var safeZoneRepository: SafeZoneRepository
    @Inject lateinit var geoReminderRepository: GeoReminderRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        Log.d(TAG, "Message received: ${event.path}")
        when (event.path) {
            WearableCommunicationService.PATH_SAFE_ZONE_CONFIG -> {
                handleSafeZoneConfig(event.data)
            }
            WearableCommunicationService.PATH_REMINDERS_SYNC -> {
                handleRemindersSync(event.data)
            }
            WearableCommunicationService.PATH_CONFIG_UPDATE -> {
                handleConfigUpdate(event.data)
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                Log.d(TAG, "Data changed: ${event.dataItem.uri.path}")
            }
        }
    }

    private fun handleSafeZoneConfig(data: ByteArray) {
        scope.launch {
            try {
                val json = String(data)
                val obj = Json.parseToJsonElement(json).jsonObject
                val config = SafeZoneConfig(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@launch,
                    centerLat = obj["centerLat"]?.jsonPrimitive?.double ?: return@launch,
                    centerLng = obj["centerLng"]?.jsonPrimitive?.double ?: return@launch,
                    radiusMeters = obj["radiusMeters"]?.jsonPrimitive?.int ?: return@launch,
                    isActive = true,
                    alarmEnabled = true,
                    vibrationEnabled = true
                )
                safeZoneRepository.updateFromFirestore(config)
                Log.d(TAG, "Safe zone config updated: ${config.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse safe zone config", e)
            }
        }
    }

    private fun handleRemindersSync(data: ByteArray) {
        scope.launch {
            try {
                val json = String(data)
                val array = Json.parseToJsonElement(json) as JsonArray
                val reminders = array.map { element ->
                    val obj = element.jsonObject
                    GeoReminder(
                        id = obj["id"]!!.jsonPrimitive.content,
                        title = obj["title"]!!.jsonPrimitive.content,
                        body = obj["body"]!!.jsonPrimitive.content,
                        latitude = obj["latitude"]!!.jsonPrimitive.double,
                        longitude = obj["longitude"]!!.jsonPrimitive.double,
                        radiusMeters = obj["radiusMeters"]!!.jsonPrimitive.int,
                        imageUrl = obj["imageUrl"]?.jsonPrimitive?.contentOrNull,
                        videoUrl = obj["videoUrl"]?.jsonPrimitive?.contentOrNull,
                        isActive = true
                    )
                }
                geoReminderRepository.syncFromPhone(reminders)
                Log.d(TAG, "Synced ${reminders.size} reminders from phone")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse reminders sync", e)
            }
        }
    }

    private fun handleConfigUpdate(data: ByteArray) {
        Log.d(TAG, "Config update received: ${String(data)}")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WearableDataListener"
    }
}
