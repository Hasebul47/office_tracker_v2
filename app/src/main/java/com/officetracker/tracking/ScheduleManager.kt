package com.officetracker.tracking

import android.app.AlarmManager
import com.officetracker.core.model.effectiveSchedule
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.officetracker.core.model.Role
import com.officetracker.core.model.WorkSchedule
import com.officetracker.core.util.Dates
import com.officetracker.data.repo.AuthRepository
import com.officetracker.data.repo.OrgRepository
import com.officetracker.data.repo.WorkdayRepository

/**
 * Sets exact alarms for the next automatic start and end of the workday. The effective schedule
 * is the person's own (set by the admin on their page) or, for employees, the company's.
 */
class ScheduleManager(
    private val context: Context,
    private val auth: AuthRepository,
    private val org: OrgRepository,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** Schedule in force right now, or null when none applies. */
    fun effective(): WorkSchedule? {
        val profile = auth.currentProfile ?: return null
        val company = org.currentCompany ?: return null
        if (!company.features.scheduler) return null
        return profile.effectiveSchedule(company.settings.schedule)
    }

    /** True when the schedule forbids pausing / ending right now. */
    fun isLockedNow(): Boolean {
        val s = effective() ?: return false
        return s.enforce && s.isWithinHours(System.currentTimeMillis(), Dates.zone)
    }

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms?.canScheduleExactAlarms() == true

    fun reschedule() {
        cancel()
        val s = effective() ?: return
        val now = System.currentTimeMillis()
        if (s.autoStart) s.nextStart(now, Dates.zone)?.let { set(ScheduleReceiver.ACTION_START, it) }
        if (s.autoEnd) s.nextEnd(now, Dates.zone)?.let { set(ScheduleReceiver.ACTION_END, it) }
    }

    fun cancel() {
        alarms?.cancel(pending(ScheduleReceiver.ACTION_START))
        alarms?.cancel(pending(ScheduleReceiver.ACTION_END))
    }

    /**
     * Makes the day match the schedule even if an alarm was missed (phone off, or the maker's
     * battery manager blocked it): starts during working hours if no day exists yet today,
     * and ends an open day after working hours. Called on app start, on every schedule change
     * and by the 15-minute watchdog.
     */
    suspend fun catchUp(workdays: WorkdayRepository, tracking: TrackingController): String? {
        val s = effective() ?: return null
        val uid = auth.currentUid ?: return null
        val now = System.currentTimeMillis()
        val today = Dates.todayKey()
        val open = workdays.openWorkday(uid)
        val minute = java.time.LocalTime.now(Dates.zone).let { it.hour * 60 + it.minute }
        return when {
            // Past the end time (or a day left open from before): end it.
            s.autoEnd && open != null && (open.date != today || (s.isWorkDay(Dates.today()) && minute >= s.endMinute)) -> {
                tracking.endDay(manual = false).fold({ "ended" }, { null })
            }
            // Inside working hours, nothing recorded yet today: start.
            s.autoStart && open == null && s.isWithinHours(now, Dates.zone) && !workdays.hasLocalDay(uid, today) -> {
                tracking.startDay().fold({ "started" }, { it.message })
            }
            // Locked schedule: a paused day during working hours is resumed.
            s.enforce && open?.status == com.officetracker.core.model.WorkStatus.PAUSED && s.isWithinHours(now, Dates.zone) -> {
                tracking.resume().fold({ "resumed" }, { null })
            }
            else -> null
        }
    }

    /** Next automatic start / end, for display. */
    fun upcoming(): Pair<Long?, Long?> {
        val s = effective() ?: return null to null
        val now = System.currentTimeMillis()
        return (if (s.autoStart) s.nextStart(now, Dates.zone) else null) to (if (s.autoEnd) s.nextEnd(now, Dates.zone) else null)
    }

    private fun set(action: String, at: Long) {
        val am = alarms ?: return
        val pi = pending(action)
        try {
            // Exact alarms also let Android start the location service from the background.
            if (canScheduleExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            Log.w("ScheduleManager", "Exact alarm refused", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pending(action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        if (action == ScheduleReceiver.ACTION_START) 11 else 12,
        Intent(context, ScheduleReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
