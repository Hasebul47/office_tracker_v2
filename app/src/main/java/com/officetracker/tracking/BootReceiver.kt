package com.officetracker.tracking

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.officetracker.MainActivity
import com.officetracker.OfficeTrackerApp
import com.officetracker.R
import com.officetracker.core.model.WorkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Resumes tracking after a reboot or app update if the employee's day is still active. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = OfficeTrackerApp.container
                if (!container.firebaseReady) return@launch
                val uid = container.auth.currentUid ?: return@launch
                val open = container.workdays.openWorkday(uid) ?: return@launch
                if (open.status != WorkStatus.ACTIVE) return@launch
                if (container.locationClient.hasBackgroundPermission()) {
                    TrackingService.start(context)
                } else {
                    notifyResumeNeeded(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun notifyResumeNeeded(context: Context) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, OfficeTrackerApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle("Tracking paused by restart")
            .setContentText("Open Office Tracker to continue your workday.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(202, n)
        } catch (_: SecurityException) {
        }
    }
}
