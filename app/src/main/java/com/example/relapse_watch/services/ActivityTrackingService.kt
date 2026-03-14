package com.example.relapse_watch.services

import android.util.Log
import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.domain.model.ActivityRecord
import com.example.relapse_watch.domain.model.DailySummary
import com.example.relapse_watch.domain.model.EventTypes
import com.example.relapse_watch.domain.model.LocationPoint
import com.example.relapse_watch.domain.repository.ActivityRepository
import com.example.relapse_watch.domain.repository.DailySummaryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivityTrackingService @Inject constructor(
    private val locationService: LocationService,
    private val activityRepository: ActivityRepository,
    private val dailySummaryRepository: DailySummaryRepository,
    private val preferences: WatchPreferences
) {

    fun startTracking(intervalMs: Long = 30_000L): Flow<LocationPoint> {
        return locationService.getLocationUpdates(intervalMs)
    }

    suspend fun recordLocationUpdate(point: LocationPoint) {
        val patientId = preferences.patientId.first()
        if (patientId.isBlank()) {
            Log.w(TAG, "Dropping location update: patientId is blank")
            return
        }

        val record = ActivityRecord(
            id = UUID.randomUUID().toString(),
            patientId = patientId,
            timestamp = point.timestamp,
            latitude = point.latitude,
            longitude = point.longitude,
            eventType = EventTypes.LOCATION_UPDATE,
            metadata = point.accuracy?.let { mapOf("accuracy" to it) }
        )
        activityRepository.insertRecord(record)
    }

    suspend fun recordSafeZoneEvent(eventType: String, point: LocationPoint) {
        val patientId = preferences.patientId.first()
        if (patientId.isBlank()) {
            Log.w(TAG, "Dropping safe-zone event: patientId is blank")
            return
        }

        val record = ActivityRecord(
            id = UUID.randomUUID().toString(),
            patientId = patientId,
            timestamp = point.timestamp,
            latitude = point.latitude,
            longitude = point.longitude,
            eventType = eventType
        )
        activityRepository.insertRecord(record)
    }

    suspend fun recordReminderTriggered(reminderId: String, point: LocationPoint) {
        val patientId = preferences.patientId.first()
        if (patientId.isBlank()) {
            Log.w(TAG, "Dropping reminder event: patientId is blank")
            return
        }

        val record = ActivityRecord(
            id = UUID.randomUUID().toString(),
            patientId = patientId,
            timestamp = point.timestamp,
            latitude = point.latitude,
            longitude = point.longitude,
            eventType = EventTypes.REMINDER_TRIGGERED,
            metadata = mapOf("reminderId" to reminderId)
        )
        activityRepository.insertRecord(record)
    }

    suspend fun updateDailySummary() {
        val today = LocalDate.now()
        val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val records = activityRepository.getRecordsByDateRange(startOfDay, endOfDay).first()
        val locationRecords = records.filter { it.eventType == EventTypes.LOCATION_UPDATE }

        val summary = DailySummary(
            date = today.toString(),
            distanceMeters = computeTotalDistance(locationRecords),
            activeMinutes = 0,
            placesVisited = computeDistinctPlaces(locationRecords),
            safeZoneExits = records.count { it.eventType == EventTypes.SAFE_ZONE_EXIT },
            remindersTriggered = records.count { it.eventType == EventTypes.REMINDER_TRIGGERED },
            totalEvents = records.size,
            stepCount = 0
        )
        dailySummaryRepository.upsert(summary)
    }

    private fun computeTotalDistance(locationRecords: List<ActivityRecord>): Double {
        if (locationRecords.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until locationRecords.size) {
            val prev = locationRecords[i - 1]
            val curr = locationRecords[i]
            total += locationService.calculateDistance(
                LocationPoint(prev.latitude, prev.longitude, prev.timestamp),
                LocationPoint(curr.latitude, curr.longitude, curr.timestamp)
            )
        }
        return total
    }

    private fun computeDistinctPlaces(locationRecords: List<ActivityRecord>, clusterRadiusMeters: Float = 100f): Int {
        if (locationRecords.isEmpty()) return 0
        val clusters = mutableListOf<LocationPoint>()
        for (record in locationRecords) {
            val point = LocationPoint(record.latitude, record.longitude, record.timestamp)
            val isNew = clusters.none { locationService.calculateDistance(it, point) < clusterRadiusMeters }
            if (isNew) clusters.add(point)
        }
        return clusters.size
    }

    companion object {
        private const val TAG = "ActivityTracking"
    }
}
