package com.example.relapse_watch.data.repository

import com.example.relapse_watch.data.remote.FirestoreActivitySource
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreActivitySourceTest {

    @Test
    fun `activityRecordToFirestoreMap converts epoch to Timestamp`() {
        val epochMs = 1700000000000L // Nov 14 2023

        val map = FirestoreActivitySource.activityRecordToFirestoreMap(
            id = "test-id",
            patientId = "patient1",
            timestamp = epochMs,
            latitude = 10.0,
            longitude = 20.0,
            eventType = "location_update",
            metadataJson = null
        )

        assertEquals("test-id", map["id"])
        assertEquals("patient1", map["patientId"])
        assertTrue(map["timestamp"] is Timestamp)
        assertEquals(10.0, map["latitude"])
        assertEquals(20.0, map["longitude"])
        assertEquals("location_update", map["eventType"])
        assertNull(map["metadata"])
    }

    @Test
    fun `activityRecordToFirestoreMap includes metadata when present`() {
        val map = FirestoreActivitySource.activityRecordToFirestoreMap(
            id = "test-id",
            patientId = "patient1",
            timestamp = 1000L,
            latitude = 10.0,
            longitude = 20.0,
            eventType = "location_update",
            metadataJson = """{"accuracy":"5.0"}"""
        )

        @Suppress("UNCHECKED_CAST")
        val metadata = map["metadata"] as Map<String, Any>
        assertEquals("5.0", metadata["accuracy"])
    }

    @Test
    fun `activityRecordToFirestoreMap timestamp conversion is accurate`() {
        val epochMs = 1500L // 1.5 seconds

        val map = FirestoreActivitySource.activityRecordToFirestoreMap(
            id = "id", patientId = "p", timestamp = epochMs,
            latitude = 0.0, longitude = 0.0, eventType = "test",
            metadataJson = null
        )

        val ts = map["timestamp"] as Timestamp
        assertEquals(1L, ts.seconds)
        assertEquals(500_000_000, ts.nanoseconds)
    }
}
