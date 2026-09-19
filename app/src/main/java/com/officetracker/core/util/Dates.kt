package com.officetracker.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Dates {
    val zone: ZoneId get() = ZoneId.systemDefault()
    private val keyFormat = DateTimeFormatter.ISO_LOCAL_DATE // yyyy-MM-dd
    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val dayFormat = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
    private val shortDayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

    fun today(): LocalDate = LocalDate.now(zone)
    fun todayKey(): String = today().format(keyFormat)
    fun key(date: LocalDate): String = date.format(keyFormat)
    fun keyOf(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().format(keyFormat)
    fun parse(key: String): LocalDate = LocalDate.parse(key, keyFormat)

    fun time(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).format(timeFormat)
    fun day(key: String): String = parse(key).format(dayFormat)
    fun shortDay(key: String): String = parse(key).format(shortDayFormat)
    fun month(ym: YearMonth): String = ym.format(monthFormat)

    fun friendlyDay(key: String): String = when (parse(key)) {
        today() -> "Today"
        today().minusDays(1) -> "Yesterday"
        else -> day(key)
    }

    fun monthRange(ym: YearMonth): Pair<String, String> = key(ym.atDay(1)) to key(ym.atEndOfMonth())

    /** "2h 05m", "12m", "45s" */
    fun duration(millis: Long): String {
        val totalMinutes = millis / 60_000
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h > 0 -> "%dh %02dm".format(h, m)
            totalMinutes > 0 -> "${m}m"
            else -> "${(millis / 1000).coerceAtLeast(0)}s"
        }
    }

    /** "just now", "5 min ago", "3 h ago", "2 d ago" */
    fun ago(millis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - millis).coerceAtLeast(0)
        return when {
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${diff / 60_000} min ago"
            diff < 86_400_000 -> "${diff / 3_600_000} h ago"
            else -> "${diff / 86_400_000} d ago"
        }
    }
}
