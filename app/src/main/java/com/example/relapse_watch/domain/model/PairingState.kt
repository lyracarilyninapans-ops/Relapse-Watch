package com.example.relapse_watch.domain.model

data class PairingState(
    val isPaired: Boolean = false,
    val pairingCode: String = "",
    val caregiverUid: String = "",
    val patientId: String = "",
    val patientName: String = "",
    val watchId: String = "",
    val pairedAt: Long? = null
)
