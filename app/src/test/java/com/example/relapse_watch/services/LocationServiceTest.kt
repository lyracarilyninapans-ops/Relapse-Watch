package com.example.relapse_watch.services

import com.example.relapse_watch.domain.model.LocationPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class LocationServiceTest {

    private lateinit var locationService: LocationService

    @Before
    fun setup() {
        // LocationService needs a real FusedLocationProviderClient and Context,
        // but we can test the pure calculation methods via a partially mocked instance
        locationService = LocationService(mock(), mock())
    }

    @Test
    fun `calculateDistance returns zero for same point`() {
        val point = LocationPoint(latitude = 40.7128, longitude = -74.0060, timestamp = 0L)

        val distance = locationService.calculateDistance(point, point)

        assertEquals(0f, distance, 0.1f)
    }

    @Test
    fun `calculateDistance returns positive for different points`() {
        val nyc = LocationPoint(latitude = 40.7128, longitude = -74.0060, timestamp = 0L)
        val la = LocationPoint(latitude = 34.0522, longitude = -118.2437, timestamp = 0L)

        val distance = locationService.calculateDistance(nyc, la)

        // NYC to LA is roughly 3,940 km
        assertTrue(distance > 3_900_000f)
        assertTrue(distance < 4_000_000f)
    }

    @Test
    fun `calculateBearing returns value in 0-360 range`() {
        val point1 = LocationPoint(latitude = 40.7128, longitude = -74.0060, timestamp = 0L)
        val point2 = LocationPoint(latitude = 34.0522, longitude = -118.2437, timestamp = 0L)

        val bearing = locationService.calculateBearing(point1, point2)

        assertTrue(bearing >= 0f)
        assertTrue(bearing < 360f)
    }

    @Test
    fun `calculateBearing due north is approximately 0`() {
        val south = LocationPoint(latitude = 0.0, longitude = 0.0, timestamp = 0L)
        val north = LocationPoint(latitude = 10.0, longitude = 0.0, timestamp = 0L)

        val bearing = locationService.calculateBearing(south, north)

        // Should be approximately 0 (due north)
        assertTrue(bearing < 1f || bearing > 359f)
    }
}
