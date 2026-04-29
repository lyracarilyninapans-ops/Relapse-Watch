package com.example.relapse_watch.services

import android.util.Log
import com.example.relapse_watch.data.preferences.WatchPreferences
import com.example.relapse_watch.domain.model.ActivityRecord
import com.example.relapse_watch.domain.model.DailySummary
import com.example.relapse_watch.domain.model.EventTypes
import com.example.relapse_watch.domain.model.LocationPoint
import com.example.relapse_watch.domain.repository.ActivityRepository
import com.example.relapse_watch.domain.repository.DailySummaryRepository
import com.example.relapse_watch.domain.repository.SafeZoneRepository
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
    private val preferences: WatchPreferences,
    private val safeZoneRepository: SafeZoneRepository
) {

    private val summaryMutex = Mutex()
    private var cachedDate: String? = null
    private var cachedSummary: DailySummary? = null
    private var lastLocationPoint: LocationPoint? = null
    private var lastOutsideTimestamp: Long? = null
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

        val activeMinutes = computeTimeOutsideSafeZone(locationRecords)

        val summary = DailySummary(
            date = todayString,
            distanceMeters = computeTotalDistance(locationRecords),
            activeMinutes = activeMinutes,
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
            lastOutsideTimestamp = computeLastOutsideTimestamp(locationRecords)
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

            // Accumulate time outside safe zone
            val activeMinutesDelta = computeIncrementalActiveMinutes(point)

            val nextSummary = currentSummary.copy(
                distanceMeters = currentSummary.distanceMeters + distanceDelta,
                activeMinutes = currentSummary.activeMinutes + activeMinutesDelta,
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
        lastOutsideTimestamp = computeLastOutsideTimestamp(locationRecords)
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

    /**
     * Compute total minutes spent outside the safe zone from historical records.
     * For each consecutive pair of location records where both are outside the
     * safe zone, we count the time interval between them as "outside" time.
     * If no safe zone is configured, all time counts as outside.
     */
    private suspend fun computeTimeOutsideSafeZone(locationRecords: List<ActivityRecord>): Int {
        if (locationRecords.size < 2) return 0

        val safeZone = safeZoneRepository.getActiveSafeZone().first()

        var totalMs = 0L
        var prevOutside = isRecordOutsideSafeZone(locationRecords[0], safeZone)
        var prevTimestamp = locationRecords[0].timestamp

        for (i in 1 until locationRecords.size) {
            val record = locationRecords[i]
            val currentOutside = isRecordOutsideSafeZone(record, safeZone)

            if (prevOutside && currentOutside) {
                val elapsedMs = record.timestamp - prevTimestamp
                // Cap individual intervals at 10 minutes to handle GPS gaps
                // where the watch may have been asleep.
                totalMs += elapsedMs.coerceAtMost(MAX_INTERVAL_MS)
            }

            prevOutside = currentOutside
            prevTimestamp = record.timestamp
        }

        return (totalMs / 60_000L).toInt()
    }

    /**
     * Compute incremental activeMinutes delta for a single new location tick.
     * Returns the minutes elapsed since the last known "outside" tick, or 0
     * if the current point is inside the safe zone.
     *
     * Also updates [lastOutsideTimestamp] as a side effect.
     */
    private suspend fun computeIncrementalActiveMinutes(point: LocationPoint): Int {
        val safeZone = safeZoneRepository.getActiveSafeZone().first()
        val isOutside = isPointOutsideSafeZone(point, safeZone)

        if (!isOutside) {
            // Patient is inside — reset the outside tracking anchor.
            lastOutsideTimestamp = null
            return 0
        }

        val prevOutsideTs = lastOutsideTimestamp
        lastOutsideTimestamp = point.timestamp

        if (prevOutsideTs == null) {
            // First outside tick (or just exited safe zone) — no interval yet.
            return 0
        }

        val elapsedMs = (point.timestamp - prevOutsideTs).coerceAtMost(MAX_INTERVAL_MS)
        return (elapsedMs / 60_000L).toInt()
    }

    /**
     * Look at the last location record and determine whether it was outside
     * the safe zone. If so, return its timestamp as the initial
     * [lastOutsideTimestamp] for incremental tracking.
     */
    private suspend fun computeLastOutsideTimestamp(
        locationRecords: List<ActivityRecord>
    ): Long? {
        val lastRecord = locationRecords.lastOrNull() ?: return null
        val safeZone = safeZoneRepository.getActiveSafeZone().first()
        return if (isRecordOutsideSafeZone(lastRecord, safeZone)) {
            lastRecord.timestamp
        } else {
            null
        }
    }

    private fun isPointOutsideSafeZone(
        point: LocationPoint,
        safeZone: com.example.relapse_watch.domain.model.SafeZoneConfig?
    ): Boolean {
        if (safeZone == null || !safeZone.isActive) {
            // No safe zone configured — treat all time as outside.
            return true
        }
        val center = LocationPoint(
            latitude = safeZone.centerLat,
            longitude = safeZone.centerLng,
            timestamp = point.timestamp
        )
        val distance = locationService.calculateDistance(point, center)
        return distance > safeZone.radiusMeters
    }

    private fun isRecordOutsideSafeZone(
        record: ActivityRecord,
        safeZone: com.example.relapse_watch.domain.model.SafeZoneConfig?
    ): Boolean {
        return isPointOutsideSafeZone(
            LocationPoint(record.latitude, record.longitude, record.timestamp),
            safeZone
        )
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
        // Cap individual GPS intervals at 10 minutes to avoid inflating
        // time-outside when the watch wakes from a long sleep.
        private const val MAX_INTERVAL_MS = 10L * 60_000L
    }
}
