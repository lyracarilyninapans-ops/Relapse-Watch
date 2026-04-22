package com.example.relapse_watch.services

import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.domain.model.GeoReminder
import com.example.relapse_watch.domain.model.LocationPoint
import com.example.relapse_watch.domain.repository.GeoReminderRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ReminderPlaybackQueueManagerTest {

    private val notificationService: NotificationService = mock()
    private val geoReminderRepository: GeoReminderRepository = mock()
    private val activityTrackingService: ActivityTrackingService = mock()
    private val watchPreferences: WatchPreferences = mock()
    private val mediaCacheManager: MediaCacheManager = mock()

    @Test
    fun `enqueue skips duplicate reminder id while active`() = runTest {
        whenever(watchPreferences.reminderCooldownMinutes).thenReturn(flowOf(30))
        whenever(geoReminderRepository.getReminder("reminder1")).thenReturn(
            GeoReminder(
                id = "reminder1",
                title = "Take medication",
                body = "Body",
                latitude = 1.0,
                longitude = 1.0,
                radiusMeters = 100,
                lastTriggeredAt = 1000L
            )
        )

        val manager = ReminderPlaybackQueueManager(
            notificationService,
            geoReminderRepository,
            activityTrackingService,
            watchPreferences,
            mediaCacheManager
        )

        val request = ReminderPlaybackRequest(
            reminderId = "reminder1",
            triggeredAt = 1000L,
            location = LocationPoint(latitude = 1.1, longitude = 1.2, timestamp = 1000L),
            title = "Take medication",
            body = "Body",
            imageUrl = null,
            audioUrl = null,
            videoUrl = null
        )

        val firstEnqueue = manager.enqueue(request)
        val secondEnqueue = manager.enqueue(request)

        assertTrue(firstEnqueue)
        assertFalse(secondEnqueue)

        verify(notificationService, timeout(1000).times(1)).showReminderPlaybackNotification(
            reminderId = eq("reminder1"),
            triggeredAt = eq(1000L),
            title = eq("Take medication"),
            body = eq("Body"),
            imageUrl = isNull(),
            audioUrl = isNull(),
            videoUrl = isNull()
        )
    }

    @Test
    fun `onPlaybackFinished launches next queued reminder`() = runTest {
        whenever(watchPreferences.reminderCooldownMinutes).thenReturn(flowOf(30))
        whenever(geoReminderRepository.getReminder(any())).thenAnswer { invocation ->
            val id = invocation.arguments[0] as String
            GeoReminder(
                id = id,
                title = "Title-$id",
                body = "Body",
                latitude = 1.0,
                longitude = 1.0,
                radiusMeters = 100,
                lastTriggeredAt = if (id == "r1") 1000L else 2000L
            )
        }

        val manager = ReminderPlaybackQueueManager(
            notificationService,
            geoReminderRepository,
            activityTrackingService,
            watchPreferences,
            mediaCacheManager
        )

        manager.enqueue(
            ReminderPlaybackRequest(
                reminderId = "r1",
                triggeredAt = 1000L,
                location = LocationPoint(latitude = 1.0, longitude = 1.0, timestamp = 1000L),
                title = "R1",
                body = "Body",
                imageUrl = null,
                audioUrl = null,
                videoUrl = null
            )
        )
        manager.enqueue(
            ReminderPlaybackRequest(
                reminderId = "r2",
                triggeredAt = 2000L,
                location = LocationPoint(latitude = 1.0, longitude = 1.0, timestamp = 2000L),
                title = "R2",
                body = "Body",
                imageUrl = null,
                audioUrl = null,
                videoUrl = null
            )
        )

        verify(notificationService, timeout(1000).times(1)).showReminderPlaybackNotification(
            reminderId = eq("r1"),
            triggeredAt = eq(1000L),
            title = eq("R1"),
            body = eq("Body"),
            imageUrl = isNull(),
            audioUrl = isNull(),
            videoUrl = isNull()
        )

        manager.onPlaybackFinished("r1")

        verify(notificationService, timeout(1000).times(1)).showReminderPlaybackNotification(
            reminderId = eq("r2"),
            triggeredAt = eq(2000L),
            title = eq("R2"),
            body = eq("Body"),
            imageUrl = isNull(),
            audioUrl = isNull(),
            videoUrl = isNull()
        )
        verify(notificationService, times(1)).dismissReminderPlaybackNotification()
    }

    @Test
    fun `onPlaybackFinished with unknown id does not launch next`() = runTest {
        whenever(watchPreferences.reminderCooldownMinutes).thenReturn(flowOf(30))
        val manager = ReminderPlaybackQueueManager(
            notificationService,
            geoReminderRepository,
            activityTrackingService,
            watchPreferences,
            mediaCacheManager
        )

        val launchedNext = manager.onPlaybackFinished("unknown")

        verify(notificationService).dismissReminderPlaybackNotification()
        verify(notificationService, never()).showReminderPlaybackNotification(
            reminderId = any(),
            triggeredAt = any(),
            title = any(),
            body = any(),
            imageUrl = any(),
            audioUrl = any(),
            videoUrl = any()
        )
        assertFalse(launchedNext)
    }
}
