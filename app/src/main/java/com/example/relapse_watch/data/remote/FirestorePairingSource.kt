package com.example.relapse_watch.data.remote

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestorePairingSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {

    suspend fun createPairingEntry(code: String, watchId: String): Result<Unit> {
        return try {
            firestore.collection("watchPairingCodes").document(code).set(
                mapOf(
                    "watchId" to watchId,
                    "status" to "pending",
                    "createdAt" to com.google.firebase.Timestamp.now()
                )
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observePairingStatus(code: String): Flow<Map<String, Any>?> = callbackFlow {
        val docRef = firestore.collection("watchPairingCodes").document(code)
        val listener: ListenerRegistration = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.data)
        }

        awaitClose { listener.remove() }
    }

    suspend fun deletePairingEntry(code: String): Result<Unit> {
        return try {
            firestore.collection("watchPairingCodes").document(code).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
