package com.officetracker.tracking

import com.officetracker.core.util.Geo
import java.util.UUID

/**
 * Detects visits ("stays") from a stream of accepted GPS fixes.
 *
 * A stay opens once the device has remained within [radiusMeters] of a moving centroid for at
 * least [minDwellMillis]; its arrival time is back-dated to the first fix of that cluster.
 * A stay closes after two consecutive fixes outside the radius (or one far outside it), and
 * its departure time is the last fix that was still inside - so brief GPS drift does not
 * split one visit into several.
 */
class StayDetector(
    var radiusMeters: Double = 80.0,
    var minDwellMillis: Long = 5 * 60_000L,
) {
    data class OpenStay(
        val id: String,
        val latitude: Double,
        val longitude: Double,
        val samples: Int,
        val arrivalAt: Long,
        val lastInsideAt: Long,
    )

    sealed interface Event {
        data class Opened(val stay: OpenStay) : Event
        data class Updated(val stay: OpenStay) : Event
        data class Closed(val stay: OpenStay, val departureAt: Long) : Event
    }

    var open: OpenStay? = null
        private set

    private val candidate = ArrayList<LocationSample>()
    private var outsideStreak = 0

    fun restore(stay: OpenStay?) {
        open = stay
        candidate.clear()
        outsideStreak = 0
    }

    fun reset() = restore(null)

    fun onSample(s: LocationSample): List<Event> {
        val current = open
        return if (current != null) handleOpen(current, s) else handleCandidate(s)
    }

    private fun handleOpen(stay: OpenStay, s: LocationSample): List<Event> {
        val d = Geo.distanceMeters(stay.latitude, stay.longitude, s.latitude, s.longitude)
        if (d <= radiusMeters) {
            outsideStreak = 0
            // Refine the centre with a running mean, capped so a long stay still reacts.
            val n = stay.samples.coerceAtMost(60)
            val updated = stay.copy(
                latitude = (stay.latitude * n + s.latitude) / (n + 1),
                longitude = (stay.longitude * n + s.longitude) / (n + 1),
                samples = stay.samples + 1,
                lastInsideAt = maxOf(stay.lastInsideAt, s.time),
            )
            open = updated
            return listOf(Event.Updated(updated))
        }
        outsideStreak++
        if (outsideStreak >= 2 || d > radiusMeters * 3) {
            open = null
            outsideStreak = 0
            candidate.clear()
            candidate += s
            return listOf(Event.Closed(stay, stay.lastInsideAt))
        }
        return emptyList()
    }

    private fun handleCandidate(s: LocationSample): List<Event> {
        if (candidate.isEmpty()) {
            candidate += s
            return emptyList()
        }
        val (cLat, cLng) = centroid()
        val d = Geo.distanceMeters(cLat, cLng, s.latitude, s.longitude)
        if (d > radiusMeters) {
            // Keep only the most recent run of fixes that are still near the new one.
            var cut = candidate.size
            while (cut > 0 && Geo.distanceMeters(candidate[cut - 1].latitude, candidate[cut - 1].longitude, s.latitude, s.longitude) <= radiusMeters) cut--
            val keep = candidate.subList(cut, candidate.size).toList()
            candidate.clear()
            candidate.addAll(keep)
        }
        candidate += s
        trimCandidate()

        val first = candidate.first()
        if (s.time - first.time >= minDwellMillis) {
            val (lat, lng) = centroid()
            val stay = OpenStay(
                id = UUID.randomUUID().toString(),
                latitude = lat,
                longitude = lng,
                samples = candidate.size,
                arrivalAt = first.time,
                lastInsideAt = s.time,
            )
            open = stay
            candidate.clear()
            outsideStreak = 0
            return listOf(Event.Opened(stay))
        }
        return emptyList()
    }

    private fun centroid(): Pair<Double, Double> {
        // Weight by accuracy so poor fixes pull the centre less.
        var wSum = 0.0; var lat = 0.0; var lng = 0.0
        for (p in candidate) {
            val w = 1.0 / (p.accuracy.coerceAtLeast(3f) * p.accuracy.coerceAtLeast(3f))
            lat += p.latitude * w; lng += p.longitude * w; wSum += w
        }
        return (lat / wSum) to (lng / wSum)
    }

    private fun trimCandidate() {
        // Bound memory: keep the first fix (arrival time) and the most recent ones.
        if (candidate.size > 240) {
            val first = candidate.first()
            val recent = candidate.takeLast(180)
            candidate.clear()
            candidate += first
            candidate.addAll(recent)
        }
    }
}
