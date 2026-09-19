package com.officetracker.tracking

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.officetracker.MainActivity
import com.officetracker.OfficeTrackerApp
import com.officetracker.R
import com.officetracker.core.model.WorkStatus
import com.officetracker.core.util.Format
import com.officetracker.data.local.WorkdayEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Foreground service that owns GPS updates while a workday is ACTIVE. */
class TrackingService : LifecycleService() {

    private var trackingJob: Job? = null
    private val mode = MutableStateFlow(MotionMode.MOVING)
    private var lastPlace: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PAUSE -> {
                lifecycleScope.launch { OfficeTrackerApp.container.tracking.pause() }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }
            else -> startTracking()
        }
        return START_STICKY
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startTracking() {
        if (!promoteToForeground(buildNotification(null))) return
        if (trackingJob?.isActive == true) return

        val container = OfficeTrackerApp.container
        val uid = container.auth.currentUid
        if (uid == null) {
            stopTracking()
            return
        }
        val processor = container.newProcessor(uid)

        trackingJob = lifecycleScope.launch {
            processor.restore()

            // Stop as soon as the workday is paused / ended (from any screen or device state).
            launch {
                container.db.trackerDao().observeOpenWorkday(uid).collect { day ->
                    if (day == null || day.status != WorkStatus.ACTIVE.name) stopTracking()
                    else updateNotification(day)
                }
            }

            // Heartbeat so the admin sees battery/GPS state even when no fixes arrive.
            launch {
                while (isActive) {
                    delay(3 * 60_000L)
                    runCatching { processor.heartbeat() }
                }
            }

            mode.flatMapLatest { m -> container.locationClient.updates(m.intervalMs, m.minIntervalMs) }
                .catch { e -> Log.w(TAG, "Location updates failed", e); stopTracking() }
                .collect { location ->
                    try {
                        val result = processor.onSample(location.toSample())
                        lastPlace = result.currentPlace
                        if (mode.value != result.mode) mode.value = result.mode
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process location", e)
                    }
                }
        }
    }

    private fun promoteToForeground(notification: Notification): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        true
    } catch (e: Exception) {
        // Android 12+ refuses when started from the background without an exemption, and
        // Android 14+ refuses without location permission.
        Log.w(TAG, "Cannot start foreground tracking", e)
        stopSelf()
        false
    }

    private fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(day: WorkdayEntity) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(day))
        } catch (_: SecurityException) {
        }
    }

    private fun buildNotification(day: WorkdayEntity?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pause = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val now = System.currentTimeMillis()
        val builder = NotificationCompat.Builder(this, OfficeTrackerApp.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle("On duty")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Pause", pause)
        if (day != null) {
            val active = day.toModel().activeMillis(now)
            val where = lastPlace?.let { " · $it" }.orEmpty()
            builder.setContentText("${Format.distance(day.distanceMeters)} travelled$where")
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(now - active)
        } else {
            builder.setContentText("Getting your location…")
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "TrackingService"
        private const val NOTIFICATION_ID = 101
        private const val ACTION_PAUSE = "com.officetracker.action.PAUSE"
        private const val ACTION_STOP = "com.officetracker.action.STOP"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start tracking service", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                // Service not running or app in background; nothing to stop.
            }
        }
    }
}
