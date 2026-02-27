package com.example.relapse_watch.data.remote

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
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
                    batch.set(docRef, record)
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
            firestore.collection("users").document(caregiverUid)
                .collection("patients").document(patientId)
                .update("watchStatus", statusMap)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
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
                map["metadata"] = metadataJson
            }
            return map
        }

        private const val BATCH_SIZE = 500
    }
}
