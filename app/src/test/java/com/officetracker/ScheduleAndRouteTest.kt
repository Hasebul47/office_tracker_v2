package com.officetracker

import com.officetracker.core.model.RouteMath
import com.officetracker.core.model.RoutePoint
import com.officetracker.core.model.SpeedBand
import com.officetracker.core.model.WorkSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduleAndRouteTest {
    private val zone = ZoneId.of("Asia/Dhaka")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // 2026-09-18 is a Friday, 2026-09-19 a Saturday.
    private val schedule = WorkSchedule(enabled = true, startMinute = 9 * 60, endMinute = 18 * 60, lateAfterMinutes = 15)

    @Test
    fun nextStartSkipsFridayWeekend() {
        val friNoon = at(2026, 9, 18, 12, 0)
        assertEquals(at(2026, 9, 19, 9, 0), schedule.nextStart(friNoon, zone))
    }

    @Test
    fun nextStartLaterToday() {
        assertEquals(at(2026, 9, 19, 9, 0), schedule.nextStart(at(2026, 9, 19, 7, 30), zone))
        assertEquals(at(2026, 9, 19, 18, 0), schedule.nextEnd(at(2026, 9, 19, 7, 30), zone))
    }

    @Test
    fun disabledScheduleNeverFires() {
        assertNull(schedule.copy(enabled = false).nextStart(at(2026, 9, 19, 7, 0), zone))
    }

    @Test
    fun lateness() {
        assertEquals(0, schedule.lateMinutes(at(2026, 9, 19, 9, 10), zone))
        assertEquals(25, schedule.lateMinutes(at(2026, 9, 19, 9, 25), zone))
        assertEquals(0, schedule.lateMinutes(at(2026, 9, 18, 11, 0), zone)) // Friday: not a work day
    }

    @Test
    fun withinHours() {
        assertTrue(schedule.isWithinHours(at(2026, 9, 19, 10, 0), zone))
        assertFalse(schedule.isWithinHours(at(2026, 9, 19, 19, 0), zone))
    }

    @Test
    fun scheduleMapRoundTrip() {
        assertEquals(schedule, WorkSchedule.fromMap(schedule.toMap()))
    }

    private fun p(metersNorth: Double, seconds: Long, acc: Float = 8f) =
        RoutePoint(23.78 + metersNorth / 111_320.0, 90.40, seconds * 1000, acc)

    @Test
    fun routeDistanceIgnoresJitter() {
        val jitter = (0..30).map { p(if (it % 2 == 0) 0.0 else 5.0, it * 10L) }
        assertEquals(0.0, RouteMath.distance(jitter), 0.001)
        val drive = (0..10).map { p(it * 100.0, it * 10L) }
        assertEquals(1000.0, RouteMath.distance(drive), 5.0)
    }

    @Test
    fun segmentsDetectGap() {
        val route = listOf(p(0.0, 0), p(100.0, 10), p(200.0, 20), p(300.0, 20 + 600), p(400.0, 630))
        val bands = RouteMath.segments(route).map { it.band }
        assertTrue(SpeedBand.GAP in bands)
    }

    @Test
    fun nearestPoint() {
        val route = (0..10).map { p(it * 100.0, it * 10L) }
        assertEquals(3, RouteMath.nearestIndex(route, 23.78 + 310 / 111_320.0, 90.40, 50.0))
        assertNull(RouteMath.nearestIndex(route, 23.9, 90.40, 50.0))
    }
}
