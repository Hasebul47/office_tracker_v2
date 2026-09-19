package com.officetracker.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class SyncScheduler(context: Context) {
    private val wm = WorkManager.getInstance(context)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    @Volatile private var lastRequest = 0L

    /** Upload pending local changes as soon as there is a network connection. [expedited] skips throttling. */
    fun requestSync(expedited: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!expedited && now - lastRequest < 30_000) return
        lastRequest = now
        val builder = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        wm.enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.APPEND_OR_REPLACE, builder.build())
    }

    /** Safety net in case the app or service was killed with data still queued. */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelAll() {
        wm.cancelUniqueWork(ONE_TIME)
        wm.cancelUniqueWork(PERIODIC)
    }

    private companion object {
        const val ONE_TIME = "cloud-sync"
        const val PERIODIC = "cloud-sync-periodic"
    }
}
