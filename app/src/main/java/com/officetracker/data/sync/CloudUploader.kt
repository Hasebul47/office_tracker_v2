package com.officetracker.data.sync

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.officetracker.core.util.Dates
import com.officetracker.data.local.AppDatabase
import com.officetracker.data.remote.Mappers
import com.officetracker.data.remote.Paths
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Pushes dirty rows to Firestore in batches. Every write is idempotent (stable document ids),
 * so a crash between "uploaded" and "marked synced" only causes a harmless re-upload.
 */
class CloudUploader(
    private val db: AppDatabase,
    private val firestore: FirebaseFirestore,
) {
    private val dao = db.trackerDao()
    private val mutex = Mutex()

    suspend fun uploadPending(uid: String) = mutex.withLock {
        uploadWorkdays(uid)
        uploadStays(uid)
        uploadPoints(uid)
    }

    private suspend fun uploadWorkdays(uid: String) {
        val days = dao.dirtyWorkdays(uid)
        if (days.isEmpty()) return
        days.chunked(MAX_BATCH).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { d ->
                batch.set(Paths.day(firestore, uid, d.date), Mappers.workdayMap(d.toModel(), d.updatedAt), SetOptions.merge())
            }
            batch.commit().await()
            chunk.forEach { dao.markWorkdaySynced(uid, it.date, it.updatedAt) }
        }
    }

    private suspend fun uploadStays(uid: String) {
        val stays = dao.dirtyStays(uid)
        if (stays.isEmpty()) return
        stays.chunked(MAX_BATCH).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { s ->
                val ref = Paths.day(firestore, uid, s.date).collection(Paths.STAYS).document(s.id)
                batch.set(ref, Mappers.stayMap(s.toModel(), s.updatedAt))
            }
            batch.commit().await()
            chunk.forEach { dao.markStaySynced(it.id, it.updatedAt) }
        }
    }

    private suspend fun uploadPoints(uid: String) {
        while (true) {
            val pending = dao.unsyncedPoints(uid, POINTS_PER_ROUND)
            if (pending.isEmpty()) return
            val batch = firestore.batch()
            pending.groupBy { it.date }.forEach { (date, points) ->
                points.chunked(POINTS_PER_CHUNK).forEach { chunk ->
                    // Id = first timestamp: re-uploading the same points overwrites the same doc.
                    val ref = Paths.day(firestore, uid, date).collection(Paths.TRACKS).document(chunk.first().time.toString())
                    batch.set(ref, Mappers.trackChunkMap(chunk.map { it.toModel() }))
                }
            }
            batch.commit().await()
            // SQLite allows at most 999 bound variables on older Android versions.
            pending.map { it.id }.chunked(500).forEach { dao.markPointsSynced(it) }
            if (pending.size < POINTS_PER_ROUND) return
        }
    }

    /** GPS points older than 30 days that are safely in the cloud are removed from the phone. */
    suspend fun pruneOldPoints() {
        dao.pruneSyncedPoints(Dates.key(Dates.today().minusDays(30)))
    }

    private companion object {
        const val MAX_BATCH = 400
        const val POINTS_PER_CHUNK = 400
        const val POINTS_PER_ROUND = 4000
    }
}
