package com.officetracker.tracking

import com.officetracker.core.util.Geo
import kotlin.math.max

/**
 * Decides whether a GPS fix is trustworthy and whether it represents real movement.
 *
 * - Fixes worse than [maxAccuracyMeters] are dropped.
 * - Fixes that imply an impossible speed from the last anchor are dropped (GPS "teleports").
 * - Movement smaller than the combined uncertainty of both fixes is treated as jitter, so a
 *   phone lying on a desk does not accumulate kilometres.
 */
class LocationFilter(
    var maxAccuracyMeters: Double = 50.0,
    private val maxSpeedMps: Double = 70.0, // 252 km/h
    private val minMoveMeters: Double = 15.0,
) {
    sealed interface Decision {
        data class Rejected(val reason: String) : Decision
        /** [moved] = true means the odometer advances by [distanceMeters] and the anchor moves. */
        data class Accepted(val moved: Boolean, val distanceMeters: Double) : Decision
    }

    fun evaluate(anchor: LocationSample?, sample: LocationSample): Decision {
        if (sample.accuracy <= 0f || sample.accuracy > maxAccuracyMeters) {
            return Decision.Rejected("accuracy ${sample.accuracy} m")
        }
        if (anchor == null) return Decision.Accepted(moved = true, distanceMeters = 0.0)

        val seconds = (sample.time - anchor.time) / 1000.0
        if (seconds <= 0) return Decision.Rejected("out of order")

        val d = Geo.distanceMeters(anchor.latitude, anchor.longitude, sample.latitude, sample.longitude)
        if (d > 200 && d / seconds > maxSpeedMps) return Decision.Rejected("jump ${d.toInt()} m in ${seconds.toInt()} s")

        val jitter = max(minMoveMeters, (sample.accuracy + anchor.accuracy) / 2.0)
        return if (d < jitter) Decision.Accepted(moved = false, distanceMeters = 0.0)
        else Decision.Accepted(moved = true, distanceMeters = d)
    }
}
