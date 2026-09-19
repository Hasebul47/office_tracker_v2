package com.officetracker.tracking

import android.content.Context
import androidx.room.withTransaction
import com.officetracker.BuildConfig
import com.officetracker.core.model.LiveState
import com.officetracker.core.model.PlaceCategory
import com.officetracker.core.model.WorkStatus
import com.officetracker.data.local.AppDatabase
import com.officetracker.data.local.StayEntity
import com.officetracker.data.local.TrackPointEntity
import com.officetracker.data.local.WorkdayEntity
import com.officetracker.data.repo.OrgRepository
import com.officetracker.data.sync.SyncScheduler

enum class MotionMode(val intervalMs: Long, val minIntervalMs: Long) {
    /** Travelling: frequent fixes for an accurate route. */
    MOVING(10_000, 5_000),
    /** Inside a stay: slower fixes save battery; still enough to notice leaving. */
    STATIONARY(45_000, 30_000),
}

data class ProcessResult(
    val mode: MotionMode,
    val workday: WorkdayEntity?,
    val currentPlace: String?,
)

/**
 * Turns raw GPS fixes into workday data: filters noise, advances the odometer, stores the
 * route, detects stays and names them, and publishes the live position for the admin map.
 * One instance lives inside the tracking service.
 */
class TrackingProcessor(
    private val context: Context,
    private val uid: String,
    private val db: AppDatabase,
    private val org: OrgRepository,
    private val namer: PlaceNamer,
    private val sync: SyncScheduler,
    private val locationClient: LocationClient,
) {
    private val dao = db.trackerDao()
    private val filter = LocationFilter()
    private val detector = StayDetector()
    private var anchor: LocationSample? = null
    private var lastStoredAt = 0L
    private var lastLiveAt = 0L
    private var lastSample: LocationSample? = null
    private var storedSinceSync = 0
    private var currentPlace: String? = null

    /** Rebuild in-memory state after the service (re)starts. */
    suspend fun restore() {
        val day = dao.getOpenWorkday(uid)
        anchor = if (day?.anchorLat != null && day.anchorLng != null && day.anchorTime != null) {
            LocationSample(day.anchorLat, day.anchorLng, 20f, 0f, day.anchorTime, false)
        } else null
        val open = dao.getOpenStay(uid)
        detector.restore(
            open?.let {
                StayDetector.OpenStay(it.id, it.latitude, it.longitude, it.sampleCount, it.arrivalAt, it.lastSeenAt)
            }
        )
        currentPlace = open?.name
    }

    suspend fun onSample(sample: LocationSample): ProcessResult {
        applyConfig()
        val day = dao.getOpenWorkday(uid)
        if (day == null || day.status != WorkStatus.ACTIVE.name) {
            return ProcessResult(MotionMode.MOVING, day, null)
        }
        lastSample = sample

        val decision = filter.evaluate(anchor, sample)
        if (decision is LocationFilter.Decision.Rejected) {
            maybePublishLive(day, sample, force = false)
            return ProcessResult(mode(), day, currentPlace)
        }
        decision as LocationFilter.Decision.Accepted

        val events = detector.onSample(sample)
        val insideStay = events.any { it is StayDetector.Event.Updated }
        val addDistance = if (decision.moved && !insideStay) decision.distanceMeters else 0.0
        if (decision.moved) anchor = sample

        val store = decision.moved || sample.time - lastStoredAt >= 60_000
        val newStays = mutableListOf<String>()

        val updatedDay = db.withTransaction {
            if (store) {
                dao.insertPoint(
                    TrackPointEntity(
                        userId = uid, date = day.date, latitude = sample.latitude, longitude = sample.longitude,
                        accuracy = sample.accuracy, speed = sample.speed, time = sample.time, mock = sample.mock,
                    )
                )
                lastStoredAt = sample.time
                storedSinceSync++
            }
            for (event in events) {
                when (event) {
                    is StayDetector.Event.Opened -> {
                        val s = event.stay
                        val label = namer.quickLabel(uid, s.latitude, s.longitude)
                        dao.upsertStay(
                            StayEntity(
                                id = s.id, userId = uid, date = day.date,
                                latitude = s.latitude, longitude = s.longitude, sampleCount = s.samples,
                                arrivalAt = s.arrivalAt, lastSeenAt = s.lastInsideAt,
                                placeId = label?.placeId,
                                name = label?.name ?: PlaceNamer.UNNAMED,
                                address = label?.address,
                                category = (label?.category ?: PlaceCategory.OTHER).name,
                                manualLabel = label?.manual ?: false,
                            )
                        )
                        currentPlace = label?.name
                        if (label == null) newStays += s.id
                    }
                    is StayDetector.Event.Updated -> {
                        val s = event.stay
                        dao.getStay(s.id)?.let { existing ->
                            dao.upsertStay(
                                existing.copy(
                                    latitude = s.latitude, longitude = s.longitude, sampleCount = s.samples,
                                    lastSeenAt = s.lastInsideAt, updatedAt = System.currentTimeMillis(), dirty = true,
                                )
                            )
                            currentPlace = existing.name
                        }
                    }
                    is StayDetector.Event.Closed -> {
                        dao.getStay(event.stay.id)?.let { existing ->
                            dao.upsertStay(
                                existing.copy(
                                    departureAt = event.departureAt, lastSeenAt = event.departureAt,
                                    updatedAt = System.currentTimeMillis(), dirty = true,
                                )
                            )
                        }
                        currentPlace = null
                    }
                }
            }
            val updated = day.copy(
                distanceMeters = day.distanceMeters + addDistance,
                anchorLat = anchor?.latitude, anchorLng = anchor?.longitude, anchorTime = anchor?.time,
                pointCount = day.pointCount + if (store) 1 else 0,
                mockCount = day.mockCount + if (sample.mock) 1 else 0,
                updatedAt = System.currentTimeMillis(), dirty = true,
            )
            dao.upsertWorkday(updated)
            updated
        }

        // Network naming happens outside the transaction.
        for (id in newStays) nameStayInBackground(id)

        val hasStayEvent = events.any { it !is StayDetector.Event.Updated }
        if (hasStayEvent || storedSinceSync >= 60) {
            storedSinceSync = 0
            sync.requestSync()
        }
        maybePublishLive(updatedDay, sample, force = hasStayEvent)
        return ProcessResult(mode(), updatedDay, currentPlace)
    }

    private suspend fun nameStayInBackground(stayId: String) {
        val stay = dao.getStay(stayId) ?: return
        val (name, address) = namer.reverseGeocode(stay.latitude, stay.longitude) ?: return
        val latest = dao.getStay(stayId) ?: return
        if (latest.manualLabel || latest.placeId != null) return
        dao.upsertStay(
            latest.copy(
                name = name ?: latest.name, address = address ?: latest.address,
                updatedAt = System.currentTimeMillis(), dirty = true,
            )
        )
        if (detector.open?.id == stayId) currentPlace = name
    }

    /** Called periodically even without new fixes, so the admin sees battery / GPS state. */
    suspend fun heartbeat() {
        val day = dao.getOpenWorkday(uid) ?: return
        val s = lastSample
        publishLive(day, s)
    }

    private fun maybePublishLive(day: WorkdayEntity, sample: LocationSample, force: Boolean) {
        val now = System.currentTimeMillis()
        if (force || now - lastLiveAt >= LIVE_INTERVAL_MS) publishLive(day, sample)
    }

    private fun publishLive(day: WorkdayEntity, sample: LocationSample?) {
        lastLiveAt = System.currentTimeMillis()
        val health = DeviceStatus.read(context)
        org.publishLive(
            LiveState(
                uid = uid,
                status = WorkStatus.from(day.status),
                latitude = sample?.latitude ?: day.anchorLat,
                longitude = sample?.longitude ?: day.anchorLng,
                accuracy = sample?.accuracy,
                placeName = currentPlace,
                batteryPercent = health.batteryPercent,
                charging = health.charging,
                gpsEnabled = locationClient.isLocationEnabled(),
                mockLocation = sample?.mock ?: false,
                distanceMeters = day.distanceMeters,
                appVersion = BuildConfig.VERSION_NAME,
                updatedAt = lastLiveAt,
            )
        )
    }

    private fun applyConfig() {
        val c = org.config.value
        filter.maxAccuracyMeters = c.maxAccuracyMeters
        detector.radiusMeters = c.stayRadiusMeters
        detector.minDwellMillis = c.minStayMinutes * 60_000L
    }

    private fun mode(): MotionMode = if (detector.open != null) MotionMode.STATIONARY else MotionMode.MOVING

    private companion object {
        const val LIVE_INTERVAL_MS = 60_000L
    }
}
