package com.officetracker.core.util

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres (haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Approximate degrees of latitude spanned by [meters]; used for cheap bounding-box queries. */
    fun metersToLatDegrees(meters: Double): Double = meters / 111_320.0

    fun metersToLonDegrees(meters: Double, atLatitude: Double): Double =
        meters / (111_320.0 * cos(Math.toRadians(atLatitude)).coerceAtLeast(0.01))
}
