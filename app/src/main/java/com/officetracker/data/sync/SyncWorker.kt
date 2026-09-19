package com.officetracker.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.officetracker.OfficeTrackerApp
import com.google.firebase.auth.FirebaseAuth

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = OfficeTrackerApp.container
        if (!container.firebaseReady) return Result.success()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()
        val uploader = container.uploader
        return try {
            uploader.uploadPending(uid)
            uploader.pruneOldPoints()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 8) Result.retry() else Result.failure()
        }
    }
}
