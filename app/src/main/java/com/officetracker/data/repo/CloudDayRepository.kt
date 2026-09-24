package com.officetracker.data.repo

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.officetracker.core.model.DayDetail
import com.officetracker.core.model.Workday
import com.officetracker.data.remote.Mappers
import com.officetracker.data.remote.Paths
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/** Reads any employee's history from the cloud (administrator, or an employee on a new phone). */
class CloudDayRepository(private val db: FirebaseFirestore) {

    suspend fun fetchDay(uid: String, date: String): Result<DayDetail> = runCatching {
        coroutineScope {
            val dayRef = Paths.day(db, uid, date)
            val day = async { dayRef.get(Source.DEFAULT).await() }
            val stays = async { dayRef.collection(Paths.STAYS).get().await() }
            val tracks = async { dayRef.collection(Paths.TRACKS).get().await() }
            DayDetail(
                workday = Mappers.workday(uid, day.await()),
                stays = stays.await().documents.mapNotNull { Mappers.stay(it) }.sortedBy { it.arrivalAt },
                route = tracks.await().documents.flatMap { Mappers.trackChunk(it) }.sortedBy { it.time },
            )
        }
    }.mapError()

    suspend fun fetchWorkdays(uid: String, from: String, to: String): Result<List<Workday>> = runCatching {
        Paths.user(db, uid).collection(Paths.DAYS)
            .whereGreaterThanOrEqualTo("date", from)
            .whereLessThanOrEqualTo("date", to)
            .get().await()
            .documents.mapNotNull { Mappers.workday(uid, it) }
            .sortedBy { it.date }
    }.mapError()

    /** Administrator: approves (or un-approves) the overtime recorded on one day. */
    suspend fun setOtApproved(uid: String, date: String, approved: Boolean): Result<Unit> = runCatching {
        Paths.day(db, uid, date).update(
            mapOf("otApproved" to approved, "updatedAt" to System.currentTimeMillis())
        ).await()
        Unit
    }.mapError()

    suspend fun fetchStayCount(uid: String, date: String): Int = runCatching {
        Paths.day(db, uid, date).collection(Paths.STAYS).get().await().size()
    }.getOrDefault(0)
}
