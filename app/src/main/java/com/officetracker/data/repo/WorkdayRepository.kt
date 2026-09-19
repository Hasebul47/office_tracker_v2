package com.officetracker.data.repo

import androidx.room.withTransaction
import com.officetracker.core.model.DayDetail
import com.officetracker.core.model.PlaceCategory
import com.officetracker.core.model.WorkStatus
import com.officetracker.core.model.Workday
import com.officetracker.core.util.Dates
import com.officetracker.data.local.AppDatabase
import com.officetracker.data.local.WorkdayEntity
import com.officetracker.data.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class NamedLocation(val latitude: Double, val longitude: Double, val name: String?)

/** Local (offline-first) workday state. Room is the source of truth; the cloud is a mirror. */
class WorkdayRepository(
    private val db: AppDatabase,
    private val sync: SyncScheduler,
) {
    private val dao = db.trackerDao()

    fun observeOpenWorkday(uid: String): Flow<Workday?> =
        dao.observeOpenWorkday(uid).map { it?.toModel() }.distinctUntilChanged()

    suspend fun openWorkday(uid: String): Workday? = dao.getOpenWorkday(uid)?.toModel()

    /** What the "Today" screen shows: the open workday (even if it began yesterday), else today's. */
    fun observeCurrent(uid: String, today: String): Flow<Workday?> =
        combine(dao.observeOpenWorkday(uid), dao.observeWorkday(uid, today)) { open, todays ->
            (open ?: todays)?.toModel()
        }.distinctUntilChanged()

    fun observeDay(uid: String, date: String): Flow<DayDetail> = combine(
        dao.observeWorkday(uid, date),
        dao.observeStays(uid, date),
        dao.observePoints(uid, date),
    ) { day, stays, points ->
        DayDetail(day?.toModel(), stays.map { it.toModel() }, points.map { it.toModel() })
    }

    suspend fun hasLocalDay(uid: String, date: String): Boolean = dao.getWorkday(uid, date) != null

    fun observeWorkdays(uid: String, from: String, to: String): Flow<List<Workday>> =
        dao.observeWorkdays(uid, from, to).map { list -> list.map { it.toModel() } }

    fun observeStayCounts(uid: String, from: String, to: String): Flow<Map<String, Int>> =
        dao.observeStayCounts(uid, from, to).map { rows -> rows.associate { it.k to it.c } }

    fun observePendingUploads(uid: String): Flow<Int> = dao.observePendingUploads(uid)

    suspend fun workdays(uid: String, from: String, to: String): List<Workday> =
        dao.getWorkdays(uid, from, to).map { it.toModel() }

    suspend fun startDay(uid: String, at: Long, location: NamedLocation?): Workday {
        val day = db.withTransaction {
            // Close a workday someone forgot to end on a previous date.
            dao.getOpenWorkday(uid)?.let { stale ->
                if (stale.date != Dates.keyOf(at)) closeDay(stale, stale.anchorTime ?: stale.updatedAt, null)
            }
            val date = Dates.keyOf(at)
            val existing = dao.getWorkday(uid, date)
            val updated = when {
                existing == null -> WorkdayEntity(
                    userId = uid, date = date, status = WorkStatus.ACTIVE.name, startedAt = at,
                    startName = location?.name, startLat = location?.latitude, startLng = location?.longitude,
                    updatedAt = System.currentTimeMillis(),
                )
                existing.status == WorkStatus.ENDED.name -> existing.copy(
                    // Re-opening a finished day: the gap counts as a pause.
                    status = WorkStatus.ACTIVE.name,
                    pausedMillis = existing.pausedMillis + (at - (existing.endedAt ?: at)).coerceAtLeast(0),
                    pauseCount = existing.pauseCount + 1,
                    endedAt = null, endName = null, endLat = null, endLng = null,
                    anchorLat = null, anchorLng = null, anchorTime = null,
                    updatedAt = System.currentTimeMillis(), dirty = true,
                )
                else -> existing
            }
            dao.upsertWorkday(updated)
            updated
        }
        sync.requestSync()
        return day.toModel()
    }

    suspend fun pause(uid: String, at: Long) {
        db.withTransaction {
            val day = dao.getOpenWorkday(uid) ?: return@withTransaction
            if (day.status != WorkStatus.ACTIVE.name) return@withTransaction
            closeOpenStay(uid, at)
            dao.upsertWorkday(
                day.copy(
                    status = WorkStatus.PAUSED.name, pausedAt = at, pauseCount = day.pauseCount + 1,
                    updatedAt = System.currentTimeMillis(), dirty = true,
                )
            )
        }
        sync.requestSync()
    }

    suspend fun resume(uid: String, at: Long) {
        db.withTransaction {
            val day = dao.getOpenWorkday(uid) ?: return@withTransaction
            if (day.status != WorkStatus.PAUSED.name) return@withTransaction
            val paused = (at - (day.pausedAt ?: at)).coerceAtLeast(0)
            dao.upsertWorkday(
                day.copy(
                    status = WorkStatus.ACTIVE.name, pausedAt = null, pausedMillis = day.pausedMillis + paused,
                    // Movement during the pause is not counted.
                    anchorLat = null, anchorLng = null, anchorTime = null,
                    updatedAt = System.currentTimeMillis(), dirty = true,
                )
            )
        }
        sync.requestSync()
    }

    suspend fun endDay(uid: String, at: Long, location: NamedLocation?) {
        db.withTransaction {
            val day = dao.getOpenWorkday(uid) ?: return@withTransaction
            closeDay(day, at, location)
        }
        sync.requestSync(expedited = true)
    }

    private suspend fun closeDay(day: WorkdayEntity, at: Long, location: NamedLocation?) {
        closeOpenStay(day.userId, at)
        val runningPause = if (day.status == WorkStatus.PAUSED.name) (at - (day.pausedAt ?: at)).coerceAtLeast(0) else 0
        dao.upsertWorkday(
            day.copy(
                status = WorkStatus.ENDED.name, endedAt = at, pausedAt = null,
                pausedMillis = day.pausedMillis + runningPause,
                endName = location?.name, endLat = location?.latitude, endLng = location?.longitude,
                updatedAt = System.currentTimeMillis(), dirty = true,
            )
        )
    }

    private suspend fun closeOpenStay(uid: String, at: Long) {
        val open = dao.getOpenStay(uid) ?: return
        dao.upsertStay(
            open.copy(
                // Still there when paused/ended -> leave "now"; otherwise when the last fix was inside.
                departureAt = maxOf(open.arrivalAt, if (at - open.lastSeenAt < 5 * 60_000) at else open.lastSeenAt),
                updatedAt = System.currentTimeMillis(), dirty = true,
            )
        )
    }

    suspend fun relabelStay(stayId: String, name: String, category: PlaceCategory) {
        val stay = dao.getStay(stayId) ?: return
        dao.upsertStay(
            stay.copy(
                name = name.trim().ifEmpty { stay.name }, category = category.name, manualLabel = true,
                updatedAt = System.currentTimeMillis(), dirty = true,
            )
        )
        sync.requestSync()
    }
}
