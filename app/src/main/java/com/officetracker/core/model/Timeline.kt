package com.officetracker.core.model

import com.officetracker.core.util.Geo

sealed interface TimelineEntry {
    data class Visit(val index: Int, val stay: Stay) : TimelineEntry
    data class Travel(val fromTime: Long, val toTime: Long, val distanceMeters: Double) : TimelineEntry
}

object Timeline {

    /** Interleaves stays with the travel legs between them, measured on the recorded route. */
    fun build(stays: List<Stay>, route: List<RoutePoint>): List<TimelineEntry> {
        val sorted = stays.sortedBy { it.arrivalAt }
        val result = mutableListOf<TimelineEntry>()
        sorted.forEachIndexed { i, stay ->
            if (i > 0) {
                val prev = sorted[i - 1]
                val from = prev.departureAt ?: prev.lastSeenAt
                val to = stay.arrivalAt
                if (to > from) {
                    result += TimelineEntry.Travel(from, to, routeDistance(route, from, to))
                }
            }
            result += TimelineEntry.Visit(i + 1, stay)
        }
        return result
    }

    fun routeDistance(route: List<RoutePoint>, from: Long, to: Long): Double {
        var total = 0.0
        var last: RoutePoint? = null
        for (p in route) {
            if (p.time < from || p.time > to) continue
            last?.let { total += Geo.distanceMeters(it.latitude, it.longitude, p.latitude, p.longitude) }
            last = p
        }
        return total
    }
}
