package com.example.relapse_watch.services

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.example.relapse_watch.domain.model.LocationPoint
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Singleton
class LocationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient
) {

    @SuppressLint("MissingPermission")
    fun getLocationUpdates(intervalMs: Long = 30_000L): Flow<LocationPoint> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    trySend(location.toLocationPoint())
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(request, callback, context.mainLooper)

        awaitClose {
            fusedLocationClient.removeLocationUpdates(callback)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun getLastKnownLocation(): LocationPoint? {
        return try {
            fusedLocationClient.lastLocation.await()?.toLocationPoint()
        } catch (_: Exception) {
            null
        }
    }

    fun calculateDistance(from: LocationPoint, to: LocationPoint): Float {
        val results = FloatArray(1)
        Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, results)
        return results[0]
    }

    fun calculateBearing(from: LocationPoint, to: LocationPoint): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLng = Math.toRadians(to.longitude - from.longitude)

        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)

        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }

    private fun Location.toLocationPoint(): LocationPoint {
        return LocationPoint(
            latitude = latitude,
            longitude = longitude,
            timestamp = time,
            accuracy = if (hasAccuracy()) accuracy else null
        )
    }
}
