package com.officetracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackerDao {

    // ---- Workdays ----
    @Upsert
    suspend fun upsertWorkday(day: WorkdayEntity)

    @Query("SELECT * FROM workdays WHERE userId = :userId AND date = :date")
    suspend fun getWorkday(userId: String, date: String): WorkdayEntity?

    @Query("SELECT * FROM workdays WHERE userId = :userId AND date = :date")
    fun observeWorkday(userId: String, date: String): Flow<WorkdayEntity?>

    /** The single open (ACTIVE or PAUSED) workday, whatever date it started on. */
    @Query("SELECT * FROM workdays WHERE userId = :userId AND status IN ('ACTIVE','PAUSED') ORDER BY startedAt DESC LIMIT 1")
    suspend fun getOpenWorkday(userId: String): WorkdayEntity?

    @Query("SELECT * FROM workdays WHERE userId = :userId AND status IN ('ACTIVE','PAUSED') ORDER BY startedAt DESC LIMIT 1")
    fun observeOpenWorkday(userId: String): Flow<WorkdayEntity?>

    @Query("SELECT * FROM workdays WHERE status IN ('ACTIVE','PAUSED') ORDER BY startedAt DESC LIMIT 1")
    suspend fun getAnyOpenWorkday(): WorkdayEntity?

    @Query("SELECT * FROM workdays WHERE userId = :userId AND date BETWEEN :from AND :to ORDER BY date DESC")
    fun observeWorkdays(userId: String, from: String, to: String): Flow<List<WorkdayEntity>>

    @Query("SELECT * FROM workdays WHERE userId = :userId AND date BETWEEN :from AND :to ORDER BY date ASC")
    suspend fun getWorkdays(userId: String, from: String, to: String): List<WorkdayEntity>

    @Query("SELECT * FROM workdays WHERE userId = :userId AND dirty = 1")
    suspend fun dirtyWorkdays(userId: String): List<WorkdayEntity>

    @Query("UPDATE workdays SET dirty = 0 WHERE userId = :userId AND date = :date AND updatedAt = :updatedAt")
    suspend fun markWorkdaySynced(userId: String, date: String, updatedAt: Long)

    @Query(
        """UPDATE workdays SET startLat = :lat, startLng = :lng, startName = COALESCE(startName, :name), updatedAt = :now, dirty = 1
           WHERE userId = :userId AND date = :date AND startLat IS NULL"""
    )
    suspend fun fillStart(userId: String, date: String, lat: Double, lng: Double, name: String?, now: Long)

    @Query("UPDATE workdays SET startName = :name, updatedAt = :now, dirty = 1 WHERE userId = :userId AND date = :date AND startName IS NULL")
    suspend fun fillStartName(userId: String, date: String, name: String, now: Long)

    @Query("UPDATE workdays SET endName = :name, updatedAt = :now, dirty = 1 WHERE userId = :userId AND date = :date AND endName IS NULL")
    suspend fun fillEndName(userId: String, date: String, name: String, now: Long)

    // ---- Stays ----
    @Upsert
    suspend fun upsertStay(stay: StayEntity)

    @Query("SELECT * FROM stays WHERE id = :id")
    suspend fun getStay(id: String): StayEntity?

    @Query("SELECT * FROM stays WHERE userId = :userId AND date = :date ORDER BY arrivalAt ASC")
    fun observeStays(userId: String, date: String): Flow<List<StayEntity>>

    @Query("SELECT * FROM stays WHERE userId = :userId AND date = :date ORDER BY arrivalAt ASC")
    suspend fun getStays(userId: String, date: String): List<StayEntity>

    @Query("SELECT * FROM stays WHERE userId = :userId AND departureAt IS NULL ORDER BY arrivalAt DESC LIMIT 1")
    suspend fun getOpenStay(userId: String): StayEntity?

    @Query("SELECT COUNT(*) FROM stays WHERE userId = :userId AND date BETWEEN :from AND :to")
    suspend fun countStays(userId: String, from: String, to: String): Int

    @Query("SELECT date AS k, COUNT(*) AS c FROM stays WHERE userId = :userId AND date BETWEEN :from AND :to GROUP BY date")
    fun observeStayCounts(userId: String, from: String, to: String): Flow<List<KeyCount>>

    /** Manually-labelled stays near a point, used to re-apply the employee's own labels. */
    @Query(
        """SELECT * FROM stays WHERE userId = :userId AND manualLabel = 1
           AND latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLng AND :maxLng
           ORDER BY updatedAt DESC LIMIT 20"""
    )
    suspend fun labelledStaysNear(userId: String, minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<StayEntity>

    @Query("SELECT * FROM stays WHERE userId = :userId AND dirty = 1")
    suspend fun dirtyStays(userId: String): List<StayEntity>

    @Query("UPDATE stays SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markStaySynced(id: String, updatedAt: Long)

    // ---- Track points ----
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPoint(point: TrackPointEntity): Long

    @Query("SELECT * FROM track_points WHERE userId = :userId AND date = :date ORDER BY time ASC")
    fun observePoints(userId: String, date: String): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM track_points WHERE userId = :userId AND date = :date ORDER BY time ASC")
    suspend fun getPoints(userId: String, date: String): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE userId = :userId AND date = :date ORDER BY time DESC LIMIT 1")
    suspend fun lastPoint(userId: String, date: String): TrackPointEntity?

    @Query("SELECT * FROM track_points WHERE userId = :userId AND synced = 0 ORDER BY date ASC, time ASC LIMIT :limit")
    suspend fun unsyncedPoints(userId: String, limit: Int): List<TrackPointEntity>

    @Query("UPDATE track_points SET synced = 1 WHERE id IN (:ids)")
    suspend fun markPointsSynced(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM track_points WHERE userId = :userId AND synced = 0")
    fun observeUnsyncedPointCount(userId: String): Flow<Int>

    @Query("SELECT (SELECT COUNT(*) FROM workdays WHERE userId = :userId AND dirty = 1) + (SELECT COUNT(*) FROM stays WHERE userId = :userId AND dirty = 1) + (SELECT COUNT(*) FROM track_points WHERE userId = :userId AND synced = 0)")
    fun observePendingUploads(userId: String): Flow<Int>

    /** Keeps the phone small: synced GPS points older than the cutoff are already in the cloud. */
    @Query("DELETE FROM track_points WHERE synced = 1 AND date < :beforeDate")
    suspend fun pruneSyncedPoints(beforeDate: String): Int
}

data class KeyCount(val k: String, val c: Int)
