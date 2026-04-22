package com.example.relapse_watch.services

import com.example.relapse_watch.data.local.dao.ActivityRecordDao
import com.example.relapse_watch.data.local.entity.ActivityRecordEntity
import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.data.remote.FirestoreActivitySource
import com.example.relapse_watch.domain.model.SafeZoneConfig
import com.example.relapse_watch.domain.model.SafeZoneEvent
import com.example.relapse_watch.domain.repository.DailySummaryRepository
import com.example.relapse_watch.domain.repository.GeoReminderRepository
import com.example.relapse_watch.domain.repository.SafeZoneRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SyncServiceTest {

    private lateinit var syncService: SyncService
    private val activityRecordDao: ActivityRecordDao = mock()
    private val firestoreActivitySource: FirestoreActivitySource = mock()
    private val preferences: WatchPreferences = mock()
    private val batteryStatusProvider: BatteryStatusProvider = mock()
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
            batteryStatusProvider,
            dailySummaryRepository,
            geoReminderRepository,
            safeZoneRepository,
            geofenceService,
            mediaCacheManager
        )

        whenever(geoReminderRepository.getActiveReminders()).thenReturn(flowOf(emptyList()))
        whenever(batteryStatusProvider.getBatteryLevelPercent()).thenReturn(null)
    }

    @Test
    fun `syncActivityData skips when not paired`() = runTest {
        whenever(preferences.isPaired).thenReturn(flowOf(false))
        whenever(preferences.caregiverUid).thenReturn(flowOf(""))
        whenever(preferences.patientId).thenReturn(flowOf(""))
        whenever(preferences.watchId).thenReturn(flowOf(""))
        whenever(geoReminderRepository.syncFromFirestore(any(), any())).thenReturn(Result.success(Unit))
        whenever(firestoreActivitySource.updateWatchStatus(any(), any(), any(), any())).thenReturn(Result.success(Unit))

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
        whenever(geoReminderRepository.syncFromFirestore(any(), any())).thenReturn(Result.success(Unit))
        whenever(firestoreActivitySource.updateWatchStatus(any(), any(), any(), any())).thenReturn(Result.success(Unit))

        val result = syncService.syncActivityData()

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
    }

    @Test
    fun `syncActivityData forwards battery level in heartbeat`() = runTest {
        whenever(preferences.isPaired).thenReturn(flowOf(true))
        whenever(preferences.caregiverUid).thenReturn(flowOf("uid123"))
        whenever(preferences.patientId).thenReturn(flowOf("patient123"))
        whenever(preferences.watchId).thenReturn(flowOf("watch1"))
        whenever(activityRecordDao.getPendingUpload()).thenReturn(emptyList())
        whenever(geoReminderRepository.syncFromFirestore(any(), any())).thenReturn(Result.success(Unit))
        whenever(firestoreActivitySource.updateWatchStatus(any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(batteryStatusProvider.getBatteryLevelPercent()).thenReturn(64)

        val result = syncService.syncActivityData()

        assertTrue(result.isSuccess)
        verify(firestoreActivitySource).updateWatchStatus(
            caregiverUid = eq("uid123"),
            patientId = eq("patient123"),
            watchId = eq("watch1"),
            batteryLevel = eq(64)
        )
    }

    @Test
    fun `syncActivityData uploads and marks records`() = runTest {
        whenever(preferences.isPaired).thenReturn(flowOf(true))
        whenever(preferences.caregiverUid).thenReturn(flowOf("uid123"))
        whenever(preferences.patientId).thenReturn(flowOf("patient123"))
        whenever(preferences.watchId).thenReturn(flowOf("watch1"))
        whenever(geoReminderRepository.syncFromFirestore(any(), any())).thenReturn(Result.success(Unit))

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
        whenever(geoReminderRepository.syncFromFirestore(any(), any())).thenReturn(Result.success(Unit))
        whenever(firestoreActivitySource.updateWatchStatus(any(), any(), any(), any())).thenReturn(Result.success(Unit))

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

    @Test
    fun `syncActivityData propagates reminder sync failure`() = runTest {
        whenever(preferences.isPaired).thenReturn(flowOf(true))
        whenever(preferences.caregiverUid).thenReturn(flowOf("uid123"))
        whenever(preferences.patientId).thenReturn(flowOf("patient123"))
        whenever(preferences.watchId).thenReturn(flowOf("watch1"))
        whenever(activityRecordDao.getPendingUpload()).thenReturn(emptyList())
        whenever(firestoreActivitySource.updateWatchStatus(any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(geoReminderRepository.syncFromFirestore(any(), any()))
            .thenReturn(Result.failure(Exception("Reminder sync failed")))

        val result = syncService.syncActivityData()

        assertTrue(result.isFailure)
    }

    @Test
    fun `syncActivityData single flight skips overlapping calls`() = runTest {
        whenever(preferences.isPaired).thenReturn(flowOf(true))
        whenever(preferences.caregiverUid).thenReturn(flowOf("uid123"))
        whenever(preferences.patientId).thenReturn(flowOf("patient123"))
        whenever(preferences.watchId).thenReturn(flowOf("watch1"))
        whenever(firestoreActivitySource.updateWatchStatus(any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(geoReminderRepository.syncFromFirestore(any(), any())).thenReturn(Result.success(Unit))

        val records = listOf(
            ActivityRecordEntity("id1", "patient123", 1000L, 1.0, 2.0, "location_update", null, false)
        )
        whenever(activityRecordDao.getPendingUpload()).thenReturn(records)

        val uploadStarted = CountDownLatch(1)
        val allowUploadFinish = CountDownLatch(1)
        whenever(firestoreActivitySource.uploadActivityRecords(any(), any(), any())).thenAnswer {
            uploadStarted.countDown()
            allowUploadFinish.await(1, TimeUnit.SECONDS)
            Result.success(Unit)
        }

        val first = async(Dispatchers.Default) { syncService.syncActivityData() }
        assertTrue(uploadStarted.await(1, TimeUnit.SECONDS))

        val second = async(Dispatchers.Default) { syncService.syncActivityData() }
        val secondResult = second.await()
        assertTrue(secondResult.isSuccess)
        assertEquals(0, secondResult.getOrNull())

        allowUploadFinish.countDown()
        first.await()

        verify(firestoreActivitySource, times(1)).uploadActivityRecords(any(), any(), any())
    }

    @Test
    fun `syncActivityData uploads pending safe zone events and marks uploaded`() = runTest {
        whenever(preferences.isPaired).thenReturn(flowOf(true))
        whenever(preferences.caregiverUid).thenReturn(flowOf("uid123"))
        whenever(preferences.patientId).thenReturn(flowOf("patient123"))
        whenever(preferences.watchId).thenReturn(flowOf("watch1"))
        whenever(activityRecordDao.getPendingUpload()).thenReturn(emptyList())
        whenever(firestoreActivitySource.updateWatchStatus(any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(geoReminderRepository.syncFromFirestore(any(), any())).thenReturn(Result.success(Unit))
        whenever(firestoreActivitySource.getActiveSafeZone(any(), any())).thenReturn(null)
        whenever(safeZoneRepository.getActiveSafeZone()).thenReturn(flowOf(null))
        whenever(dailySummaryRepository.getSummaryForDate(any())).thenReturn(flowOf(null))

        val events = listOf(
            SafeZoneEvent(
                id = "event1",
                safeZoneId = "zoneA",
                eventType = "safe_zone_exit",
                timestamp = 1000L,
                latitude = 1.0,
                longitude = 2.0
            )
        )
        whenever(safeZoneRepository.getPendingEventUpload()).thenReturn(events)
        whenever(firestoreActivitySource.uploadSafeZoneEvents(any(), any(), any())).thenReturn(Result.success(Unit))

        val result = syncService.syncActivityData()

        assertTrue(result.isSuccess)
        verify(firestoreActivitySource).uploadSafeZoneEvents(any(), any(), any())
        verify(safeZoneRepository).markEventsUploaded(listOf("event1"))
    }

    @Test
    fun `syncActivityData keeps safe zone events pending when upload fails`() = runTest {
        whenever(preferences.isPaired).thenReturn(flowOf(true))
        whenever(preferences.caregiverUid).thenReturn(flowOf("uid123"))
        whenever(preferences.patientId).thenReturn(flowOf("patient123"))
        whenever(preferences.watchId).thenReturn(flowOf("watch1"))
        whenever(activityRecordDao.getPendingUpload()).thenReturn(emptyList())
        whenever(firestoreActivitySource.updateWatchStatus(any(), any(), any(), any())).thenReturn(Result.success(Unit))
        whenever(geoReminderRepository.syncFromFirestore(any(), any())).thenReturn(Result.success(Unit))
        whenever(firestoreActivitySource.getActiveSafeZone(any(), any())).thenReturn(null)
        whenever(safeZoneRepository.getActiveSafeZone()).thenReturn(flowOf(null))
        whenever(dailySummaryRepository.getSummaryForDate(any())).thenReturn(flowOf(null))

        val events = listOf(
            SafeZoneEvent(
                id = "event2",
                safeZoneId = "zoneA",
                eventType = "safe_zone_enter",
                timestamp = 2000L,
                latitude = 1.1,
                longitude = 2.1
            )
        )
        whenever(safeZoneRepository.getPendingEventUpload()).thenReturn(events)
        whenever(firestoreActivitySource.uploadSafeZoneEvents(any(), any(), any()))
            .thenReturn(Result.failure(Exception("safe zone upload failed")))

        val result = syncService.syncActivityData()

        assertTrue(result.isSuccess)
        verify(firestoreActivitySource).uploadSafeZoneEvents(any(), any(), any())
        verify(safeZoneRepository, never()).markEventsUploaded(any())
    }

    @Test
    fun `syncSafeZoneFromFirestore clears inside state when remote safe zone is missing`() = runTest {
        whenever(firestoreActivitySource.getActiveSafeZone("uid123", "patient123")).thenReturn(null)
        whenever(safeZoneRepository.getActiveSafeZone()).thenReturn(
            flowOf(
                SafeZoneConfig(
                    id = "zone1",
                    centerLat = 1.0,
                    centerLng = 2.0,
                    radiusMeters = 100,
                    isActive = true
                )
            )
        )

        val result = syncService.syncSafeZoneFromFirestore("uid123", "patient123")

        assertTrue(result)
        verify(safeZoneRepository).clearActiveSafeZone()
        verify(preferences).setSafeZoneRadius(0)
        verify(preferences).clearInsideSafeZone()
        verify(geofenceService).removeGeofence("zone1")
    }

    @Test
    fun `syncSafeZoneFromFirestore clears inside state when safe zone is inactive`() = runTest {
        whenever(firestoreActivitySource.getActiveSafeZone("uid123", "patient123")).thenReturn(
            mapOf(
                "id" to "zone2",
                "centerLat" to 10.0,
                "centerLng" to 20.0,
                "radiusMeters" to 250,
                "isActive" to false,
                "alarmEnabled" to true,
                "vibrationEnabled" to true
            )
        )
        whenever(safeZoneRepository.getActiveSafeZone()).thenReturn(flowOf(null))

        val result = syncService.syncSafeZoneFromFirestore("uid123", "patient123")

        assertTrue(result)
        verify(safeZoneRepository).updateFromFirestore(any())
        verify(preferences).setSafeZoneRadius(0)
        verify(preferences).clearInsideSafeZone()
        verify(geofenceService).removeGeofence("zone2")
        verify(geofenceService, never()).registerSafeZone(any())
    }

    @Test
    fun `syncSafeZoneFromFirestore skips re-registration when config unchanged`() = runTest {
        val unchanged = SafeZoneConfig(
            id = "zone1",
            centerLat = 10.0,
            centerLng = 20.0,
            radiusMeters = 250,
            isActive = true,
            alarmEnabled = true,
            vibrationEnabled = true
        )
        whenever(firestoreActivitySource.getActiveSafeZone("uid123", "patient123")).thenReturn(
            mapOf(
                "id" to unchanged.id,
                "centerLat" to unchanged.centerLat,
                "centerLng" to unchanged.centerLng,
                "radiusMeters" to unchanged.radiusMeters,
                "isActive" to unchanged.isActive,
                "alarmEnabled" to unchanged.alarmEnabled,
                "vibrationEnabled" to unchanged.vibrationEnabled
            )
        )
        whenever(safeZoneRepository.getActiveSafeZone()).thenReturn(flowOf(unchanged))

        val result = syncService.syncSafeZoneFromFirestore("uid123", "patient123")

        assertTrue(result)
        verify(safeZoneRepository).updateFromFirestore(any())
        verify(preferences).setSafeZoneRadius(250)
        verify(geofenceService, never()).removeGeofence("zone1")
        verify(geofenceService, never()).registerSafeZone(any())
    }

    @Test
    fun `syncSafeZoneFromFirestore re-registers when active config changed`() = runTest {
        val current = SafeZoneConfig(
            id = "zone1",
            centerLat = 10.0,
            centerLng = 20.0,
            radiusMeters = 200,
            isActive = true,
            alarmEnabled = true,
            vibrationEnabled = true
        )
        whenever(firestoreActivitySource.getActiveSafeZone("uid123", "patient123")).thenReturn(
            mapOf(
                "id" to "zone1",
                "centerLat" to 10.0,
                "centerLng" to 20.0,
                "radiusMeters" to 250,
                "isActive" to true,
                "alarmEnabled" to true,
                "vibrationEnabled" to true
            )
        )
        whenever(safeZoneRepository.getActiveSafeZone()).thenReturn(flowOf(current))
        whenever(geofenceService.registerSafeZone(any())).thenReturn(Result.success(Unit))

        val result = syncService.syncSafeZoneFromFirestore("uid123", "patient123")

        assertTrue(result)
        verify(safeZoneRepository).updateFromFirestore(any())
        verify(preferences).setSafeZoneRadius(250)
        verify(geofenceService).removeGeofence("zone1")
        verify(geofenceService).registerSafeZone(any())
    }
}
