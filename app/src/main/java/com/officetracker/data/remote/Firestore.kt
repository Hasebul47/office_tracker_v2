package com.officetracker.data.remote

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.officetracker.core.model.AppConfig
import com.officetracker.core.model.AttendanceSettings
import com.officetracker.core.model.Billing
import com.officetracker.core.model.Company
import com.officetracker.core.model.Features
import com.officetracker.core.model.Payment
import com.officetracker.core.model.Plan
import com.officetracker.core.model.PlatformConfig
import com.officetracker.core.model.LiveState
import com.officetracker.core.model.Place
import com.officetracker.core.model.PlaceCategory
import com.officetracker.core.model.Role
import com.officetracker.core.model.RoutePoint
import com.officetracker.core.model.Stay
import com.officetracker.core.model.UserProfile
import com.officetracker.core.model.WorkStatus
import com.officetracker.core.model.WorkSchedule
import com.officetracker.core.model.Workday

/** Firestore layout (see firestore.rules):
 *  platform/config, platform/setup          platform switches, one-time bootstrap marker
 *  plans/{planId}                           sellable packages
 *  companies/{cid}                          tenant: subscription, limits, features, settings
 *  companies/{cid}/places|live|payments     per-company data
 *  users/{uid}                              profile + role + companyId
 *  users/{uid}/days/{yyyy-MM-dd}/...        workdays, stays, GPS track chunks
 */
object Paths {
    const val USERS = "users"
    const val DAYS = "days"
    const val STAYS = "stays"
    const val TRACKS = "tracks"
    const val LIVE = "live"
    const val PLACES = "places"
    const val PAYMENTS = "payments"
    const val COMPANIES = "companies"
    const val PLANS = "plans"
    const val PLATFORM = "platform"
    const val PLATFORM_CONFIG = "config"
    const val PLATFORM_SETUP = "setup"

    fun user(db: FirebaseFirestore, uid: String) = db.collection(USERS).document(uid)
    fun day(db: FirebaseFirestore, uid: String, date: String) = user(db, uid).collection(DAYS).document(date)
    fun company(db: FirebaseFirestore, cid: String) = db.collection(COMPANIES).document(cid)
    fun places(db: FirebaseFirestore, cid: String) = company(db, cid).collection(PLACES)
    fun live(db: FirebaseFirestore, cid: String) = company(db, cid).collection(LIVE)
    fun payments(db: FirebaseFirestore, cid: String) = company(db, cid).collection(PAYMENTS)
    fun platformConfig(db: FirebaseFirestore) = db.collection(PLATFORM).document(PLATFORM_CONFIG)
    fun platformSetup(db: FirebaseFirestore) = db.collection(PLATFORM).document(PLATFORM_SETUP)
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
        companyId = doc.getString("companyId"),
        activeDeviceId = doc.getString("activeDeviceId"),
        activeDeviceName = doc.getString("activeDeviceName"),
        activeSince = doc.long("activeSince"),
        schedule = WorkSchedule.fromMap(doc.get("schedule") as? Map<*, *>),
    )

    fun profileMap(p: UserProfile): Map<String, Any?> = mapOf(
        "name" to p.name,
        "phone" to p.phone,
        "role" to p.role.name,
        "department" to p.department,
        "disabled" to p.disabled,
        "createdAt" to p.createdAt,
        "companyId" to p.companyId,
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
            attendance = doc.getBoolean("attendance") ?: false,
        )
    }

    fun placeMap(p: Place): Map<String, Any?> = mapOf(
        "name" to p.name,
        "category" to p.category.name,
        "lat" to p.latitude,
        "lng" to p.longitude,
        "radius" to p.radiusMeters,
        "address" to p.address,
        "attendance" to p.attendance,
    )

    fun config(m: Map<*, *>?): AppConfig {
        val d = AppConfig()
        if (m == null) return d
        fun num(k: String) = (m[k] as? Number)
        return AppConfig(
            ratePerKm = num("ratePerKm")?.toDouble() ?: d.ratePerKm,
            stayRadiusMeters = num("stayRadiusMeters")?.toDouble() ?: d.stayRadiusMeters,
            minStayMinutes = num("minStayMinutes")?.toInt() ?: d.minStayMinutes,
            maxAccuracyMeters = num("maxAccuracyMeters")?.toDouble() ?: d.maxAccuracyMeters,
            schedule = WorkSchedule.fromMap(m["schedule"] as? Map<*, *>) ?: d.schedule,
            attendance = AttendanceSettings.fromMap(m["attendance"] as? Map<*, *>),
        )
    }

    fun configMap(c: AppConfig): Map<String, Any> = mapOf(
        "ratePerKm" to c.ratePerKm,
        "stayRadiusMeters" to c.stayRadiusMeters,
        "minStayMinutes" to c.minStayMinutes,
        "maxAccuracyMeters" to c.maxAccuracyMeters,
        "schedule" to c.schedule.toMap(),
        "attendance" to c.attendance.toMap(),
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
        dayStartedAt = doc.long("dayStartedAt"),
        inZone = doc.getBoolean("inZone"),
        zoneName = doc.getString("zoneName"),
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
    ) + buildMap {
        // Only written when known, so a status-only update (pause / end) merged into live/{uid}
        // does not erase what the admin's attendance and late marks are based on.
        s.dayStartedAt?.let { put("dayStartedAt", it) }
        s.inZone?.let { put("inZone", it) }
        s.zoneName?.let { put("zoneName", it) }
    }

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
            updatedAt = doc.long("updatedAt") ?: 0L,
            checkInAt = doc.long("checkInAt"),
            checkInPlace = doc.getString("checkInPlace"),
            checkOutAt = doc.long("checkOutAt"),
            checkOutPlace = doc.getString("checkOutPlace"),
            insideMillis = doc.long("insideMillis") ?: 0L,
            otMillis = doc.long("otMillis") ?: 0L,
            otApproved = doc.getBoolean("otApproved") ?: false,
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
        "checkInAt" to w.checkInAt,
        "checkInPlace" to w.checkInPlace,
        "checkOutAt" to w.checkOutAt,
        "checkOutPlace" to w.checkOutPlace,
        "insideMillis" to w.insideMillis,
        "otMillis" to w.otMillis,
        // otApproved is written by administrators in the cloud; phones never overwrite it.
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

    // ---------- SaaS ----------

    fun company(doc: DocumentSnapshot): Company? {
        if (!doc.exists()) return null
        return Company(
            id = doc.id,
            name = doc.getString("name").orEmpty(),
            contactName = doc.getString("contactName").orEmpty(),
            contactPhone = doc.getString("contactPhone").orEmpty(),
            email = doc.getString("email").orEmpty(),
            address = doc.getString("address").orEmpty(),
            notes = doc.getString("notes").orEmpty(),
            planId = doc.getString("planId"),
            planName = doc.getString("planName").orEmpty(),
            billing = Billing.from(doc.getString("billing")),
            price = doc.double("price") ?: 0.0,
            suspended = doc.getBoolean("suspended") ?: false,
            suspendReason = doc.getString("suspendReason"),
            startedAt = doc.long("startedAt") ?: 0L,
            expiresAt = doc.long("expiresAt") ?: 0L,
            accessUntil = doc.long("accessUntil") ?: 0L,
            maxUsers = doc.int("maxUsers") ?: 0,
            maxAdmins = doc.int("maxAdmins") ?: 1,
            userCount = doc.int("userCount") ?: 0,
            adminCount = doc.int("adminCount") ?: 0,
            features = Features.fromMap(doc.get("features") as? Map<*, *>),
            settings = config(doc.get("settings") as? Map<*, *>),
            createdAt = doc.long("createdAt") ?: 0L,
        )
    }

    /** Subscription + limits fields (everything the super admin controls). */
    fun subscriptionMap(c: Company): Map<String, Any?> = mapOf(
        "planId" to c.planId,
        "planName" to c.planName,
        "billing" to c.billing.name,
        "price" to c.price,
        "suspended" to c.suspended,
        "suspendReason" to c.suspendReason,
        "startedAt" to c.startedAt,
        "expiresAt" to c.expiresAt,
        "accessUntil" to c.accessUntil,
        "maxUsers" to c.maxUsers,
        "maxAdmins" to c.maxAdmins,
        "features" to c.features.toMap(),
        "updatedAt" to System.currentTimeMillis(),
    )

    fun companyProfileMap(c: Company): Map<String, Any?> = mapOf(
        "name" to c.name,
        "contactName" to c.contactName,
        "contactPhone" to c.contactPhone,
        "email" to c.email,
        "address" to c.address,
        "notes" to c.notes,
        "updatedAt" to System.currentTimeMillis(),
    )

    fun newCompanyMap(c: Company): Map<String, Any?> =
        companyProfileMap(c) + subscriptionMap(c) + mapOf(
            "userCount" to c.userCount,
            "adminCount" to c.adminCount,
            "settings" to configMap(c.settings),
            "createdAt" to c.createdAt,
        )

    fun plan(doc: DocumentSnapshot): Plan = Plan(
        id = doc.id,
        name = doc.getString("name").orEmpty(),
        description = doc.getString("description").orEmpty(),
        billing = Billing.from(doc.getString("billing")),
        durationDays = doc.int("durationDays") ?: 30,
        price = doc.double("price") ?: 0.0,
        maxUsers = doc.int("maxUsers") ?: 10,
        maxAdmins = doc.int("maxAdmins") ?: 1,
        features = Features.fromMap(doc.get("features") as? Map<*, *>),
        active = doc.getBoolean("active") ?: true,
        sortOrder = doc.int("sortOrder") ?: 0,
    )

    fun planMap(p: Plan): Map<String, Any?> = mapOf(
        "name" to p.name,
        "description" to p.description,
        "billing" to p.billing.name,
        "durationDays" to p.durationDays,
        "price" to p.price,
        "maxUsers" to p.maxUsers,
        "maxAdmins" to p.maxAdmins,
        "features" to p.features.toMap(),
        "active" to p.active,
        "sortOrder" to p.sortOrder,
    )

    fun platform(doc: DocumentSnapshot?): PlatformConfig {
        val d = PlatformConfig()
        if (doc == null || !doc.exists()) return d
        return PlatformConfig(
            appName = doc.getString("appName") ?: d.appName,
            graceDays = doc.int("graceDays") ?: d.graceDays,
            supportPhone = doc.getString("supportPhone") ?: d.supportPhone,
            supportEmail = doc.getString("supportEmail") ?: d.supportEmail,
            announcement = doc.getString("announcement") ?: d.announcement,
            maintenanceMode = doc.getBoolean("maintenanceMode") ?: d.maintenanceMode,
            maintenanceMessage = doc.getString("maintenanceMessage") ?: d.maintenanceMessage,
            minVersionCode = doc.int("minVersionCode") ?: d.minVersionCode,
        )
    }

    fun platformMap(p: PlatformConfig): Map<String, Any?> = mapOf(
        "appName" to p.appName,
        "graceDays" to p.graceDays,
        "supportPhone" to p.supportPhone,
        "supportEmail" to p.supportEmail,
        "announcement" to p.announcement,
        "maintenanceMode" to p.maintenanceMode,
        "maintenanceMessage" to p.maintenanceMessage,
        "minVersionCode" to p.minVersionCode,
        "updatedAt" to System.currentTimeMillis(),
    )

    fun payment(doc: DocumentSnapshot): Payment = Payment(
        id = doc.id,
        amount = doc.double("amount") ?: 0.0,
        method = doc.getString("method").orEmpty(),
        reference = doc.getString("reference").orEmpty(),
        note = doc.getString("note").orEmpty(),
        extendedDays = doc.int("extendedDays") ?: 0,
        createdAt = doc.long("createdAt") ?: 0L,
    )

    fun paymentMap(p: Payment): Map<String, Any?> = mapOf(
        "amount" to p.amount,
        "method" to p.method,
        "reference" to p.reference,
        "note" to p.note,
        "extendedDays" to p.extendedDays,
        "createdAt" to p.createdAt,
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
