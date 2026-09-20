package com.officetracker.core.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Working hours used for automatic start/end of tracking and for attendance (late / absent).
 * Minutes are counted from local midnight (540 = 09:00).
 */
data class WorkSchedule(
    val enabled: Boolean = false,
    /** ISO day numbers: 1 = Monday ... 7 = Sunday. Default: Saturday to Thursday. */
    val days: Set<Int> = setOf(6, 7, 1, 2, 3, 4),
    val startMinute: Int = 9 * 60,
    val endMinute: Int = 18 * 60,
    val autoStart: Boolean = true,
    val autoEnd: Boolean = true,
    /** Starting more than this many minutes after [startMinute] counts as late. */
    val lateAfterMinutes: Int = 15,
) {
    val startTime: LocalTime get() = LocalTime.of(startMinute / 60 % 24, startMinute % 60)
    val endTime: LocalTime get() = LocalTime.of(endMinute / 60 % 24, endMinute % 60)

    fun isWorkDay(date: LocalDate): Boolean = date.dayOfWeek.value in days

    /** Next moment (strictly after [now]) the day should start, or null if never. */
    fun nextStart(now: Long, zone: ZoneId): Long? = next(now, zone, startTime)

    /** Next moment (strictly after [now]) the day should end, or null if never. */
    fun nextEnd(now: Long, zone: ZoneId): Long? = next(now, zone, endTime)

    private fun next(now: Long, zone: ZoneId, time: LocalTime): Long? {
        if (!enabled || days.isEmpty()) return null
        val nowZ = Instant.ofEpochMilli(now).atZone(zone)
        for (i in 0..7) {
            val date = nowZ.toLocalDate().plusDays(i.toLong())
            if (!isWorkDay(date)) continue
            val at = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
            if (at > now) return at
        }
        return null
    }

    /** True if [now] falls inside today's working window. */
    fun isWithinHours(now: Long, zone: ZoneId): Boolean {
        if (!enabled) return false
        val z = Instant.ofEpochMilli(now).atZone(zone)
        if (!isWorkDay(z.toLocalDate())) return false
        val minute = z.hour * 60 + z.minute
        return minute in startMinute until endMinute
    }

    /** Minutes late for a day started at [startedAt] (0 if on time or not a scheduled day). */
    fun lateMinutes(startedAt: Long, zone: ZoneId): Int {
        if (!enabled) return 0
        val z = Instant.ofEpochMilli(startedAt).atZone(zone)
        if (!isWorkDay(z.toLocalDate())) return 0
        val minute = z.hour * 60 + z.minute
        val late = minute - startMinute
        return if (late > lateAfterMinutes) late else 0
    }

    fun toMap(): Map<String, Any> = mapOf(
        "enabled" to enabled,
        "days" to days.sorted(),
        "startMinute" to startMinute,
        "endMinute" to endMinute,
        "autoStart" to autoStart,
        "autoEnd" to autoEnd,
        "lateAfterMinutes" to lateAfterMinutes,
    )

    fun daysLabel(): String {
        if (days.size == 7) return "Every day"
        return DayOfWeek.entries.filter { it.value in days }
            .sortedBy { (it.value + 1) % 7 } // Saturday first, as in Bangladesh
            .joinToString(", ") { it.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } }
    }

    companion object {
        fun fromMap(m: Map<*, *>?): WorkSchedule? {
            if (m == null) return null
            val d = WorkSchedule()
            fun int(k: String) = (m[k] as? Number)?.toInt()
            return WorkSchedule(
                enabled = m["enabled"] as? Boolean ?: false,
                days = (m["days"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }?.filter { it in 1..7 }?.toSet() ?: d.days,
                startMinute = int("startMinute")?.coerceIn(0, 1439) ?: d.startMinute,
                endMinute = int("endMinute")?.coerceIn(0, 1439) ?: d.endMinute,
                autoStart = m["autoStart"] as? Boolean ?: true,
                autoEnd = m["autoEnd"] as? Boolean ?: true,
                lateAfterMinutes = int("lateAfterMinutes")?.coerceIn(0, 240) ?: d.lateAfterMinutes,
            )
        }

        fun formatMinute(minute: Int): String {
            val h = minute / 60 % 24
            val m = minute % 60
            val h12 = if (h % 12 == 0) 12 else h % 12
            return "%d:%02d %s".format(h12, m, if (h < 12) "AM" else "PM")
        }
    }
}

/**
 * The schedule that applies to a person: their own (set by the admin), otherwise the company's
 * for employees. Administrators follow a schedule only if one is set for them personally.
 */
fun UserProfile.effectiveSchedule(companySchedule: WorkSchedule): WorkSchedule? =
    (schedule ?: if (role == Role.EMPLOYEE) companySchedule else null)?.takeIf { it.enabled }
