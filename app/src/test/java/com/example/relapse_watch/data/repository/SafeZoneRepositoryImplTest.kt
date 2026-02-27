package com.example.relapse_watch.data.repository

import com.example.relapse_watch.data.local.dao.SafeZoneDao
import com.example.relapse_watch.data.local.entity.SafeZoneEntity
import com.example.relapse_watch.domain.model.SafeZoneConfig
import com.example.relapse_watch.domain.model.SafeZoneEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SafeZoneRepositoryImplTest {

    private lateinit var repository: SafeZoneRepositoryImpl
    private val dao: SafeZoneDao = mock()

    @Before
    fun setup() {
        repository = SafeZoneRepositoryImpl(dao)
    }

    @Test
    fun `getActiveSafeZone returns null when no active zone`() = runTest {
        whenever(dao.getActiveSafeZone()).thenReturn(flowOf(null))

        val result = repository.getActiveSafeZone().first()

        assertNull(result)
    }

    @Test
    fun `getActiveSafeZone maps entity to domain`() = runTest {
        val entity = SafeZoneEntity("zone1", 10.0, 20.0, 500, true, true, true)
        whenever(dao.getActiveSafeZone()).thenReturn(flowOf(entity))

        val result = repository.getActiveSafeZone().first()

        assertEquals("zone1", result?.id)
        assertEquals(10.0, result?.centerLat ?: 0.0, 0.001)
        assertEquals(500, result?.radiusMeters)
    }

    @Test
    fun `updateFromFirestore converts and upserts`() = runTest {
        val config = SafeZoneConfig("zone1", 10.0, 20.0, 500)

        repository.updateFromFirestore(config)

        verify(dao).upsertZone(any())
    }

    @Test
    fun `recordEvent converts and inserts`() = runTest {
        val event = SafeZoneEvent("ev1", "zone1", "safe_zone_exit", 1000L, 10.0, 20.0)

        repository.recordEvent(event)

        verify(dao).insertEvent(any())
    }
}
