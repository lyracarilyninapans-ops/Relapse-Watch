package com.example.relapse_watch.data.repository

import com.example.relapse_watch.data.local.dao.ActivityRecordDao
import com.example.relapse_watch.data.local.entity.ActivityRecordEntity
import com.example.relapse_watch.domain.model.ActivityRecord
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

class ActivityRepositoryImplTest {

    private lateinit var repository: ActivityRepositoryImpl
    private val dao: ActivityRecordDao = mock()

    @Before
    fun setup() {
        repository = ActivityRepositoryImpl(dao)
    }

    @Test
    fun `insertRecord converts domain to entity and inserts`() = runTest {
        val record = ActivityRecord(
            id = "test-id",
            patientId = "patient1",
            timestamp = 1000L,
            latitude = 1.0,
            longitude = 2.0,
            eventType = "location_update"
        )

        repository.insertRecord(record)

        verify(dao).insert(any())
    }

    @Test
    fun `getPendingUpload maps entities to domain`() = runTest {
        val entities = listOf(
            ActivityRecordEntity("id1", "p1", 1000L, 1.0, 2.0, "location_update", null, false),
            ActivityRecordEntity("id2", "p1", 2000L, 1.1, 2.1, "safe_zone_exit", null, false)
        )
        whenever(dao.getPendingUpload()).thenReturn(entities)

        val result = repository.getPendingUpload()

        assertEquals(2, result.size)
        assertEquals("id1", result[0].id)
        assertEquals("safe_zone_exit", result[1].eventType)
    }

    @Test
    fun `getLatestRecord returns null when empty`() = runTest {
        whenever(dao.getLatestRecord()).thenReturn(flowOf(null))

        val result = repository.getLatestRecord().first()

        assertNull(result)
    }

    @Test
    fun `markUploaded delegates to dao`() = runTest {
        val ids = listOf("id1", "id2", "id3")

        repository.markUploaded(ids)

        verify(dao).markUploaded(ids)
    }

    @Test
    fun `deleteOlderThan calculates cutoff and delegates`() = runTest {
        repository.deleteOlderThan(7)

        verify(dao).deleteOlderThan(any())
    }
}
