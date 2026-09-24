package com.officetracker.core.model

import com.officetracker.core.util.Geo
import java.time.ZoneId
import kotlin.math.roundToLong

/** Company rules for geofenced attendance punching and overtime. */
data class AttendanceSettings(
    /** Master switch for the company (the super admin also has a plan-level switch). */
    val enabled: Boolean = true,
    /** The day can only be started (punched in) while inside an attendance zone. */
    val requireZoneToPunch: Boolean = true,
    /** Overtime counting after the scheduled end time. */
    val otEnabled: Boolean = false,
    /** Overtime starts this long after the scheduled end time. */
    val otGraceMinutes: Int = 15,
    /** Anything shorter than this is not paid as overtime. */
    val otMinMinutes: Int = 30,
    /** Safety cap per day. */
    val otMaxHours: Int = 6,
    val otRatePerHour: Double = 0.0,
    /** Overtime counts in reports only after an administrator approves the day. */
    val otRequiresApproval: Boolean = false,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "enabled" to enabled,
        "requireZoneToPunch" to requireZoneToPunch,
        "otEnabled" to otEnabled,
        "otGraceMinutes" to otGraceMinutes,
        "otMinMinutes" to otMinMinutes,
        "otMaxHours" to otMaxHours,
        "otRatePerHour" to otRatePerHour,
        "otRequiresApproval" to otRequiresApproval,
    )

    companion object {
        fun fromMap(m: Map<*, *>?): AttendanceSettings {
            val d = AttendanceSettings()
            if (m == null) return d
            fun int(k: String) = (m[k] as? Number)?.toInt()
            fun bool(k: String, fallback: Boolean) = (m[k] as? Boolean) ?: fallback
            return AttendanceSettings(
                enabled = bool("enabled", d.enabled),
                requireZoneToPunch = bool("requireZoneToPunch", d.requireZoneToPunch),
                otEnabled = bool("otEnabled", d.otEnabled),
                otGraceMinutes = int("otGraceMinutes")?.coerceIn(0, 240) ?: d.otGraceMinutes,
                otMinMinutes = int("otMinMinutes")?.coerceIn(0, 240) ?: d.otMinMinutes,
                otMaxHours = int("otMaxHours")?.coerceIn(1, 16) ?: d.otMaxHours,
                otRatePerHour = (m["otRatePerHour"] as? Number)?.toDouble() ?: d.otRatePerHour,
                otRequiresApproval = bool("otRequiresApproval", d.otRequiresApproval),
            )
        }
    }
}

enum class AttendanceStatus(val label: String) {
    PRESENT("Present"),
    LATE("Late"),
    ABSENT("Absent"),
    OFF_DAY("Weekly off"),
    NO_PUNCH("No punch"),
}

object Attendance {

    /** The attendance zone a position falls inside, if any (nearest wins when they overlap). */
    fun zoneAt(places: List<Place>, latitude: Double, longitude: Double): Place? =
        places.filter { it.attendance }
            .map { it to Geo.distanceMeters(latitude, longitude, it.latitude, it.longitude) }
            .filter { (place, d) -> d <= place.radiusMeters }
            .minByOrNull { it.second }
            ?.first

    /** Paid overtime for a day, after the minimum, the daily cap and (if set) approval. */
    fun payableOtMillis(day: Workday?, settings: AttendanceSettings): Long {
        if (day == null || !settings.otEnabled) return 0
        if (settings.otRequiresApproval && !day.otApproved) return 0
        if (day.otMillis < settings.otMinMinutes * 60_000L) return 0
        return day.otMillis.coerceAtMost(settings.otMaxHours * 3_600_000L)
    }

    fun otAmount(day: Workday?, settings: AttendanceSettings): Double =
        payableOtMillis(day, settings) / 3_600_000.0 * settings.otRatePerHour

    fun status(day: Workday?, schedule: WorkSchedule?, date: String, zone: ZoneId): AttendanceStatus {
        val workDay = schedule == null || schedule.isWorkDay(com.officetracker.core.util.Dates.parse(date))
        return when {
            day == null && !workDay -> AttendanceStatus.OFF_DAY
            day == null -> AttendanceStatus.ABSENT
            day.checkInAt == null -> AttendanceStatus.NO_PUNCH
            schedule != null && schedule.lateMinutes(day.checkInAt, zone) > 0 -> AttendanceStatus.LATE
            else -> AttendanceStatus.PRESENT
        }
    }

    /** Hours rounded to two decimals, for reports. */
    fun hours(millis: Long): Double = (millis / 36_000.0).roundToLong() / 100.0
}
