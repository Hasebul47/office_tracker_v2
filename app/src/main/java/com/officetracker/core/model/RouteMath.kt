package com.officetracker.core.model

import com.officetracker.core.util.Geo
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow

enum class SpeedBand(val label: String) {
    SLOW("Walking / stopped"),
    CITY("City traffic"),
    FAST("Fast road"),
    GAP("No signal"),
}

data class RouteSegment(val band: SpeedBand, val points: List<RoutePoint>)

/** Pure route analysis used by the map, the stats and the reports. */
object RouteMath {
    /** Signal gap: no fix for this long means the line between fixes is a guess. */
    const val GAP_MS = 5 * 60_000L

    /**
     * Distance along the recorded route, ignoring GPS jitter (moves smaller than the fixes'
     * accuracy) and impossible jumps. Used to verify / repair the odometer.
     */
    fun distance(route: List<RoutePoint>): Double {
        var total = 0.0
        var anchor: RoutePoint? = null
        for (p in route) {
            val a = anchor
            if (a == null) { anchor = p; continue }
            val d = Geo.distanceMeters(a.latitude, a.longitude, p.latitude, p.longitude)
            val seconds = (p.time - a.time) / 1000.0
            if (seconds <= 0) continue
            if (d > 200 && d / seconds > 70) continue
            if (d < max(15.0, (a.accuracy + p.accuracy) / 2.0)) continue
            total += d
            anchor = p
        }
        return total
    }

    fun speedMps(a: RoutePoint, b: RoutePoint): Double {
        val seconds = (b.time - a.time) / 1000.0
        if (seconds <= 0) return 0.0
        return Geo.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude) / seconds
    }

    private fun band(a: RoutePoint, b: RoutePoint): SpeedBand {
        if (b.time - a.time > GAP_MS) return SpeedBand.GAP
        val v = speedMps(a, b)
        return when {
            v < 2.5 -> SpeedBand.SLOW   // < 9 km/h
            v < 11.0 -> SpeedBand.CITY  // < 40 km/h
            else -> SpeedBand.FAST
        }
    }

    /** Splits the route into runs of the same speed band (consecutive runs share an end point). */
    fun segments(route: List<RoutePoint>): List<RouteSegment> {
        if (route.size < 2) return emptyList()
        val out = mutableListOf<RouteSegment>()
        var current = mutableListOf(route[0])
        var currentBand = band(route[0], route[1])
        for (i in 1 until route.size) {
            val b = band(route[i - 1], route[i])
            if (b != currentBand) {
                out += RouteSegment(currentBand, current)
                current = mutableListOf(route[i - 1])
                currentBand = b
            }
            current += route[i]
        }
        out += RouteSegment(currentBand, current)
        return out
    }

    /** Index of the route point closest to the given coordinate, if within [maxMeters]. */
    fun nearestIndex(route: List<RoutePoint>, lat: Double, lng: Double, maxMeters: Double): Int? {
        var best = -1
        var bestD = Double.MAX_VALUE
        route.forEachIndexed { i, p ->
            val d = Geo.distanceMeters(lat, lng, p.latitude, p.longitude)
            if (d < bestD) { bestD = d; best = i }
        }
        return if (best >= 0 && bestD <= maxMeters) best else null
    }

    /** Metres covered by one screen pixel at a zoom level (Web Mercator). */
    fun metersPerPixel(latitude: Double, zoom: Double): Double =
        156_543.033_92 * cos(Math.toRadians(latitude)) / 2.0.pow(zoom)

    /** Cumulative distance up to each index, for the playback read-out. */
    fun cumulative(route: List<RoutePoint>): DoubleArray {
        val out = DoubleArray(route.size)
        for (i in 1 until route.size) {
            val a = route[i - 1]; val b = route[i]
            val d = Geo.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            val jump = d > 200 && speedMps(a, b) > 70
            out[i] = out[i - 1] + if (jump) 0.0 else d
        }
        return out
    }
}

/** Distance to display: the odometer, or the route-measured value if the odometer missed some. */
fun DayDetail.effectiveDistance(): Double = maxOf(workday?.distanceMeters ?: 0.0, RouteMath.distance(route))
