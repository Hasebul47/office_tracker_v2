package com.officetracker.data.remote

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.officetracker.core.model.AppConfig
import com.officetracker.core.model.LiveState
import com.officetracker.core.model.Place
import com.officetracker.core.model.PlaceCategory
import com.officetracker.core.model.Role
import com.officetracker.core.model.RoutePoint
import com.officetracker.core.model.Stay
import com.officetracker.core.model.UserProfile
import com.officetracker.core.model.WorkStatus
import com.officetracker.core.model.Workday

/** Firestore layout (see firestore.rules):
 *  users/{uid}                              profile + role
 *  users/{uid}/days/{yyyy-MM-dd}            workday summary
 *  users/{uid}/days/{date}/stays/{stayId}   detected visits
 *  users/{uid}/days/{date}/tracks/{chunk}   GPS points, up to 400 per document
 *  live/{uid}                               latest position & device status
 *  places/{id}                              known offices / sites
 *  config/app, config/setup                 settings, one-time bootstrap marker
 */
object Paths {
    const val USERS = "users"
    const val DAYS = "days"
    const val STAYS = "stays"
    const val TRACKS = "tracks"
    const val LIVE = "live"
    const val PLACES = "places"
    const val CONFIG = "config"
    const val CONFIG_APP = "app"
    const val CONFIG_SETUP = "setup"

    fun user(db: FirebaseFirestore, uid: String) = db.collection(USERS).document(uid)
    fun day(db: FirebaseFirestore, uid: String, date: String) = user(db, uid).collection(DAYS).document(date)
}

private fun DocumentSnapshot.double(field: String): Double? = (get(field) as? Number)?.toDouble()
private fun DocumentSnapshot.long(field: String): Long? = (get(field) as? Number)?.toLong()
private fun DocumentSnapshot.int(field: String): Int? = (get(field) as? Number)?.toInt()

object Mappers {

    fun isV2Profile(doc: DocumentSnapshot): Boolean = doc.contains("role") && doc.contains("name") && !doc.contains("password")

    fun profile(doc: DocumentSnapshot): UserProfile = UserProfile(
        uid = doc.id,
        name = doc.getString("name").orEmpty(),
        phone = doc.getString("phone").orEmpty(),
        role = Role.from(doc.getString("role")),
        department = doc.getString("department").orEmpty(),
        disabled = doc.getBoolean("disabled") ?: false,
        createdAt = doc.long("createdAt") ?: 0L,
    )

    fun profileMap(p: UserProfile): Map<String, Any?> = mapOf(
        "name" to p.name,
        "phone" to p.phone,
        "role" to p.role.name,
        "department" to p.department,
        "disabled" to p.disabled,
        "createdAt" to p.createdAt,
    )

    fun place(doc: DocumentSnapshot): Place? {
        val lat = doc.double("lat") ?: return null
        val lng = doc.double("lng") ?: return null
        return Place(
            id = doc.id,
            name = doc.getString("name").orEmpty(),
            category = PlaceCategory.from(doc.getString("category")),
            latitude = lat,
            longitude = lng,
            radiusMeters = doc.double("radius") ?: 100.0,
            address = doc.getString("address"),
        )
    }

    fun placeMap(p: Place): Map<String, Any?> = mapOf(
        "name" to p.name,
        "category" to p.category.name,
        "lat" to p.latitude,
        "lng" to p.longitude,
        "radius" to p.radiusMeters,
        "address" to p.address,
    )

    fun config(doc: DocumentSnapshot?): AppConfig {
        val d = AppConfig()
        if (doc == null || !doc.exists()) return d
        return AppConfig(
            ratePerKm = doc.double("ratePerKm") ?: d.ratePerKm,
            stayRadiusMeters = doc.double("stayRadiusMeters") ?: d.stayRadiusMeters,
            minStayMinutes = doc.int("minStayMinutes") ?: d.minStayMinutes,
            maxAccuracyMeters = doc.double("maxAccuracyMeters") ?: d.maxAccuracyMeters,
        )
    }

    fun configMap(c: AppConfig): Map<String, Any> = mapOf(
        "ratePerKm" to c.ratePerKm,
        "stayRadiusMeters" to c.stayRadiusMeters,
        "minStayMinutes" to c.minStayMinutes,
        "maxAccuracyMeters" to c.maxAccuracyMeters,
    )

    fun live(doc: DocumentSnapshot): LiveState = LiveState(
        uid = doc.id,
        status = WorkStatus.from(doc.getString("status")),
        latitude = doc.double("lat"),
        longitude = doc.double("lng"),
        accuracy = doc.double("accuracy")?.toFloat(),
        placeName = doc.getString("placeName"),
        batteryPercent = doc.int("battery"),
        charging = doc.getBoolean("charging") ?: false,
        gpsEnabled = doc.getBoolean("gpsEnabled") ?: true,
        mockLocation = doc.getBoolean("mock") ?: false,
        distanceMeters = doc.double("distanceMeters") ?: 0.0,
        appVersion = doc.getString("appVersion"),
        updatedAt = doc.long("updatedAt") ?: 0L,
    )

    fun liveMap(s: LiveState): Map<String, Any?> = mapOf(
        "status" to s.status.name,
        "lat" to s.latitude,
        "lng" to s.longitude,
        "accuracy" to s.accuracy?.toDouble(),
        "placeName" to s.placeName,
        "battery" to s.batteryPercent,
        "charging" to s.charging,
        "gpsEnabled" to s.gpsEnabled,
        "mock" to s.mockLocation,
        "distanceMeters" to s.distanceMeters,
        "appVersion" to s.appVersion,
        "updatedAt" to s.updatedAt,
    )

    fun workday(uid: String, doc: DocumentSnapshot): Workday? {
        if (!doc.exists()) return null
        return Workday(
            userId = uid,
            date = doc.id,
            status = WorkStatus.from(doc.getString("status")),
            startedAt = doc.long("startedAt") ?: 0L,
            endedAt = doc.long("endedAt"),
            startName = doc.getString("startName"),
            startLatitude = doc.double("startLat"),
            startLongitude = doc.double("startLng"),
            endName = doc.getString("endName"),
            endLatitude = doc.double("endLat"),
            endLongitude = doc.double("endLng"),
            distanceMeters = doc.double("distanceMeters") ?: 0.0,
            pausedMillis = doc.long("pausedMillis") ?: 0L,
            pausedAt = doc.long("pausedAt"),
            pauseCount = doc.int("pauseCount") ?: 0,
            pointCount = doc.int("pointCount") ?: 0,
            mockCount = doc.int("mockCount") ?: 0,
        )
    }

    fun workdayMap(w: Workday, updatedAt: Long): Map<String, Any?> = mapOf(
        "date" to w.date,
        "status" to w.status.name,
        "startedAt" to w.startedAt,
        "endedAt" to w.endedAt,
        "startName" to w.startName,
        "startLat" to w.startLatitude,
        "startLng" to w.startLongitude,
        "endName" to w.endName,
        "endLat" to w.endLatitude,
        "endLng" to w.endLongitude,
        "distanceMeters" to w.distanceMeters,
        "pausedMillis" to w.pausedMillis,
        "pausedAt" to w.pausedAt,
        "pauseCount" to w.pauseCount,
        "pointCount" to w.pointCount,
        "mockCount" to w.mockCount,
        "updatedAt" to updatedAt,
    )

    fun stay(doc: DocumentSnapshot): Stay? {
        val lat = doc.double("lat") ?: return null
        val lng = doc.double("lng") ?: return null
        val arrival = doc.long("arrivalAt") ?: return null
        return Stay(
            id = doc.id,
            latitude = lat,
            longitude = lng,
            arrivalAt = arrival,
            departureAt = doc.long("departureAt"),
            lastSeenAt = doc.long("lastSeenAt") ?: arrival,
            placeId = doc.getString("placeId"),
            name = doc.getString("name").orEmpty(),
            address = doc.getString("address"),
            category = PlaceCategory.from(doc.getString("category")),
            manualLabel = doc.getBoolean("manual") ?: false,
        )
    }

    fun stayMap(s: Stay, updatedAt: Long): Map<String, Any?> = mapOf(
        "lat" to s.latitude,
        "lng" to s.longitude,
        "arrivalAt" to s.arrivalAt,
        "departureAt" to s.departureAt,
        "lastSeenAt" to s.lastSeenAt,
        "placeId" to s.placeId,
        "name" to s.name,
        "address" to s.address,
        "category" to s.category.name,
        "manual" to s.manualLabel,
        "updatedAt" to updatedAt,
    )

    /** Track chunks store parallel arrays - far cheaper than one document per GPS fix. */
    fun trackChunkMap(points: List<RoutePoint>): Map<String, Any> = mapOf(
        "from" to points.first().time,
        "to" to points.last().time,
        "t" to points.map { it.time },
        "lat" to points.map { it.latitude },
        "lng" to points.map { it.longitude },
        "acc" to points.map { it.accuracy.toDouble() },
        "mock" to points.map { it.mock },
    )

    @Suppress("UNCHECKED_CAST")
    fun trackChunk(doc: DocumentSnapshot): List<RoutePoint> {
        val t = (doc.get("t") as? List<Number>) ?: return emptyList()
        val lat = (doc.get("lat") as? List<Number>) ?: return emptyList()
        val lng = (doc.get("lng") as? List<Number>) ?: return emptyList()
        val acc = (doc.get("acc") as? List<Number>).orEmpty()
        val mock = (doc.get("mock") as? List<Boolean>).orEmpty()
        val n = minOf(t.size, lat.size, lng.size)
        return List(n) { i ->
            RoutePoint(
                latitude = lat[i].toDouble(),
                longitude = lng[i].toDouble(),
                time = t[i].toLong(),
                accuracy = acc.getOrNull(i)?.toFloat() ?: 0f,
                mock = mock.getOrNull(i) ?: false,
            )
        }
    }
}
