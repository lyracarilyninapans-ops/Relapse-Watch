package com.example.relapse_watch.domain.model

data class PatientInfo(
    val id: String,
    val name: String,
    val age: Int? = null,
    val notes: String? = null,
    val photoUrl: String? = null
)
