package com.officetracker

import com.officetracker.tracking.LocationFilter
import com.officetracker.tracking.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFilterTest {
    private val filter = LocationFilter(maxAccuracyMeters = 50.0)
    private fun s(metersNorth: Double, seconds: Long, acc: Float = 8f) =
        LocationSample(23.78 + metersNorth / 111_320.0, 90.40, acc, 0f, seconds * 1000, false)

    @Test
    fun rejectsInaccurateFix() {
        assertTrue(filter.evaluate(null, s(0.0, 0, acc = 120f)) is LocationFilter.Decision.Rejected)
    }

    @Test
    fun jitterDoesNotAddDistance() {
        val d = filter.evaluate(s(0.0, 0), s(6.0, 10)) as LocationFilter.Decision.Accepted
        assertFalse(d.moved)
        assertEquals(0.0, d.distanceMeters, 0.0)
    }

    @Test
    fun realMovementAddsDistance() {
        val d = filter.evaluate(s(0.0, 0), s(100.0, 30)) as LocationFilter.Decision.Accepted
        assertTrue(d.moved)
        assertEquals(100.0, d.distanceMeters, 1.0)
    }

    @Test
    fun rejectsTeleport() {
        // 5 km in 10 seconds.
        assertTrue(filter.evaluate(s(0.0, 0), s(5_000.0, 10)) is LocationFilter.Decision.Rejected)
    }

    @Test
    fun acceptsLongGapWithPlausibleSpeed() {
        // 5 km in 10 minutes (30 km/h) after a signal gap.
        assertTrue(filter.evaluate(s(0.0, 0), s(5_000.0, 600)) is LocationFilter.Decision.Accepted)
    }
}
