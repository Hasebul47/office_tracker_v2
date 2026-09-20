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
