package com.officetracker.core.model

enum class Role(val label: String) {
    /** Platform owner: manages companies, plans and subscriptions. Belongs to no company. */
    SUPER_ADMIN("Super admin"),
    /** Company administrator: manages employees, places and settings of one company. */
    ADMIN("Administrator"),
    EMPLOYEE("Employee");

    companion object {
        fun from(value: String?): Role = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: EMPLOYEE
    }
}

data class UserProfile(
    val uid: String,
    val name: String,
    val phone: String,
    val role: Role,
    val department: String,
    val disabled: Boolean,
    val createdAt: Long,
    /** Tenant this person belongs to; null only for the super admin. */
    val companyId: String? = null,
    /** The one device currently allowed to use this account (single-device login). */
    val activeDeviceId: String? = null,
    val activeDeviceName: String? = null,
    val activeSince: Long? = null,
    /** Personal work schedule; null means the company schedule applies. */
    val schedule: WorkSchedule? = null,
) {
    val isAdmin: Boolean get() = role == Role.ADMIN
    val isSuperAdmin: Boolean get() = role == Role.SUPER_ADMIN
    val initials: String
        get() = name.split(' ').filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
}

enum class WorkStatus {
    NOT_STARTED, ACTIVE, PAUSED, ENDED;

    val isOnDuty: Boolean get() = this == ACTIVE || this == PAUSED

    companion object {
        fun from(value: String?): WorkStatus = entries.firstOrNull { it.name == value } ?: NOT_STARTED
    }
}

enum class PlaceCategory(val label: String) {
    OFFICE("Office"),
    FACTORY("Factory"),
    CLIENT("Client"),
    SUPPLIER("Supplier"),
    MEAL("Meal break"),
    HOME("Home"),
    OTHER("Other");

    companion object {
        fun from(value: String?): PlaceCategory = entries.firstOrNull { it.name == value } ?: OTHER
    }
}

/** A known office / site managed by the administrator. */
data class Place(
    val id: String,
    val name: String,
    val category: PlaceCategory,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val address: String? = null,
)

/** A company's own tracking settings (edited by its administrator). */
data class AppConfig(
    val ratePerKm: Double = 6.0,
    val stayRadiusMeters: Double = 80.0,
    val minStayMinutes: Int = 5,
    val maxAccuracyMeters: Double = 50.0,
    val schedule: WorkSchedule = WorkSchedule(),
)

/** Latest position and device health of one employee (Firestore live/{uid}). */
data class LiveState(
    val uid: String,
    val status: WorkStatus,
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Float?,
    val placeName: String?,
    val batteryPercent: Int?,
    val charging: Boolean,
    val gpsEnabled: Boolean,
    val mockLocation: Boolean,
    val distanceMeters: Double,
    val appVersion: String?,
    val updatedAt: Long,
    val dayStartedAt: Long? = null,
) {
    fun isStale(now: Long): Boolean = status.isOnDuty && now - updatedAt > STALE_AFTER_MS

    companion object {
        const val STALE_AFTER_MS = 15 * 60_000L
    }
}

data class Workday(
    val userId: String,
    val date: String,
    val status: WorkStatus,
    val startedAt: Long,
    val endedAt: Long?,
    val startName: String?,
    val startLatitude: Double?,
    val startLongitude: Double?,
    val endName: String?,
    val endLatitude: Double?,
    val endLongitude: Double?,
    val distanceMeters: Double,
    val pausedMillis: Long,
    val pausedAt: Long?,
    val pauseCount: Int,
    val pointCount: Int,
    val mockCount: Int,
    val updatedAt: Long = 0L,
) {
    /** Time on duty, excluding pauses. */
    fun activeMillis(now: Long): Long {
        val end = endedAt ?: now
        val runningPause = if (status == WorkStatus.PAUSED && pausedAt != null) (end - pausedAt).coerceAtLeast(0) else 0
        return (end - startedAt - pausedMillis - runningPause).coerceAtLeast(0)
    }
}

data class Stay(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val arrivalAt: Long,
    val departureAt: Long?,
    val lastSeenAt: Long,
    val placeId: String?,
    val name: String,
    val address: String?,
    val category: PlaceCategory,
    val manualLabel: Boolean,
) {
    val isOpen: Boolean get() = departureAt == null
    fun durationMillis(now: Long): Long = ((departureAt ?: maxOf(lastSeenAt, now)) - arrivalAt).coerceAtLeast(0)
}

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val time: Long,
    val accuracy: Float,
    val mock: Boolean = false,
)

/** Everything needed to show one employee's day. */
data class DayDetail(
    val workday: Workday?,
    val stays: List<Stay>,
    val route: List<RoutePoint>,
)
