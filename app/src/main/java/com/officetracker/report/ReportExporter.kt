package com.officetracker.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.officetracker.core.model.DayDetail
import com.officetracker.core.model.Timeline
import com.officetracker.core.model.TimelineEntry
import com.officetracker.core.model.UserProfile
import com.officetracker.core.model.effectiveDistance
import com.officetracker.core.model.WorkSchedule
import com.officetracker.core.model.Workday
import com.officetracker.core.util.Dates
import com.officetracker.core.util.Format
import java.io.File
import java.time.YearMonth

data class TeamReportRow(val profile: UserProfile, val workdays: List<Workday>, val schedule: WorkSchedule? = null)

/** CSV reports (open in Excel / Google Sheets) shared through the Android share sheet. */
class ReportExporter(private val context: Context) {

    fun dayReport(profile: UserProfile, detail: DayDetail, ratePerKm: Double): File {
        val day = detail.workday
        val now = System.currentTimeMillis()
        val date = day?.date ?: "day"
        val rows = buildList<List<String>> {
            add(listOf("Employee", profile.name))
            add(listOf("Phone", profile.phone))
            add(listOf("Date", day?.date?.let { Dates.day(it) } ?: ""))
            if (day != null) {
                add(listOf("Started", Dates.time(day.startedAt), day.startName.orEmpty()))
                add(listOf("Ended", day.endedAt?.let { Dates.time(it) } ?: "(open)", day.endName.orEmpty()))
                add(listOf("Time on duty", Dates.duration(day.activeMillis(now))))
                add(listOf("Paused", "${day.pauseCount} times", Dates.duration(day.pausedMillis)))
                val meters = detail.effectiveDistance()
                add(listOf("Distance (km)", Format.km(meters)))
                add(listOf("Travel allowance (BDT)", Format.takaPlain(Format.allowance(meters, ratePerKm))))
                if (day.mockCount > 0) add(listOf("WARNING", "${day.mockCount} fake-GPS fixes detected"))
            }
            add(emptyList())
            add(listOf("#", "Type", "Place", "Category", "Arrived", "Left", "Duration", "Distance (km)", "Address", "Latitude", "Longitude"))
            for (entry in Timeline.build(detail.stays, detail.route)) {
                when (entry) {
                    is TimelineEntry.Visit -> {
                        val s = entry.stay
                        add(
                            listOf(
                                entry.index.toString(), "Visit", s.name, s.category.label,
                                Dates.time(s.arrivalAt), s.departureAt?.let { Dates.time(it) } ?: "(here now)",
                                Dates.duration(s.durationMillis(now)), "", s.address.orEmpty(),
                                "%.6f".format(s.latitude), "%.6f".format(s.longitude),
                            )
                        )
                    }
                    is TimelineEntry.Travel -> add(
                        listOf(
                            "", "Travel", "", "", Dates.time(entry.fromTime), Dates.time(entry.toTime),
                            Dates.duration(entry.toTime - entry.fromTime), Format.km(entry.distanceMeters),
                        )
                    )
                }
            }
        }
        return write("day-${safe(profile.name)}-$date.csv", rows)
    }

    fun monthReport(
        profile: UserProfile,
        month: YearMonth,
        workdays: List<Workday>,
        stayCounts: Map<String, Int>,
        ratePerKm: Double,
        schedule: WorkSchedule? = null,
    ): File {
        val sched = schedule?.takeIf { it.enabled }
        val now = System.currentTimeMillis()
        val rows = buildList<List<String>> {
            add(listOf("Employee", profile.name, "Phone", profile.phone, "Month", Dates.month(month)))
            add(emptyList())
            add(listOf("Date", "Start", "End", "Start place", "End place", "Hours on duty", "Visits", "Distance (km)", "Allowance (BDT)", "Late (min)"))
            var totalMs = 0L
            var totalM = 0.0
            for (d in workdays.sortedBy { it.date }) {
                val active = d.activeMillis(now)
                totalMs += active
                totalM += d.distanceMeters
                add(
                    listOf(
                        d.date, Dates.time(d.startedAt), d.endedAt?.let { Dates.time(it) } ?: "(open)",
                        d.startName.orEmpty(), d.endName.orEmpty(), "%.2f".format(active / 3_600_000.0),
                        (stayCounts[d.date] ?: 0).toString(), Format.km(d.distanceMeters),
                        Format.takaPlain(Format.allowance(d.distanceMeters, ratePerKm)),
                        (sched?.lateMinutes(d.startedAt, Dates.zone) ?: 0).toString(),
                    )
                )
            }
            add(
                listOf(
                    "TOTAL (${workdays.size} days)", "", "", "", "", "%.2f".format(totalMs / 3_600_000.0), "",
                    Format.km(totalM), Format.takaPlain(Format.allowance(totalM, ratePerKm)),
                )
            )
        }
        return write("month-${safe(profile.name)}-$month.csv", rows)
    }

    fun teamReport(month: YearMonth, team: List<TeamReportRow>, ratePerKm: Double): File {
        val now = System.currentTimeMillis()
        val rows = buildList<List<String>> {
            add(listOf("Team report", Dates.month(month), "Rate per km (BDT)", Format.takaPlain(ratePerKm)))
            add(emptyList())
            add(listOf("Employee", "Phone", "Department", "Days worked", "Days late", "Hours on duty", "Distance (km)", "Allowance (BDT)", "Fake-GPS fixes"))
            for (r in team.sortedBy { it.profile.name.lowercase() }) {
                val hours = r.workdays.sumOf { it.activeMillis(now) } / 3_600_000.0
                val meters = r.workdays.sumOf { it.distanceMeters }
                add(
                    listOf(
                        r.profile.name, r.profile.phone, r.profile.department, r.workdays.size.toString(),
                        r.workdays.count { d -> (r.schedule?.takeIf { it.enabled }?.lateMinutes(d.startedAt, Dates.zone) ?: 0) > 0 }.toString(),
                        "%.2f".format(hours), Format.km(meters), Format.takaPlain(Format.allowance(meters, ratePerKm)),
                        r.workdays.sumOf { it.mockCount }.toString(),
                    )
                )
            }
            add(emptyList())
            add(listOf("Employee", "Date", "Start", "End", "Hours on duty", "Distance (km)", "Allowance (BDT)", "Late (min)"))
            for (r in team.sortedBy { it.profile.name.lowercase() }) {
                for (d in r.workdays.sortedBy { it.date }) {
                    add(
                        listOf(
                            r.profile.name, d.date, Dates.time(d.startedAt), d.endedAt?.let { Dates.time(it) } ?: "(open)",
                            "%.2f".format(d.activeMillis(now) / 3_600_000.0), Format.km(d.distanceMeters),
                            Format.takaPlain(Format.allowance(d.distanceMeters, ratePerKm)),
                            (r.schedule?.takeIf { it.enabled }?.lateMinutes(d.startedAt, Dates.zone) ?: 0).toString(),
                        )
                    )
                }
            }
        }
        return write("team-$month.csv", rows)
    }

    fun shareIntent(file: File, title: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, title)
    }

    private fun write(name: String, rows: List<List<String>>): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, name)
        // UTF-8 BOM so Excel shows Bangla text correctly.
        file.writeText("﻿" + rows.joinToString("\r\n") { row -> row.joinToString(",") { csv(it) } }, Charsets.UTF_8)
        return file
    }

    private fun csv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "employee" }
}
