package com.example.relapse_watch.data.repository

import android.util.Log
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
                    patientName = data["patientName"] as? String ?: "",
                    watchId = data["watchId"] as? String ?: ""
                )
            }
        }
    }

    override suspend fun confirmPairing(caregiverUid: String, patientId: String, patientName: String, watchId: String): Result<Unit> {
        return try {
            // Store patient info BEFORE setting isPaired so the UI renders
            // with the correct name instead of briefly showing the placeholder.
            preferences.setPatientInfo(name = patientName, id = patientId)
            preferences.setPaired(isPaired = true, caregiverUid = caregiverUid, watchId = watchId)
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

    override suspend fun deletePairingEntry(code: String): Result<Unit> {
        return firestorePairingSource.deletePairingEntry(code)
    }

    override suspend fun unpairCaregiver(caregiverUid: String): Result<Unit> {
        return firestorePairingSource.unpairCaregiver(caregiverUid)
    }

    override fun isPaired(): Flow<Boolean> {
        return preferences.isPaired
    }

    override fun observeRemoteUnpairCommand(pairingCode: String): Flow<Boolean> {
        return firestorePairingSource.observePairingStatus(pairingCode)
            .map { data -> data?.get("status") == "unpaired" }
    }

    override fun observeCaregiverUnpairCommand(caregiverUid: String): Flow<Boolean> {
        return firestorePairingSource.observeCaregiverPairingStatus(caregiverUid)
            .map { status -> status == "unpaired" }
    }

    override suspend fun confirmRemoteUnpairFromServer(pairingCode: String, caregiverUid: String): Boolean {
        return try {
            val codeStatus = firestorePairingSource.fetchPairingCodeStatusFromServer(pairingCode)
            if (codeStatus == "unpaired") {
                Log.d(TAG, "SERVER_UNPAIR_CONFIRM codeStatus=unpaired codePresent=${pairingCode.isNotBlank()}")
                return true
            }

            val caregiverStatus = firestorePairingSource.fetchCaregiverPairingStatusFromServer(caregiverUid)
            val confirmed = caregiverStatus == "unpaired"
            Log.d(
                TAG,
                "SERVER_UNPAIR_CONFIRM codeStatus=${codeStatus ?: "null"} caregiverStatus=${caregiverStatus ?: "null"} confirmed=$confirmed codePresent=${pairingCode.isNotBlank()} uidPresent=${caregiverUid.isNotBlank()}"
            )
            confirmed
        } catch (e: Exception) {
            Log.w(TAG, "SERVER_UNPAIR_CONFIRM failed", e)
            false
        }
    }

    override fun observePatientDocument(caregiverUid: String, patientId: String): Flow<Map<String, Any>?> {
        return firestorePairingSource.observePatientDocument(caregiverUid, patientId)
    }
}

private const val TAG = "PairingRepository"
