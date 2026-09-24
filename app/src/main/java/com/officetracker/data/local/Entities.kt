package com.officetracker.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.officetracker.core.model.PlaceCategory
import com.officetracker.core.model.RoutePoint
import com.officetracker.core.model.Stay
import com.officetracker.core.model.WorkStatus
import com.officetracker.core.model.Workday

@Entity(tableName = "workdays", primaryKeys = ["userId", "date"])
data class WorkdayEntity(
    val userId: String,
    val date: String,
    val status: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val startName: String? = null,
    val startLat: Double? = null,
    val startLng: Double? = null,
    val endName: String? = null,
    val endLat: Double? = null,
    val endLng: Double? = null,
    val distanceMeters: Double = 0.0,
    val pausedMillis: Long = 0,
    val pausedAt: Long? = null,
    val pauseCount: Int = 0,
    val pointCount: Int = 0,
    val mockCount: Int = 0,
    /** Last point that moved the odometer; survives service restarts. */
    val anchorLat: Double? = null,
    val anchorLng: Double? = null,
    val anchorTime: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
    // ---- Attendance ----
    val checkInAt: Long? = null,
    val checkInPlace: String? = null,
    val checkOutAt: Long? = null,
    val checkOutPlace: String? = null,
    val insideMillis: Long = 0,
    val otMillis: Long = 0,
    val otApproved: Boolean = false,
    /** Time of the last GPS fix inside an attendance zone (for time accounting). */
    val zoneSince: Long? = null,
) {
    fun toModel() = Workday(
        userId = userId, date = date, status = WorkStatus.from(status),
        startedAt = startedAt, endedAt = endedAt,
        startName = startName, startLatitude = startLat, startLongitude = startLng,
        endName = endName, endLatitude = endLat, endLongitude = endLng,
        distanceMeters = distanceMeters, pausedMillis = pausedMillis, pausedAt = pausedAt,
        pauseCount = pauseCount, pointCount = pointCount, mockCount = mockCount, updatedAt = updatedAt,
        checkInAt = checkInAt, checkInPlace = checkInPlace, checkOutAt = checkOutAt, checkOutPlace = checkOutPlace,
        insideMillis = insideMillis, otMillis = otMillis, otApproved = otApproved,
    )
}

@Entity(
    tableName = "stays",
    indices = [Index("userId", "date"), Index("dirty"), Index("latitude", "longitude")],
)
data class StayEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val date: String,
    val latitude: Double,
    val longitude: Double,
    val sampleCount: Int = 1,
    val arrivalAt: Long,
    val departureAt: Long? = null,
    val lastSeenAt: Long,
    val placeId: String? = null,
    val name: String,
    val address: String? = null,
    val category: String = PlaceCategory.OTHER.name,
    val manualLabel: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
) {
    fun toModel() = Stay(
        id = id, latitude = latitude, longitude = longitude, arrivalAt = arrivalAt,
        departureAt = departureAt, lastSeenAt = lastSeenAt, placeId = placeId, name = name,
        address = address, category = PlaceCategory.from(category), manualLabel = manualLabel,
    )
}

@Entity(
    tableName = "track_points",
    indices = [Index("userId", "date"), Index("synced")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val date: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float,
    val time: Long,
    val mock: Boolean,
    val synced: Boolean = false,
) {
    fun toModel() = RoutePoint(latitude, longitude, time, accuracy, mock)
}
