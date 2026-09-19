package com.officetracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.osmdroid.config.Configuration
import java.io.File

class OfficeTrackerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        configureMaps()
        createChannels()
        container.start()
    }

    private fun configureMaps() {
        Configuration.getInstance().apply {
            userAgentValue = "$packageName/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
            tileFileSystemCacheMaxBytes = 150L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 120L * 1024 * 1024
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, "Workday tracking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while your workday is being recorded"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tracking problems that need your attention"
            }
        )
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_ALERTS = "alerts"

        lateinit var container: AppContainer
            private set
    }
}
