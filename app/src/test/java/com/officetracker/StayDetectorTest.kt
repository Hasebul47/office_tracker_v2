package com.officetracker

import com.officetracker.tracking.LocationSample
import com.officetracker.tracking.StayDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StayDetectorTest {

    private val office = 23.7806 to 90.4070
    private fun at(lat: Double, lng: Double, minute: Double, acc: Float = 10f) =
        LocationSample(lat, lng, acc, 0f, (minute * 60_000).toLong(), false)

    /** ~[meters] north of the office. */
    private fun north(meters: Double) = office.first + meters / 111_320.0

    @Test
    fun opensStayAfterDwellTimeWithBackdatedArrival() {
        val d = StayDetector(radiusMeters = 80.0, minDwellMillis = 5 * 60_000L)
        val events = (0..6).flatMap { m -> d.onSample(at(north((m % 3) * 10.0), office.second, m.toDouble())) }
        val opened = events.filterIsInstance<StayDetector.Event.Opened>().single()
        assertEquals(0L, opened.stay.arrivalAt)
        assertNotNull(d.open)
    }

    @Test
    fun doesNotOpenWhileMoving() {
        val d = StayDetector(radiusMeters = 80.0, minDwellMillis = 5 * 60_000L)
        // 200 m per minute - a slow walk, never 5 minutes inside 80 m.
        val events = (0..20).flatMap { m -> d.onSample(at(north(m * 200.0), office.second, m.toDouble())) }
        assertTrue(events.isEmpty())
        assertNull(d.open)
    }

    @Test
    fun singleGpsSpikeDoesNotCloseStay() {
        val d = StayDetector(radiusMeters = 80.0, minDwellMillis = 5 * 60_000L)
        (0..6).forEach { d.onSample(at(office.first, office.second, it.toDouble())) }
        val spike = d.onSample(at(north(150.0), office.second, 7.0))
        assertTrue(spike.isEmpty())
        val back = d.onSample(at(office.first, office.second, 8.0))
        assertTrue(back.single() is StayDetector.Event.Updated)
        assertNotNull(d.open)
    }

    @Test
    fun closesWithDepartureAtLastInsideFix() {
        val d = StayDetector(radiusMeters = 80.0, minDwellMillis = 5 * 60_000L)
        (0..10).forEach { d.onSample(at(office.first, office.second, it.toDouble())) }
        d.onSample(at(north(120.0), office.second, 11.0))
        val closed = d.onSample(at(north(300.0), office.second, 12.0)).filterIsInstance<StayDetector.Event.Closed>().single()
        assertEquals(10 * 60_000L, closed.departureAt)
        assertNull(d.open)
    }

    @Test
    fun farJumpClosesImmediately() {
        val d = StayDetector(radiusMeters = 80.0, minDwellMillis = 5 * 60_000L)
        (0..6).forEach { d.onSample(at(office.first, office.second, it.toDouble())) }
        val events = d.onSample(at(north(1_000.0), office.second, 8.0))
        assertTrue(events.single() is StayDetector.Event.Closed)
    }
}
