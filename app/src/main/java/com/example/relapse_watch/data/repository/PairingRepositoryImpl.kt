package com.example.relapse_watch.data.repository

import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.data.remote.FirestorePairingSource
import com.example.relapse_watch.domain.model.PairingState
import com.example.relapse_watch.domain.repository.PairingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PairingRepositoryImpl @Inject constructor(
    private val firestorePairingSource: FirestorePairingSource,
    private val preferences: WatchPreferences
) : PairingRepository {

    override suspend fun createPairingEntry(code: String, watchId: String): Result<Unit> {
        preferences.setPairingCode(code)
        return firestorePairingSource.createPairingEntry(code, watchId)
    }

    override fun observePairingStatus(code: String): Flow<PairingState> {
        return firestorePairingSource.observePairingStatus(code).map { data ->
            if (data == null) {
                PairingState(isPaired = false, pairingCode = code)
            } else {
                PairingState(
                    isPaired = data["status"] == "paired",
                    pairingCode = code,
                    caregiverUid = data["caregiverUid"] as? String ?: "",
                    patientId = data["patientId"] as? String ?: "",
                    watchId = data["watchId"] as? String ?: ""
                )
            }
        }
    }

    override suspend fun confirmPairing(caregiverUid: String, patientId: String): Result<Unit> {
        return try {
            preferences.setPaired(isPaired = true, caregiverUid = caregiverUid, watchId = "")
            preferences.setPatientInfo(name = "", id = patientId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clearPairing(): Result<Unit> {
        return try {
            preferences.clearPairing()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun isPaired(): Flow<Boolean> {
        return preferences.isPaired
    }
}
