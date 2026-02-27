package com.example.relapse_watch.domain.repository

import com.example.relapse_watch.domain.model.PairingState
import kotlinx.coroutines.flow.Flow

interface PairingRepository {
    suspend fun createPairingEntry(code: String, watchId: String): Result<Unit>
    fun observePairingStatus(code: String): Flow<PairingState>
    suspend fun confirmPairing(caregiverUid: String, patientId: String): Result<Unit>
    suspend fun clearPairing(): Result<Unit>
    fun isPaired(): Flow<Boolean>
}
