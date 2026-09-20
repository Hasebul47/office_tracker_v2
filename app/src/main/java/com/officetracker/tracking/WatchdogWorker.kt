package com.officetracker.tracking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.officetracker.OfficeTrackerApp
import com.officetracker.core.model.WorkStatus
import java.util.concurrent.TimeUnit

/**
 * Every 15 minutes: if a workday is on but tracking is not running (phone makers like Xiaomi,
 * Oppo and Vivo kill background apps), restart it, or ask the employee to open the app.
 * Also warns when no GPS fix has been stored for a long time.
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = OfficeTrackerApp.container
        if (!c.firebaseReady) return Result.success()
        val uid = c.auth.currentUid ?: return Result.success()
        val open = c.workdays.openWorkday(uid) ?: return Result.success()
        if (open.status != WorkStatus.ACTIVE) return Result.success()
        val planOk = c.org.currentCompany?.access(System.currentTimeMillis())?.usable ?: true
        if (!planOk) return Result.success()

        if (!TrackingService.running) {
            val started = c.locationClient.hasForegroundPermission() && TrackingService.start(applicationContext)
            if (!started) {
                Notifier.alert(
                    applicationContext, Notifier.ID_RESUME, "Tracking stopped",
                    "Your phone stopped Office Tracker. Tap to continue recording your workday.",
                )
            }
            return Result.success()
        }
        val lastFix = c.db.trackerDao().lastPoint(uid, open.date)?.time ?: open.startedAt
        if (System.currentTimeMillis() - lastFix > 30 * 60_000L) {
            Notifier.alert(
                applicationContext, Notifier.ID_NO_GPS, "No GPS for 30 minutes",
                if (c.locationClient.isLocationEnabled()) "Your location isn't being recorded. Move near a window or open the app."
                else "Location is turned off. Turn it on to keep recording your workday.",
            )
        } else {
            Notifier.cancel(applicationContext, Notifier.ID_NO_GPS)
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "tracking-watchdog", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
