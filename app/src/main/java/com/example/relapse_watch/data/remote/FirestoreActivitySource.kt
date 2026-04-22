package com.example.relapse_watch.data.remote

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreActivitySource @Inject constructor(
    private val firestore: FirebaseFirestore
) {

    suspend fun uploadActivityRecords(
        caregiverUid: String,
        patientId: String,
        records: List<Map<String, Any>>
    ): Result<Unit> {
        return try {
            val basePath = "users/$caregiverUid/patients/$patientId/activityRecords"
            // Firestore batch limit is 500 writes per batch
            records.chunked(BATCH_SIZE).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { record ->
                    val docId = record["id"] as String
                    val docRef = firestore.collection(basePath).document(docId)
                    batch.set(docRef, record, SetOptions.merge())
                }
                batch.commit().await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadSafeZoneEvents(
        caregiverUid: String,
        patientId: String,
        events: List<Map<String, Any>>
    ): Result<Unit> {
        return try {
            val basePath = "users/$caregiverUid/patients/$patientId/safeZoneEvents"
            events.chunked(BATCH_SIZE).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { event ->
                    val docId = event["id"] as String
                    val docRef = firestore.collection(basePath).document(docId)
                    batch.set(docRef, event, com.google.firebase.firestore.SetOptions.merge())
                }
                batch.commit().await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateWatchStatus(
        caregiverUid: String,
        patientId: String,
        watchId: String,
        batteryLevel: Int?
    ): Result<Unit> {
        return try {
            val statusMap = mutableMapOf<String, Any>(
                "isConnected" to true,
                "lastSyncTimestamp" to Timestamp.now(),
                "watchId" to watchId
            )
            if (batteryLevel != null) {
                statusMap["batteryLevel"] = batteryLevel
            }
            // Write watchStatus as a map field on the patient document.
            // The Flutter app listens to this same document for real-time status.
            firestore.collection("users").document(caregiverUid)
                .collection("patients").document(patientId)
                .update("watchStatus", statusMap)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadDailySummary(
        caregiverUid: String,
        patientId: String,
        summary: Map<String, Any>
    ): Result<Unit> {
        return try {
            val dateKey = summary["date"] as String
            firestore.collection("users").document(caregiverUid)
                .collection("patients").document(patientId)
                .collection("dailySummaries").document(dateKey)
                .set(summary, SetOptions.merge())
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getReminderCooldownMinutes(
        caregiverUid: String,
        patientId: String
    ): Int? {
        val snapshot = firestore.collection("users").document(caregiverUid)
            .collection("patients").document(patientId)
            .get()
            .await()

        val settings = snapshot.get("settings") as? Map<*, *> ?: return null
        return (settings["reminderCooldownMinutes"] as? Number)?.toInt()
    }

    suspend fun getActiveSafeZone(
        caregiverUid: String,
        patientId: String
    ): Map<String, Any>? {
        val snapshot = firestore.collection("users").document(caregiverUid)
            .collection("patients").document(patientId)
            .collection("safeZones")
            .whereEqualTo("isActive", true)
            .limit(1)
            .get()
            .await()

        val doc = snapshot.documents.firstOrNull() ?: return null
        val data = doc.data ?: return null
        return data + mapOf("id" to doc.id)
    }

    /**
     * Real-time Firestore listener for the active safe zone.
     * Emits whenever the safeZones collection changes.
     */
    fun observeActiveSafeZone(
        caregiverUid: String,
        patientId: String
    ): Flow<Map<String, Any>?> = callbackFlow {
        val query = firestore.collection("users").document(caregiverUid)
            .collection("patients").document(patientId)
            .collection("safeZones")
            .whereEqualTo("isActive", true)
            .limit(1)
        val listener: ListenerRegistration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            val doc = snapshot?.documents?.firstOrNull()
            trySend(doc?.data?.plus("id" to doc.id))
        }
        awaitClose { listener.remove() }
    }

    /**
     * Real-time Firestore listener for active memory reminders.
     * Emits the full list whenever the collection changes.
     */
    fun observeActiveReminders(
        caregiverUid: String,
        patientId: String
    ): Flow<List<Map<String, Any>>> = callbackFlow {
        val query = firestore.collection("users").document(caregiverUid)
            .collection("patients").document(patientId)
            .collection("memoryReminders")
            .whereEqualTo("isActive", true)
        val listener: ListenerRegistration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            val results = snapshot?.documents?.mapNotNull { doc ->
                doc.data?.plus("id" to doc.id)
            } ?: emptyList()
            trySend(results)
        }
        awaitClose { listener.remove() }
    }

    companion object {
        private val json = Json

        fun activityRecordToFirestoreMap(
            id: String,
            patientId: String,
            timestamp: Long,
            latitude: Double,
            longitude: Double,
            eventType: String,
            metadataJson: String?
        ): Map<String, Any> {
            val map = mutableMapOf<String, Any>(
                "id" to id,
                "patientId" to patientId,
                "timestamp" to Timestamp(timestamp / 1000, ((timestamp % 1000) * 1_000_000).toInt()),
                "latitude" to latitude,
                "longitude" to longitude,
                "eventType" to eventType
            )
            if (metadataJson != null) {
                map["metadata"] = parseMetadataJson(metadataJson) ?: metadataJson
            }
            return map
        }

        private fun parseMetadataJson(raw: String): Map<String, Any>? {
            return try {
                val decoded = json.decodeFromString(
                    MapSerializer(String.serializer(), JsonElement.serializer()),
                    raw
                )
                decoded.mapValues { (_, value) -> value.toNativeValue() }
            } catch (_: Exception) {
                null
            }
        }

        private fun JsonElement.toNativeValue(): Any {
            return when (this) {
                is JsonObject -> this.mapValues { (_, value) -> value.toNativeValue() }
                is kotlinx.serialization.json.JsonArray -> this.map { it.toNativeValue() }
                is JsonPrimitive -> {
                    when {
                        isString -> content
                        content.equals("true", ignoreCase = true) -> true
                        content.equals("false", ignoreCase = true) -> false
                        content.toLongOrNull() != null -> content.toLong()
                        content.toDoubleOrNull() != null -> content.toDouble()
                        else -> content
                    }
                }
            }
        }

        private const val BATCH_SIZE = 500
    }
}
