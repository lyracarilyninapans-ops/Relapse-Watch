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

    /**
     * Observe the caregiver's watchPairing/current document status.
     * Emits the raw status string (e.g. "paired", "unpaired") whenever it changes.
     * Used post-pairing to detect phone-initiated unpair.
     */
    fun observeCaregiverPairingStatus(caregiverUid: String): Flow<String?> = callbackFlow {
        val docRef = firestore.collection("users")
            .document(caregiverUid)
            .collection("watchPairing")
            .document("current")
        val listener: ListenerRegistration = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.data?.get("status") as? String)
        }
        awaitClose { listener.remove() }
    }

    /**
     * Update the caregiver's watchPairing/current document to 'unpaired'
     * so the phone app reflects the unpairing.
     */
    suspend fun unpairCaregiver(caregiverUid: String): Result<Unit> {
        return try {
            firestore.collection("users")
                .document(caregiverUid)
                .collection("watchPairing")
                .document("current")
                .set(
                    mapOf(
                        "pairingCode" to "",
                        "watchId" to null,
                        "pairedAt" to null,
                        "status" to "unpaired"
                    )
                ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Observe the patient document directly from Firestore.
     * Returns a flow of the raw document data map whenever it changes.
     * This lets the watch pick up name/age/notes/photo edits made on the phone.
     */
    fun observePatientDocument(caregiverUid: String, patientId: String): Flow<Map<String, Any>?> = callbackFlow {
        val docRef = firestore.collection("users")
            .document(caregiverUid)
            .collection("patients")
            .document(patientId)
        val listener: ListenerRegistration = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                // Don't close — emit null so the collector continues retrying.
                trySend(null)
                return@addSnapshotListener
            }
            trySend(snapshot?.data)
        }
        awaitClose { listener.remove() }
    }
}
