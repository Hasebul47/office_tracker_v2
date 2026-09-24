package com.officetracker.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.officetracker.OfficeTrackerApp
import com.officetracker.core.model.WorkSchedule
import com.officetracker.core.model.WorkStatus
import com.officetracker.core.util.Dates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.officetracker.data.repo.CompanyState
import com.officetracker.data.repo.Session

/** Fires at the scheduled start / end time and starts or ends the workday automatically. */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                val c = OfficeTrackerApp.container
                if (!c.firebaseReady) return@launch
                // A cold start from the alarm: wait until the profile and company are loaded.
                withTimeoutOrNull(8_000) {
                    val session = c.auth.session.first { it !is Session.Loading }
                    if (session is Session.SignedIn && session.profile.companyId != null) {
                        c.org.company.first { it !is CompanyState.Loading }
                    }
                    Unit
                }
                when (intent.action) {
                    ACTION_START -> c.scheduler.effective()?.let { autoStart(context, it) }
                    ACTION_END -> c.scheduler.effective()?.let { autoEnd(context) }
                }
                c.scheduler.reschedule()
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun autoStart(context: Context, schedule: WorkSchedule) {
        val c = OfficeTrackerApp.container
        val uid = c.auth.currentUid ?: return
        if (!schedule.isWorkDay(Dates.today())) return
        val open = c.workdays.openWorkday(uid)
        // Already on duty (or deliberately paused) today. A day left open from an earlier date
        // does not block: startDay() closes it first.
        if (open != null && open.date == Dates.todayKey()) return
        val time = WorkSchedule.formatMinute(schedule.startMinute)
        if (!c.locationClient.hasForegroundPermission()) {
            Notifier.alert(
                context, Notifier.ID_SCHEDULE, "Your workday starts at $time",
                "Tap to start tracking. Location permission is missing.",
            )
            return
        }
        c.tracking.startDay(manual = false)
            .onSuccess {
                Notifier.alert(context, Notifier.ID_SCHEDULE, "Workday started", "Tracking started automatically at $time.")
            }
            .onFailure {
                Notifier.alert(context, Notifier.ID_SCHEDULE, "Couldn't start your workday", (it.message ?: "Open the app to start.") + " Tap to open.")
            }
    }

    private suspend fun autoEnd(context: Context) {
        val c = OfficeTrackerApp.container
        val uid = c.auth.currentUid ?: return
        val open = c.workdays.openWorkday(uid) ?: return
        if (open.status != WorkStatus.ACTIVE && open.status != WorkStatus.PAUSED) return
        c.tracking.endDay(manual = false)
            .onSuccess { Notifier.alert(context, Notifier.ID_SCHEDULE, "Workday ended", "Your workday was ended automatically at the scheduled time.") }
            .onFailure { Notifier.alert(context, Notifier.ID_SCHEDULE, "Couldn't end your workday", "Open the app to end it.") }
    }

    companion object {
        const val ACTION_START = "com.officetracker.schedule.START"
        const val ACTION_END = "com.officetracker.schedule.END"
    }
}
