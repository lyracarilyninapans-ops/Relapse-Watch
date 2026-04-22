package com.example.relapse_watch.services

import com.example.relapse_watch.domain.model.GeoReminder
import com.example.relapse_watch.domain.model.LocationPoint
import com.example.relapse_watch.domain.repository.GeoReminderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ReminderTriggerCoordinatorTest {

    private val geoReminderRepository: GeoReminderRepository = mock()
    private val reminderPlaybackQueueManager: ReminderPlaybackQueueManager = mock()

    @Test
    fun `triggerFromProximity allows duplicate triggers`() = runTest {
        var currentReminder = GeoReminder(
            id = "r1",
            title = "Memory",
            body = "Body",
            latitude = 1.0,
            longitude = 2.0,
            radiusMeters = 100,
            lastTriggeredAt = null
        )

        whenever(geoReminderRepository.getReminder("r1")).thenAnswer { currentReminder }
        whenever(reminderPlaybackQueueManager.enqueue(any())).thenAnswer {
            currentReminder = currentReminder.copy(lastTriggeredAt = System.currentTimeMillis())
            true
        }

        val coordinator = ReminderTriggerCoordinator(
            geoReminderRepository,
            reminderPlaybackQueueManager
        )

        val point = LocationPoint(latitude = 1.1, longitude = 2.1, timestamp = 1000L)
        coordinator.triggerFromProximity(reminderId = "r1", location = point, distanceMeters = 10f)
        coordinator.triggerFromProximity(reminderId = "r1", location = point, distanceMeters = 10f)

        verify(reminderPlaybackQueueManager, times(2)).enqueue(any())
    }

    @Test
    fun `triggerFromGeofence skips when reminder is missing`() = runTest {
        whenever(geoReminderRepository.getReminder("missing")).thenReturn(null)

        val coordinator = ReminderTriggerCoordinator(
            geoReminderRepository,
            reminderPlaybackQueueManager
        )

        coordinator.triggerFromGeofence(reminderId = "missing", location = null)

        verify(reminderPlaybackQueueManager, times(0)).enqueue(any())
    }

    @Test
    fun `concurrent geofence and proximity triggers emit once`() = runTest {
        var currentReminder = GeoReminder(
            id = "r1",
            title = "Memory",
            body = "Body",
            latitude = 1.0,
            longitude = 2.0,
            radiusMeters = 100,
            lastTriggeredAt = null
        )

        whenever(geoReminderRepository.getReminder("r1")).thenAnswer { currentReminder }
        var acceptedEnqueueCount = 0
        whenever(reminderPlaybackQueueManager.enqueue(any())).thenAnswer {
            if (acceptedEnqueueCount == 0) {
                acceptedEnqueueCount++
                currentReminder = currentReminder.copy(lastTriggeredAt = System.currentTimeMillis())
                true
            } else {
                false
            }
        }

        val coordinator = ReminderTriggerCoordinator(
            geoReminderRepository,
            reminderPlaybackQueueManager
        )

        val point = LocationPoint(latitude = 1.1, longitude = 2.1, timestamp = 1000L)
        val geofenceCall = async(Dispatchers.Default) {
            coordinator.triggerFromGeofence(reminderId = "r1", location = point)
        }
        val proximityCall = async(Dispatchers.Default) {
            coordinator.triggerFromProximity(reminderId = "r1", location = point, distanceMeters = 10f)
        }

        geofenceCall.await()
        proximityCall.await()

        // Coordinator may attempt to enqueue from both sources; the queue
        // manager is responsible for accepting only one duplicate reminder.
        verify(reminderPlaybackQueueManager, times(2)).enqueue(any())
        assertEquals(1, acceptedEnqueueCount)
    }
}
