package com.officetracker

import com.officetracker.core.model.PlaceCategory
import com.officetracker.core.model.RoutePoint
import com.officetracker.core.model.Stay
import com.officetracker.core.model.Timeline
import com.officetracker.core.model.TimelineEntry
import com.officetracker.core.model.WorkStatus
import com.officetracker.core.model.Workday
import com.officetracker.core.util.Format
import com.officetracker.core.util.Phone
import com.officetracker.update.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilTest {

    @Test
    fun phoneNormalisation() {
        assertEquals("01712345678", Phone.normalize("+880 1712-345678"))
        assertEquals("01712345678", Phone.normalize("8801712345678"))
        assertEquals("01712345678", Phone.normalize("1712345678"))
        assertTrue(Phone.isValid("01712345678"))
        assertFalse(Phone.isValid("01212345678"))
        assertFalse(Phone.isValid("0171234567"))
        assertEquals("01712345678@x.app", Phone.toAuthEmail("+8801712345678", "x.app"))
    }

    @Test
    fun versionComparison() {
        assertTrue(UpdateManager.isNewer("2.0.1", "2.0.0"))
        assertTrue(UpdateManager.isNewer("2.1", "2.0.9"))
        assertTrue(UpdateManager.isNewer("10.0.0", "9.9.9"))
        assertFalse(UpdateManager.isNewer("2.0.0", "2.0.0"))
        assertFalse(UpdateManager.isNewer("1.9.9", "2.0.0"))
        assertFalse(UpdateManager.isNewer("2.0.0", "2.0.0-debug"))
    }

    @Test
    fun allowance() {
        assertEquals(60.0, Format.allowance(10_000.0, 6.0), 0.0001)
    }

    @Test
    fun activeTimeExcludesPauses() {
        val day = Workday(
            userId = "u", date = "2026-01-01", status = WorkStatus.PAUSED, startedAt = 0, endedAt = null,
            startName = null, startLatitude = null, startLongitude = null, endName = null, endLatitude = null, endLongitude = null,
            distanceMeters = 0.0, pausedMillis = 10 * 60_000L, pausedAt = 50 * 60_000L, pauseCount = 2, pointCount = 0, mockCount = 0,
        )
        // 60 min elapsed - 10 min earlier pause - 10 min current pause.
        assertEquals(40 * 60_000L, day.activeMillis(60 * 60_000L))
    }

    @Test
    fun timelineInsertsTravelWithRouteDistance() {
        fun stay(id: String, lat: Double, arr: Long, dep: Long) =
            Stay(id, lat, 90.0, arr, dep, dep, null, id, null, PlaceCategory.OTHER, false)
        val a = stay("a", 23.0, 0, 100)
        val b = stay("b", 23.01, 200, 300)
        val route = listOf(RoutePoint(23.0, 90.0, 100, 5f), RoutePoint(23.005, 90.0, 150, 5f), RoutePoint(23.01, 90.0, 200, 5f))
        val t = Timeline.build(listOf(b, a), route)
        assertEquals(3, t.size)
        val travel = t[1] as TimelineEntry.Travel
        assertEquals(1113.0, travel.distanceMeters, 5.0)
    }
}
