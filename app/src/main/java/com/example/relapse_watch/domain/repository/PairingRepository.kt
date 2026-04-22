package com.example.relapse_watch.domain.repository

import com.example.relapse_watch.domain.model.PairingState
import kotlinx.coroutines.flow.Flow

interface PairingRepository {
    suspend fun createPairingEntry(code: String, watchId: String): Result<Unit>
    fun observePairingStatus(code: String): Flow<PairingState>
    suspend fun confirmPairing(caregiverUid: String, patientId: String, patientName: String, watchId: String): Result<Unit>
    suspend fun clearPairing(): Result<Unit>
    suspend fun deletePairingEntry(code: String): Result<Unit>
    suspend fun unpairCaregiver(caregiverUid: String): Result<Unit>
    fun isPaired(): Flow<Boolean>
    fun observeRemoteUnpairCommand(pairingCode: String): Flow<Boolean>
    fun observeCaregiverUnpairCommand(caregiverUid: String): Flow<Boolean>
    suspend fun confirmRemoteUnpairFromServer(pairingCode: String, caregiverUid: String): Boolean

    /** Observe the patient Firestore document for live edits (name, age, etc.). */
    fun observePatientDocument(caregiverUid: String, patientId: String): Flow<Map<String, Any>?>
}
