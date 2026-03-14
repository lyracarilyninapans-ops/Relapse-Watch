package com.example.relapse_watch.services

import com.example.relapse_watch.data.local.dao.ActivityRecordDao
import com.example.relapse_watch.data.local.entity.ActivityRecordEntity
import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.data.remote.FirestoreActivitySource
import com.example.relapse_watch.domain.repository.DailySummaryRepository
import com.example.relapse_watch.domain.repository.GeoReminderRepository
import com.example.relapse_watch.domain.repository.SafeZoneRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SyncServiceTest {

    private lateinit var syncService: SyncService
    private val activityRecordDao: ActivityRecordDao = mock()
    private val firestoreActivitySource: FirestoreActivitySource = mock()
    private val preferences: WatchPreferences = mock()
    private val dailySummaryRepository: DailySummaryRepository = mock()
    private val geoReminderRepository: GeoReminderRepository = mock()
    private val safeZoneRepository: SafeZoneRepository = mock()
    private val geofenceService: GeofenceService = mock()
    private val mediaCacheManager: MediaCacheManager = mock()

    @Before
    fun setup() {
        syncService = SyncService(
            activityRecordDao,
            firestoreActivitySource,
            preferences,
            dailySummaryRepository,
            geoReminderRepository,
            safeZoneRepository,
            geofenceService,
            mediaCacheManager
        )
    }

    @Test
    fun `syncActivityData skips when not paired`() = runTest {
        whenever(preferences.isPaired).thenReturn(flowOf(false))
        whenever(preferences.caregiverUid).thenReturn(flowOf(""))
        whenever(preferences.patientId).thenReturn(flowOf(""))
        whenever(preferences.watchId).thenReturn(flowOf(""))

        val result = syncService.syncActivityData()

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
        verify(activityRecordDao, never()).getPendingUpload()
    }

    @Test
    fun `syncActivityData skips when no pending records`() = runTest {
        whenever(preferences.isPaired).thenReturn(flowOf(true))
        whenever(preferences.caregiverUid).thenReturn(flowOf("uid123"))
        whenever(preferences.patientId).thenReturn(flowOf("patient123"))
        whenever(preferences.watchId).thenReturn(flowOf("watch1"))
        whenever(activityRecordDao.getPendingUpload()).thenReturn(emptyList())

        val result = syncService.syncActivityData()

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
    }

    @Test
    fun `syncActivityData uploads and marks records`() = runTest {
        whenever(preferences.isPaired).thenReturn(flowOf(true))
        whenever(preferences.caregiverUid).thenReturn(flowOf("uid123"))
        whenever(preferences.patientId).thenReturn(flowOf("patient123"))
        whenever(preferences.watchId).thenReturn(flowOf("watch1"))

        val records = listOf(
            ActivityRecordEntity("id1", "patient123", 1000L, 1.0, 2.0, "location_update", null, false),
            ActivityRecordEntity("id2", "patient123", 2000L, 1.1, 2.1, "location_update", null, false)
        )
        whenever(activityRecordDao.getPendingUpload()).thenReturn(records)
        whenever(firestoreActivitySource.uploadActivityRecords(any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(firestoreActivitySource.updateWatchStatus(any(), any(), any(), any())).thenReturn(Result.success(Unit))

        val result = syncService.syncActivityData()

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull())
        verify(activityRecordDao).markUploaded(listOf("id1", "id2"))
    }

    @Test
    fun `syncActivityData returns failure when upload fails`() = runTest {
        whenever(preferences.isPaired).thenReturn(flowOf(true))
        whenever(preferences.caregiverUid).thenReturn(flowOf("uid123"))
        whenever(preferences.patientId).thenReturn(flowOf("patient123"))
        whenever(preferences.watchId).thenReturn(flowOf("watch1"))

        val records = listOf(
            ActivityRecordEntity("id1", "patient123", 1000L, 1.0, 2.0, "location_update", null, false)
        )
        whenever(activityRecordDao.getPendingUpload()).thenReturn(records)
        whenever(firestoreActivitySource.uploadActivityRecords(any(), any(), any()))
            .thenReturn(Result.failure(Exception("Network error")))

        val result = syncService.syncActivityData()

        assertTrue(result.isFailure)
        verify(activityRecordDao, never()).markUploaded(any())
    }
}
