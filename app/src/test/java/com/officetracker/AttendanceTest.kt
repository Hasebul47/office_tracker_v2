package com.officetracker

import com.officetracker.core.model.Attendance
import com.officetracker.core.model.AttendanceSettings
import com.officetracker.core.model.AttendanceStatus
import com.officetracker.core.model.Place
import com.officetracker.core.model.PlaceCategory
import com.officetracker.core.model.WorkSchedule
import com.officetracker.core.model.WorkStatus
import com.officetracker.core.model.Workday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AttendanceTest {
    private val zone = ZoneId.of("Asia/Dhaka")
    private fun at(d: Int, h: Int, mi: Int) = LocalDateTime.of(2026, 9, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val office = Place("p1", "Head Office", PlaceCategory.OFFICE, 23.7806, 90.4070, 100.0, attendance = true)
    private val clientSite = Place("p2", "Client", PlaceCategory.CLIENT, 23.7806, 90.4070, 100.0, attendance = false)
    private val settings = AttendanceSettings(enabled = true, otEnabled = true, otMinMinutes = 30, otMaxHours = 4, otRatePerHour = 50.0)
    private val schedule = WorkSchedule(enabled = true, startMinute = 9 * 60, endMinute = 18 * 60)

    private fun day(
        checkIn: Long? = null,
        inside: Long = 0,
        ot: Long = 0,
        approved: Boolean = false,
    ) = Workday(
        userId = "u", date = "2026-09-19", status = WorkStatus.ENDED, startedAt = at(19, 9, 0), endedAt = at(19, 20, 0),
        startName = null, startLatitude = null, startLongitude = null, endName = null, endLatitude = null, endLongitude = null,
        distanceMeters = 0.0, pausedMillis = 0, pausedAt = null, pauseCount = 0, pointCount = 0, mockCount = 0,
        checkInAt = checkIn, insideMillis = inside, otMillis = ot, otApproved = approved,
    )

    @Test
    fun zoneOnlyMatchesAttendancePlaces() {
        // 50 m north of the office, inside the 100 m radius.
        val lat = 23.7806 + 50 / 111_320.0
        assertEquals("Head Office", Attendance.zoneAt(listOf(office, clientSite), lat, 90.4070)?.name)
        assertNull(Attendance.zoneAt(listOf(clientSite), lat, 90.4070))
        // 300 m away: outside.
        assertNull(Attendance.zoneAt(listOf(office), 23.7806 + 300 / 111_320.0, 90.4070))
    }

    @Test
    fun overtimeNeedsTheMinimum() {
        assertEquals(0L, Attendance.payableOtMillis(day(ot = 20 * 60_000L), settings))
        assertEquals(90 * 60_000L, Attendance.payableOtMillis(day(ot = 90 * 60_000L), settings))
    }

    @Test
    fun overtimeIsCappedAndPriced() {
        assertEquals(4 * 3_600_000L, Attendance.payableOtMillis(day(ot = 9 * 3_600_000L), settings))
        assertEquals(200.0, Attendance.otAmount(day(ot = 9 * 3_600_000L), settings), 0.01)
        assertEquals(100.0, Attendance.otAmount(day(ot = 2 * 3_600_000L), settings), 0.01)
    }

    @Test
    fun approvalGate() {
        val needsApproval = settings.copy(otRequiresApproval = true)
        assertEquals(0L, Attendance.payableOtMillis(day(ot = 2 * 3_600_000L), needsApproval))
        assertEquals(2 * 3_600_000L, Attendance.payableOtMillis(day(ot = 2 * 3_600_000L, approved = true), needsApproval))
    }

    @Test
    fun otOffMeansNoPay() {
        assertEquals(0L, Attendance.payableOtMillis(day(ot = 3 * 3_600_000L), settings.copy(otEnabled = false)))
    }

    @Test
    fun statuses() {
        assertEquals(AttendanceStatus.PRESENT, Attendance.status(day(checkIn = at(19, 9, 5)), schedule, "2026-09-19", zone))
        assertEquals(AttendanceStatus.LATE, Attendance.status(day(checkIn = at(19, 9, 45)), schedule, "2026-09-19", zone))
        assertEquals(AttendanceStatus.NO_PUNCH, Attendance.status(day(), schedule, "2026-09-19", zone))
        assertEquals(AttendanceStatus.ABSENT, Attendance.status(null, schedule, "2026-09-19", zone))
        // 18 September 2026 is a Friday, which is not a working day.
        assertEquals(AttendanceStatus.OFF_DAY, Attendance.status(null, schedule, "2026-09-18", zone))
    }

    @Test
    fun hoursRoundToTwoDecimals() {
        assertEquals(1.5, Attendance.hours(90 * 60_000L), 0.0001)
        assertEquals(0.25, Attendance.hours(15 * 60_000L), 0.0001)
    }

    @Test
    fun settingsRoundTrip() {
        assertEquals(settings, AttendanceSettings.fromMap(settings.toMap()))
    }
}
