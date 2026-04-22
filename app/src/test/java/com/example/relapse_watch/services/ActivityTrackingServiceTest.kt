package com.example.relapse_watch.services

import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.domain.model.DailySummary
import com.example.relapse_watch.domain.model.EventTypes
import com.example.relapse_watch.domain.model.LocationPoint
import com.example.relapse_watch.domain.repository.ActivityRepository
import com.example.relapse_watch.domain.repository.DailySummaryRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ActivityTrackingServiceTest {

    private lateinit var service: ActivityTrackingService
    private val locationService: LocationService = mock()
    private val activityRepository: ActivityRepository = mock()
    private val dailySummaryRepository: DailySummaryRepository = mock()
    private val preferences: WatchPreferences = mock()

    @Before
    fun setup() {
        service = ActivityTrackingService(
            locationService,
            activityRepository,
            dailySummaryRepository,
            preferences
        )

        whenever(dailySummaryRepository.getSummaryForDate(any())).thenReturn(flowOf(null))
        whenever(activityRepository.getRecordsByDateRange(any(), any())).thenReturn(flowOf(emptyList()))
        whenever(preferences.patientId).thenReturn(flowOf("patient1"))
    }

    @Test
    fun `incremental location updates avoid repeated day-range reloads`() = runTest {
        whenever(locationService.calculateDistance(any(), any())).thenReturn(50f)

        val p1 = LocationPoint(latitude = 1.0, longitude = 1.0, timestamp = 1000L)
        val p2 = LocationPoint(latitude = 1.001, longitude = 1.001, timestamp = 2000L)

        service.updateDailySummaryWithLocation(p1)
        service.updateDailySummaryWithLocation(p2)

        verify(activityRepository, times(1)).getRecordsByDateRange(any(), any())

        val summaryCaptor = argumentCaptor<DailySummary>()
        verify(dailySummaryRepository, times(2)).upsert(summaryCaptor.capture())

        val first = summaryCaptor.firstValue
        val second = summaryCaptor.secondValue

        assertEquals(1, first.totalEvents)
        assertEquals(2, second.totalEvents)
        assertEquals(50.0, second.distanceMeters, 0.001)
        assertTrue(second.placesVisited >= 1)
    }

    @Test
    fun `safe zone exit increments summary counters`() = runTest {
        val today = LocalDate.now().toString()
        whenever(dailySummaryRepository.getSummaryForDate(any())).thenReturn(flowOf(DailySummary(date = today)))

        val point = LocationPoint(latitude = 1.0, longitude = 1.0, timestamp = 1000L)
        service.recordSafeZoneEvent(EventTypes.SAFE_ZONE_EXIT, point)

        val summaryCaptor = argumentCaptor<DailySummary>()
        verify(dailySummaryRepository).upsert(summaryCaptor.capture())

        assertEquals(1, summaryCaptor.firstValue.safeZoneExits)
        assertEquals(1, summaryCaptor.firstValue.totalEvents)
    }
}
