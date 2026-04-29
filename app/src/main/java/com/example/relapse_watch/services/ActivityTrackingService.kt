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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivityTrackingService @Inject constructor(
    private val locationService: LocationService,
    private val activityRepository: ActivityRepository,
    private val dailySummaryRepository: DailySummaryRepository,
    private val preferences: WatchPreferences
) {

    private val summaryMutex = Mutex()
    private var cachedDate: String? = null
    private var cachedSummary: DailySummary? = null
    private var lastLocationPoint: LocationPoint? = null
    private var visitedPlaceBuckets: MutableSet<String> = mutableSetOf()

    fun startTracking(
        intervalMs: Long = 30_000L,
        profile: TrackingProfile = TrackingProfile.BALANCED
    ): Flow<LocationPoint> {
        return locationService.getLocationUpdates(intervalMs, profile)
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

        incrementEventCounters(
            safeZoneExitDelta = if (eventType == EventTypes.SAFE_ZONE_EXIT) 1 else 0,
            totalEventsDelta = 1
        )
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

        incrementEventCounters(
            reminderTriggeredDelta = 1,
            totalEventsDelta = 1
        )
    }

    suspend fun updateDailySummary() {
        val today = LocalDate.now()
        val todayString = today.toString()
        val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val records = activityRepository.getRecordsByDateRange(startOfDay, endOfDay).first().sortedBy { it.timestamp }
        val locationRecords = records.filter { it.eventType == EventTypes.LOCATION_UPDATE }

        val summary = DailySummary(
            date = todayString,
            distanceMeters = computeTotalDistance(locationRecords),
            activeMinutes = 0,
            placesVisited = computeDistinctPlaces(locationRecords),
            safeZoneExits = records.count { it.eventType == EventTypes.SAFE_ZONE_EXIT },
            remindersTriggered = records.count { it.eventType == EventTypes.REMINDER_TRIGGERED },
            totalEvents = records.size,
            stepCount = 0
        )
        dailySummaryRepository.upsert(summary)

        // Refresh in-memory incremental state from full recompute output.
        summaryMutex.withLock {
            cachedDate = todayString
            cachedSummary = summary
            lastLocationPoint = locationRecords.lastOrNull()?.toLocationPoint()
            visitedPlaceBuckets = locationRecords
                .map { placeBucket(it.latitude, it.longitude) }
                .toMutableSet()
        }
    }

    suspend fun updateDailySummaryWithLocation(point: LocationPoint) {
        summaryMutex.withLock {
            val today = LocalDate.now()
            ensureDailyState(today)

            val currentSummary = cachedSummary ?: DailySummary(date = today.toString())
            val distanceDelta = lastLocationPoint?.let { previous ->
                locationService.calculateDistance(previous, point).toDouble()
            } ?: 0.0
            val currentBucket = placeBucket(point.latitude, point.longitude)
            visitedPlaceBuckets.add(currentBucket)

            val nextSummary = currentSummary.copy(
                distanceMeters = currentSummary.distanceMeters + distanceDelta,
                placesVisited = max(currentSummary.placesVisited, visitedPlaceBuckets.size),
                totalEvents = currentSummary.totalEvents + 1
            )

            cachedSummary = nextSummary
            lastLocationPoint = point
            dailySummaryRepository.upsert(nextSummary)
        }
    }

    private suspend fun incrementEventCounters(
        safeZoneExitDelta: Int = 0,
        reminderTriggeredDelta: Int = 0,
        totalEventsDelta: Int = 0
    ) {
        summaryMutex.withLock {
            ensureDailyState(LocalDate.now())

            val currentSummary = cachedSummary ?: DailySummary(date = LocalDate.now().toString())
            val nextSummary = currentSummary.copy(
                safeZoneExits = currentSummary.safeZoneExits + safeZoneExitDelta,
                remindersTriggered = currentSummary.remindersTriggered + reminderTriggeredDelta,
                totalEvents = currentSummary.totalEvents + totalEventsDelta
            )

            cachedSummary = nextSummary
            dailySummaryRepository.upsert(nextSummary)
        }
    }

    private suspend fun ensureDailyState(today: LocalDate) {
        val date = today.toString()
        if (cachedDate == date && cachedSummary != null) {
            return
        }

        val summary = dailySummaryRepository.getSummaryForDate(date).first()
            ?: DailySummary(date = date)
        val (startOfDay, endOfDay) = todayEpochRange(today)
        val records = activityRepository
            .getRecordsByDateRange(startOfDay, endOfDay)
            .first()
            .sortedBy { it.timestamp }
        val locationRecords = records.filter { it.eventType == EventTypes.LOCATION_UPDATE }

        cachedDate = date
        cachedSummary = summary
        lastLocationPoint = locationRecords.lastOrNull()?.toLocationPoint()
        visitedPlaceBuckets = locationRecords
            .map { placeBucket(it.latitude, it.longitude) }
            .toMutableSet()
    }

    private fun todayEpochRange(today: LocalDate): Pair<Long, Long> {
        val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endOfDay = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return startOfDay to endOfDay
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

    private fun ActivityRecord.toLocationPoint(): LocationPoint {
        return LocationPoint(
            latitude = latitude,
            longitude = longitude,
            timestamp = timestamp
        )
    }

    private fun placeBucket(latitude: Double, longitude: Double): String {
        // Approx 100m buckets to avoid O(n^2) clustering checks.
        val latBucket = (latitude * 1000).toInt()
        val lonBucket = (longitude * 1000).toInt()
        return "${abs(latBucket)}:${abs(lonBucket)}:${latBucket < 0}:${lonBucket < 0}"
    }

    companion object {
        private const val TAG = "ActivityTracking"
    }
}
