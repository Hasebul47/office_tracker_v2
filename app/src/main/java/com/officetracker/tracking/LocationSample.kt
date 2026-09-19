package com.officetracker.tracking

import android.location.Location
import android.os.Build

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float,
    val time: Long,
    val mock: Boolean,
)

fun Location.toSample(): LocationSample = LocationSample(
    latitude = latitude,
    longitude = longitude,
    accuracy = if (hasAccuracy()) accuracy else 999f,
    speed = if (hasSpeed()) speed else 0f,
    // Wall-clock time of the fix; guard against devices reporting 0.
    time = if (time > 0) time else System.currentTimeMillis(),
    mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider,
)
